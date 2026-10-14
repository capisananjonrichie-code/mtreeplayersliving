package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotInventory;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Chooses gear from what the bot ALREADY OWNS: wears the best armour per slot and holds the best weapon for its style.
 * Items only move between inventory slots - nothing is created. Main thread.
 */
public final class BotEquipmentService {
    private final BotEntityAdapter adapter;

    public BotEquipmentService(BotEntityAdapter adapter) { this.adapter = adapter; }

    /** @return true if anything changed */
    public boolean refresh(BotIdentity bot) {
        BotInventory inv = bot.inventory();
        boolean changed = false;

        List<String> names = inv.names();
        for (int slot = 0; slot < 4; slot++) {
            ItemStack worn = inv.armor(slot);
            double best = worn == null ? 0 : EquipmentEvaluator.armorScore(worn.getType().name());
            int from = -1;
            for (int i = 0; i < names.size(); i++) {
                if (EquipmentEvaluator.armorSlot(names.get(i)) != slot) continue;
                double sc = EquipmentEvaluator.armorScore(names.get(i));
                if (sc > best) { best = sc; from = i; }
            }
            if (from >= 0) {
                ItemStack candidate = inv.get(from);
                inv.set(from, worn);          // old piece goes back into the slot the new one left
                inv.setArmor(slot, candidate);
                names = inv.names();
                changed = true;
            }
        }

        int w = EquipmentEvaluator.bestWeaponIndex(names, bot.style());
        if (w >= 0) {
            ItemStack held = inv.held();
            double heldScore = held == null ? 0 : EquipmentEvaluator.weaponScore(held.getType().name(), bot.style());
            if (EquipmentEvaluator.weaponScore(names.get(w), bot.style()) > heldScore + 1e-9) { inv.select(w); changed = true; }
        }

        if (changed) {
            bot.markDirty();
            adapter.pushState(bot);
        }
        return changed;
    }
}
