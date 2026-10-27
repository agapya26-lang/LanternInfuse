package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A tiny menu framework: slots with click actions. Locked by default; free() lets players move items. */
public final class Gui implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    private Consumer<InventoryCloseEvent> closeHandler;
    private boolean free;

    public Gui(int rows, String title) {
        this.inv = Bukkit.createInventory(this, rows * 9, Text.c(title));
    }

    public Gui free() {
        this.free = true;
        return this;
    }

    public Gui set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        return this;
    }

    public Gui put(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    public Gui fillEmpty(ItemStack item) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, item);
        }
        return this;
    }

    public Gui onClose(Consumer<InventoryCloseEvent> handler) {
        this.closeHandler = handler;
        return this;
    }

    public Inventory inventory() {
        return inv;
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    /** Register once. */
    public static final class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            Inventory top = e.getView().getTopInventory();
            if (!(top.getHolder() instanceof Gui g)) return;
            if (!g.free) e.setCancelled(true);
            boolean inTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
            if (inTop) {
                Consumer<InventoryClickEvent> action = g.actions.get(e.getSlot());
                if (action != null) {
                    e.setCancelled(true);
                    action.accept(e);
                }
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            Inventory top = e.getView().getTopInventory();
            if (!(top.getHolder() instanceof Gui g) || g.free) return;
            for (int raw : e.getRawSlots()) {
                if (raw < top.getSize()) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof Gui g && g.closeHandler != null) {
                g.closeHandler.accept(e);
            }
        }
    }
}
