package com.hpt.adbtool;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 单条 ADB 数据流（对应一个 adb service，例如 exec:ls、sync:）。
 */
public final class AdbStream {

    private final AdbConnection conn;
    private final int localId;

    private volatile int remoteId = -1;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>();
    private final Semaphore credits = new Semaphore(0);
    private final CountDownLatch opened = new CountDownLatch(1);

    private volatile boolean closed;
    private volatile boolean remoteClosed;
    private volatile String brokenReason;

    private byte[] pending;
    private int pendingOff;

    AdbStream(AdbConnection conn, int localId) {
        this.conn = conn;
        this.localId = localId;
    }

    boolean awaitOpen(int timeoutMs) {
        try {
            return opened.await(timeoutMs, TimeUnit.MILLISECONDS) && remoteId >= 0;
        } catch (InterruptedException e) {
            return false;
        }
    }

    void onOkay(int remote) {
        if (remoteId < 0) {
            remoteId = remote;
            opened.countDown();
        }
        credits.release();
    }

    void onData(byte[] data) {
        if (data != null && data.length > 0) {
            queue.offer(data);
        }
    }

    void onRemoteClose() {
        remoteClosed = true;
        if (remoteId < 0) {
            brokenReason = "设备拒绝打开该服务（可能不支持该 adb service）";
        }
        opened.countDown();
    }

    void onBroken(String reason) {
        brokenReason = reason;
        remoteClosed = true;
        opened.countDown();
    }

    String getOpenError() {
        return brokenReason;
    }

    /** 写入数据（按设备协商的最大包长自动分片） */
    public void write(byte[] data, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("数据流已关闭");
        }
        int remain = len;
        int pos = off;
        while (remain > 0) {
            int chunk = Math.min(remain, conn.peerMaxData());
            try {
                // 流控：设备每接收一个 WRTE 会回一个 OKAY
                credits.tryAcquire(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException("写入被中断");
            }
            if (closed) {
                throw new IOException("数据流已关闭");
            }
            byte[] buf = Arrays.copyOfRange(data, pos, pos + chunk);
            // ADB 协议：消息的 arg0 是发送方（本机）的 local-id，arg1 是对端的 remote-id
            conn.send(AdbConnection.A_WRTE, localId, remoteId, buf);
            pos += chunk;
            remain -= chunk;
        }
    }

    /** 读取数据，流结束返回 -1 */
    public int read(byte[] buf, int off, int len) throws IOException {
        while (true) {
            int n = readWithTimeout(buf, off, len, 60);
            if (n != -2) {
                return n;
            }
        }
    }

    /**
     * 带超时的读取。
     *
     * @return 读到的字节数；-1 表示流已结束；-2 表示本次超时未读到数据
     */
    public int readWithTimeout(byte[] buf, int off, int len, long timeoutMs) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (pending != null) {
            int n = Math.min(len, pending.length - pendingOff);
            System.arraycopy(pending, pendingOff, buf, off, n);
            pendingOff += n;
            if (pendingOff >= pending.length) {
                pending = null;
                pendingOff = 0;
            }
            return n;
        }

        byte[] head = queue.poll();
        if (head == null) {
            if (remoteClosed || closed) {
                if (brokenReason != null) {
                    throw new IOException(brokenReason);
                }
                return -1;
            }
            try {
                head = queue.poll(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new IOException("读取被中断");
            }
            if (head == null) {
                if (remoteClosed || closed) {
                    if (brokenReason != null) {
                        throw new IOException(brokenReason);
                    }
                    return -1;
                }
                return -2;
            }
        }

        pending = head;
        pendingOff = 0;
        int n = Math.min(len, pending.length);
        System.arraycopy(pending, 0, buf, off, n);
        pendingOff = n;
        if (pendingOff >= pending.length) {
            pending = null;
            pendingOff = 0;
        }
        return n;
    }

    /** 主动关闭（向设备发送 CLSE） */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            conn.send(AdbConnection.A_CLSE, localId, remoteId < 0 ? 0 : remoteId, null);
        } catch (IOException ignored) {
        }
        conn.removeStream(localId);
        opened.countDown();
    }

    void destroy() {
        closed = true;
        opened.countDown();
    }
}
