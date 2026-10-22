package net.lanternlight.infuse;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** /recipes: a menu of every effect bottle; click one to see its 3x3 recipe. */
public final class RecipeMenu implements Listener {
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24};
    private static final int BACK_SLOT = 26;

    private static final class Holder implements InventoryHolder {
        final InfuseType type; // null = main menu
        Inventory inv;

        Holder(InfuseType type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final LanternInfuse plugin;

    public RecipeMenu(LanternInfuse plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ main menu

    public void openMain(Player p) {
        Holder h = new Holder(null);
        Inventory inv = Bukkit.createInventory(h, 36, Text.c("&6Effect Recipes"));
        h.inv = inv;
        fill(inv);

        int idx = 0;
        for (InfuseType t : InfuseType.values()) {
            ItemStack bottle = plugin.items().create(t, false);
            ItemMeta m = bottle.getItemMeta();
            List<Component> lore = new ArrayList<>(m.lore() == null ? List.of() : m.lore());
            lore.add(Text.c("&eClick to view the recipe"));
            m.lore(lore);
            bottle.setItemMeta(m);
            inv.setItem(SLOTS[idx++], bottle);
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------ recipe view

    public void openRecipe(Player p, InfuseType t) {
        EffectSettings s = plugin.settings(t);
        Holder h = new Holder(t);
        Inventory inv = Bukkit.createInventory(h, 27, Text.c(s.name() + " &8Recipe"));
        h.inv = inv;
        fill(inv);

        Material[] grid = plugin.crafting().grid(t);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                Material m = grid[r * 3 + c];
                inv.setItem(r * 9 + 1 + c, m == null ? null : new ItemStack(m));
            }
        }
        inv.setItem(13, named(Material.ARROW, "&7➜"));
        inv.setItem(15, plugin.items().create(t, false));

        int made = plugin.crafted(t);
        int max = plugin.crafting().maxCrafts();
        inv.setItem(17, named(Material.PAPER, "&eCrafted so far: &f" + made + "&7/&f" + max));
        inv.setItem(BACK_SLOT, named(Material.BARRIER, "&cBack"));

        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        if (h.type == null) {
            ItemFactory.Held held = plugin.items().read(e.getCurrentItem());
            if (held != null) openRecipe(p, held.type());
        } else if (e.getSlot() == BACK_SLOT) {
            openMain(p);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ helpers

    private void fill(Inventory inv) {
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private ItemStack named(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.c(name));
        it.setItemMeta(meta);
        return it;
    }
}
