package com.hpt.adbtool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 设备端 scrcpy-server 的推送与启动（纯应用层：ADB 推 jar + shell 里 app_process 拉起，无需 root）。
 */
public final class ScrcpyServer {

    public static final String VERSION = "2.7";
    public static final String REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar";

    public interface JarSource {
        InputStream open() throws IOException;

        long size();
    }

    private final AdbDevice device;
    private final AdbLog log;

    private AdbStream processStream;

    public ScrcpyServer(AdbDevice device, AdbLog log) {
        this.device = device;
        this.log = log;
    }

    /** 进程输出流（用来读服务端日志；为空表示未启动） */
    public AdbStream getProcessStream() {
        return processStream;
    }

    /** 每次都推送一遍（jar 只有 70KB，几秒钟；避免上一次异常退出把设备上的 jar 删掉） */
    public void ensurePushed(JarSource src) throws IOException {
        long local = src.size();
        log.log(AdbLog.INFO, "推送 scrcpy-server v" + VERSION + "（" + local + " 字节）…");
        AdbStream s = device.open("exec:cat > " + REMOTE_PATH, 15000);
        InputStream in = src.open();
        try {
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            while (true) {
                int n = in.read(buf);
                if (n <= 0) {
                    break;
                }
                s.write(buf, 0, n);
                total += n;
            }
            log.log(AdbLog.INFO, "scrcpy-server 推送完成（" + total + " 字节）");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            s.close();
        }
        long check = remoteSize();
        if (check != local) {
            throw new IOException("scrcpy-server 推送校验失败：设备侧 " + check + " 字节，应为 " + local);
        }
    }

    /** 结束可能残留的旧服务进程（避免 abstract socket 端口被占用） */
    public void killOld() {
        String script = "for d in /proc/[0-9]*; do c=$(tr '\\0' ' ' < $d/cmdline 2>/dev/null); "
                + "case \"$c\" in *scrcpy*) kill -9 ${d#/proc/} 2>/dev/null;; esac; done; echo done";
        try {
            AdbStream s = device.open("shell:" + script, 8000);
            try {
                byte[] buf = new byte[1024];
                long deadline = System.currentTimeMillis() + 6000;
                while (System.currentTimeMillis() < deadline) {
                    int n = s.readWithTimeout(buf, 0, buf.length, 500);
                    if (n == -2) {
                        continue;
                    }
                    if (n < 0) {
                        break;
                    }
                }
            } finally {
                s.close();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 启动服务端。tunnel_forward=true 表示服务端在设备上监听 localabstract:scrcpy，
     * 由本机用 OPEN localabstract:scrcpy 连过去（等价于 adb forward）。
     */
    public void start(int maxSize, int maxFps, int bitRate, boolean control) throws IOException {
        StringBuilder cmd = new StringBuilder();
        cmd.append("CLASSPATH=").append(REMOTE_PATH)
                .append(" app_process / com.genymobile.scrcpy.Server ").append(VERSION)
                .append(" log_level=info")
                .append(" max_size=").append(maxSize)
                .append(" max_fps=").append(maxFps)
                .append(" video_bit_rate=").append(bitRate)
                .append(" tunnel_forward=true")
                .append(" audio=false")
                .append(" control=").append(control)
                .append(" cleanup=false")
                .append(" power_on=false")
                .append(" stay_awake=true");
        log.log(AdbLog.INFO, "启动设备端 scrcpy 服务：" + cmd);
        // 必须走 shell:（exec: 不会做 VAR=value 环境赋值）
        processStream = device.open("shell:" + cmd, 15000);
    }

    public void stop() {
        AdbStream s = processStream;
        processStream = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private long remoteSize() {
        try {
            String out = device.shell("wc -c " + REMOTE_PATH, 8000);
            String first = firstToken(out);
            long v = Long.parseLong(first);
            return v > 0 ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstToken(String s) {
        if (s == null) {
            return "0";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "0";
        }
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        return t.substring(0, i);
    }

    /** 读完进程输出（用于日志，不会阻塞太久） */
    public static String drain(AdbStream s, int maxBytes) {
        if (s == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (bos.size() < maxBytes) {
                int n = s.readWithTimeout(buf, 0, buf.length, 200);
                if (n == -2 || n < 0) {
                    break;
                }
                bos.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
        return new String(bos.toByteArray()).replace("\r", "");
    }
}
