package net.mtree.livingplayers.bot;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BotCodecTest {
    @Test void personalityRoundTrip() {
        Personality p = PersonalityGenerator.generate(new Random(11), null);
        Personality back = BotCodec.personalityFromJson(BotCodec.personalityToJson(p));
        for (Trait t : Trait.values()) assertEquals(p.get(t), back.get(t), 1e-12);
    }
    @Test void listAndMapRoundTrip() {
        assertEquals(List.of("A", "B"), BotCodec.listFromJson(BotCodec.listToJson(List.of("A", "B"))));
        Map<String, String> m = new LinkedHashMap<>();
        m.put("s0", "AAAA=="); m.put("off", "x/y+z");
        assertEquals(m, BotCodec.mapFromJson(BotCodec.mapToJson(m)));
    }
    @Test void garbageInputNeverThrows() {
        assertTrue(BotCodec.listFromJson("{not json").isEmpty());
        assertTrue(BotCodec.mapFromJson("[1,2]").isEmpty());
        assertTrue(BotCodec.mapFromJson(null).isEmpty());
        assertEquals(0.5, BotCodec.personalityFromJson("garbage").get(Trait.COURAGE));
        assertEquals(0.5, BotCodec.personalityFromJson("{\"courage\":\"abc\"}").get(Trait.COURAGE));
    }
}
