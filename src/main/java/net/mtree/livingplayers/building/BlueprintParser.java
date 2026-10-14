package net.mtree.livingplayers.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and VALIDATES blueprint JSON. Invalid blueprints are rejected with a message, never half-loaded.
 * <pre>
 * { "name": "starter_hut", "description": "...",
 *   "palette": { "W": "PLANKS" },
 *   "layers": [ [ "WWW", "W.W", "WWW" ], [ ... ] ] }   // layers[y][z] is a row string, one char per x; '.' or ' ' = nothing
 * </pre>
 */
public final class BlueprintParser {
    private BlueprintParser() {}

    public static final int MAX_DIM = 16;
    public static final int MAX_BLOCKS = 1024;
    /** Material groups Stage 5 bots can actually obtain and place. */
    public static final Set<String> ALLOWED_GROUPS = Set.of("PLANKS");

    public record Result(Blueprint blueprint, String error) {
        public boolean ok() { return blueprint != null; }
    }

    public static Result parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String name = str(o, "name");
            if (name == null || !name.matches("[a-z0-9_]{1,40}")) return err("name must be 1-40 chars of a-z 0-9 _");
            String desc = o.has("description") ? o.get("description").getAsString() : "";

            Map<Character, String> palette = new HashMap<>();
            if (!o.has("palette") || !o.get("palette").isJsonObject()) return err("missing palette");
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("palette").entrySet()) {
                if (e.getKey().length() != 1 || e.getKey().equals(".") || e.getKey().equals(" ")) return err("palette keys must be a single character other than '.' or space");
                String g = e.getValue().getAsString().toUpperCase();
                if (!ALLOWED_GROUPS.contains(g)) return err("palette material '" + g + "' is not supported (allowed: " + ALLOWED_GROUPS + ")");
                palette.put(e.getKey().charAt(0), g);
            }
            if (!o.has("layers") || !o.get("layers").isJsonArray()) return err("missing layers");
            JsonArray layers = o.getAsJsonArray("layers");
            if (layers.isEmpty() || layers.size() > MAX_DIM) return err("layers must be 1-" + MAX_DIM);

            List<Blueprint.Placement> out = new ArrayList<>();
            int width = 0, depth = 0;
            Set<Character> used = new HashSet<>();
            for (int y = 0; y < layers.size(); y++) {
                JsonArray rows = layers.get(y).getAsJsonArray();
                if (rows.isEmpty() || rows.size() > MAX_DIM) return err("layer " + y + " must have 1-" + MAX_DIM + " rows");
                depth = Math.max(depth, rows.size());
                for (int z = 0; z < rows.size(); z++) {
                    String row = rows.get(z).getAsString();
                    if (row.length() > MAX_DIM) return err("row too long at layer " + y);
                    width = Math.max(width, row.length());
                    for (int x = 0; x < row.length(); x++) {
                        char c = row.charAt(x);
                        if (c == '.' || c == ' ') continue;
                        String g = palette.get(c);
                        if (g == null) return err("character '" + c + "' at layer " + y + " is not in the palette");
                        used.add(c);
                        out.add(new Blueprint.Placement(x, y, z, g));
                    }
                }
            }
            if (out.isEmpty()) return err("blueprint places no blocks");
            if (out.size() > MAX_BLOCKS) return err("too many blocks (" + out.size() + " > " + MAX_BLOCKS + ")");
            return new Result(new Blueprint(name, desc, width, layers.size(), depth, List.copyOf(out)), null);
        } catch (RuntimeException e) {
            return err("invalid JSON structure: " + e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null; }
    private static Result err(String m) { return new Result(null, m); }
}
