package io.github.ultra0magnus.discordeasylogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator();

    @Test
    void generatesRequestedLength() {
        assertEquals(8, generator.generate(8).length());
        assertEquals(4, generator.generate(4).length());
    }

    @Test
    void usesOnlyUnambiguousUppercaseCharacters() {
        for (int i = 0; i < 500; i++) {
            for (char c : generator.generate(8).toCharArray()) {
                assertTrue(CodeGenerator.ALPHABET.indexOf(c) >= 0, "unexpected character: " + c);
            }
        }
        for (char c : "OIL01".toCharArray()) {
            assertTrue(CodeGenerator.ALPHABET.indexOf(c) < 0, "lookalike character in alphabet: " + c);
        }
    }

    @Test
    void doesNotRepeatCodes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertTrue(seen.add(generator.generate(8)), "duplicate code generated");
        }
    }

    @Test
    void rejectsTooShortCodes() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(3));
    }
}
