package net.frosting.core;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Locale;

/** /spawn, /setspawn, spawn protection (no building, no PvP, no explosions near spawn). */
public final class SpawnModule implements Listener, CommandExecutor {
    private final FrostingCore plugin;

    public SpawnModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    public Location spawn() {
        return plugin.serverData().loc("spawn");
    }

    public boolean isProtected(Location l) {
        Location s = spawn();
        if (s == null || l.getWorld() == null || !l.getWorld().equals(s.getWorld())) return false;
        double r = plugin.getConfig().getDouble("spawn.protection-radius", 40);
        double dx = l.getX() - s.getX();
        double dz = l.getZ() - s.getZ();
        return dx * dx + dz * dz <= r * r;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (cmd.getName().toLowerCase(Locale.ROOT).equals("setspawn")) {
            if (!p.hasPermission("frostingcore.admin")) {
                plugin.msg(p, "&cYou don't have permission.");
                return true;
            }
            plugin.serverData().setLoc("spawn", p.getLocation());
            plugin.msg(p, "&aSpawn set to your location.");
            return true;
        }
        Location s = spawn();
        if (s == null) {
            plugin.msg(p, "&cSpawn hasn't been set yet.");
            return true;
        }
        plugin.teleports().teleport(p, s, "Teleporting to spawn", null);
        return true;
    }

    private boolean blocked(Player p, Location l) {
        return isProtected(l) && !p.hasPermission("frostingcore.bypass.spawn");
    }

    private void notice(Player p) {
        p.sendActionBar(Text.c("&cSpawn is protected."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            notice(e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            notice(e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = Util.playerSource(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (isProtected(victim.getLocation()) || isProtected(attacker.getLocation())) e.setCancelled(true);
    }

    /** Nobody can be hurt at spawn at all (fall, fire, mobs, anything). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isProtected(p.getLocation())) return;
        if (!plugin.getConfig().getBoolean("spawn.invulnerable", true)) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID && spawn() != null) p.teleportAsync(spawn());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && isProtected(p.getLocation()) && e.getFoodLevel() < p.getFoodLevel()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Monster && isProtected(e.getLocation())) e.setCancelled(true);
    }

    /** Fell out of the world? Back to spawn. */
    @EventHandler
    public void onVoid(PlayerMoveEvent e) {
        Location t = e.getTo();
        if (t == null || t.getY() >= t.getWorld().getMinHeight() - 8) return;
        Location s = spawn();
        World w = t.getWorld();
        if (s != null && w.equals(s.getWorld())) e.getPlayer().teleportAsync(s);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> isProtected(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> isProtected(b.getLocation()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Location s = spawn();
        if (s != null && !e.getPlayer().hasPlayedBefore()) e.getPlayer().teleportAsync(s);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Location s = spawn();
        if (s == null || !plugin.getConfig().getBoolean("spawn.respawn-at-spawn", true)) return;
        if (!e.isBedSpawn() && !e.isAnchorSpawn()) e.setRespawnLocation(s);
    }
}
