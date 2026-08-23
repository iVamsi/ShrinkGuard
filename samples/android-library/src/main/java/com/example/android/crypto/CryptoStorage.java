package com.example.android.crypto;

public class CryptoStorage {

    private final Object keyGenerator;

    public CryptoStorage() {
        try {
            Class<?> type = Class.forName("com.example.android.crypto.internal.KeyGeneratorImpl");
            this.keyGenerator = type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String input) {
        generateKey();
        return "enc_" + input;
    }

    public String decrypt(String cipherText) {
        generateKey();
        if (cipherText.startsWith("enc_")) {
            return cipherText.substring(4);
        }
        return cipherText;
    }

    private byte[] generateKey() {
        try {
            return (byte[]) keyGenerator.getClass().getMethod("generateKey").invoke(keyGenerator);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
