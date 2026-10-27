package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Every teleport in the plugin goes through here: countdown, cancel on move/damage, combat check, effects. */
public final class Teleports implements Listener {
    private static final class Pending {
        final Location start;
        BukkitTask task;

        Pending(Location start) {
            this.start = start;
        }
    }

    private final FrostingCore plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public Teleports(FrostingCore plugin) {
        this.plugin = plugin;
    }

    public void teleport(Player p, Location dest, String label, Runnable after) {
        if (plugin.combat() != null && plugin.combat().tagged(p) && !p.hasPermission("frostingcore.bypass.combat")) {
            plugin.msg(p, "&cYou can't teleport while in combat!");
            return;
        }
        stop(p, false);
        int secs = p.hasPermission("frostingcore.bypass.teleport") ? 0 : plugin.getConfig().getInt("teleport.countdown", 3);
        if (secs <= 0) {
            go(p, dest, after);
            return;
        }
        UUID id = p.getUniqueId();
        Pending pend = new Pending(p.getLocation());
        pending.put(id, pend);
        pend.task = new BukkitRunnable() {
            int left = secs;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    pending.remove(id);
                    cancel();
                    return;
                }
                if (left <= 0) {
                    pending.remove(id);
                    cancel();
                    go(p, dest, after);
                    return;
                }
                p.sendActionBar(Text.c("&e" + label + " &7in &f" + left + "s &8- &7don't move!"));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void go(Player p, Location dest, Runnable after) {
        p.teleportAsync(dest).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!ok || !p.isOnline()) return;
            Location at = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.PORTAL, at, 40, 0.4, 0.8, 0.4, 0.3);
            p.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f);
            if (after != null) after.run();
        }));
    }

    private void stop(Player p, boolean notify) {
        Pending pend = pending.remove(p.getUniqueId());
        if (pend == null) return;
        if (pend.task != null) pend.task.cancel();
        if (notify) plugin.msg(p, "&cTeleport cancelled.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Pending pend = pending.get(e.getPlayer().getUniqueId());
        if (pend == null || e.getTo() == null) return;
        Location s = pend.start;
        Location t = e.getTo();
        if (Math.abs(t.getX() - s.getX()) > 0.4 || Math.abs(t.getZ() - s.getZ()) > 0.4 || Math.abs(t.getY() - s.getY()) > 1.2) {
            stop(e.getPlayer(), true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && pending.containsKey(p.getUniqueId())) stop(p, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer(), false);
    }
}
