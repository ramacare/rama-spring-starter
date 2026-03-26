package org.rama.starter.crypto;

public class NoOpTextEncryptor implements TextEncryptor {
    @Override
    public String encrypt(String value) {
        return value;
    }

    @Override
    public String decrypt(String value) {
        return value;
    }
}
