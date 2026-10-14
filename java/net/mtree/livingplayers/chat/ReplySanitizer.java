package net.mtree.livingplayers.chat;

import java.util.Optional;
import java.util.regex.Pattern;

/** Everything that reaches chat passes through here - templates and (especially) language-model text. */
public final class ReplySanitizer {
    private ReplySanitizer() {}

    private static final Pattern COLOR = Pattern.compile("(?i)[§&][0-9a-fk-orx]");
    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.)\\S+");
    private static final Pattern CTRL = Pattern.compile("[\\p{Cntrl}&&[^ ]]");

    public static Optional<String> clean(String raw, int maxChars) {
        if (raw == null) return Optional.empty();
        String s = CTRL.matcher(raw.replace('\n', ' ').replace('\r', ' ')).replaceAll("");
        s = COLOR.matcher(s).replaceAll("");
        s = URL.matcher(s).replaceAll("");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.startsWith("/") || s.startsWith("!") || s.startsWith("@")) s = s.substring(1).trim(); // never look like a command
        if (s.isEmpty()) return Optional.empty();
        if (s.length() > maxChars) {
            int cut = s.lastIndexOf(' ', maxChars);
            s = s.substring(0, cut > maxChars / 2 ? cut : maxChars).trim();
        }
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }
}
