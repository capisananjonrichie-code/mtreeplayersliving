package net.mtree.livingplayers.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mtree.livingplayers.chat.ConversationService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Observes player chat (never cancels or edits it) and hands it to the main thread for bots to consider. */
public final class ChatListener implements Listener {
    private final Plugin plugin;
    private final ConversationService conversations;

    public ChatListener(Plugin plugin, ConversationService conversations) { this.plugin = plugin; this.conversations = conversations; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> conversations.onPlayerMessage(p, text)); // AsyncChatEvent is off-thread; Bukkit world access is not
    }
}
