package net.lanternlight.infuse;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Builds and reads the physical effect items. */
public final class ItemFactory {
    public record Held(InfuseType type, boolean aug) {
    }

    private final LanternInfuse plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey augKey;

    public ItemFactory(LanternInfuse plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "item_effect");
        this.augKey = new NamespacedKey(plugin, "item_aug");
    }

    public ItemStack create(InfuseType t, boolean aug) {
        EffectSettings s = plugin.settings(t);
        ItemStack item = new ItemStack(s.material());
        ItemMeta m = item.getItemMeta();
        if (m instanceof PotionMeta pm) {
            pm.setColor(s.color());
            pm.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        }

        String title = aug
                ? "&6✦ " + s.name() + " Effect &6(Augmented)"
                : s.name() + " Effect";
        m.displayName(Text.c(title));

        String icon = "&" + s.hex() + s.icon();
        List<Component> lore = new ArrayList<>();
        lore.add(Text.c("&9&lINFUSE EFFECT"));
        lore.add(Component.empty());
        lore.add(Text.c("&9Passive"));
        lore.add(Text.c(icon + " &7" + s.passive()));
        lore.add(Component.empty());
        lore.add(Text.c("&9Spark ability"));
        lore.add(Text.c(icon + " &7" + s.spark()));
        lore.add(Component.empty());
        int cd = aug ? s.augCooldown() : s.cooldown();
        lore.add(Text.c("&8Duration: &b" + s.duration() + "s  &8Cooldown: &b" + cd + "s"));
        if (aug) {
            lore.add(Text.c("&6✦ Augmented: stronger spark, half cooldown"));
        }
        lore.add(Component.empty());
        lore.add(Text.c("&8Right-click to equip in slot 1 or 2"));
        m.lore(lore);

        m.setMaxStackSize(1);
        m.setEnchantmentGlintOverride(true);

        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(typeKey, PersistentDataType.STRING, t.id());
        pdc.set(augKey, PersistentDataType.BYTE, (byte) (aug ? 1 : 0));

        item.setItemMeta(m);
        return item;
    }

    /** Returns what effect this item is, or null if it isn't an effect item. */
    public Held read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(typeKey, PersistentDataType.STRING);
        if (id == null) return null;
        InfuseType t = InfuseType.byId(id);
        if (t == null) return null;
        Byte a = pdc.get(augKey, PersistentDataType.BYTE);
        return new Held(t, a != null && a == 1);
    }
}
