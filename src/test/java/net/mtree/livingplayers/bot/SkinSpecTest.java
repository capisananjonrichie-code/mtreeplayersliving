package net.mtree.livingplayers.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkinSpecTest {
    @Test void defaultsAndGarbageFallBackToDefault() {
        assertEquals(SkinSpec.Kind.DEFAULT, SkinSpec.parse(null).kind());
        assertEquals(SkinSpec.Kind.DEFAULT, SkinSpec.parse("default").kind());
        assertEquals(SkinSpec.Kind.DEFAULT, SkinSpec.parse("???").kind());
        assertEquals(SkinSpec.Kind.DEFAULT, SkinSpec.parse("player:bad name!").kind());
        assertEquals(SkinSpec.Kind.DEFAULT, SkinSpec.parse("texture:onlyvalue").kind());
        assertEquals(SkinSpec.Kind.DEFAULT, SkinSpec.parse("texture:bad value:sig").kind());
    }
    @Test void playerAndTextureRoundTrip() {
        SkinSpec p = SkinSpec.parse("Player:Dinnerbone");
        assertEquals(SkinSpec.Kind.PLAYER, p.kind());
        assertEquals("Dinnerbone", p.name());
        assertEquals("player:Dinnerbone", p.serialize());
        SkinSpec t = SkinSpec.parse("texture:QUJD+/=:REVG==");
        assertEquals(SkinSpec.Kind.TEXTURE, t.kind());
        assertEquals("QUJD+/=", t.value());
        assertEquals("REVG==", t.signature());
        assertEquals(t, SkinSpec.parse(t.serialize()));
    }
}
