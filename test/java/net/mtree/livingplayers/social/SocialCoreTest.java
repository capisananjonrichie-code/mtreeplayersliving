package net.mtree.livingplayers.social;

import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.social.MemoryStore.Memory;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SocialCoreTest {
    static Personality pers(double friendly, double aggressive) {
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) m.put(t, 0.5);
        m.put(Trait.FRIENDLINESS, friendly); m.put(Trait.AGGRESSION, aggressive);
        return new Personality(m);
    }
    static final Personality MID = pers(0.5, 0.5);

    @Test void eventsMoveFeelingsTheRightWay() {
        Relationship r = Relationship.NONE;
        Relationship gifted = RelationshipModel.apply(r, SocialEvent.GIFT, MID, 1000);
        assertTrue(gifted.affinity() > 0 && gifted.trust() > 0);
        Relationship attacked = RelationshipModel.apply(r, SocialEvent.ATTACKED, MID, 1000);
        assertTrue(attacked.affinity() < 0 && attacked.trust() == 0, "trust is floored at 0");
        assertTrue(RelationshipModel.apply(r, SocialEvent.SAVED, MID, 1).affinity() > RelationshipModel.apply(r, SocialEvent.GIFT, MID, 1).affinity());
        assertTrue(RelationshipModel.apply(r, SocialEvent.KILLED_ME, MID, 1).affinity() < attacked.affinity());
        assertEquals(1.0, gifted.familiarity());
    }
    @Test void botToBotEventsHaveTheRightSign() {
        assertTrue(RelationshipModel.apply(Relationship.NONE, SocialEvent.SHARED_TIME, MID, 1).affinity() > 0);
        assertTrue(RelationshipModel.apply(Relationship.NONE, SocialEvent.BICKERED, MID, 1).affinity() < 0);
    }
    @Test void personalityScalesReactionsButNeverFlipsThem() {
        double warm = RelationshipModel.apply(Relationship.NONE, SocialEvent.GIFT, pers(1.0, 0.5), 1).affinity();
        double cool = RelationshipModel.apply(Relationship.NONE, SocialEvent.GIFT, pers(0.0, 0.5), 1).affinity();
        assertTrue(warm > cool && cool > 0);
        double fierce = RelationshipModel.apply(Relationship.NONE, SocialEvent.ATTACKED, pers(0.5, 1.0), 1).affinity();
        double mild = RelationshipModel.apply(Relationship.NONE, SocialEvent.ATTACKED, pers(0.5, 0.0), 1).affinity();
        assertTrue(fierce < mild && mild < 0, "aggressive bots hold bigger grudges");
    }
    @Test void valuesStayClamped() {
        Relationship r = Relationship.NONE;
        for (int i = 0; i < 100; i++) r = RelationshipModel.apply(r, SocialEvent.SAVED, pers(1, 0), i + 1);
        assertEquals(100, r.affinity()); assertEquals(100, r.trust()); assertEquals(100, r.familiarity());
        for (int i = 0; i < 100; i++) r = RelationshipModel.apply(r, SocialEvent.KILLED_ME, pers(0, 1), 200 + i);
        assertEquals(-100, r.affinity()); assertEquals(0, r.trust());
    }
    @Test void stanceThresholds() {
        assertEquals(Stance.NEUTRAL, Relationship.NONE.stance());
        assertEquals(Stance.FRIENDLY, new Relationship(30, 10, 5, 1).stance());
        assertEquals(Stance.FRIEND, new Relationship(60, 50, 5, 1).stance());
        assertEquals(Stance.FRIENDLY, new Relationship(60, 10, 5, 1).stance());
        assertEquals(Stance.RIVAL, new Relationship(-30, 0, 5, 1).stance());
        assertEquals(Stance.HOSTILE, new Relationship(-70, 0, 5, 1).stance());
    }
    @Test void feelingsFadeWithHalfLifeButFamiliarityStays() {
        Relationship r = new Relationship(80, 60, 20, 1000);
        Relationship later = RelationshipModel.decay(r, 2000, 1000);
        assertEquals(40, later.affinity(), 1e-9);
        assertEquals(30, later.trust(), 1e-9);
        assertEquals(20, later.familiarity());
        assertSame(Relationship.NONE, RelationshipModel.decay(Relationship.NONE, 99999, 1000));
        assertEquals(r, RelationshipModel.decay(r, 500, 1000));
    }

    @Test void memoryIsBoundedAndKeepsWhatMatters() {
        MemoryStore m = new MemoryStore(10);
        UUID villain = UUID.randomUUID();
        long t = 1_000_000;
        m.add(new Memory("ATTACKED_BY", villain, "they hit me", 9, t));
        for (int i = 0; i < 100; i++) m.add(new Memory("GREETED", UUID.randomUUID(), "hi", 1, t + 70_000L * (i + 1)));
        assertEquals(10, m.size());
        assertTrue(m.has("ATTACKED_BY", villain), "an important memory outlives 100 greetings");
    }
    @Test void oldTrivialFadesBeforeOldImportant() {
        MemoryStore m = new MemoryStore(2);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        long day = 24L * 3600 * 1000;
        m.add(new Memory("X", a, "important old", 8, 0));
        m.add(new Memory("Y", b, "trivial old", 2, 0));
        m.add(new Memory("Z", c, "new", 3, 30 * day));
        assertTrue(m.has("X", a)); assertFalse(m.has("Y", b));
    }
    @Test void sameEventWithinAMinuteIsOneMemory() {
        MemoryStore m = new MemoryStore(10);
        UUID p = UUID.randomUUID();
        m.add(new Memory("ATTACKED_BY", p, "hit", 5, 1000));
        m.add(new Memory("ATTACKED_BY", p, "hit again", 7, 20_000));
        assertEquals(1, m.size());
        assertEquals(7, m.all().get(0).importance());
        m.add(new Memory("ATTACKED_BY", p, "later", 5, 200_000));
        assertEquals(2, m.size());
        assertEquals(2, m.about(p).size());
    }

    @Test void rumorLocationsAreCoarseAndNeverContainNumbers() {
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            String w = RumorBook.where((r.nextDouble() - 0.5) * 3000, (r.nextDouble() - 0.5) * 3000);
            assertFalse(w.matches(".*\\d.*"), "leaked a number: " + w);
        }
        assertEquals("right around here", RumorBook.where(10, 10));
        assertTrue(RumorBook.where(0, -100).contains("north"));
        assertTrue(RumorBook.where(300, 0).contains("east") && RumorBook.where(300, 0).startsWith("a good walk"));
        assertTrue(RumorBook.where(0, 900).startsWith("far off") && RumorBook.where(0, 900).endsWith("south"));
    }
    @Test void retellingLosesConfidenceAndCountsHops() {
        Rumor cur = new Rumor(UUID.randomUUID(), "THREAT", "a creeper nest", "not far to the east", 90, 0, 1);
        Random rnd = new Random(3);
        int last = 90;
        for (int i = 0; i < 4; i++) {
            cur = RumorBook.retell(cur, rnd);
            assertTrue(cur.confidence() < last); last = cur.confidence();
            assertEquals(i + 1, cur.hops());
            assertEquals("a creeper nest", cur.subject());
            assertFalse(cur.where().matches(".*\\d.*"));
        }
    }
    @Test void unreliableRumorsAreNotShared() {
        RumorBook b = new RumorBook(5);
        b.add(new Rumor(UUID.randomUUID(), "A", "weak", "far off to the east", RumorBook.MIN_SHAREABLE_CONFIDENCE - 1, 3, 1));
        assertTrue(b.pickToShare(new Random(1)).isEmpty());
        b.add(new Rumor(UUID.randomUUID(), "B", "solid", "right around here", 80, 0, 1));
        assertEquals("solid", b.pickToShare(new Random(1)).orElseThrow().subject());
    }
    @Test void rumorBookDedupesAndEvictsTheLeastReliable() {
        RumorBook b = new RumorBook(2);
        b.add(new Rumor(UUID.randomUUID(), "K", "same", "x", 40, 0, 1));
        b.add(new Rumor(UUID.randomUUID(), "K", "SAME", "y", 70, 0, 1));
        assertEquals(1, b.size()); assertEquals(70, b.all().get(0).confidence());
        b.add(new Rumor(UUID.randomUUID(), "K", "two", "z", 60, 0, 1));
        b.add(new Rumor(UUID.randomUUID(), "K", "three", "z", 90, 0, 1));
        assertEquals(2, b.size());
        assertTrue(b.all().stream().noneMatch(r -> r.confidence() == 60), "weakest evicted");
    }

    @Test void factionLifecycle() {
        FactionRegistry f = new FactionRegistry(3);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        assertTrue(f.create("Samahan ng Tala", a, 1).ok());
        assertFalse(f.create("Another", a, 1).ok(), "one faction per bot");
        assertFalse(f.create("samahan ng tala", b, 1).ok(), "unique names, case-insensitive");
        assertFalse(f.create("x", b, 1).ok());
        UUID id = f.of(a).orElseThrow().id();
        assertTrue(f.join(id, b).ok()); assertTrue(f.join(id, c).ok());
        assertFalse(f.join(id, d).ok(), "max size");
        assertTrue(f.sameFaction(a, c)); assertFalse(f.sameFaction(a, d));
        assertTrue(f.leave(a).ok());
        assertEquals(b, f.of(b).orElseThrow().founder(), "banner passes on");
        f.leave(b); f.leave(c);
        assertTrue(f.all().isEmpty(), "empty faction dissolves");
        assertFalse(f.leave(a).ok());
    }
    @Test void formationNeedsMutualLikingAndTemperament() {
        Personality social = pers(0.7, 0.3);
        Relationship fond = new Relationship(65, 40, 10, 1), meh = new Relationship(20, 40, 10, 1);
        assertTrue(FactionFormation.shouldForm(fond, fond, social, social, 60));
        assertFalse(FactionFormation.shouldForm(fond, meh, social, social, 60), "must be mutual");
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) m.put(t, 0.5);
        m.put(Trait.SOCIABILITY, 0.1);
        assertFalse(FactionFormation.shouldForm(fond, fond, social, new Personality(m), 60), "a loner does not found groups");
        assertTrue(FactionFormation.shouldJoin(55, social, 60));
        assertFalse(FactionFormation.shouldJoin(10, social, 60));
    }
    @Test void socialStateForgetsADeletedBotEverywhere() {
        SocialState s = new SocialState(10, 5, 4);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.record(a, b, false, SocialEvent.HELPED, MID, 100);
        s.record(b, a, false, SocialEvent.HELPED, MID, 100);
        s.factions.create("Pair Group", a, 1); s.factions.join(s.factions.of(a).orElseThrow().id(), b);
        assertTrue(s.takeDirty()); assertFalse(s.takeDirty());
        s.forget(a);
        assertEquals(Relationship.NONE, s.relationship(b, a), "others forget the deleted bot");
        assertTrue(s.factions.of(a).isEmpty());
        assertEquals(b, s.factions.of(b).orElseThrow().founder());
        assertTrue(s.takeDirty());
    }
}
