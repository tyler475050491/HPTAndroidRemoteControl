package com.hpt.adbtool;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.util.Arrays;

/**
 * 屏幕流预览：基于 ADB 的 screencap 截图流（无需 root、无需推送 scrcpy-server）。
 *
 * 每帧执行一次 exec:screencap -p，把 PNG 流解码成 Bitmap 交给 UI。
 * 默认 500ms 一帧，够用于查看设备画面并支持点击下发。
 */
public final class ScreenMirror {

    public interface Listener {
        void onFrame(Bitmap bmp, float fps);

        void onError(String msg);

        void onEnded(String reason);
    }

    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] IEND = {'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    private static final String[] SERVICES = {
            "exec:screencap -p",
            "shell:stty raw -echo; screencap -p"
    };

    private volatile String mirrorService;

    private final AdbDevice device;
    private final AdbLog log;
    private final Listener listener;
    private final int intervalMs;

    private volatile boolean running;
    private Thread thread;
    private long stopAt;

    public ScreenMirror(AdbDevice device, AdbLog log, Listener listener, int intervalMs) {
        this.device = device;
        this.log = log;
        this.listener = listener;
        this.intervalMs = intervalMs;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "screen-mirror");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        stopAt = System.currentTimeMillis();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        thread = null;
    }

    /** 依次尝试 exec: / shell:(raw 模式) 两种截图服务，记住可用的那个 */
    private AdbStream openScreencap() throws IOException {
        if (mirrorService != null) {
            return device.open(mirrorService, 10000);
        }
        IOException last = null;
        for (String svc : SERVICES) {
            try {
                AdbStream s = device.open(svc, 10000);
                mirrorService = svc;
                return s;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("无法打开 screencap 服务") : last;
    }

    private void loop() {
        float fps = 0f;
        int failCount = 0;
        String lastError = "";
        String shownError = "";
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                byte[] png = capturePng();
                Bitmap bmp = decode(png);
                if (bmp == null) {
                    throw new IOException("图像解码失败");
                }
                failCount = 0;
                shownError = "";
                long cost = System.currentTimeMillis() - t0;
                float inst = cost > 0 ? 1000f / cost : 0f;
                fps = fps == 0f ? inst : (fps * 0.7f + inst * 0.3f);
                if (listener != null && running) {
                    listener.onFrame(bmp, fps);
                }
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                failCount++;
                lastError = e.getMessage() == null ? "未知错误" : e.getMessage();
                // 只在错误内容变化或每 3 次失败时上报一次，避免刷屏
                if (listener != null && (!lastError.equals(shownError) || failCount % 3 == 0)) {
                    shownError = lastError;
                    listener.onError(lastError);
                }
                if (failCount >= 8) {
                    break;
                }
                // 退避重试：设备忙（如 pm install 后 dexopt）时降低请求频率
                sleepQuiet(Math.min(3000, 300L * failCount));
            } catch (Exception e) {
                failCount++;
                lastError = String.valueOf(e.getMessage());
                sleepQuiet(400);
            }

            long elapsed = System.currentTimeMillis() - t0;
            long sleep = intervalMs - elapsed;
            if (sleep > 0) {
                sleepQuiet(sleep);
            }
        }
        running = false;
        if (listener != null) {
            listener.onEnded(lastError == null || lastError.isEmpty() ? "已停止" : "已停止：" + lastError);
        }
    }

    /** 抓一帧 PNG */
    private byte[] capturePng() throws IOException {
        AdbStream s;
        try {
            s = openScreencap();
        } catch (IOException e) {
            mirrorService = null;
            throw e;
        }
        byte[] acc = new byte[256 * 1024];
        int len = 0;
        int scanFrom = 0;
        byte[] tmp = new byte[16 * 1024];
        try {
            while (true) {
                int n = s.readWithTimeout(tmp, 0, tmp.length, 4000);
                if (n == -2) {
                    if (len > 0) {
                        break;
                    }
                    throw new IOException("screencap 无数据返回");
                }
                if (n < 0) {
                    break;
                }
                if (len + n > acc.length) {
                    acc = Arrays.copyOf(acc, Math.max(acc.length * 2, len + n));
                }
                System.arraycopy(tmp, 0, acc, len, n);
                len += n;

                int end = findEnd(acc, len, Math.max(0, scanFrom - 8));
                scanFrom = len;
                if (end > 0) {
                    return Arrays.copyOf(acc, end);
                }
                if (!looksLikePng(acc, len)) {
                    throw new IOException("设备不支持 screencap 截图流：" + preview(acc, len));
                }
                if (len > 24 * 1024 * 1024) {
                    throw new IOException("图像数据超长，已放弃");
                }
            }
        } finally {
            s.close();
        }
        if (len > 0 && looksLikePng(acc, len)) {
            return Arrays.copyOf(acc, len);
        }
        throw new IOException("未收到完整图像帧");
    }

    private static int findEnd(byte[] buf, int len, int from) {
        for (int i = Math.max(0, from); i <= len - IEND.length; i++) {
            boolean hit = true;
            for (int j = 0; j < IEND.length; j++) {
                if (buf[i + j] != IEND[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return i + IEND.length;
            }
        }
        return -1;
    }

    private static boolean looksLikePng(byte[] buf, int len) {
        if (len < PNG_HEAD.length + 8) {
            return true; // 数据还不够，先别判定
        }
        for (int i = 0; i < PNG_HEAD.length; i++) {
            if (buf[i] != PNG_HEAD[i]) {
                return false;
            }
        }
        return true;
    }

    private static String preview(byte[] buf, int len) {
        try {
            return new String(buf, 0, Math.min(len, 200)).trim().replace("\n", " ");
        } catch (Exception e) {
            return "";
        }
    }

    private static Bitmap decode(byte[] png) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, o);

        int sample = 1;
        while (o.outWidth > 0 && o.outWidth / (sample * 2) >= 720) {
            sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.RGB_565;
        o2.inDither = false;
        return BitmapFactory.decodeByteArray(png, 0, png.length, o2);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
