package com.hpt.adbtool;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真正的视频流投屏：设备端跑 scrcpy-server 编码 H.264，本机用 MediaCodec 硬解，
 * 逐帧转成 Bitmap 交给预览画布；同时提供控制通道做触控注入。
 *
 * 相比 screencap 截图流（约 2 FPS），这条链路是 30 FPS 级别的。
 */
public final class ScrcpyMirror {

    public interface Listener {
        /** 视频流已就绪（拿到分辨率与编码格式） */
        void onStarted(int width, int height, String codec, boolean control);

        void onFrame(Bitmap bmp, float fps);

        /** 视频帧已解码并渲染到 Surface（TextureView 直出），只用来刷新帧率显示 */
        void onVideoFrame(float fps);

        void onError(String msg);

        void onEnded(String reason);

        /** scrcpy 链路起不来（例如设备不支持 app_process），调用方可以回退到截图流 */
        void onFallback(String reason);

        /** 设备端 scrcpy-server 的输出 */
        void onServerLog(String line);
    }

    private final AdbDevice device;
    private final AdbLog log;
    private final Listener listener;
    private final ScrcpyServer.JarSource jarSource;
    private final int maxSize;
    private final int maxFps;
    private final int bitRate;

    private final ScrcpyServer server;
    private volatile ScrcpyControl control;
    private volatile AdbStream videoStream;

    private volatile boolean running;
    private volatile boolean controlReady;
    private volatile int videoWidth;
    private volatile int videoHeight;

    private Thread startThread;
    private Thread logThread;
    private Thread readThread;
    private Thread decodeThread;

    private MediaCodec codec;

    private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<Packet>(8);
    private final AtomicBoolean eos = new AtomicBoolean(false);
    /** 触控注入专用单线程（socket 写不能在 UI 线程做） */
    private final java.util.concurrent.ExecutorService touchExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 解码器输出目标（TextureView 的 Surface），由调用方传入 */
    private volatile Surface outputSurface;
    private volatile Surface pendingSurface;
    private long lastUiFpsAt;

    private float fpsSmooth;
    private long fpsWindowStart;
    private int fpsFrames;
    private int renderFail;
    private long frameCount;
    private long lastPts;

    private boolean avccMode;

    // YUV → RGB 的 BT.601 查表（每帧几百万次运算，用查表避免浮点）
    private static final int[] R_V = new int[256];
    private static final int[] G_U = new int[256];
    private static final int[] G_V = new int[256];
    private static final int[] B_U = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i - 128;
            R_V[i] = (int) (1.402 * c);
            G_U[i] = (int) (0.344 * c);
            G_V[i] = (int) (0.714 * c);
            B_U[i] = (int) (1.764 * c);
        }
    }

    static final class Packet {
        long pts;
        boolean config;
        byte[] data;

        Packet(long pts, boolean config, byte[] data) {
            this.pts = pts;
            this.config = config;
            this.data = data;
        }
    }

    public ScrcpyMirror(AdbDevice device, AdbLog log, ScrcpyServer.JarSource jarSource,
                        int maxSize, int maxFps, int bitRate, Surface surface, Listener listener) {
        this.device = device;
        this.log = log;
        this.jarSource = jarSource;
        this.maxSize = maxSize;
        this.maxFps = maxFps;
        this.bitRate = bitRate;
        this.outputSurface = surface;
        this.listener = listener;
        this.server = new ScrcpyServer(device, log);
    }

    /** 全屏切换时 TextureView 的 Surface 会被重建，这里把新 Surface 交给解码器 */
    public void updateSurface(Surface surface) {
        if (surface == null) {
            return;
        }
        outputSurface = surface;
        pendingSurface = surface;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isControlReady() {
        return controlReady && control != null;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        startThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runPipeline();
            }
        }, "scrcpy-start");
        startThread.setDaemon(true);
        startThread.start();
    }

    public void stop() {
        boolean wasRunning = running;
        running = false;
        touchExec.shutdownNow();
        ScrcpyControl c = control;
        control = null;
        controlReady = false;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        AdbStream v = videoStream;
        videoStream = null;
        if (v != null) {
            try {
                v.close();
            } catch (Exception ignored) {
            }
        }
        server.stop();
        releaseCodec();
        if (wasRunning) {
            Thread t = startThread;
            if (t != null) {
                t.interrupt();
            }
        }
        startThread = null;
        decodeThread = null;
    }

    /** 全屏触控：归一化坐标 → 设备像素坐标 → 注入（在专用线程写 socket，避免主线程网络异常） */
    public boolean touch(int action, float nx, float ny) {
        final ScrcpyControl c = control;
        if (!controlReady || c == null) {
            return false;
        }
        final int a = action;
        final float fx = nx;
        final float fy = ny;
        try {
            touchExec.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        c.touch(a, fx, fy);
                    } catch (IOException e) {
                        controlReady = false;
                        if (listener != null) {
                            listener.onError("控制通道写入失败：" + e.getMessage());
                        }
                    }
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    // ------------------------------------------------------------- 主流程

    private void runPipeline() {
        String failReason = null;
        try {
            server.killOld();
            server.ensurePushed(jarSource);
            server.start(maxSize, maxFps, bitRate, true);
            startLogReader();

            AdbStream video;
            try {
                video = openVideoSocket(35000);
            } catch (IOException e) {
                // 服务端偶发没起来（比如上一个残留进程占了抽象 socket），重启一次再试
                log.log(AdbLog.WARN, "视频端口未就绪，重启设备端服务后重试：" + e.getMessage());
                server.killOld();
                server.start(maxSize, maxFps, bitRate, true);
                video = openVideoSocket(35000);
            }
            videoStream = video;

            // 1) 服务端先发一个哑字节（scrcpy 约定）
            ByteReader reader = new ByteReader(video);
            byte[] dummy = reader.readFully(1, 8000);
            if (dummy == null) {
                throw new IOException("未收到视频流握手字节");
            }
            log.log(AdbLog.INFO, "视频通道握手完成（哑字节 0x" + hex(dummy, 0, 1) + "）");

            // 2) 视频通道建立后必须立刻把控制通道也接上，否则服务端不会继续发数据
            openControlChannel();

            // 3) 设备名(64) + 编码(4) + 宽(4) + 高(4)
            int[] dims = readVideoHeader(reader);
            videoWidth = dims[0];
            videoHeight = dims[1];
            ScrcpyControl ctl = control;
            if (ctl != null) {
                ctl.setVideoSize(videoWidth, videoHeight);
            }
            String codecName = codecFourcc;
            log.log(AdbLog.OK, "视频流已建立：" + codecName.toUpperCase(Locale.US) + " "
                    + videoWidth + "x" + videoHeight + "（scrcpy-server v" + ScrcpyServer.VERSION
                    + "，设备 " + devName + "）");

            Packet first = readPacket(reader, 8000);
            byte[] csd = null;
            if (first != null && first.config) {
                csd = first.data;
            }
            configureDecoder(codecName, videoWidth, videoHeight, csd);
            if (first != null && !first.config) {
                enqueue(first);
            }
            if (listener != null) {
                listener.onStarted(videoWidth, videoHeight, codecName, controlReady);
            }

            startDecodeThread();
            // 读包循环就在当前线程跑，直到 stop() 或流结束
            while (running) {
                Packet p = readPacket(reader, 3000);
                if (p == null) {
                    if (!running) {
                        break;
                    }
                    continue;
                }
                enqueue(p);
            }
        } catch (IOException e) {
            failReason = e.getMessage() == null ? "视频流初始化失败" : e.getMessage();
            if (running && listener != null) {
                listener.onFallback(failReason);
            }
        } catch (Throwable t) {
            failReason = "视频流初始化异常：" + t;
            if (running && listener != null) {
                listener.onFallback(failReason);
            }
        } finally {
            boolean wasRunning = running;
            stop();
            if (wasRunning && listener != null) {
                listener.onEnded(failReason == null ? "已停止" : "已停止：" + failReason);
            }
        }
    }

    private AdbStream openVideoSocket(long timeoutMs) throws IOException {
        IOException last = null;
        long deadline = System.currentTimeMillis() + timeoutMs;
        int tries = 0;
        while (running && System.currentTimeMillis() < deadline) {
            long t0 = System.currentTimeMillis();
            try {
                AdbStream s = device.open("localabstract:scrcpy", 6000);
                log.log(AdbLog.OK, "视频端口已连上（第 " + (tries + 1) + " 次尝试，耗时 "
                        + (System.currentTimeMillis() - t0) + "ms）");
                return s;
            } catch (IOException e) {
                last = e;
                tries++;
                if (tries <= 6 || tries % 10 == 0) {
                    log.log(AdbLog.WARN, "视频端口连接失败（第 " + tries + " 次，耗时 "
                            + (System.currentTimeMillis() - t0) + "ms）：" + e.getMessage());
                }
                sleepQuiet(900);
            }
        }
        throw new IOException("连不上设备端 scrcpy 视频端口"
                + (last == null ? "（超时）" : "（" + last.getMessage() + "）"));
    }

    private void openControlChannel() {
        IOException last = null;
        long deadline = System.currentTimeMillis() + 25000;
        int tries = 0;
        while (running && System.currentTimeMillis() < deadline) {
            long t0 = System.currentTimeMillis();
            try {
                AdbStream s = device.open("localabstract:scrcpy", 6000);
                control = new ScrcpyControl(s, videoWidth, videoHeight, log);
                controlReady = true;
                log.log(AdbLog.OK, "控制通道已就绪（第 " + (tries + 1) + " 次尝试，耗时 "
                        + (System.currentTimeMillis() - t0) + "ms）");
                return;
            } catch (IOException e) {
                last = e;
                tries++;
                if (tries <= 6 || tries % 10 == 0) {
                    log.log(AdbLog.WARN, "控制通道连接失败（第 " + tries + " 次）：" + e.getMessage());
                }
                sleepQuiet(900);
            }
        }
        controlReady = false;
        log.log(AdbLog.WARN, "控制通道打开失败，触控将回退到 shell 输入："
                + (last == null ? "超时" : last.getMessage()));
    }

    private String codecFourcc = "h264";

    /** 解析视频头：64 字节设备名 + 4 字节编码格式 + 4 字节宽 + 4 字节高（大端） */
    private int[] readVideoHeader(ByteReader r) throws IOException {
        byte[] head = r.readFully(76, 10000);
        if (head == null) {
            throw new IOException("读取视频头超时（设备端可能未启动成功）");
        }
        devName = new String(head, 0, 64).trim();
        String four = fourcc(head, 64);
        if (!isCodecFourcc(four)) {
            throw new IOException("视频头格式不认识：" + hex(head, 0, 24));
        }
        codecFourcc = four;
        int w = intAt(head, 68);
        int h = intAt(head, 72);
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) {
            throw new IOException("视频头异常：编码=" + four + " 尺寸=" + w + "x" + h);
        }
        return new int[]{w, h};
    }

    private String devName = "";

    private static boolean isCodecFourcc(String s) {
        return "h264".equals(s) || "h265".equals(s) || "av01".equals(s)
                || "vp09".equals(s) || "hvc1".equals(s) || "avc1".equals(s);
    }

    private static String fourcc(byte[] b, int off) {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            char c = (char) (b[off + i] & 0xff);
            if (c < 32 || c > 126) {
                return "";
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int intAt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && off + i < b.length; i++) {
            sb.append(String.format(Locale.US, "%02X ", b[off + i] & 0xff));
        }
        return sb.toString().trim();
    }

    private Packet readPacket(ByteReader r, long timeoutMs) throws IOException {
        byte[] head = r.readFully(12, timeoutMs);
        if (head == null) {
            return null;
        }
        long pts = ((long) (head[0] & 0xff) << 56) | ((long) (head[1] & 0xff) << 48)
                | ((long) (head[2] & 0xff) << 40) | ((long) (head[3] & 0xff) << 32)
                | ((long) (head[4] & 0xff) << 24) | ((long) (head[5] & 0xff) << 16)
                | ((long) (head[6] & 0xff) << 8) | (long) (head[7] & 0xff);
        int size = intAt(head, 8);
        boolean config = (pts & (1L << 63)) != 0;
        // 高两位是标志位（bit63=config、bit62=key），必须剥掉，否则硬件解码器收到
        // 天文数字的时间戳会直接报 DEAD_OBJECT 挂掉
        long ptsUs = pts & 0x3FFFFFFFFFFFFFFFL;
        if (size <= 0 || size > 32 * 1024 * 1024) {
            throw new IOException("视频包头异常：size=" + size);
        }
        byte[] data = r.readFully(size, Math.max(3000, timeoutMs));
        if (data == null) {
            throw new IOException("视频包数据读取超时（size=" + size + "）");
        }
        return new Packet(ptsUs, config, data);
    }

    private void enqueue(Packet p) {
        if (!queue.offer(p)) {
            queue.poll();
            queue.offer(p);
        }
    }

    private void startDecodeThread() {
        decodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                decodeLoop();
            }
        }, "scrcpy-decode");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    // ------------------------------------------------------------- 解码

    private void configureDecoder(String fourcc, int w, int h, byte[] csd) throws IOException {
        String mime;
        if ("h265".equals(fourcc) || "hvc1".equals(fourcc)) {
            mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
        } else {
            mime = MediaFormat.MIMETYPE_VIDEO_AVC;
        }
        byte[] normalized = null;
        if (csd != null && csd.length > 4) {
            avccMode = !isAnnexB(csd);
            normalized = avccMode ? avccToAnnexB(csd) : csd;
        }
        Surface surface = outputSurface;
        if (surface == null || !surface.isValid()) {
            throw new IOException("预览画布 Surface 不可用");
        }
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, w, h);
            if (normalized != null) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(normalized));
            }
            codec = MediaCodec.createDecoderByType(mime);
            // 直接输出到 TextureView 的 Surface：零拷贝，比「ImageReader + YUV 转 ARGB」快得多
            codec.configure(fmt, surface, null, 0);
            codec.start();
        } catch (Exception e) {
            releaseCodec();
            throw new IOException("解码器初始化失败（" + mime + " " + w + "x" + h + "）：" + e.getMessage(), e);
        }
        fpsWindowStart = System.currentTimeMillis();
        log.log(AdbLog.INFO, "解码器已启动：" + mime + " " + w + "x" + h
                + (avccMode ? "（AVCC）" : "（Annex-B）") + "（直出 TextureView）");
    }

    private static boolean isAnnexB(byte[] d) {
        if (d.length >= 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) {
            return true;
        }
        return d.length >= 3 && d[0] == 0 && d[1] == 0 && d[2] == 1;
    }

    /** 把 AVCC（4 字节长度前缀）转成 Annex-B（00 00 00 01 起始码） */
    private static byte[] avccToAnnexB(byte[] d) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(d.length + 16);
        int p = 0;
        while (p + 4 <= d.length) {
            int len = ((d[p] & 0xff) << 24) | ((d[p + 1] & 0xff) << 16)
                    | ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
            p += 4;
            if (len <= 0 || p + len > d.length) {
                break;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(d, p, len);
            p += len;
        }
        return out.toByteArray();
    }

    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Packet pendingPacket = null;
        while (running) {
            try {
                // 全屏切换后 TextureView 的 Surface 会重建，这里把新 Surface 交给解码器
                Surface newSurface = pendingSurface;
                if (newSurface != null && codec != null) {
                    pendingSurface = null;
                    try {
                        codec.setOutputSurface(newSurface);
                        log.log(AdbLog.INFO, "解码输出已切到新的画面 Surface");
                    } catch (Exception e) {
                        log.log(AdbLog.WARN, "切换画面 Surface 失败：" + e.getMessage());
                    }
                }
                Packet p = pendingPacket != null ? pendingPacket : queue.poll(20, TimeUnit.MILLISECONDS);
                pendingPacket = null;
                if (p != null) {
                    int inIdx = codec.dequeueInputBuffer(5000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        if (ib != null) {
                            byte[] data = p.data;
                            if (avccMode && !p.config) {
                                data = avccToAnnexB(data);
                            }
                            ib.clear();
                            if (data.length > ib.capacity()) {
                                data = trim(data, ib.capacity());
                            }
                            ib.put(data);
                            int flags = p.config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                            // 保证时间戳单调、且在合理范围内（部分硬解要求严格递增）
                            long ptsUs = p.pts;
                            if (ptsUs <= lastPts || ptsUs > lastPts + 2000000L) {
                                ptsUs = lastPts + 33000L;
                            }
                            lastPts = ptsUs;
                            codec.queueInputBuffer(inIdx, 0, data.length, ptsUs, flags);
                        }
                    } else {
                        // 输入缓冲暂时用满，下一轮再喂
                        pendingPacket = p;
                    }
                }
                while (running) {
                    int outIdx = codec.dequeueOutputBuffer(info, 0);
                    if (outIdx >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eos.set(true);
                        }
                        try {
                            codec.releaseOutputBuffer(outIdx, true);
                            renderFail = 0;
                        } catch (IllegalStateException ise) {
                            // Surface 正在切换/被释放，丢帧继续，绝不让解码线程退出
                            renderFail++;
                            if (renderFail <= 3) {
                                log.log(AdbLog.WARN, "画面 Surface 暂不可用，跳帧等待重挂：" + ise.getMessage());
                            }
                        }
                        onRenderedFrame();
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        try {
                            MediaFormat of = codec.getOutputFormat();
                            log.log(AdbLog.INFO, "解码输出：" + of.getInteger("width") + "x"
                                    + of.getInteger("height") + " · color-format="
                                    + of.getInteger("color-format") + "（直出 TextureView）");
                        } catch (Exception e) {
                            log.log(AdbLog.INFO, "解码输出格式已变更");
                        }
                    } else {
                        break;
                    }
                }
                if (eos.get() && queue.isEmpty()) {
                    break;
                }
            } catch (IllegalStateException e) {
                if (running) {
                    log.log(AdbLog.ERR, "解码异常：" + e);
                }
                break;
            } catch (Exception e) {
                if (running) {
                    log.log(AdbLog.ERR, "解码异常：" + e);
                }
                sleepQuiet(200);
            }
        }
    }

    private static byte[] trim(byte[] d, int cap) {
        byte[] r = new byte[cap];
        System.arraycopy(d, 0, r, 0, cap);
        return r;
    }

    private void onRenderedFrame() {
        frameCount++;
        fpsFrames++;
        long now = System.currentTimeMillis();
        long span = now - fpsWindowStart;
        if (span >= 1000) {
            float inst = fpsFrames * 1000f / span;
            fpsSmooth = fpsSmooth == 0f ? inst : (fpsSmooth * 0.7f + inst * 0.3f);
            fpsFrames = 0;
            fpsWindowStart = now;
        }
        // 帧率文案每 500ms 刷一次，避免每帧都去动 UI
        if (listener != null && running && now - lastUiFpsAt >= 500) {
            lastUiFpsAt = now;
            listener.onVideoFrame(fpsSmooth);
        }
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try {
                c.stop();
            } catch (Exception ignored) {
            }
            try {
                c.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void startLogReader() {
        final AdbStream s = server.getProcessStream();
        if (s == null) {
            return;
        }
        logThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[2048];
                StringBuilder line = new StringBuilder();
                try {
                    while (running) {
                        int n = s.readWithTimeout(buf, 0, buf.length, 500);
                        if (n == -2) {
                            continue;
                        }
                        if (n < 0) {
                            break;
                        }
                        for (int i = 0; i < n; i++) {
                            char ch = (char) (buf[i] & 0xff);
                            if (ch == '\n' || ch == '\r') {
                                if (line.length() > 0) {
                                    if (listener != null) {
                                        listener.onServerLog(line.toString());
                                    }
                                    line.setLength(0);
                                }
                            } else if (line.length() < 400) {
                                line.append(ch);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "scrcpy-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 带预读缓冲的读取器（视频头多读的字节可以还回去） */
    private static final class ByteReader {
        private final AdbStream stream;
        private byte[] buf;
        private int off;
        private int len;

        ByteReader(AdbStream s) {
            this.stream = s;
        }

        void pushBack(byte[] b, int n) {
            buf = b;
            off = 0;
            len = n;
        }

        byte[] readFully(int n, long timeoutMs) throws IOException {
            byte[] out = new byte[n];
            int done = 0;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (done < n) {
                if (off < len) {
                    int take = Math.min(n - done, len - off);
                    System.arraycopy(buf, off, out, done, take);
                    off += take;
                    done += take;
                    continue;
                }
                if (System.currentTimeMillis() > deadline) {
                    return null;
                }
                int r = stream.readWithTimeout(out, done, n - done, 1000);
                if (r == -2) {
                    continue;
                }
                if (r < 0) {
                    throw new IOException("视频流已结束");
                }
                done += r;
            }
            return out;
        }
    }
}
