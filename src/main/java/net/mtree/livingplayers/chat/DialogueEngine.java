package net.mtree.livingplayers.chat;

import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.chat.Compliance.Response;
import net.mtree.livingplayers.social.Stance;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Template dialogue with a per-bot voice. No external service involved. A bot's voice comes from its personality
 * (terse / gruff / enthusiastic) plus a stable Taglish-or-English flavour, and its tone from how it feels about the speaker.
 * It never states coordinates; location questions get a deliberately vague answer.
 */
public final class DialogueEngine {
    private DialogueEngine() {}

    public record Style(boolean terse, boolean gruff, boolean enthusiastic, boolean taglish) {
        public static Style of(Personality p, UUID botId) {
            return new Style(p.get(Trait.SOCIABILITY) < 0.35, p.get(Trait.AGGRESSION) > 0.65 || p.get(Trait.FRIENDLINESS) < 0.3,
                p.get(Trait.FRIENDLINESS) > 0.65 && p.get(Trait.SOCIABILITY) > 0.5, (botId.hashCode() & 1) == 0);
        }
    }

    public enum Tone { WARM, NEUTRAL, GRUFF, COLD }

    public enum AmbientTopic { NIGHT_FALLS, RAIN, FINISHED_BUILD, GOT_TOOLS, WOUNDED, WON_FIGHT, MISSING_FRIEND, SAW_PLAYER, SAW_RIVAL }

    public record Facts(String player, String bot, String activity) {}

    public static Tone tone(Stance s, Style st) {
        if (s == Stance.HOSTILE || s == Stance.RIVAL) return Tone.COLD;
        if (s == Stance.FRIEND || s == Stance.FRIENDLY) return Tone.WARM;
        return st.gruff() ? Tone.GRUFF : Tone.NEUTRAL;
    }

    private static final Map<String, List<String>> EN = Map.ofEntries(
        Map.entry("GREETING", List.of("hey {player}!", "hello there, {player}", "oh, hi {player}")),
        Map.entry("FAREWELL", List.of("see you around, {player}", "bye {player}, stay safe", "later!")),
        Map.entry("THANKS", List.of("no problem!", "anytime, {player}", "don't mention it")),
        Map.entry("INSULT", List.of("that's not very nice", "watch it, {player}", "wow. ok.")),
        Map.entry("ASK_NAME", List.of("i'm {bot}", "name's {bot}", "{bot}. nice to meet you")),
        Map.entry("ASK_STATUS", List.of("{activity}, mostly", "just {activity}", "i'm {activity} right now")),
        Map.entry("ASK_LOCATION", List.of("somewhere not too far, i won't say exactly", "around here, that's all i'll say", "that's my business, {player}")),
        Map.entry("ACCEPT_FOLLOW", List.of("sure, lead the way", "ok, i'll tag along", "alright, coming")),
        Map.entry("ACCEPT_HELP", List.of("on my way!", "coming, hold on", "ok i'll come over")),
        Map.entry("ACCEPT_STOP", List.of("ok, staying put", "alright, stopping", "fine, i'll wait")),
        Map.entry("REFUSE_BUSY", List.of("can't right now, i'm busy", "not now, i'm in the middle of something", "later, i'm {activity}")),
        Map.entry("REFUSE_DISLIKE", List.of("nah", "i don't think so, {player}", "no thanks")),
        Map.entry("NOT_SUPPORTED", List.of("i don't trade yet", "no trading from me for now", "sorry, not trading")),
        Map.entry("SMALL_TALK", List.of("hm, ok", "yeah, maybe", "if you say so, {player}", "interesting")));

    private static final Map<String, List<String>> TL = Map.ofEntries(
        Map.entry("GREETING", List.of("uy {player}, kumusta!", "musta {player}", "hoy, {player}!")),
        Map.entry("FAREWELL", List.of("sige {player}, ingat", "paalam, {player}", "sige na, later")),
        Map.entry("THANKS", List.of("walang anuman!", "wala yun, {player}", "sige lang")),
        Map.entry("INSULT", List.of("grabe ka naman", "ang sama mo, {player}", "uy, tama na")),
        Map.entry("ASK_NAME", List.of("ako si {bot}", "{bot} ang pangalan ko", "{bot}, ikaw?")),
        Map.entry("ASK_STATUS", List.of("{activity} lang", "nag-{activity} ako ngayon", "{activity}, bakit?")),
        Map.entry("ASK_LOCATION", List.of("malapit lang, hindi ko sasabihin", "diyan lang sa tabi-tabi", "sekreto yan, {player}")),
        Map.entry("ACCEPT_FOLLOW", List.of("sige, sunod ako", "tara, sunod na", "ok, game")),
        Map.entry("ACCEPT_HELP", List.of("papunta na ako!", "sandali, pupunta", "sige, lapit ako")),
        Map.entry("ACCEPT_STOP", List.of("sige, hihinto ako", "ok, dito lang ako", "sige, hintay lang")),
        Map.entry("REFUSE_BUSY", List.of("hindi pwede ngayon, busy ako", "mamaya na, may ginagawa ako", "sandali lang, {activity} ako")),
        Map.entry("REFUSE_DISLIKE", List.of("ayoko", "hindi, {player}", "wag na")),
        Map.entry("NOT_SUPPORTED", List.of("hindi pa ako nagtitinda", "wala pang palitan, sorry", "di pa ako nakikipagpalit")),
        Map.entry("SMALL_TALK", List.of("ah, ganun ba", "siguro nga", "sige, {player}", "hmm")));

    private static final Map<AmbientTopic, List<String>> AMB = Map.of(
        AmbientTopic.NIGHT_FALLS, List.of("getting dark, better be careful", "gabi na, ingat sa mga zombie", "night already..."),
        AmbientTopic.RAIN, List.of("ugh, rain again", "umuulan na naman", "at least the crops will like it"),
        AmbientTopic.FINISHED_BUILD, List.of("finally finished my hut!", "tapos na ang bahay ko!", "home sweet home"),
        AmbientTopic.GOT_TOOLS, List.of("got my stone tools, now we're talking", "may bato na akong gamit!", "time to get serious"),
        AmbientTopic.WOUNDED, List.of("ow, that hurt", "aray, ang sakit nun", "i need to recover"),
        AmbientTopic.WON_FIGHT, List.of("got it", "ayos, tapos na", "that was close"),
        AmbientTopic.MISSING_FRIEND, List.of("i can't believe they're gone...", "sayang, kaibigan ko yun", "someone will pay for that"),
        AmbientTopic.SAW_PLAYER, List.of("oh, a player", "uy, may tao", "hello?"),
        AmbientTopic.SAW_RIVAL, List.of("you again.", "ikaw na naman.", "keep your distance"));

    public static String reply(Intent intent, Response response, Stance stance, Style style, Facts facts, Random rnd) {
        String key = switch (intent) {
            case FOLLOW_REQUEST -> response == Response.ACCEPT ? "ACCEPT_FOLLOW" : refusalKey(response);
            case HELP_REQUEST -> response == Response.ACCEPT ? "ACCEPT_HELP" : refusalKey(response);
            case STOP_REQUEST -> "ACCEPT_STOP";
            case TRADE_REQUEST -> "NOT_SUPPORTED";
            default -> intent.name();
        };
        return voice(pick(key, style, rnd), tone(stance, style), style, facts);
    }

    private static String refusalKey(Response r) { return r == Response.REFUSE_BUSY ? "REFUSE_BUSY" : r == Response.NOT_SUPPORTED ? "NOT_SUPPORTED" : "REFUSE_DISLIKE"; }

    public static String ambient(AmbientTopic topic, Stance stance, Style style, Facts facts, Random rnd) {
        List<String> pool = AMB.get(topic);
        String base = style.taglish() ? pool.get(rnd.nextInt(pool.size())) : pool.get(rnd.nextInt(pool.size()));
        return voice(base, tone(stance, style), style, facts);
    }

    private static String pick(String key, Style style, Random rnd) {
        List<String> pool = style.taglish() && rnd.nextBoolean() ? TL.get(key) : EN.get(key);
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** Applies placeholders, then tone and temperament. */
    static String voice(String line, Tone tone, Style style, Facts f) {
        String s = line.replace("{player}", f.player() == null || f.player().isBlank() ? "friend" : f.player())
            .replace("{bot}", f.bot()).replace("{activity}", f.activity() == null ? "wandering" : f.activity());
        if (style.terse()) { int c = s.indexOf(','); if (c > 3) s = s.substring(0, c); }
        switch (tone) {
            case WARM -> { if (style.enthusiastic() && !s.endsWith("!") && !s.endsWith("?")) s = s + "!"; }
            case COLD -> s = s.toLowerCase().replace("!", ".").replaceAll("\\s*[:;]\\)", "");
            case GRUFF -> s = "hmph. " + s.replace("!", ".");
            default -> { }
        }
        return s.trim();
    }
}
