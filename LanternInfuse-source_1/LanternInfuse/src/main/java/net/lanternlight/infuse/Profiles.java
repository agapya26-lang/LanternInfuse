package net.lanternlight.infuse;

import net.lanternlight.infuse.InfuseType.Slot;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything we remember about a player. Equipped effects, cooldowns, trust list and
 * control mode live in the player's own data, so they survive restarts and relogs.
 * Only "spark currently active" is kept in memory.
 */
public final class Profiles {
    private final NamespacedKey[] slotKey = new NamespacedKey[2];
    private final NamespacedKey[] augKey = new NamespacedKey[2];
    private final NamespacedKey[] cdKey = new NamespacedKey[2];
    private final NamespacedKey trustKey;
    private final NamespacedKey controlsKey;
    private final Map<UUID, long[]> active = new HashMap<>();

    public Profiles(JavaPlugin plugin) {
        for (Slot s : Slot.values()) {
            String n = s == Slot.ONE ? "primary" : "support"; // key names kept for compatibility
            slotKey[s.ordinal()] = new NamespacedKey(plugin, "slot_" + n);
            augKey[s.ordinal()] = new NamespacedKey(plugin, "aug_" + n);
            cdKey[s.ordinal()] = new NamespacedKey(plugin, "cd_" + n);
        }
        trustKey = new NamespacedKey(plugin, "trusted");
        controlsKey = new NamespacedKey(plugin, "controls");
    }

    // ---- equipped effects ----

    public InfuseType get(Player p, Slot slot) {
        String id = p.getPersistentDataContainer().get(slotKey[slot.ordinal()], PersistentDataType.STRING);
        return InfuseType.byId(id);
    }

    public boolean isAug(Player p, Slot slot) {
        Byte b = p.getPersistentDataContainer().get(augKey[slot.ordinal()], PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    public void set(Player p, Slot slot, InfuseType type, boolean aug) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.set(slotKey[slot.ordinal()], PersistentDataType.STRING, type.id());
        pdc.set(augKey[slot.ordinal()], PersistentDataType.BYTE, (byte) (aug ? 1 : 0));
    }

    public void clear(Player p, Slot slot) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.remove(slotKey[slot.ordinal()]);
        pdc.remove(augKey[slot.ordinal()]);
        long[] a = active.get(p.getUniqueId());
        if (a != null) a[slot.ordinal()] = 0L;
    }

    /** Which slot holds this effect, or null. */
    public Slot slotOf(Player p, InfuseType t) {
        for (Slot s : Slot.values()) {
            if (get(p, s) == t) return s;
        }
        return null;
    }

    public boolean has(Player p, InfuseType t) {
        return slotOf(p, t) != null;
    }

    // ---- cooldowns (kept per slot, so swapping effects can't be used to dodge a cooldown) ----

    public long cooldownUntil(Player p, Slot slot) {
        Long v = p.getPersistentDataContainer().get(cdKey[slot.ordinal()], PersistentDataType.LONG);
        return v == null ? 0L : v;
    }

    public void setCooldownUntil(Player p, Slot slot, long millis) {
        p.getPersistentDataContainer().set(cdKey[slot.ordinal()], PersistentDataType.LONG, millis);
    }

    public void resetCooldowns(Player p) {
        for (Slot s : Slot.values()) {
            p.getPersistentDataContainer().remove(cdKey[s.ordinal()]);
        }
        active.remove(p.getUniqueId());
    }

    // ---- active spark windows ----

    public long activeUntil(Player p, Slot slot) {
        long[] a = active.get(p.getUniqueId());
        return a == null ? 0L : a[slot.ordinal()];
    }

    public void setActiveUntil(Player p, Slot slot, long millis) {
        active.computeIfAbsent(p.getUniqueId(), k -> new long[2])[slot.ordinal()] = millis;
    }

    public boolean isActive(Player p, Slot slot) {
        return System.currentTimeMillis() < activeUntil(p, slot);
    }

    public void forget(Player p) {
        active.remove(p.getUniqueId());
    }

    // ---- trust ----

    public Set<UUID> trusted(Player p) {
        Set<UUID> out = new HashSet<>();
        String raw = p.getPersistentDataContainer().get(trustKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            try {
                out.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // skip corrupted entry
            }
        }
        return out;
    }

    public void setTrusted(Player p, Set<UUID> set) {
        String joined = set.stream().map(UUID::toString).collect(Collectors.joining(";"));
        p.getPersistentDataContainer().set(trustKey, PersistentDataType.STRING, joined);
    }

    public boolean trusts(Player owner, Entity other) {
        return other instanceof Player op && trusted(owner).contains(op.getUniqueId());
    }

    // ---- controls ----

    /** true = F / Shift+F, false = commands. Falls back to the config default. */
    public boolean offhandControls(Player p, boolean defaultValue) {
        Byte b = p.getPersistentDataContainer().get(controlsKey, PersistentDataType.BYTE);
        return b == null ? defaultValue : b == 1;
    }

    public void setOffhandControls(Player p, boolean offhand) {
        p.getPersistentDataContainer().set(controlsKey, PersistentDataType.BYTE, (byte) (offhand ? 1 : 0));
    }
}
