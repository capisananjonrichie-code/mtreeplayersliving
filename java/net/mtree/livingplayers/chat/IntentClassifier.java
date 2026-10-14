package net.mtree.livingplayers.chat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword intent detection (English + a little Tagalog). It is NOT language understanding: it matches whole words/phrases,
 * checks intents in a fixed priority order, and falls back to SMALL_TALK. Misclassification is expected for unusual phrasing.
 */
public final class IntentClassifier {
    private IntentClassifier() {}

    private static final Map<Intent, Pattern> RULES = new LinkedHashMap<>();
    private static void rule(Intent i, String regex) { RULES.put(i, Pattern.compile("(?<![\\p{L}])(?:" + regex + ")(?![\\p{L}])")); }

    static { // order = priority
        rule(Intent.STOP_REQUEST, "stop|stay here|stay there|wait here|hinto|tigil|huwag sumunod|stop following|don't follow");
        rule(Intent.FOLLOW_REQUEST, "follow me|follow|come with me|tag along|sunod ka|sumunod ka|sunod|sumunod");
        rule(Intent.THANKS, "thanks|thank you|thx|ty|salamat");            // before HELP: "thanks for the help" is a thank-you
        rule(Intent.HELP_REQUEST, "help|tulong|saklolo|come here|i need you|backup|assist");
        rule(Intent.TRADE_REQUEST, "trade|swap|barter|sell|buy|palit|bili|benta");
        rule(Intent.ASK_STATUS, "what are you doing|what're you doing|what are you up to|wyd|ano ginagawa mo|anong ginagawa mo|what's up with you");
        rule(Intent.ASK_LOCATION, "where are you|where r u|where do you live|where's your (?:base|house|home)|saan ka|saan ka nakatira");
        rule(Intent.ASK_NAME, "who are you|what's your name|what is your name|your name|sino ka");
        rule(Intent.INSULT, "idiot|stupid|noob|trash|ugly|dumb|tanga|gago|bobo|useless");
        rule(Intent.FAREWELL, "bye|goodbye|see you|see ya|gtg|paalam|sige na|later|stay safe");
        rule(Intent.GREETING, "hello|hi|hey|yo|sup|good morning|good evening|kumusta|musta|uy|hoy");
    }

    public static Intent classify(String message) {
        if (message == null) return Intent.SMALL_TALK;
        String m = message.toLowerCase(Locale.ROOT).replace('’', '\'');
        for (Map.Entry<Intent, Pattern> e : RULES.entrySet()) if (e.getValue().matcher(m).find()) return e.getKey();
        return Intent.SMALL_TALK;
    }
}
