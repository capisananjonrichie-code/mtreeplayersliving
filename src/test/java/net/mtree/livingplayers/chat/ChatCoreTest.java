package net.mtree.livingplayers.chat;

import com.sun.net.httpserver.HttpServer;
import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.chat.Compliance.Response;
import net.mtree.livingplayers.chat.DialogueEngine.*;
import net.mtree.livingplayers.social.Relationship;
import net.mtree.livingplayers.social.Stance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatCoreTest {
    static Personality pers(double fr, double ag, double so, double co) {
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) m.put(t, 0.5);
        m.put(Trait.FRIENDLINESS, fr); m.put(Trait.AGGRESSION, ag); m.put(Trait.SOCIABILITY, so); m.put(Trait.COOPERATION, co);
        return new Personality(m);
    }

    // ---------------------------------------------------------------- intents
    @Test void classifiesSupportedIntents() {
        assertEquals(Intent.GREETING, IntentClassifier.classify("Hello!"));
        assertEquals(Intent.GREETING, IntentClassifier.classify("kumusta ka?"));
        assertEquals(Intent.FAREWELL, IntentClassifier.classify("ok bye"));
        assertEquals(Intent.FAREWELL, IntentClassifier.classify("stay safe out there"));
        assertEquals(Intent.THANKS, IntentClassifier.classify("thanks for the help"));
        assertEquals(Intent.THANKS, IntentClassifier.classify("salamat"));
        assertEquals(Intent.FOLLOW_REQUEST, IntentClassifier.classify("can you follow me?"));
        assertEquals(Intent.FOLLOW_REQUEST, IntentClassifier.classify("sunod ka sakin"));
        assertEquals(Intent.STOP_REQUEST, IntentClassifier.classify("stop following me"));
        assertEquals(Intent.STOP_REQUEST, IntentClassifier.classify("wait here"));
        assertEquals(Intent.HELP_REQUEST, IntentClassifier.classify("please help me, zombies!"));
        assertEquals(Intent.TRADE_REQUEST, IntentClassifier.classify("wanna trade?"));
        assertEquals(Intent.ASK_STATUS, IntentClassifier.classify("what are you doing"));
        assertEquals(Intent.ASK_LOCATION, IntentClassifier.classify("where are you?"));
        assertEquals(Intent.ASK_NAME, IntentClassifier.classify("who are you"));
        assertEquals(Intent.INSULT, IntentClassifier.classify("you are so stupid"));
    }
    @Test void matchesWholeWordsOnlyAndFallsBackToSmallTalk() {
        assertEquals(Intent.SMALL_TALK, IntentClassifier.classify("this is a chilling place"), "'hi' inside 'chilling' is not a greeting");
        assertEquals(Intent.SMALL_TALK, IntentClassifier.classify("thanksgiving is a holiday"));
        assertEquals(Intent.SMALL_TALK, IntentClassifier.classify("i like your pickaxe"));
        assertEquals(Intent.SMALL_TALK, IntentClassifier.classify(""));
        assertEquals(Intent.SMALL_TALK, IntentClassifier.classify(null));
        assertEquals(Intent.THANKS, IntentClassifier.classify("thanks for following me"), "'following' is not the word 'follow', so it must not become an order");
    }

    // ---------------------------------------------------------------- compliance
    @Test void complianceDependsOnFeelingsTemperamentAndBusyness() {
        Personality kind = pers(0.8, 0.1, 0.6, 0.8), surly = pers(0.2, 0.9, 0.3, 0.1);
        Relationship stranger = Relationship.NONE, friend = new Relationship(60, 50, 20, 1), enemy = new Relationship(-70, 0, 20, 1);
        assertEquals(Response.ACCEPT, Compliance.decide(Intent.FOLLOW_REQUEST, stranger, kind, false));
        assertEquals(Response.REFUSE_DISLIKE, Compliance.decide(Intent.FOLLOW_REQUEST, stranger, surly, false));
        assertEquals(Response.ACCEPT, Compliance.decide(Intent.FOLLOW_REQUEST, friend, surly, false), "even a surly bot follows a friend");
        assertEquals(Response.REFUSE_DISLIKE, Compliance.decide(Intent.HELP_REQUEST, enemy, kind, false));
        assertEquals(Response.REFUSE_BUSY, Compliance.decide(Intent.FOLLOW_REQUEST, friend, kind, true));
        assertEquals(Response.ACCEPT, Compliance.decide(Intent.STOP_REQUEST, enemy, surly, true), "stopping is always fine");
        assertEquals(Response.NOT_SUPPORTED, Compliance.decide(Intent.TRADE_REQUEST, friend, kind, false), "trading does not exist yet");
        assertEquals(Response.ACCEPT, Compliance.decide(Intent.GREETING, enemy, surly, false));
    }

    // ---------------------------------------------------------------- dialogue
    @Test void everyCombinationYieldsCleanShortText() {
        Facts facts = new Facts("Alex", "Tala", "chopping trees");
        for (Intent i : Intent.values()) for (Response r : Response.values()) for (Stance s : Stance.values())
            for (int bits = 0; bits < 16; bits++) {
                Style st = new Style((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
                for (int seed = 0; seed < 6; seed++) {
                    String out = DialogueEngine.reply(i, r, s, st, facts, new Random(seed));
                    assertFalse(out.isBlank());
                    assertFalse(out.contains("{") || out.contains("}"), "unresolved placeholder: " + out);
                    assertTrue(out.length() <= 120, out);
                }
            }
        for (AmbientTopic t : AmbientTopic.values())
            for (int seed = 0; seed < 10; seed++) assertFalse(DialogueEngine.ambient(t, Stance.NEUTRAL, new Style(false, false, false, true), facts, new Random(seed)).contains("{"));
    }
    @Test void locationAnswersNeverRevealNumbersOrCoordinates() {
        for (Stance s : Stance.values()) for (int seed = 0; seed < 50; seed++) {
            String out = DialogueEngine.reply(Intent.ASK_LOCATION, Response.ACCEPT, s, new Style(false, false, false, seed % 2 == 0), new Facts("Alex", "Tala", "x"), new Random(seed));
            assertFalse(out.matches(".*\\d.*"), out);
        }
    }
    @Test void toneChangesTheVoice() {
        Style st = new Style(false, false, true, false);
        Facts f = new Facts("Alex", "Tala", "mining stone");
        String warm = DialogueEngine.reply(Intent.GREETING, Response.ACCEPT, Stance.FRIEND, st, f, new Random(1));
        String cold = DialogueEngine.reply(Intent.GREETING, Response.ACCEPT, Stance.RIVAL, st, f, new Random(1));
        String gruff = DialogueEngine.reply(Intent.GREETING, Response.ACCEPT, Stance.NEUTRAL, new Style(false, true, false, false), f, new Random(1));
        assertNotEquals(warm, cold);
        assertEquals(cold, cold.toLowerCase());
        assertFalse(cold.contains("!"));
        assertTrue(gruff.startsWith("hmph."));
        assertTrue(warm.endsWith("!"));
        assertEquals(Tone.COLD, DialogueEngine.tone(Stance.HOSTILE, st));
        assertEquals(Tone.WARM, DialogueEngine.tone(Stance.FRIEND, new Style(false, true, false, false)));
    }
    @Test void terseBotsAreBriefer() {
        Facts f = new Facts("Alex", "Tala", "x");
        int terse = 0, chatty = 0;
        for (int seed = 0; seed < 100; seed++) {
            terse += DialogueEngine.reply(Intent.GREETING, Response.ACCEPT, Stance.NEUTRAL, new Style(true, false, false, false), f, new Random(seed)).length();
            chatty += DialogueEngine.reply(Intent.GREETING, Response.ACCEPT, Stance.NEUTRAL, new Style(false, false, false, false), f, new Random(seed)).length();
        }
        assertTrue(terse < chatty);
    }
    @Test void styleIsStablePerBotAndFollowsPersonality() {
        UUID id = UUID.randomUUID();
        assertEquals(Style.of(pers(0.5, 0.5, 0.5, 0.5), id), Style.of(pers(0.5, 0.5, 0.5, 0.5), id));
        assertTrue(Style.of(pers(0.9, 0.1, 0.9, 0.5), id).enthusiastic());
        assertTrue(Style.of(pers(0.1, 0.9, 0.5, 0.5), id).gruff());
        assertTrue(Style.of(pers(0.5, 0.5, 0.1, 0.5), id).terse());
    }
    @Test void missingPlayerNameFallsBack() {
        String s = DialogueEngine.reply(Intent.GREETING, Response.ACCEPT, Stance.NEUTRAL, new Style(false, false, false, false), new Facts(null, "Tala", null), new Random(0));
        assertFalse(s.contains("null"));
    }

    // ---------------------------------------------------------------- limiter / sanitizer / activity
    @Test void limiterEnforcesCooldownAndGlobalCap() {
        ChatLimiter l = new ChatLimiter(10_000, 3);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        assertTrue(l.tryAcquire(a, 0, false));
        assertFalse(l.tryAcquire(a, 5_000, false), "per-bot cooldown");
        assertTrue(l.tryAcquire(a, 5_000, true), "direct replies skip the per-bot cooldown");
        assertTrue(l.tryAcquire(b, 6_000, false));
        assertFalse(l.tryAcquire(c, 7_000, false), "global cap of 3 per minute");
        assertFalse(l.tryAcquire(d, 7_000, true), "replies obey the global cap too");
        assertTrue(l.tryAcquire(d, 61_000, true), "window slides");
    }
    @Test void sanitizerNeutralisesHostileText() {
        assertEquals(Optional.of("hello there"), ReplySanitizer.clean("  hello\nthere  ", 100));
        assertEquals(Optional.of("op me"), ReplySanitizer.clean("/op me", 100));
        assertEquals(Optional.of("ban everyone"), ReplySanitizer.clean("!!/ban everyone", 100));
        assertEquals(Optional.of("red text"), ReplySanitizer.clean("§cred &ltext", 100));
        assertEquals(Optional.of("visit now"), ReplySanitizer.clean("visit https://evil.example/x now", 100));
        assertTrue(ReplySanitizer.clean("   ", 10).isEmpty());
        assertTrue(ReplySanitizer.clean(null, 10).isEmpty());
        assertTrue(ReplySanitizer.clean("http://only.a.link", 50).isEmpty());
        String cut = ReplySanitizer.clean("word ".repeat(100), 30).orElseThrow();
        assertTrue(cut.length() <= 30 && !cut.endsWith(" "));
        assertFalse(ReplySanitizer.clean("a\u0007b\u0000c", 10).orElseThrow().chars().anyMatch(Character::isISOControl));
    }
    @Test void activityPhrases() {
        assertEquals("fighting", ActivitySummary.describe(true, false, false, null, null));
        assertEquals("running away to recover", ActivitySummary.describe(true, true, false, null, null));
        assertEquals("building my hut", ActivitySummary.describe(false, false, false, "BUILD", "GATHER:LOG"));
        assertEquals("chopping trees", ActivitySummary.describe(false, false, false, null, "GATHER:LOG"));
        assertEquals("mining stone", ActivitySummary.describe(false, false, false, null, "GATHER:COBBLESTONE"));
        assertEquals("just wandering around", ActivitySummary.describe(false, false, false, null, null));
    }

    // ---------------------------------------------------------------- language-model adapter (local HTTP server, no internet)
    interface Handler { void handle(com.sun.net.httpserver.HttpExchange ex) throws IOException; }

    static HttpServer server(Handler h) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> { try { h.handle(ex); } finally { ex.close(); } });
        s.start();
        return s;
    }
    static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
    }
    static HttpLanguageModelAdapter adapter(HttpServer s, int timeoutMs, int perMin, String key) {
        return new HttpLanguageModelAdapter(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/"), key, Duration.ofMillis(timeoutMs), perMin);
    }
    static Optional<String> ask(HttpLanguageModelAdapter a) { return a.reply("sys", "ctx", "hi").orTimeout(5, TimeUnit.SECONDS).join(); }

    @Test void adapterReturnsTheReplyAndSendsTheContractAndKey() throws Exception {
        String[] seen = new String[2];
        HttpServer s = server(ex -> {
            seen[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen[1] = ex.getRequestHeaders().getFirst("Authorization");
            respond(ex, 200, "{\"reply\":\"hello from the model\"}");
        });
        try {
            assertEquals(Optional.of("hello from the model"), ask(adapter(s, 2000, 10, "secret-key")));
            assertTrue(seen[0].contains("\"system\":\"sys\"") && seen[0].contains("\"message\":\"hi\"") && seen[0].contains("\"context\":\"ctx\""));
            assertEquals("Bearer secret-key", seen[1]);
        } finally { s.stop(0); }
    }
    @Test void adapterTurnsEveryFailureIntoNoReply() throws Exception {
        for (String[] bad : new String[][] {{"500", "{}"}, {"429", "{}"}, {"200", "not json"}, {"200", "{\"nope\":1}"}, {"200", "[1,2]"}, {"200", "{\"reply\":\"  \"}"}, {"200", "{\"reply\":{\"x\":1}}"}}) {
            HttpServer s = server(ex -> respond(ex, Integer.parseInt(bad[0]), bad[1]));
            try { assertEquals(Optional.empty(), ask(adapter(s, 2000, 10, null)), bad[0] + " " + bad[1]); } finally { s.stop(0); }
        }
        HttpServer huge = server(ex -> respond(ex, 200, "{\"reply\":\"" + "x".repeat(20_000) + "\"}"));
        try { assertEquals(Optional.empty(), ask(adapter(huge, 2000, 10, null)), "oversized body is rejected"); } finally { huge.stop(0); }
    }
    @Test void adapterTimesOutInsteadOfHanging() throws Exception {
        HttpServer s = server(ex -> { try { Thread.sleep(2000); } catch (InterruptedException ignored) { } respond(ex, 200, "{\"reply\":\"late\"}"); });
        try {
            long t0 = System.nanoTime();
            assertEquals(Optional.empty(), ask(adapter(s, 300, 10, null)));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1800, "must give up near the timeout");
        } finally { s.stop(0); }
    }
    @Test void adapterRateLimitsItselfAndSurvivesADeadServer() throws Exception {
        int[] hits = {0};
        HttpServer s = server(ex -> { hits[0]++; respond(ex, 200, "{\"reply\":\"ok\"}"); });
        HttpLanguageModelAdapter a = adapter(s, 2000, 2, null);
        assertTrue(ask(a).isPresent()); assertTrue(ask(a).isPresent());
        assertEquals(Optional.empty(), ask(a), "third request in a minute is refused locally");
        assertEquals(2, hits[0], "the refused request never reached the server");
        s.stop(0);
        assertEquals(Optional.empty(), ask(adapter(s, 500, 10, null)), "connection refused");
    }
}
