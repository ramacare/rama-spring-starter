package org.rama.starter.crypto;

public interface TextEncryptor {
    String encrypt(String value);
    String decrypt(String value);
}
