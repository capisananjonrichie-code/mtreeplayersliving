package net.mtree.livingplayers.bot;

import java.util.regex.Pattern;

/**
 * Persisted skin identifier. Formats:
 * <pre>
 *   default                      - the vanilla mannequin skin (no network, always works)
 *   player:&lt;MinecraftName&gt;       - resolved asynchronously through Paper's profile lookup (needs internet)
 *   texture:&lt;value&gt;:&lt;signature&gt; - pre-signed texture property (no lookup needed)
 * </pre>
 * Anything unparseable falls back to {@code default}; a bad skin must never prevent a bot from spawning.
 */
public record SkinSpec(Kind kind, String name, String value, String signature) {
    public enum Kind { DEFAULT, PLAYER, TEXTURE }

    private static final Pattern MC_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern B64 = Pattern.compile("^[A-Za-z0-9+/=]+$");

    public static final SkinSpec DEFAULT = new SkinSpec(Kind.DEFAULT, null, null, null);

    public static SkinSpec parse(String raw) {
        if (raw == null) return DEFAULT;
        String s = raw.trim();
        if (s.regionMatches(true, 0, "player:", 0, 7)) {
            String n = s.substring(7).trim();
            return MC_NAME.matcher(n).matches() ? new SkinSpec(Kind.PLAYER, n, null, null) : DEFAULT;
        }
        if (s.regionMatches(true, 0, "texture:", 0, 8)) {
            String[] parts = s.substring(8).split(":", 2);
            if (parts.length == 2 && B64.matcher(parts[0]).matches() && B64.matcher(parts[1]).matches())
                return new SkinSpec(Kind.TEXTURE, null, parts[0], parts[1]);
        }
        return DEFAULT;
    }

    public String serialize() {
        return switch (kind) {
            case DEFAULT -> "default";
            case PLAYER -> "player:" + name;
            case TEXTURE -> "texture:" + value + ":" + signature;
        };
    }
}
