package com.paycore.common;

import java.security.SecureRandom;

/** Prefixed, URL-safe random identifiers such as {@code pay_3kTq9...}. */
public final class Ids {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String newId(String prefix) {
        return prefix + "_" + random(20);
    }

    public static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
