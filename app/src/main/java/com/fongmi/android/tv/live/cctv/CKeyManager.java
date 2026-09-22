package com.fongmi.android.tv.live.cctv;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 央视频（CKey）协议加密引擎——由 Python 版 {@code live_ysp.py} 的 {@code CKeyManager} 移植。
 *
 * <p>实现 TEA-ECB 加解密、自定义 Base64 变表、OISymmetry 分组加密（类 XXTEA/OFB 结构）
 * 以及 cKey / ck_guard_time 的构造。全部为确定性位运算，无外部依赖。
 */
public final class CKeyManager {

    private static final int DELTA = 0x9e3779b9;
    private static final int ROUNDS = 16;
    private static final int LOG_ROUNDS = 4;
    private static final int SALT_LEN = 2;
    private static final int ZERO_LEN = 7;

    /** TEA_CKEY */
    private static final byte[] TEA_CKEY = hex("59b2f7cf725ef43c34fdd7c123411ed3");
    /** GUARD_TEA_KEY */
    private static final byte[] GUARD_TEA_KEY = hex("110DBEC10C23E7D2E56A1CAD6914EF1B");

    private static final byte[] XOR_KEY = {
            (byte) 0x84, (byte) 0x2E, (byte) 0xED, (byte) 0x08,
            (byte) 0xF0, (byte) 0x66, (byte) 0xE6, (byte) 0xEA,
            (byte) 0x48, (byte) 0xB4, (byte) 0xCA, (byte) 0xA9,
            (byte) 0x91, (byte) 0xED, (byte) 0x6F, (byte) 0xF3
    };

    private static final byte[] GUARD_XOR_KEY = {
            (byte) 0xB3, (byte) 0xC9, (byte) 0x53, (byte) 0xA0,
            (byte) 0x69, (byte) 0x13, (byte) 0xAD, (byte) 0x4D
    };

    private static final String STANDARD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
    private static final String CUSTOM_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-=";

    /** 自定义字母表 → 标准字母表 的映射表（用于解码） */
    private static final int[] CUSTOM_TO_STD = new int[128];
    /** 标准字母表 → 自定义字母表 的映射表（用于编码） */
    private static final int[] STD_TO_CUSTOM = new int[128];

    static {
        for (int i = 0; i < 128; i++) {
            CUSTOM_TO_STD[i] = i;
            STD_TO_CUSTOM[i] = i;
        }
        for (int i = 0; i < 64; i++) {
            CUSTOM_TO_STD[CUSTOM_ALPHABET.charAt(i)] = STANDARD_ALPHABET.charAt(i);
            STD_TO_CUSTOM[STANDARD_ALPHABET.charAt(i)] = CUSTOM_ALPHABET.charAt(i);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String guid;

    public CKeyManager() {
        this.guid = generateGuid();
    }

    public String getGuid() {
        return guid;
    }

    // ---------------------------------------------------------------- 工具

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** {@code random.getrandbits(bits)} 的等价实现（bits 为 8 的倍数）。 */
    private static long getRandBits(int bits) {
        int bytes = bits / 8;
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        long v = 0;
        for (byte b : buf) v = (v << 8) | (b & 0xFFL);
        return v;
    }

    private static String hexLower(long value, int width) {
        String s = Long.toHexString(value);
        while (s.length() < width) s = "0" + s;
        return s.length() > width ? s.substring(s.length() - width) : s;
    }

    private static String hexUpper(long value, int width) {
        return hexLower(value, width).toUpperCase();
    }

    // ---------------------------------------------------------------- GUID

    public static String generateGuid() {
        StringBuilder sb = new StringBuilder(32);
        sb.append(hexLower(getRandBits(32), 8));
        sb.append(hexLower(getRandBits(16), 4));
        sb.append(hexLower(getRandBits(16), 4));
        sb.append(hexLower(getRandBits(16), 4));
        sb.append(hexLower(getRandBits(48), 12));
        while (sb.length() < 32) sb.append('0');
        return sb.substring(0, 32);
    }

    /** 校验和：{@code sig = sig * 0x83 + b}，按 31 位截断。 */
    public static int calcSignature(byte[] buffer) {
        int signature = 0;
        for (byte b : buffer) {
            signature = (0x83 * signature + (b & 0xFF)) & 0x7FFFFFFF;
        }
        return signature;
    }

    // ------------------------------------------------------- 自定义 Base64

    /** 自定义字母表 Base64 解码。 */
    public byte[] customDecode(String text) {
        if (text == null || text.isEmpty()) return new byte[0];
        String t = text;
        while (t.endsWith("=")) t = t.substring(0, t.length() - 1);
        int rem = t.length() % 4;
        if (rem != 0) {
            StringBuilder sb = new StringBuilder(t);
            for (int i = 0; i < 4 - rem; i++) sb.append('=');
            t = sb.toString();
        }
        // 自定义字母表 → 标准字母表
        StringBuilder std = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            std.append(c < 128 ? (char) CUSTOM_TO_STD[c] : c);
        }
        try {
            return java.util.Base64.getDecoder().decode(std.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 自定义字母表 Base64 编码（去掉尾部 '='）。 */
    public String customEncode(byte[] data) {
        String encoded = java.util.Base64.getEncoder().encodeToString(data);
        StringBuilder out = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            out.append(c == '=' ? '=' : (c < 128 ? (char) STD_TO_CUSTOM[c] : c));
        }
        // rstrip('=')
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '=') end--;
        return out.substring(0, end);
    }

    // ---------------------------------------------------------------- XOR

    public byte[] xorArray(byte[] input) {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = (byte) (input[i] ^ XOR_KEY[i & 0xF]);
        }
        return out;
    }

    // ---------------------------------------------------------------- TEA

    public byte[] teaEncryptEcb(byte[] in, byte[] key) {
        byte[] buf = in;
        if (buf.length < 8) {
            buf = new byte[8];
            System.arraycopy(in, 0, buf, 0, in.length);
        }
        int y = readBE32(buf, 0);
        int z = readBE32(buf, 4);
        int k0 = readBE32(key, 0), k1 = readBE32(key, 4);
        int k2 = readBE32(key, 8), k3 = readBE32(key, 12);
        int sum = 0;
        for (int i = 0; i < ROUNDS; i++) {
            sum += DELTA;
            y += ((z << 4) + k0) ^ (z + sum) ^ ((z >>> 5) + k1);
            z += ((y << 4) + k2) ^ (y + sum) ^ ((y >>> 5) + k3);
        }
        byte[] out = new byte[8];
        writeBE32(out, 0, y);
        writeBE32(out, 4, z);
        return out;
    }

    public byte[] teaDecryptEcb(byte[] in, byte[] key) {
        int y = readBE32(in, 0);
        int z = readBE32(in, 4);
        int k0 = readBE32(key, 0), k1 = readBE32(key, 4);
        int k2 = readBE32(key, 8), k3 = readBE32(key, 12);
        int sum = DELTA << LOG_ROUNDS;
        for (int i = 0; i < ROUNDS; i++) {
            z -= ((y << 4) + k2) ^ (y + sum) ^ ((y >>> 5) + k3);
            y -= ((z << 4) + k0) ^ (z + sum) ^ ((z >>> 5) + k1);
            sum -= DELTA;
        }
        byte[] out = new byte[8];
        writeBE32(out, 0, y);
        writeBE32(out, 4, z);
        return out;
    }

    private static int readBE32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeBE32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    // -------------------------------------------------- OISymmetry 分组加密

    public byte[] oiSymmetryEncrypt2(byte[] inBuf, int inLen, byte[] key) {
        int nPadSaltBodyZeroLen = inLen + 1 + SALT_LEN + ZERO_LEN;
        int nPadLen = nPadSaltBodyZeroLen % 8;
        if (nPadLen != 0) nPadLen = 8 - nPadLen;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] srcBuf = new byte[8];
        srcBuf[0] = (byte) ((nextInt(256) & 0xF8) | nPadLen);
        int srcI = 1;
        while (nPadLen > 0) {
            srcBuf[srcI++] = (byte) nextInt(256);
            nPadLen--;
        }

        byte[] ivPlain = new byte[8];
        byte[] ivCrypt = new byte[8];

        int i = 0;
        while (i < SALT_LEN) {
            if (srcI < 8) {
                srcBuf[srcI++] = (byte) nextInt(256);
                i++;
            }
            if (srcI == 8) {
                for (int j = 0; j < 8; j++) srcBuf[j] ^= ivCrypt[j];
                byte[] tmp = teaEncryptEcb(srcBuf, key);
                byte[] tmpBytes = new byte[8];
                for (int j = 0; j < 8; j++) tmpBytes[j] = (byte) (tmp[j] ^ ivPlain[j]);
                ivPlain = srcBuf.clone();
                ivCrypt = tmpBytes;
                out.write(tmpBytes, 0, 8);
                srcI = 0;
            }
        }

        int inIdx = 0;
        int remaining = inLen;
        while (remaining > 0) {
            if (srcI < 8) {
                srcBuf[srcI++] = inBuf[inIdx++];
                remaining--;
            }
            if (srcI == 8) {
                for (int j = 0; j < 8; j++) srcBuf[j] ^= ivCrypt[j];
                byte[] tmp = teaEncryptEcb(srcBuf, key);
                byte[] tmpBytes = new byte[8];
                for (int j = 0; j < 8; j++) tmpBytes[j] = (byte) (tmp[j] ^ ivPlain[j]);
                ivPlain = srcBuf.clone();
                ivCrypt = tmpBytes;
                out.write(tmpBytes, 0, 8);
                srcI = 0;
            }
        }

        i = 0;
        while (i < ZERO_LEN) {
            if (srcI < 8) {
                srcBuf[srcI++] = 0;
                i++;
            }
            if (srcI == 8) {
                for (int j = 0; j < 8; j++) srcBuf[j] ^= ivCrypt[j];
                byte[] tmp = teaEncryptEcb(srcBuf, key);
                byte[] tmpBytes = new byte[8];
                for (int j = 0; j < 8; j++) tmpBytes[j] = (byte) (tmp[j] ^ ivPlain[j]);
                ivPlain = srcBuf.clone();
                ivCrypt = tmpBytes;
                out.write(tmpBytes, 0, 8);
                srcI = 0;
            }
        }

        if (srcI > 0) {
            for (int j = srcI; j < 8; j++) srcBuf[j] = 0;
            for (int j = 0; j < 8; j++) srcBuf[j] ^= ivCrypt[j];
            byte[] tmp = teaEncryptEcb(srcBuf, key);
            byte[] tmpBytes = new byte[8];
            for (int j = 0; j < 8; j++) tmpBytes[j] = (byte) (tmp[j] ^ ivPlain[j]);
            out.write(tmpBytes, 0, 8);
        }

        return out.toByteArray();
    }

    /**
     * OISymmetry 对称解密（严格对应 Python 版 {@code oi_symmetry_decrypt2}）。
     *
     * <p>注意其链式结构与标准 CBC 不同：每个新块先用 {@code iv_cur_crypt} 解扰，
     * 再 TEA 解密，最后按 {@code dest_buf[i] ^ iv_pre_crypt[i]} 还原明文字节。
     *
     * @return 明文，输入不合法时返回 null
     */
    public byte[] oiSymmetryDecrypt2(byte[] inBuf, int inLen, byte[] key) {
        if (inLen % 8 != 0 || inLen < 16) return null;

        byte[] destBuf = teaDecryptEcb(java.util.Arrays.copyOf(inBuf, 8), key);
        int padLen = destBuf[0] & 0x07;

        int outLen = inLen - 1 - padLen - SALT_LEN - ZERO_LEN;
        if (outLen < 0) return null;

        byte[] ivPreCrypt = new byte[8];
        byte[] ivCurCrypt = java.util.Arrays.copyOf(inBuf, 8);
        int inOffset = 8;
        int destI = 1 + padLen;

        // 跳过 SALT_LEN 个字节
        int saltCount = 1;
        while (saltCount <= SALT_LEN) {
            if (destI < 8) {
                destI++;
                saltCount++;
            } else if (destI == 8) {
                ivPreCrypt = ivCurCrypt.clone();
                if (inOffset + 8 > inLen) return null;
                ivCurCrypt = java.util.Arrays.copyOfRange(inBuf, inOffset, inOffset + 8);
                for (int j = 0; j < 8; j++) destBuf[j] ^= ivCurCrypt[j];
                destBuf = teaDecryptEcb(destBuf, key);
                inOffset += 8;
                destI = 0;
            }
        }

        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        int remaining = outLen;
        while (remaining > 0) {
            if (destI < 8) {
                plain.write(destBuf[destI] ^ ivPreCrypt[destI]);
                destI++;
                remaining--;
            } else if (destI == 8) {
                ivPreCrypt = ivCurCrypt.clone();
                if (inOffset + 8 > inLen) return null;
                ivCurCrypt = java.util.Arrays.copyOfRange(inBuf, inOffset, inOffset + 8);
                for (int j = 0; j < 8; j++) destBuf[j] ^= ivCurCrypt[j];
                destBuf = teaDecryptEcb(destBuf, key);
                inOffset += 8;
                destI = 0;
            }
        }
        return plain.toByteArray();
    }

    private static int nextInt(int bound) {
        return RANDOM.nextInt(bound);
    }

    // ------------------------------------------------- 业务：ck_guard_time

    private static String guardLastFive(Object value) {
        String s = String.valueOf(value);
        return s.length() >= 5 ? s.substring(s.length() - 5) : "";
    }

    /** 生成 ck_guard_time（大写十六进制）。 */
    public String generateCkGuardTime(long timestamp, String guid,
                                      String guardData, String packageName, String processName) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeBE32(body, (int) timestamp);
        for (String part : new String[]{
                guardLastFive(guid), guardLastFive(packageName),
                guardLastFive(processName), guardData}) {
            byte[] pb = part.getBytes(StandardCharsets.UTF_8);
            writeBE16(body, pb.length);
            body.write(pb, 0, pb.length);
        }
        byte[] bodyBytes = body.toByteArray();

        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        writeBE16(plain, bodyBytes.length);
        plain.write(bodyBytes, 0, bodyBytes.length);
        byte[] plainBytes = plain.toByteArray();

        int checksum = calcSignature(plainBytes);
        byte[] encrypted = oiSymmetryEncrypt2(plainBytes, plainBytes.length, GUARD_TEA_KEY);

        byte[] result = new byte[encrypted.length + 4];
        System.arraycopy(encrypted, 0, result, 0, encrypted.length);
        writeBE32(result, encrypted.length, checksum);

        for (int i = 0; i < result.length; i++) {
            result[i] ^= GUARD_XOR_KEY[i & 7];
        }
        return toHexUpper(result);
    }

    public String generateCkGuardTime(long timestamp, String guid) {
        return generateCkGuardTime(timestamp, guid, "-1", "null", "null");
    }

    // ------------------------------------------------- 业务：encrypt ckey

    /** 数据加密为 cKey（前缀 {@code --01}）。 */
    public String encryptDataToCkey(byte[] data) {
        int len = data.length;
        int checksum = calcSignature(data);
        byte[] encrypted = oiSymmetryEncrypt2(data, len, TEA_CKEY);

        byte[] withSum = new byte[encrypted.length + 4];
        System.arraycopy(encrypted, 0, withSum, 0, encrypted.length);
        writeBE32(withSum, encrypted.length, checksum);

        byte[] xored = xorArray(withSum);
        return "--01" + customEncode(xored);
    }

    /** cKey 解密为原始数据，失败返回 null。 */
    public byte[] decryptCkeyToData(String ckey) {
        if (ckey == null || ckey.length() <= 4) return null;
        byte[] decoded = customDecode(ckey.substring(4));
        if (decoded == null) return null;
        byte[] xored = xorArray(decoded);
        if (xored.length < 4) return null;
        int dataLen = xored.length - 4;
        byte[] encryptedData = java.util.Arrays.copyOf(xored, dataLen);
        return oiSymmetryDecrypt2(encryptedData, dataLen, TEA_CKEY);
    }

    // ------------------------------------------------------- 业务：packet

    /** 构造播放请求数据包（含头部签名回填）。 */
    public byte[] buildPacket(long platform, long timestamp, String sdtfrom, String randFlag,
                              String appVer, String vid, String uuid4, String ckGuardTime) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] head = hex("0000004200000004000004d2");
        data.write(head, 0, head.length);
        writeBE32(data, (int) platform);
        writeBE32(data, 0);
        writeBE32(data, (int) timestamp);

        writePascalString(data, sdtfrom);
        writePascalString(data, randFlag);
        writePascalString(data, appVer);
        writePascalString(data, vid);
        writePascalString(data, guid);

        writeBE32(data, 1);
        writeBE32(data, 1);
        writePascalString(data, "2622783A");
        writePascalString(data, "nil");
        writePascalString(data, uuid4);
        writePascalString(data, "nil");
        writePascalString(data, "v0.1.000");
        writePascalString(data, "com.cctv.yangshipin.app.iphone");
        writePascalString(data, "4330403");
        writePascalString(data, "ex_json_bus");
        writePascalString(data, "ex_json_vs");
        writePascalString(data, ckGuardTime);

        byte[] body = data.toByteArray();
        byte[] buffer = new byte[body.length + 2];
        writeBE16(buffer, 0, body.length);
        System.arraycopy(body, 0, buffer, 2, body.length);

        int signature = calcSignature(buffer);
        writeBE32(buffer, 18, signature);
        return buffer;
    }

    private void writePascalString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeBE16(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeBE16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeBE16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static void writeBE32(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static String toHexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString().toUpperCase();
    }

    // ------------------------------------------------------- 业务：ckey

    /** 生成 cKey 及其请求参数。 */
    public CKeyResult generateCkey(String cnlid, Long timestampOrNull) {
        long timestamp = timestampOrNull != null ? timestampOrNull : System.currentTimeMillis() / 1000L;

        byte[] randBytes = new byte[18];
        RANDOM.nextBytes(randBytes);
        String randFlag = java.util.Base64.getEncoder().encodeToString(randBytes);

        String uuid4 = hexLower(getRandBits(16), 4) + hexLower(getRandBits(16), 4) + "-"
                + hexLower(getRandBits(16), 4) + "-"
                + hexLower(getRandBits(16), 4) + "-"
                + hexLower(getRandBits(16), 4) + "-"
                + hexLower(getRandBits(16), 4) + hexLower(getRandBits(16), 4) + hexLower(getRandBits(16), 4);

        String ckGuardTime = generateCkGuardTime(timestamp, guid);

        byte[] buffer = buildPacket(4330403L, timestamp, "dcgh", randFlag,
                "V8.22.1035.3031", cnlid, uuid4, ckGuardTime);
        String ckey = encryptDataToCkey(buffer);
        return new CKeyResult(ckey, timestamp);
    }

    /** cKey 生成结果。 */
    public static final class CKeyResult {
        public final String ckey;
        public final long timestamp;

        public CKeyResult(String ckey, long timestamp) {
            this.ckey = ckey;
            this.timestamp = timestamp;
        }
    }

    /** 生成 flowid。 */
    public static String generateFlowId() {
        return hexUpper(getRandBits(16), 4) + hexUpper(getRandBits(16), 4) + "-"
                + hexUpper(getRandBits(16), 4) + "-"
                + hexUpper(getRandBits(16), 4) + "-"
                + hexUpper(getRandBits(16), 4) + "-"
                + hexUpper(getRandBits(16), 4) + hexUpper(getRandBits(16), 4) + hexUpper(getRandBits(16), 4)
                + "_4330403";
    }
}
