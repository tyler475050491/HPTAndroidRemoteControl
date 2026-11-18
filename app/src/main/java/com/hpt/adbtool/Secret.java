package com.hpt.adbtool;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.security.MessageDigest;

/**
 * 内置包（hpt_payload.dat）的密钥派生。
 * <p>
 * 两道绑定，任何一条不成立都解不出内置安装包：
 * 1) 口令与盐不写成明文字符串，改成 XOR 编码的字节表，运行时还原；
 *    这样即使把 dex 拖进 jadx 逐行看，也看不到可用的口令常量。
 * 2) 最终密钥 = SHA-256( 口令与盐 + "|" + 本应用 APK 的签名证书 SHA-256 )。
 *    也就是说：资源文件被单独拷到别处、或外壳 APK 被换成别的证书重打包，
 *    派生出的密钥都对不上，GCM 校验会直接失败，拿不到明文的 APK。
 * <p>
 * 构建侧（app/build.gradle 的 syncBuiltinApk 任务）用同一份证书文件算出同一个密钥，
 * 两侧算法必须完全一致，改动这里要同步改构建脚本。
 */
final class Secret {

    /** "HPT2026#AdbTool#Builtin|hpt-builtin-apk-v1" 逐字节 XOR 0x5A 后的结果 */
    private static final int[] BLOB = {
            18, 10, 14, 104, 106, 104, 108, 121, 27, 62, 56, 14,
            53, 53, 54, 121, 24, 47, 51, 54, 46, 51, 52, 38,
            50, 42, 46, 119, 56, 47, 51, 54, 46, 51, 52, 119,
            59, 42, 49, 119, 44, 107,
    };

    private Secret() {
    }

    static byte[] key(Context ctx) throws Exception {
        byte[] blob = blob();
        byte[] cert = certDigest(ctx);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(blob);
        md.update((byte) '|');
        md.update(cert);
        return md.digest();
    }

    /** 密钥校验值：容器头部写进去的 4 字节，比较它就能在解密前判断签名是否匹配 */
    static int[] keyCheck(byte[] key) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
        return new int[]{h[0] & 0xFF, h[1] & 0xFF, h[2] & 0xFF, h[3] & 0xFF};
    }

    private static byte[] blob() {
        byte[] out = new byte[BLOB.length];
        for (int i = 0; i < BLOB.length; i++) {
            out[i] = (byte) (BLOB[i] ^ 0x5A);
        }
        return out;
    }

    private static byte[] certDigest(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null || pi.signingInfo.getApkContentsSigners().length == 0) {
                throw new Exception("读不到本应用的签名信息");
            }
            return MessageDigest.getInstance("SHA-256")
                    .digest(pi.signingInfo.getApkContentsSigners()[0].toByteArray());
        }
        PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
        if (pi.signatures == null || pi.signatures.length == 0) {
            throw new Exception("读不到本应用的签名信息");
        }
        return MessageDigest.getInstance("SHA-256").digest(pi.signatures[0].toByteArray());
    }
}
