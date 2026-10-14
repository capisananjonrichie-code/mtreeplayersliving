package net.mtree.livingplayers.survival;

import net.mtree.livingplayers.storage.BotStorage;
import net.mtree.livingplayers.storage.PlacedBlock;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Registry of blocks bots placed themselves (memory + database). Main thread. */
public final class BotPlacedBlocks {
    private record Key(UUID world, int x, int y, int z) {}

    private final BotStorage storage;
    private final Logger log;
    private final Set<Key> keys = new HashSet<>();

    public BotPlacedBlocks(BotStorage storage, Logger log) { this.storage = storage; this.log = log; }

    public void load(List<PlacedBlock> rows) { for (PlacedBlock b : rows) keys.add(new Key(b.worldUuid(), b.x(), b.y(), b.z())); }

    private static Key key(Block b) { return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }

    public boolean contains(Block b) { return keys.contains(key(b)); }
    public int size() { return keys.size(); }

    public void add(Block b, UUID bot) {
        keys.add(key(b));
        storage.addPlacedBlock(new PlacedBlock(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(), bot, b.getType().name(), System.currentTimeMillis()))
            .whenComplete((v, err) -> { if (err != null) log.log(Level.SEVERE, "Could not persist a bot-placed block", err); });
    }

    public void remove(Block b) {
        if (!keys.remove(key(b))) return;
        storage.removePlacedBlock(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ())
            .whenComplete((v, err) -> { if (err != null) log.log(Level.SEVERE, "Could not remove a bot-placed block record", err); });
    }
}
