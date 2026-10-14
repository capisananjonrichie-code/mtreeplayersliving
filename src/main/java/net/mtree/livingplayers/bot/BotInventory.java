package net.mtree.livingplayers.bot;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin-managed inventory (36 main slots, 4 armour, offhand). Mannequins have no real inventory,
 * so this is the single source of truth for what a bot owns. Items are only ever MOVED in or out
 * (add/remove/drain) - there is no method that creates items, so the AI cannot conjure resources.
 * Main-thread only.
 */
public final class BotInventory {
    public static final int MAIN_SLOTS = 36;
    private final ItemStack[] main = new ItemStack[MAIN_SLOTS];
    private final ItemStack[] armor = new ItemStack[4]; // 0=boots 1=leggings 2=chestplate 3=helmet
    private ItemStack offhand;
    private int selected = 0;

    public static boolean isEmpty(ItemStack s) { return s == null || s.getType().isAir() || s.getAmount() <= 0; }

    public ItemStack get(int slot) { return main[slot]; }
    public ItemStack armor(int i) { return armor[i]; }
    public ItemStack offhand() { return offhand; }
    public ItemStack held() { return main[selected]; }
    public int selectedSlot() { return selected; }

    public void setArmor(int i, ItemStack s) { armor[i] = isEmpty(s) ? null : s.clone(); }
    public void setOffhand(ItemStack s) { offhand = isEmpty(s) ? null : s.clone(); }
    public void select(int slot) { if (slot >= 0 && slot < MAIN_SLOTS) selected = slot; }
    public void set(int slot, ItemStack s) { main[slot] = isEmpty(s) ? null : s.clone(); }

    /** Material names of the 36 main slots (null = empty), for the pure gear/food evaluators. */
    public List<String> names() {
        List<String> out = new ArrayList<>(MAIN_SLOTS);
        for (ItemStack s : main) out.add(s == null ? null : s.getType().name());
        return out;
    }

    public void setHeld(ItemStack s) { main[selected] = isEmpty(s) ? null : s.clone(); }

    /** Stores as much as fits; returns the leftover that did NOT fit (null if everything fit). */
    public ItemStack add(ItemStack in) {
        if (isEmpty(in)) return null;
        ItemStack rest = in.clone();
        for (int i = 0; i < MAIN_SLOTS && rest.getAmount() > 0; i++) {
            ItemStack s = main[i];
            if (s != null && s.isSimilar(rest) && s.getAmount() < s.getMaxStackSize()) {
                int move = Math.min(s.getMaxStackSize() - s.getAmount(), rest.getAmount());
                s.setAmount(s.getAmount() + move);
                rest.setAmount(rest.getAmount() - move);
            }
        }
        for (int i = 0; i < MAIN_SLOTS && rest.getAmount() > 0; i++) {
            if (main[i] == null) {
                int move = Math.min(rest.getMaxStackSize(), rest.getAmount());
                ItemStack put = rest.clone();
                put.setAmount(move);
                main[i] = put;
                rest.setAmount(rest.getAmount() - move);
            }
        }
        return rest.getAmount() > 0 ? rest : null;
    }

    public int count(Material m) {
        int n = 0;
        for (ItemStack s : main) if (s != null && s.getType() == m) n += s.getAmount();
        return n;
    }

    /** All-or-nothing removal from main slots. */
    public boolean remove(Material m, int amount) {
        if (amount <= 0 || count(m) < amount) return false;
        int left = amount;
        for (int i = MAIN_SLOTS - 1; i >= 0 && left > 0; i--) {
            ItemStack s = main[i];
            if (s == null || s.getType() != m) continue;
            int take = Math.min(left, s.getAmount());
            s.setAmount(s.getAmount() - take);
            left -= take;
            if (s.getAmount() <= 0) main[i] = null;
        }
        return true;
    }

    public int freeSlots() {
        int n = 0;
        for (ItemStack s : main) if (s == null) n++;
        return n;
    }

    public boolean isEmpty() {
        for (ItemStack s : main) if (s != null) return false;
        for (ItemStack s : armor) if (s != null) return false;
        return offhand == null;
    }

    /** Removes and returns every item (main, armour, offhand). Used for drops - items leave the inventory exactly once. */
    public List<ItemStack> drainAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < MAIN_SLOTS; i++) { if (main[i] != null) out.add(main[i]); main[i] = null; }
        for (int i = 0; i < armor.length; i++) { if (armor[i] != null) out.add(armor[i]); armor[i] = null; }
        if (offhand != null) out.add(offhand);
        offhand = null;
        return out;
    }

    /** Human-readable totals per material (main + equipment) for /mtreebots inv. */
    public List<String> describe() {
        Map<String, Integer> totals = new TreeMap<>();
        for (ItemStack s : main) if (s != null) totals.merge(s.getType().name(), s.getAmount(), Integer::sum);
        List<String> lines = new ArrayList<>();
        totals.forEach((k, v) -> lines.add(v + "x " + k));
        String[] names = {"boots", "leggings", "chestplate", "helmet"};
        for (int i = 0; i < 4; i++) if (armor[i] != null) lines.add("[" + names[i] + "] " + armor[i].getType().name());
        if (offhand != null) lines.add("[offhand] " + offhand.getType().name() + " x" + offhand.getAmount());
        return lines;
    }

    // ---- persistence (ItemStack#serializeAsBytes is Paper's version-upgrade-safe format)

    public Map<String, String> serialize() {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < MAIN_SLOTS; i++) if (main[i] != null) out.put("s" + i, enc(main[i]));
        for (int i = 0; i < armor.length; i++) if (armor[i] != null) out.put("a" + i, enc(armor[i]));
        if (offhand != null) out.put("off", enc(offhand));
        return out;
    }

    /** Tolerant: a single undecodable item is skipped (and logged); it never discards the rest. */
    public static BotInventory deserialize(Map<String, String> data, Logger log, String owner) {
        BotInventory inv = new BotInventory();
        for (Map.Entry<String, String> e : data.entrySet()) {
            try {
                ItemStack item = dec(e.getValue());
                String k = e.getKey();
                if (k.startsWith("s")) { int i = Integer.parseInt(k.substring(1)); if (i >= 0 && i < MAIN_SLOTS) inv.main[i] = item; }
                else if (k.startsWith("a")) { int i = Integer.parseInt(k.substring(1)); if (i >= 0 && i < 4) inv.armor[i] = item; }
                else if (k.equals("off")) inv.offhand = item;
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "Bot " + owner + ": could not restore inventory entry '" + e.getKey() + "', skipping", ex);
            }
        }
        return inv;
    }

    private static String enc(ItemStack s) { return Base64.getEncoder().encodeToString(s.serializeAsBytes()); }
    private static ItemStack dec(String b64) { return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)); }
}
