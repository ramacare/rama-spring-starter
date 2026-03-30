package org.rama.service.document;

import org.apache.commons.codec.binary.Base64;
import org.rama.util.EncryptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class VerificationCodeService {
    private static final Logger log = LoggerFactory.getLogger(VerificationCodeService.class);
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();

    public String generateCode(Long id, String subject) {
        if (id == null || subject == null) {
            return null;
        }
        String idShortenBase = shortenLongBase(id);
        byte firstByte = (byte) idShortenBase.length();

        try {
            byte[] encodedValue = EncryptionUtil.encryptToByteArray(subject.getBytes(StandardCharsets.UTF_8), longToBytes(id));
            byte[] codeByte = new byte[encodedValue.length + idShortenBase.length() + 1];
            codeByte[0] = firstByte;
            System.arraycopy(idShortenBase.getBytes(StandardCharsets.UTF_8), 0, codeByte, 1, idShortenBase.length());
            System.arraycopy(encodedValue, 0, codeByte, 1 + idShortenBase.length(), encodedValue.length);
            return Base64.encodeBase64URLSafeString(codeByte);
        } catch (Exception ex) {
            log.error(ex.getMessage());
            return null;
        }
    }

    public boolean verifyCode(String code, Long id, String subject) {
        try {
            long codeId = getIdFromCode(code);
            String codeValue = getDecodedValueFromCode(code);
            return codeId == id && codeValue.equals(subject);
        } catch (Exception ex) {
            log.error(ex.getMessage());
            return false;
        }
    }

    public long getIdFromCode(String code) {
        byte[] codeByte = Base64.decodeBase64(code);
        byte firstByte = codeByte[0];
        String idShortenBase = new String(Arrays.copyOfRange(codeByte, 1, 1 + firstByte), StandardCharsets.UTF_8);
        return expandLongBase(idShortenBase);
    }

    protected String getDecodedValueFromCode(String code) throws Exception {
        long id = getIdFromCode(code);
        byte[] encodedValue = getEncodedValueFromCode(code);
        return new String(EncryptionUtil.decryptToByteArray(encodedValue, longToBytes(id)), StandardCharsets.UTF_8);
    }

    protected byte[] getEncodedValueFromCode(String code) {
        byte[] codeByte = Base64.decodeBase64(code);
        byte firstByte = codeByte[0];
        return Arrays.copyOfRange(codeByte, 1 + firstByte, codeByte.length);
    }

    private static String shortenLongBase(long value) {
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(ALPHABET.charAt((int) (value % BASE)));
            value /= BASE;
        }
        return sb.reverse().toString();
    }

    private static long expandLongBase(String shortened) {
        long num = 0;
        for (int i = 0; i < shortened.length(); i++) {
            num = num * BASE + ALPHABET.indexOf(shortened.charAt(i));
        }
        return num;
    }

    private static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[7 - i] = (byte) (value >> (i * 8));
        }
        return bytes;
    }
}
