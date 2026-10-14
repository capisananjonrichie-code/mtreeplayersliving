package net.mtree.livingplayers.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Generic JSON-over-HTTP adapter. Request:  POST {"system": "...", "context": "...", "message": "..."}
 *                                  Response: 200 {"reply": "..."}   (anything else = no reply)
 * To use a vendor API, point this at a small proxy that maps the contract; no vendor SDK or key lives in this plugin.
 * The API key (if any) is passed in by the caller (read from an environment variable), never from config files or source.
 */
public final class HttpLanguageModelAdapter implements LanguageModelAdapter {
    static final int MAX_BODY_CHARS = 8192;

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final int maxPerMinute;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

    public HttpLanguageModelAdapter(URI endpoint, String apiKeyOrNull, Duration timeout, int maxPerMinute) {
        this.endpoint = endpoint;
        this.apiKey = apiKeyOrNull;
        this.timeout = timeout;
        this.maxPerMinute = Math.max(1, maxPerMinute);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private synchronized boolean allow() {
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) recent.removeFirst();
        if (recent.size() >= maxPerMinute) return false;
        recent.addLast(now);
        return true;
    }

    @Override
    public CompletableFuture<Optional<String>> reply(String systemPrompt, String context, String playerMessage) {
        try {
            if (!allow()) return CompletableFuture.completedFuture(Optional.empty());
            JsonObject body = new JsonObject();
            body.addProperty("system", systemPrompt);
            body.addProperty("context", context);
            body.addProperty("message", playerMessage);
            HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (apiKey != null && !apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);
            return client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpLanguageModelAdapter::parse)
                .exceptionally(t -> Optional.empty());
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    static Optional<String> parse(HttpResponse<String> r) {
        try {
            if (r.statusCode() != 200 || r.body() == null || r.body().length() > MAX_BODY_CHARS) return Optional.empty();
            JsonElement el = JsonParser.parseString(r.body());
            if (!el.isJsonObject()) return Optional.empty();
            JsonElement reply = el.getAsJsonObject().get("reply");
            return reply != null && reply.isJsonPrimitive() && !reply.getAsString().isBlank() ? Optional.of(reply.getAsString()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
