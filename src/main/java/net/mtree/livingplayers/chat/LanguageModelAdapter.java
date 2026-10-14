package net.mtree.livingplayers.chat;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Optional natural-language replies. It can ONLY suggest reply TEXT: the future yields a string or empty. It has no access to
 * actions, commands or blocks; intents/actions are decided by IntentClassifier + Compliance. Implementations must never
 * throw and never block the caller: failures, timeouts, rate limits and bad responses all yield Optional.empty().
 */
public interface LanguageModelAdapter {
    CompletableFuture<Optional<String>> reply(String systemPrompt, String context, String playerMessage);
}
