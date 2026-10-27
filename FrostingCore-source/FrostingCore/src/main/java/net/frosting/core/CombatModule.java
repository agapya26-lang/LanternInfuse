package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Combat timer: blocks teleport commands while fighting, and logging out mid-fight kills you. */
public final class CombatModule implements Listener {
    private final FrostingCore plugin;
    private final Map<UUID, Long> until = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();

    public CombatModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    public boolean tagged(Player p) {
        Long u = until.get(p.getUniqueId());
        return u != null && System.currentTimeMillis() < u;
    }

    public UUID attackerOf(UUID victim) {
        return lastAttacker.get(victim);
    }

    private void tag(Player p, Player other) {
        boolean was = tagged(p);
        long dur = plugin.getConfig().getInt("combat.duration", 20) * 1000L;
        until.put(p.getUniqueId(), System.currentTimeMillis() + dur);
        lastAttacker.put(p.getUniqueId(), other.getUniqueId());
        if (!was) plugin.msg(p, "&cYou're in combat! Don't log out for &f" + (dur / 1000) + "s&c.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = Util.playerSource(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        tag(victim, attacker);
        tag(attacker, victim);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!tagged(p) || p.hasPermission("frostingcore.bypass.combat")) return;
        String root = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        Set<String> blocked = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("combat.blocked-commands")) blocked.add(s.toLowerCase(Locale.ROOT));
        if (blocked.contains(root)) {
            e.setCancelled(true);
            plugin.msg(p, "&cYou can't use &f/" + root + " &cwhile in combat!");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (tagged(p) && plugin.getConfig().getBoolean("combat.kill-on-logout", true)
                && !p.hasPermission("frostingcore.bypass.combat")) {
            Bukkit.broadcast(Text.c(plugin.prefix() + "&c" + p.getName() + " &7logged out in combat!"));
            p.setHealth(0.0);
        }
        until.remove(p.getUniqueId());
        lastAttacker.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        until.remove(e.getEntity().getUniqueId());
        lastAttacker.remove(e.getEntity().getUniqueId());
    }

    public void tickSecond() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(until.keySet())) {
            Player p = Bukkit.getPlayer(id);
            long end = until.get(id);
            if (p == null) {
                until.remove(id);
            } else if (now >= end) {
                until.remove(id);
                plugin.msg(p, "&aYou're no longer in combat.");
            } else {
                p.sendActionBar(Text.c("&c⚔ Combat &8- &f" + ((end - now + 999) / 1000) + "s"));
            }
        }
    }
}
