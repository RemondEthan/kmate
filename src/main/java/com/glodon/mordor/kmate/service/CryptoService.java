package com.glodon.mordor.kmate.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM。密钥按 design.md：MD5(password + padding) 的 32 位 hex 字符串作为 32 字节密钥。
 * 密文：Base64(IV(12) + ciphertext + tag(16))。
 *
 * AES-GCM 是什么：对称加密 + 认证加密算法。
 *   - 对称加密：加密和解密用同一把密钥（这里就是 password 派生的 32 字节 key）。
 *   - GCM (Galois/Counter Mode)：除了密文还会算一个 16 字节 tag，验证密文是否被篡改。
 *   - IV (Initialization Vector)：每次加密都用一个随机 12 字节 IV，保证同一明文每次密文不同。
 *
 * 密文打包格式（与 KServer 约定）：
 *   Base64( IV[12] || ciphertext || tag[16] )
 *   解密时先剥 Base64，再切出 IV，剩下的部分交给 Cipher.doFinal() 同时验签和出明文。
 *
 * 密钥派生流程：
 *   key_bytes = MD5( password + padding ).hexString.asciiBytes   // 32 字节（hex 字符的 ASCII）
 *   用 key_bytes 当 AES-256 key（虽然 hex 表示只有 128 bit 熵，但协议就这样定）。
 *
 * 两种用途：
 *   - 在线会话：padding 由服务端在 register 帧返回（每房间不同），用于 text/avatar 加解密。
 *   - 本地档案：forArchive() 用固定 padding "|archive|<imCode>"，保证历史文件能复访。
 */
public final class CryptoService {

    /**
     * 加密相关异常。RuntimeException 而不是 checked，避免污染调用链签名。
     */
    public static final class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }

        public CryptoException(String message) {
            super(message);
        }
    }

    // AES-GCM 标准 IV 长度。
    private static final int IV_LEN = 12;
    // GCM 认证 tag 位长（128 bit = 16 字节）。
    private static final int TAG_BITS = 128;
    // 同上转字节。
    private static final int TAG_LEN = 16;

    // SecureRandom 是线程安全的，每次 encrypt() 都重新取 12 字节。
    private final SecureRandom random = new SecureRandom();
    // 派生出来的 32 字节密钥。initialize() 之后才非 null。
    private byte[] key;

    /**
     * 构造一个用于本地档案加解密的实例：padding 固定为 "|archive|<imCode>"，
     * 同一个 imCode 派生出来的 key 永远一致，确保历史文件能复访。
     */
    public static CryptoService forArchive(String password, String imCode) {
        CryptoService crypto = new CryptoService();
        crypto.initialize(password, "|archive|" + imCode);
        return crypto;
    }

    /**
     * 用给定 password + padding 派生 32 字节 AES key。
     * 在线会话里 padding 用 Server 返回的随机串；
     * 档案场景里 padding 是固定的 "|archive|<imCode>"。
     */
    public void initialize(String password, String padding) {
        this.key = deriveKey(password, padding);
    }

    /**
     * 是否已派生密钥（未初始化的实例不能加解密）。
     */
    public boolean isReady() {
        return key != null;
    }

    /**
     * 加密：随机 IV → AES-GCM → 拼成 IV || ciphertext || tag → Base64。
     */
    public String encrypt(String plaintext) {
        ensureReady();
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);

            // Java 的 Cipher 用 provider 名称 + 模式 + padding 描述算法。
            // "AES/GCM/NoPadding" 是 GCM 模式的标准写法（认证加密，不需要 padding）。
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            // doFinal 返回 ciphertext || tag 拼接；不再额外算 tag。
            byte[] cipherAndTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[IV_LEN + cipherAndTag.length];
            System.arraycopy(iv, 0, packed, 0, IV_LEN);
            System.arraycopy(cipherAndTag, 0, packed, IV_LEN, cipherAndTag.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Encryption failed", e);
        }
    }

    /**
     * 解密：Base64 decode → 拆 IV / (cipher+tag) → AES-GCM verify+decrypt → UTF-8 字符串。
     *
     * 这里可能抛 IllegalArgumentException（Base64 不合法）、GeneralSecurityException
     * （GCM tag 校验失败 = 密钥不对或密文被改），都包成 CryptoException 抛上去。
     */
    public String decrypt(String ciphertext) {
        ensureReady();
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            // 至少要有 IV(12) + tag(16) = 28 字节，否则一定不合法。
            if (packed.length < IV_LEN + TAG_LEN) {
                throw new CryptoException("Invalid ciphertext length");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(packed, 0, iv, 0, IV_LEN);
            byte[] cipherAndTag = new byte[packed.length - IV_LEN];
            System.arraycopy(packed, IV_LEN, cipherAndTag, 0, cipherAndTag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            // GCM 的 doFinal 会同时验签：tag 对不上抛 AEADBadTagException。
            return new String(cipher.doFinal(cipherAndTag), StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new CryptoException("Decryption failed", e);
        }
    }

    /**
     * 派生密钥：MD5(password + padding) → 16 字节摘要 → 转 32 个 hex 字符 → 取 ASCII 字节。
     * 结果恰好是 32 字节，喂给 AES-256。
     *
     * 注意：MD5 用于此场景不是用来"安全 hash"，而是确定性 16 字节 → hex 32 字节 ASCII 展开成 32 字节 AES key 的桥。
     * 与服务端对齐：双方算出来的 key 必须完全一致才能解密。
     */
    static byte[] deriveKey(String password, String padding) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((password + padding).getBytes(StandardCharsets.UTF_8));
            String hex = toHex(digest);
            return hex.getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Key derivation failed", e);
        }
    }

    /**
     * 调用 encrypt/decrypt 前检查 key 是否已派生，否则抛明确异常而不是 NPE。
     */
    private void ensureReady() {
        if (key == null) {
            throw new CryptoException("CryptoService not initialized");
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
