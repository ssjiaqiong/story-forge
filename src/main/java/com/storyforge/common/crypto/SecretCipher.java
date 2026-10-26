package com.storyforge.common.crypto;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.config.CryptoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密（AES-256-GCM）。
 *
 * <p>密文格式：{@code v1:base64(iv[12] | ciphertext+tag[16])}。前缀为版本号，便于将来轮换算法。</p>
 *
 * <p>主密钥来自 {@code storyforge.crypto.secret}（生产用环境变量 {@code STORYFORGE_SECRET_KEY}），
 * 经 SHA-256 派生为 32 字节密钥。每次加密使用随机 IV，同一明文多次加密结果不同。</p>
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec keySpec;

    public SecretCipher(CryptoProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置 storyforge.crypto.secret，无法加密敏感字段");
        }
        this.keySpec = new SecretKeySpec(sha256(secret), "AES");
    }

    /** 加密明文，返回可直接入库的字符串。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv).put(encrypted);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception ex) {
            log.error("[crypto] encrypt failed", ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "敏感信息加密失败");
        }
    }

    /** 解密数据库中的密文；空值返回 {@code null}。 */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (!cipherText.startsWith(PREFIX)) {
            // 兼容历史/脏数据：不以版本号开头视为不可解密
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "密文格式不正确，请重新保存该密钥");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
            if (raw.length <= IV_LENGTH) {
                throw new IllegalArgumentException("cipher too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            System.arraycopy(raw, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            // 常见原因：主密钥被更换
            log.error("[crypto] decrypt failed, master key may have changed", ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "密钥解密失败，请重新填写 API Key");
        }
    }

    /**
     * 生成用于界面回显的掩码，例如 {@code sk-****abcd}。
     *
     * <p>明文长度不足 8 位时仅保留前 2 位，避免短密钥被还原。</p>
     */
    public String mask(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        int length = plaintext.length();
        if (length <= 8) {
            return plaintext.substring(0, Math.min(2, length)) + "****";
        }
        return plaintext.substring(0, 4) + "****" + plaintext.substring(length - 4);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化加密密钥", ex);
        }
    }
}
