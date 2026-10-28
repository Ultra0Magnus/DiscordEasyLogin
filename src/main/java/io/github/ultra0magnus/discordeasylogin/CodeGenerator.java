package io.github.ultra0magnus.discordeasylogin;

import java.security.SecureRandom;

/**
 * Generates single-use verification codes.
 */
public final class CodeGenerator {

    /** Digits and uppercase letters minus the O/0 and I/1/L lookalikes, so codes survive being read aloud. */
    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();

    public String generate(int length) {
        if (length < 4) {
            throw new IllegalArgumentException("code length must be at least 4, got " + length);
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
