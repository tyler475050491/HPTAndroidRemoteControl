package com.hpt.adbtool;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内置包的加密容器读取流：容器格式
 * "HPTK"(4) | version(1) | chunkSize(4) | chunkCount(4) | keyCheck(4) | { iv(12) | ctLen(4) | ciphertext }
 * 每块独立 AES-256-GCM 加密，这里逐块解密后按顺序吐出明文字节，
 * 因此上传过程无需把明文落盘，也能在内存里只占一个块的大小。
 * <p>
 * keyCheck 是密钥 SHA-256 的头 4 字节：签名证书与打包时不一致会在这里先报错，
 * 不必等到 GCM 校验失败才发现。
 */
final class BuiltinApkStream extends InputStream {

    private static final int CHUNK_LIMIT = 8 * 1024 * 1024;
    /** 签名绑定失败时的提示，出现这个说明 APK 被换了证书重打包 */
    static final String ERR_SIGN = "内置安装包不可用，请使用正式版安装包后重试";

    private final DataInputStream in;
    private final SecretKeySpec key;
    private final int chunkSize;
    private final int chunkCount;

    private int nextChunk;
    private byte[] plain = new byte[0];
    private int pos;

    BuiltinApkStream(InputStream raw, SecretKeySpec key, int[] expectCheck) throws IOException {
        this.in = new DataInputStream(raw);
        this.key = key;
        byte[] magic = new byte[4];
        if (in.read(magic) < 4 || magic[0] != 'H' || magic[1] != 'P' || magic[2] != 'T' || magic[3] != 'K') {
            throw new IOException("内置包容器格式未知");
        }
        int version = in.readUnsignedByte();
        if (version != 2) {
            throw new IOException("内置包容器版本不支持：" + version);
        }
        chunkSize = in.readInt();
        chunkCount = in.readInt();
        int h0 = in.readUnsignedByte();
        int h1 = in.readUnsignedByte();
        int h2 = in.readUnsignedByte();
        int h3 = in.readUnsignedByte();
        if (chunkSize <= 0 || chunkSize > CHUNK_LIMIT || chunkCount < 0) {
            throw new IOException("内置包容器头部损坏");
        }
        if (expectCheck == null || expectCheck.length < 4
                || expectCheck[0] != h0 || expectCheck[1] != h1
                || expectCheck[2] != h2 || expectCheck[3] != h3) {
            throw new IOException(ERR_SIGN);
        }
    }

    /** 读取并解密下一块，返回是否有数据 */
    private boolean loadNextChunk() throws IOException {
        while (nextChunk < chunkCount) {
            nextChunk++;
            byte[] iv = new byte[12];
            byte[] head = new byte[4];
            in.readFully(iv);
            in.readFully(head);
            int ctLen = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16)
                    | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
            if (ctLen <= 0 || ctLen > chunkSize + 16) {
                throw new IOException("内置包分块长度异常：" + ctLen);
            }
            byte[] ct = new byte[ctLen];
            in.readFully(ct);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                plain = cipher.doFinal(ct);
            } catch (Exception e) {
                throw new IOException("内置安装包读取失败（文件可能已损坏，请重新安装正式版）", e);
            }
            pos = 0;
            if (plain.length > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int read() throws IOException {
        while (pos >= plain.length) {
            if (!loadNextChunk()) {
                return -1;
            }
        }
        return plain[pos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (pos >= plain.length) {
            if (!loadNextChunk()) {
                return -1;
            }
        }
        int n = Math.min(len, plain.length - pos);
        System.arraycopy(plain, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long skipped = 0;
        while (skipped < n) {
            if (pos >= plain.length && !loadNextChunk()) {
                break;
            }
            int step = (int) Math.min(n - skipped, plain.length - pos);
            pos += step;
            skipped += step;
        }
        return skipped;
    }

    @Override
    public int available() {
        return plain.length - pos;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
