package com.hpt.adbtool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * ADB 主机端连接（socket + 协议消息收发 + 多路数据流）。
 */
public final class AdbConnection {

    public static final int A_CNXN = 0x4e584e43; // CNXN
    public static final int A_AUTH = 0x48545541; // AUTH
    public static final int A_OPEN = 0x4e45504f; // OPEN
    public static final int A_OKAY = 0x59414b4f; // OKAY
    public static final int A_CLSE = 0x45534c43; // CLSE
    public static final int A_WRTE = 0x45545257; // WRTE

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBKEY = 3;

    private static final int MAX_PAYLOAD = 256 * 1024;
    /** ADB 协议版本号，必须放在 CNXN 的 arg0 */
    private static final int A_VERSION = 0x01000001;

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;
    private final String host;
    private final int port;
    private final AdbLog log;

    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<Integer, AdbStream>();
    private final AtomicInteger nextId = new AtomicInteger(0);

    private volatile boolean closed;
    private volatile Runnable closedListener;
    private int traceCount;

    public void setOnClosedListener(Runnable r) {
        closedListener = r;
    }

    private void notifyClosed() {
        Runnable r = closedListener;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }
    private int peerMaxData = 64 * 1024;

    private AdbConnection(Socket s, OutputStream out, InputStream in,
                          String host, int port, int peerMaxData, AdbLog log) {
        this.socket = s;
        this.out = out;
        this.in = in;
        this.host = host;
        this.port = port;
        this.log = log;
        this.peerMaxData = peerMaxData;
    }

    private static String cmdName(int cmd) {
        switch (cmd) {
            case A_CNXN:
                return "CNXN";
            case A_AUTH:
                return "AUTH";
            case A_OPEN:
                return "OPEN";
            case A_OKAY:
                return "OKAY";
            case A_CLSE:
                return "CLSE";
            case A_WRTE:
                return "WRTE";
            default:
                return "0x" + Integer.toHexString(cmd);
        }
    }

    private static void trace0(AdbLog log, String dir, int cmd, int arg0, int arg1, int len) {
        if (log == null) {
            return;
        }
        log.log(AdbLog.INFO, "[adb] " + dir + " " + cmdName(cmd)
                + " a0=" + arg0 + " a1=" + arg1 + " len=" + len);
    }

    private void trace(String dir, int cmd, int arg0, int arg1, int len) {
        // 只保留 OPEN 的追踪（协议联调用），其余报文不再刷日志
        if (log == null || cmd != A_OPEN || traceCount >= 60) {
            return;
        }
        traceCount++;
        log.log(AdbLog.INFO, "[adb] " + dir + " " + cmdName(cmd)
                + " a0=" + arg0 + " a1=" + arg1 + " len=" + len);
    }

    /** 建立连接并完成 ADB 握手 / RSA 授权 */
    public static AdbConnection open(String host, int port, AdbCrypto crypto, AdbLog log)
            throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setTcpNoDelay(true);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32 * 1024);
            InputStream in = new BufferedInputStream(s.getInputStream(), 32 * 1024);

            byte[] banner = "host::".getBytes("UTF-8");
            // CNXN: arg0 = 协议版本，arg1 = 本机最大报文长度（顺序不能反）
            writeMessage(out, A_CNXN, A_VERSION, MAX_PAYLOAD, banner);
            if (log != null) {
                log.log(AdbLog.INFO, "[adb] → CNXN version=0x" + Integer.toHexString(A_VERSION)
                        + " maxData=" + MAX_PAYLOAD);
            }

            s.setSoTimeout(8000);
            int peerMax = 64 * 1024;
            boolean signed = false;
            boolean pubSent = false;

            while (true) {
                Msg m;
                try {
                    m = readMessage(in);
                } catch (SocketTimeoutException e) {
                    if (pubSent) {
                        throw new IOException("等待设备授权超时，请确认设备屏幕上的调试授权弹窗");
                    }
                    throw new IOException("ADB 握手超时：" + host + ":" + port);
                }

                if (m.cmd == A_CNXN) {
                    trace0(log, "←", m.cmd, m.arg0, m.arg1, m.data == null ? 0 : m.data.length);
                    // 设备 CNXN: arg0 = 版本，arg1 = 设备最大报文长度
                    if (m.arg1 >= 4096) {
                        peerMax = Math.min(MAX_PAYLOAD, m.arg1);
                    }
                    if (log != null) {
                        log.log(AdbLog.INFO, "[adb] 握手成功：设备版本=0x"
                                + Integer.toHexString(m.arg0) + " maxData=" + m.arg1);
                    }
                    break;
                } else if (m.cmd == A_AUTH) {
                    trace0(log, "←", m.cmd, m.arg0, m.arg1, m.data == null ? 0 : m.data.length);
                    if (m.arg0 == AUTH_TOKEN) {
                        if (!signed) {
                            writeMessage(out, A_AUTH, AUTH_SIGNATURE, 0, crypto.sign(m.data));
                            signed = true;
                            trace0(log, "→", A_AUTH, AUTH_SIGNATURE, 0, 0);
                        } else if (!pubSent) {
                            writeMessage(out, A_AUTH, AUTH_PUBKEY, 0, crypto.publicKeyPayload());
                            pubSent = true;
                            trace0(log, "→", A_AUTH, AUTH_PUBKEY, 0, 0);
                            s.setSoTimeout(90000);
                            if (log != null) {
                                log.log(AdbLog.WARN, "设备未信任本机密钥，等待设备端确认授权（约 90 秒）…");
                            }
                        }
                    }
                } else if (m.cmd == A_CLSE) {
                    throw new IOException("设备拒绝连接");
                } else {
                    trace0(log, "←", m.cmd, m.arg0, m.arg1, m.data == null ? 0 : m.data.length);
                }
            }

            s.setSoTimeout(0);
            AdbConnection conn = new AdbConnection(s, out, in, host, port, peerMax, log);
            conn.startReader();
            return conn;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw new IOException("ADB 认证失败：" + e.getMessage(), e);
        }
    }

    public String getAddress() {
        return host + ":" + port;
    }

    /**
     * 探测某个地址是否为 ADB 设备：连上后发一次 CNXN，只要收到 CNXN/AUTH 就认定是 ADB 设备。
     * 只做握手，不建立长连接，用于网段扫描。
     */
    public static boolean probe(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout(readTimeoutMs);
            OutputStream out = s.getOutputStream();
            writeMessage(out, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::".getBytes("UTF-8"));
            Msg m = readMessage(s.getInputStream());
            return m.cmd == A_CNXN || m.cmd == A_AUTH;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }

    int peerMaxData() {
        return peerMaxData;
    }

    void send(int cmd, int arg0, int arg1, byte[] payload) throws IOException {
        if (closed) {
            throw new IOException("连接已关闭");
        }
        writeMessage(out, cmd, arg0, arg1, payload);
    }

    /**
     * 打开一个 adb service，如 exec:xxx / shell:xxx / sync: / localabstract:xxx。
     * <p>服务名必须补上结尾 NUL：Android 11 的 adbd 对 localabstract: 这类名字做整串比对，
     * 少了结尾 NUL 会直接回 CLSE（日志表现为"设备拒绝打开该服务"），实测带 NUL 才回 OKAY。
     */
    public AdbStream openStream(String service, int timeoutMs) throws IOException {
        if (closed) {
            throw new IOException("连接已关闭");
        }
        int id = nextId.incrementAndGet();
        AdbStream stream = new AdbStream(this, id);
        streams.put(id, stream);
        String svc = service.endsWith("\0") ? service : service + "\0";
        send(A_OPEN, id, 0, svc.getBytes("UTF-8"));
        trace("→", A_OPEN, id, 0, svc.length());
        if (!stream.awaitOpen(timeoutMs)) {
            streams.remove(id);
            String reason = stream.getOpenError();
            stream.destroy();
            throw new IOException("打开服务失败：" + service
                    + (reason == null ? "（设备无响应）" : "（" + reason + "）"));
        }
        return stream;
    }

    void removeStream(int localId) {
        streams.remove(localId);
    }

    public void close() {
        closed = true;
        for (AdbStream s : streams.values()) {
            s.destroy();
        }
        streams.clear();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void startReader() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!closed) {
                        Msg m = readMessage(in);
                        if (m.cmd == A_OKAY) {
                            trace("←", m.cmd, m.arg0, m.arg1, 0);
                            AdbStream s = streams.get(m.arg1);
                            if (s != null) {
                                s.onOkay(m.arg0);
                            }
                        } else if (m.cmd == A_WRTE) {
                            AdbStream s = streams.get(m.arg1);
                            if (s != null) {
                                s.onData(m.data);
                                try {
                                    // 本机 local-id 在前，设备 remote-id 在后
                                    send(A_OKAY, m.arg1, m.arg0, null);
                                } catch (IOException ignored) {
                                }
                            }
                        } else if (m.cmd == A_CLSE) {
                            trace("←", m.cmd, m.arg0, m.arg1, 0);
                            AdbStream s = streams.get(m.arg1);
                            if (s != null) {
                                s.onRemoteClose();
                                streams.remove(m.arg1);
                            }
                            // CLSE 是终止包，按 ADB 协议不需要回包（回包会让 adbd 认为在关闭未知流）
                        }
                    }
                } catch (Exception e) {
                    if (!closed) {
                        closed = true;
                        for (AdbStream s : streams.values()) {
                            s.onBroken(e.getMessage());
                        }
                        streams.clear();
                        if (log != null) {
                            log.log(AdbLog.ERR, "[adb] 读线程退出：" + e);
                        }
                        notifyClosed();
                    }
                }
            }
        }, "adb-reader");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- message

    static final class Msg {
        int cmd;
        int arg0;
        int arg1;
        byte[] data;
    }

    private static void writeMessage(OutputStream out, int cmd, int arg0, int arg1, byte[] payload)
            throws IOException {
        int len = payload == null ? 0 : payload.length;
        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(len);
        bb.putInt(len == 0 ? 0 : crc(payload));
        bb.putInt(~cmd);
        if (len > 0) {
            bb.put(payload);
        }
        synchronized (out) {
            out.write(bb.array());
            out.flush();
        }
    }

    private static Msg readMessage(InputStream in) throws IOException {
        byte[] head = new byte[24];
        readFully(in, head);
        ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // crc
        int magic = bb.getInt();
        if (magic != (~m.cmd)) {
            throw new IOException("ADB 消息校验失败（magic 不匹配）");
        }
        if (len < 0 || len > 16 * 1024 * 1024) {
            throw new IOException("ADB 消息长度异常：" + len);
        }
        if (len > 0) {
            byte[] data = new byte[len];
            readFully(in, data);
            m.data = data;
        } else {
            m.data = new byte[0];
        }
        return m;
    }

    static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("连接已被设备断开");
            }
            off += n;
        }
    }

    private static int crc(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data, 0, data.length);
        return (int) c.getValue();
    }
}
