package net.mtree.livingplayers.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for the structured columns. Tolerant on read: bad/blank input yields empty values. */
public final class BotCodec {
    private BotCodec() {}

    public static String personalityToJson(Personality p) {
        JsonObject o = new JsonObject();
        for (Trait t : Trait.values()) o.addProperty(t.key(), p.get(t));
        return o.toString();
    }

    public static Personality personalityFromJson(String json) {
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        JsonObject o = parseObject(json);
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            Trait t = Trait.fromKey(e.getKey());
            if (t != null && e.getValue().isJsonPrimitive()) {
                try { m.put(t, e.getValue().getAsDouble()); } catch (NumberFormatException ignored) { /* default */ }
            }
        }
        return new Personality(m);
    }

    public static String listToJson(List<String> list) {
        JsonArray a = new JsonArray();
        if (list != null) list.forEach(a::add);
        return a.toString();
    }

    public static List<String> listFromJson(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el.isJsonArray()) for (JsonElement e : el.getAsJsonArray()) if (e.isJsonPrimitive()) out.add(e.getAsString());
        } catch (RuntimeException ignored) { /* empty */ }
        return out;
    }

    public static String mapToJson(Map<String, String> map) {
        JsonObject o = new JsonObject();
        if (map != null) map.forEach(o::addProperty);
        return o.toString();
    }

    public static Map<String, String> mapFromJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : parseObject(json).entrySet())
            if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }

    private static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
