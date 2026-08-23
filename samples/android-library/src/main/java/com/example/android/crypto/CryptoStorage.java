package com.example.android.crypto;

public class CryptoStorage {
    public static String encrypt(String input) {
        return "enc_" + input;
    }

    public static String decrypt(String cipherText) {
        if (cipherText.startsWith("enc_")) {
            return cipherText.substring(4);
        }
        return cipherText;
    }
}
