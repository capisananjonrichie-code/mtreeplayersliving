package net.mtree.livingplayers.bot;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NameRulesTest {
    @Test void validation() {
        assertTrue(NameRules.isValid("Kuya_Jun"));
        assertTrue(NameRules.isValid("abc"));
        assertFalse(NameRules.isValid("ab"));
        assertFalse(NameRules.isValid("a_name_that_is_way_too_long"));
        assertFalse(NameRules.isValid("bad name"));
        assertFalse(NameRules.isValid("ñandú"));
        assertFalse(NameRules.isValid(null));
    }
    @Test void skipsTakenNamesCaseInsensitively() {
        List<String> pool = List.of("Tala", "Bayani");
        for (int i = 0; i < 50; i++)
            assertEquals("Bayani", NameRules.generate(new Random(i), pool, List.of("tala")));
    }
    @Test void numberedVariantsWhenPoolExhaustedStayValidAndUnique() {
        List<String> pool = List.of("Tala", "Bayani");
        Set<String> taken = new HashSet<>(List.of("tala", "bayani"));
        Random r = new Random(9);
        for (int i = 0; i < 100; i++) {
            String n = NameRules.generate(r, pool, taken);
            assertTrue(NameRules.isValid(n), n);
            assertTrue(taken.add(n.toLowerCase()), "duplicate generated: " + n);
        }
        assertEquals(102, taken.size());
    }
    @Test void emptyPoolFallsBackToGeneratedTag() {
        String n = NameRules.generate(new Random(1), List.of(), List.of());
        assertTrue(n.startsWith("Bot_"));
        assertTrue(NameRules.isValid(n));
    }
    @Test void longPoolNamesGetTruncatedBeforeSuffix() {
        List<String> pool = List.of("Sixteen_Chars_AB");
        String n = NameRules.generate(new Random(4), pool, List.of("Sixteen_Chars_AB"));
        assertTrue(NameRules.isValid(n), n);
        assertTrue(n.length() <= 16);
    }
}
