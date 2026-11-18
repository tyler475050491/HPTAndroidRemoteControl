package com.hpt.adbtool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 一台已完成 ADB 授权的设备的高层操作封装。
 */
public final class AdbDevice {

    public interface ProgressListener {
        void onProgress(long sent, long total);
    }

    private final AdbConnection conn;
    private final AdbLog log;
    private volatile DevInfo info;

    private AdbDevice(AdbConnection conn, AdbLog log) {
        this.conn = conn;
        this.log = log;
    }

    public static AdbDevice connect(String host, int port, AdbCrypto crypto, AdbLog log)
            throws IOException {
        AdbConnection conn = AdbConnection.open(host, port, crypto, log);
        return new AdbDevice(conn, log);
    }

    public String getAddress() {
        return conn.getAddress();
    }

    public boolean isAlive() {
        return !conn.isClosed();
    }

    public void close() {
        conn.close();
    }

    /** 连接被设备侧断开时回调（在 adb 读线程里触发，调用方需自行切回 UI 线程） */
    public void setOnClosedListener(Runnable r) {
        conn.setOnClosedListener(r);
    }

    public AdbStream open(String service, int timeoutMs) throws IOException {
        return conn.openStream(service, timeoutMs);
    }

    /** 执行一条 shell 命令并返回全部输出 */
    public String shell(String cmd, long timeoutMs) throws IOException {
        try {
            return shellInner("exec:" + cmd, timeoutMs);
        } catch (IOException first) {
            // 部分老设备没有 exec: 服务，退回 shell:（pty 会把 \n 转成 \r\n，这里统一去噪）
        }
        try {
            return shellInner("shell:" + cmd, timeoutMs);
        } catch (IOException second) {
            // 设备可能正忙（例如 pm install 之后的 dexopt 把 CPU 占满），稍等后重试一次
            sleep(2000);
            try {
                return shellInner("shell:" + cmd, timeoutMs);
            } catch (IOException third) {
                throw new IOException("执行命令失败：" + cmd + "（" + third.getMessage()
                        + "）。设备可能正在安装或优化应用，请稍等几秒后重试", third);
            }
        }
    }

    private String shellInner(String service, long timeoutMs) throws IOException {
        AdbStream s = conn.openStream(service, 15000);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (true) {
                int n = s.readWithTimeout(buf, 0, buf.length, 2000);
                if (n == -2) {
                    if (System.currentTimeMillis() > deadline) {
                        break;
                    }
                    continue;
                }
                if (n < 0) {
                    break;
                }
                bos.write(buf, 0, n);
                if (bos.size() > 4 * 1024 * 1024) {
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    break;
                }
            }
        } finally {
            s.close();
        }
        return normalize(bos.toByteArray());
    }

    public String shell(String cmd) throws IOException {
        return shell(cmd, 20000);
    }

    /** 读取设备基础信息（品牌/型号/系统版本/分辨率） */
    public DevInfo readInfo() throws IOException {
        String raw = shell("getprop ro.product.brand; getprop ro.product.model; "
                + "getprop ro.build.version.release; getprop ro.build.version.sdk; wm size", 20000);
        String[] lines = raw.split("\n");
        DevInfo di = new DevInfo();
        di.brand = line(lines, 0);
        di.model = line(lines, 1);
        di.androidVersion = line(lines, 2);
        di.sdk = line(lines, 3);
        for (String l : lines) {
            if (l.contains("Physical size:")) {
                di.resolution = l.replace("Physical size:", "").trim();
                break;
            }
        }
        if (di.resolution.isEmpty()) {
            for (String l : lines) {
                if (l.matches("[0-9]+x[0-9]+")) {
                    di.resolution = l;
                    break;
                }
            }
        }
        di.address = conn.getAddress();
        di.name = buildName(di.brand, di.model);
        info = di;
        return di;
    }

    public DevInfo getInfo() {
        return info;
    }

    /**
     * 上传安装包并安装（无需 root）。
     * 传入的是「明文 APK 的字节流」——加密内置包在读取时实时解密，所以设备上不会出现解密后的持久文件：
     * 临时文件用随机名放在 /data/local/tmp，装完（含失败）立刻删除。
     */
    public String installApk(InputStream in, long size, ProgressListener listener) throws IOException {
        String remote = "/data/local/tmp/.hpt_" + randomSuffix() + ".apk";
        AdbStream s = null;
        long sent = 0;
        try {
            try {
                s = conn.openStream("exec:cat > " + remote, 15000);
                byte[] buf = new byte[128 * 1024];
                long step = Math.max(1, size / 20);
                long last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    s.write(buf, 0, n);
                    sent += n;
                    if (listener != null && (sent - last >= step || sent >= size)) {
                        last = sent;
                        listener.onProgress(sent, size);
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                if (s != null) {
                    s.close();
                }
            }

            // 设备侧写入需要一点时间落盘
            sleep(1000);

            long actual = parseLong(shell("wc -c < " + remote, 10000));
            if (actual != size) {
                throw new IOException("上传校验失败：期望 " + size + " 字节，设备实际 " + actual + " 字节");
            }
            shell("chmod 600 " + remote, 10000);
            if (log != null) {
                log.log(AdbLog.OK, "安装包上传完成（" + human(size) + "），开始静默安装…");
            }

            String result = shell("pm install -r -d -t " + remote, 240000);
            if (result.toLowerCase().contains("success")) {
                return "Success";
            }
            return result.isEmpty() ? "Failure [未知错误]" : result;
        } finally {
            // 无论成功失败都清掉设备上的临时明文包
            try {
                shell("rm -f " + remote, 15000);
                String left = shell("ls " + remote + " 2>/dev/null", 8000).trim();
                if (log != null) {
                    log.log(AdbLog.INFO, left.isEmpty()
                            ? "已删除设备临时文件：" + remote
                            : "临时文件仍存在：" + left);
                }
            } catch (Exception e) {
                if (log != null) {
                    log.log(AdbLog.WARN, "删除设备临时文件失败：" + e.getMessage());
                }
            }
        }
    }

    private static String randomSuffix() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** 卸载指定包名 */
    public String uninstall(String pkg) throws IOException {
        return shell("pm uninstall " + pkg, 120000);
    }

    /**
     * 读取设备上的第三方应用（不含系统应用），返回 {包名, 版本} 列表，按包名排序。
     */
    public java.util.List<String[]> listThirdPartyApps() throws IOException {
        String cmd = "pm list packages -3 | sed 's/^package://' | sort | while read p; do "
                + "v=$(dumpsys package \"$p\" 2>/dev/null | awk '/versionName/{print $1; exit}'); "
                + "echo \"$p|$v\"; done";
        String raw = shell(cmd, 60000);
        java.util.List<String[]> list = new java.util.ArrayList<String[]>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("|")) {
                continue;
            }
            String[] parts = line.split("\\|");
            String pkg = parts[0].trim();
            if (pkg.isEmpty() || pkg.contains(" ")) {
                continue;
            }
            String ver = parts.length > 1 ? parts[1].trim() : "";
            if (ver.startsWith("versionName=")) {
                ver = ver.substring("versionName=".length()).trim();
            }
            list.add(new String[]{pkg, ver});
        }
        return list;
    }

    /** 重启设备 */
    public void reboot() throws IOException {
        try {
            shell("reboot", 8000);
        } catch (IOException ignored) {
            // reboot 会让连接立即断开，属正常现象
        }
    }

    /** 屏幕点击 */
    public void tap(int x, int y) throws IOException {
        shell("input tap " + x + " " + y, 15000);
    }

    /** 屏幕滑动（控制通道不可用时兜底） */
    public void swipe(int x1, int y1, int x2, int y2, int durationMs) throws IOException {
        shell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs, 20000);
    }

    private static String buildName(String brand, String model) {
        String b = brand == null ? "" : brand.trim();
        String m = model == null ? "" : model.trim();
        if (m.isEmpty()) {
            return b.isEmpty() ? "Android 设备" : b;
        }
        if (b.isEmpty() || m.toLowerCase().startsWith(b.toLowerCase())) {
            return m;
        }
        return b + " " + m;
    }

    private static String line(String[] lines, int idx) {
        return idx < lines.length ? lines[idx].trim() : "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    private static String normalize(byte[] data) {
        String s = new String(data);
        return s.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
