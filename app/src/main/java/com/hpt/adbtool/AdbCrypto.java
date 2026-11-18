package com.hpt.adbtool;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * ADB 认证所需的 RSA 密钥。
 *
 * ADB 的授权机制：
 *  1. 设备发 A_AUTH(TOKEN=1) + 随机 token；
 *  2. 主机用私钥做 SHA1-RSA 签名回 A_AUTH(SIGNATURE=2)；
 *  3. 未知公钥时设备再发一次 token，主机回 A_AUTH(RSAPUBLICKEY=3)，
 *     设备屏幕弹出“是否允许 USB 调试”，用户点允许后设备发 A_CNXN 建立连接。
 *
 * 公钥payload 需要 Android 私有格式 RSAPublicKey 结构（len/n0inv/n[64]/rr[64]），
 * 否则设备无法解析、也不会弹出授权框。
 */
public final class AdbCrypto {

    private static final String PRIV_FILE = "adbkey.pk8";
    private static final String PUB_FILE = "adbkey.x509";
    private static final int WORDS = 64;      // 2048 bit = 64 * 32bit

    private final KeyPair keyPair;

    private AdbCrypto(KeyPair kp) {
        this.keyPair = kp;
    }

    public static AdbCrypto loadOrCreate(File dir) throws Exception {
        File priv = new File(dir, PRIV_FILE);
        File pub = new File(dir, PUB_FILE);
        if (priv.isFile() && pub.isFile()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                RSAPrivateKey p = (RSAPrivateKey) kf.generatePrivate(
                        new PKCS8EncodedKeySpec(readAll(priv)));
                RSAPublicKey q = (RSAPublicKey) kf.generatePublic(
                        new X509EncodedKeySpec(readAll(pub)));
                if (p.getModulus().equals(q.getModulus())) {
                    return new AdbCrypto(new KeyPair(q, p));
                }
            } catch (Exception ignored) {
                // 密钥损坏，重新生成
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return new AdbCrypto(kp);
    }

    /** 对设备下发的 token 做 PKCS#1 v1.5 + SHA1 签名 */
    public byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(token);
        return s.sign();
    }

    /** 生成 adb A_AUTH(RSAPUBLICKEY) 需要的 payload */
    public byte[] publicKeyPayload() {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        BigInteger n = pub.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);

        // n0inv = -1 / n[0] (mod 2^32)
        BigInteger inv = n.modInverse(two32);
        int n0inv = two32.subtract(inv).intValue();
        // rr = 2^(2*2048) mod n
        BigInteger rr = BigInteger.ONE.shiftLeft(2 * 2048).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(8 + WORDS * 8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(WORDS);
        bb.putInt(n0inv);
        bb.put(littleEndianFixed(n, WORDS * 4));
        bb.put(littleEndianFixed(rr, WORDS * 4));

        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        String payload = b64 + " hpt@android\u0000";
        return payload.getBytes();
    }

    private static byte[] littleEndianFixed(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        if (be.length > len) {
            byte[] cut = new byte[len];
            System.arraycopy(be, be.length - len, cut, 0, len);
            be = cut;
        } else if (be.length < len) {
            byte[] pad = new byte[len];
            System.arraycopy(be, 0, pad, len - be.length, be.length);
            be = pad;
        }
        byte[] le = new byte[len];
        for (int i = 0; i < len; i++) {
            le[i] = be[len - 1 - i];
        }
        return le;
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }
}
