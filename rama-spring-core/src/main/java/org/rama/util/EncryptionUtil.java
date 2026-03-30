package org.rama.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
public final class EncryptionUtil {
    private static final String ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int KEY_SIZE = 16;
    private static final int IV_SIZE = 16;
    @Getter
    private static volatile String key;

    private EncryptionUtil() {
    }

    public static void setKey(String key) {
        EncryptionUtil.key = key;
    }

    public static boolean isConfigured() {
        return key != null && !key.isBlank();
    }

    public static String encrypt(String value) {
        if (value == null) {
            return null;
        }
        if (!isConfigured()) {
            return value;
        }
        try {
            return Base64.getEncoder().encodeToString(encryptToByteArray(value));
        } catch (Exception ex) {
            log.error(ex.getMessage());
            return null;
        }
    }

    public static String decrypt(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        if (!isConfigured()) {
            return encrypted;
        }
        try {
            return new String(decryptToByteArray(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error(ex.getMessage());
            return null;
        }
    }

    public static String base64UrlDecodeToString(String value) {
        if (value == null) {
            return null;
        }
        String padded = value.replace('-', '+').replace('_', '/');
        int mod = padded.length() % 4;
        if (mod == 2) {
            padded += "==";
        } else if (mod == 3) {
            padded += "=";
        } else if (mod != 0) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error(ex.getMessage());
            return null;
        }
    }

    public static String xorObfuscate(String input) {
        return xorObfuscate(input, key);
    }

    public static String xorObfuscate(String input, String key) {
        if (input == null) {
            return null;
        }
        if (key == null || key.isEmpty()) {
            return input;
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[inputBytes.length];
        for (int i = 0; i < inputBytes.length; i++) {
            output[i] = (byte) (inputBytes[i] ^ keyBytes[i % keyBytes.length]);
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    public static byte[] encryptToByteArray(String value) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        return encryptToByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] encryptToByteArray(byte[] value) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] initVector = new byte[IV_SIZE];
        new SecureRandom().nextBytes(initVector);

        byte[] encrypted = encryptToByteArray(value, initVector);

        byte[] ivAndEncrypted = new byte[IV_SIZE + encrypted.length];
        System.arraycopy(initVector, 0, ivAndEncrypted, 0, IV_SIZE);
        System.arraycopy(encrypted, 0, ivAndEncrypted, IV_SIZE, encrypted.length);

        return ivAndEncrypted;
    }

    public static byte[] encryptToByteArray(byte[] value, byte[] initVector) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        if (!isConfigured()) {
            return value;
        }
        IvParameterSpec ivSpec = new IvParameterSpec(adjustByteSize(initVector, IV_SIZE));
        SecretKeySpec keySpec = new SecretKeySpec(adjustByteSize(key.getBytes(StandardCharsets.UTF_8), KEY_SIZE), ALGORITHM);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(value);
    }

    public static byte[] decryptToByteArray(byte[] value) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        if (!isConfigured()) {
            return value;
        }
        return decryptToByteArray(Arrays.copyOfRange(value, IV_SIZE, value.length), Arrays.copyOfRange(value, 0, IV_SIZE));
    }

    public static byte[] decryptToByteArray(byte[] value, byte[] initVector) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        if (!isConfigured()) {
            return value;
        }
        IvParameterSpec ivSpec = new IvParameterSpec(adjustByteSize(initVector, IV_SIZE));
        SecretKeySpec keySpec = new SecretKeySpec(adjustByteSize(key.getBytes(StandardCharsets.UTF_8), KEY_SIZE), ALGORITHM);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(value);
    }

    private static byte[] adjustByteSize(byte[] byteValue, int size) {
        byte[] adjustedByteValue = new byte[size];
        if (byteValue.length > size) {
            System.arraycopy(byteValue, 0, adjustedByteValue, 0, size);
        } else {
            System.arraycopy(byteValue, 0, adjustedByteValue, 0, byteValue.length);
            for (int i = byteValue.length; i < size; i++) {
                adjustedByteValue[i] = 0;
            }
        }
        return adjustedByteValue;
    }
}
