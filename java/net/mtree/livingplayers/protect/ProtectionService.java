package net.mtree.livingplayers.protect;

import net.mtree.livingplayers.core.IntegrationManager;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.director.BlockNames;
import net.mtree.livingplayers.survival.BotPlacedBlocks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;

import java.util.List;
import java.util.function.Supplier;

/**
 * Gathers the facts and asks the pure {@link BlockPolicy}. Bots are not Players, so firing BlockBreakEvent cannot ask a
 * claim plugin for permission; this service therefore never pretends to. If a claim plugin is installed it denies all
 * block changes (unless the admin explicitly accepts the risk in survival.yml). Unloaded neighbour chunks count as
 * "unknown" and deny.
 */
public final class ProtectionService {
    public record Verdict(boolean allowed, String reason) {}

    private final Supplier<Settings> settings;
    private final IntegrationManager integrations;
    private final BotPlacedBlocks placed;

    public ProtectionService(Supplier<Settings> settings, IntegrationManager integrations, BotPlacedBlocks placed) {
        this.settings = settings; this.integrations = integrations; this.placed = placed;
    }

    public Verdict canSpawnAt(Location l) {
        if (l.getWorld() == null) return new Verdict(false, "no world");
        Settings.ProtectedRegion r = settings.get().worlds().regionAt(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
        return r == null ? new Verdict(true, "ok") : new Verdict(false, "inside protected region '" + r.name() + "'");
    }

    /** Gate for a blueprint block: planks only, inside the project's box, only into empty cells or wild plants. */
    public net.mtree.livingplayers.building.BuildPolicy.Verdict canBuild(Block at, String material, boolean insideProjectBox) {
        Settings st = settings.get();
        boolean enabled = st.building().enabled() && st.survival().enabled() && st.survival().blockInteractionEnabled();
        boolean region = st.worlds().regionAt(at.getWorld().getName(), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) != null;
        String claim = null;
        if (!st.survival().allowWithClaimPlugins()) {
            List<String> c = integrations.claimPluginsPresent();
            if (!c.isEmpty()) claim = c.get(0);
        }
        String cur = at.getType().name();
        boolean replaceable = at.getType().isAir() || net.mtree.livingplayers.director.BlockNames.isReplaceablePlant(cur);
        return net.mtree.livingplayers.building.BuildPolicy.decide(enabled, region, claim, material, replaceable, insideProjectBox);
    }

    public BlockPolicy.Verdict canBreak(Block b) { return decide(BlockPolicy.Op.BREAK, b); }

    public BlockPolicy.Verdict canPlace(Block b) { return decide(BlockPolicy.Op.PLACE, b); }

    private BlockPolicy.Verdict decide(BlockPolicy.Op op, Block b) {
        Settings.Survival sv = settings.get().survival();
        if (!sv.enabled() || !sv.blockInteractionEnabled()) return new BlockPolicy.Verdict(false, "block interaction is disabled in survival.yml");
        World w = b.getWorld();
        boolean region = settings.get().worlds().regionAt(w.getName(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5) != null;
        String claim = null;
        if (!sv.allowWithClaimPlugins()) {
            List<String> c = integrations.claimPluginsPresent();
            if (!c.isEmpty()) claim = c.get(0);
        }
        String name = b.getType().name();
        boolean placedHere = placed.contains(b);
        boolean artificial = false, leaves = false;
        if (op == BlockPolicy.Op.BREAK && !region && claim == null) { // skip the expensive scans when already denied
            artificial = artificialNearby(b, sv.avoidArtificialRadius());
            leaves = BlockPolicy.isNaturalLog(name) && naturalLeavesNearby(b, 3);
        }
        return BlockPolicy.decide(op, name, new BlockPolicy.Context(true, region, claim, artificial, leaves, placedHere));
    }

    /** True if anything player-made is within r blocks (blocks the bots placed themselves are exempt). Unknown chunk = true. */
    private boolean artificialNearby(Block b, int r) {
        World w = b.getWorld();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = b.getX() + dx, z = b.getZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) return true;
                for (int dy = -r; dy <= r; dy++) {
                    Block n = w.getBlockAt(x, b.getY() + dy, z);
                    if (BlockNames.isArtificial(n.getType().name()) && !placed.contains(n)) return true;
                }
            }
        return false;
    }

    /** Natural (non-persistent) leaves nearby = a real tree, not a player's log structure. */
    private boolean naturalLeavesNearby(Block b, int r) {
        World w = b.getWorld();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -r; dy <= r + 3; dy++) {
                    int x = b.getX() + dx, z = b.getZ() + dz;
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block n = w.getBlockAt(x, b.getY() + dy, z);
                    if (n.getType().name().endsWith("_LEAVES") && n.getBlockData() instanceof Leaves lv && !lv.isPersistent()) return true;
                }
        return false;
    }
}
