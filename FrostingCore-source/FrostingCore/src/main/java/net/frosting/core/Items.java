package net.frosting.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small item / inventory helpers. */
public final class Items {
    private Items() {
    }

    public static ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.c(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Text.c(s));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack pane() {
        return named(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    /** Would this whole stack fit in the inventory's normal slots? */
    public static boolean canFit(Inventory inv, ItemStack item) {
        int need = item.getAmount();
        int max = item.getMaxStackSize();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) need -= max;
            else if (s.isSimilar(item)) need -= Math.max(0, max - s.getAmount());
            if (need <= 0) return true;
        }
        return false;
    }

    /** True if the item has no custom name, lore, enchants or plugin data (safe to treat as the vanilla item). */
    public static boolean isPlain(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (!it.hasItemMeta()) return true;
        ItemMeta m = it.getItemMeta();
        return !(m.hasDisplayName() || m.hasLore() || m.hasEnchants() || !m.getPersistentDataContainer().isEmpty());
    }

    /** How many of this material would fit in the normal inventory slots. */
    public static int capacityFor(Inventory inv, Material mat) {
        int cap = 0;
        int max = mat.getMaxStackSize();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) cap += max;
            else if (s.getType() == mat && isPlain(s)) cap += Math.max(0, max - s.getAmount());
        }
        return cap;
    }

    /** Gives the item, dropping whatever doesn't fit at the player's feet. */
    public static void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    /** Adds lines to an item's lore (copy). */
    public static ItemStack withLore(ItemStack base, String... extra) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String s : extra) lore.add(Text.c(s));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }
}
