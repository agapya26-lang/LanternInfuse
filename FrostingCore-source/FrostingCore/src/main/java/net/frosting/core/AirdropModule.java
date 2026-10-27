package net.frosting.core;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** Frosting Drops: a loot barrel lands somewhere in the world on a timer, everyone races to it. */
public final class AirdropModule implements Listener, CommandExecutor {
    private record Loot(Material mat, int min, int max, int weight) {
    }

    private final FrostingCore plugin;
    private final Random rng = new Random();
    private Location active;      // where the barrel is, once it has landed
    private Location announced;   // where it WILL land (during the countdown)
    private long landsAt;
    private long expiresAt;
    private TextDisplay label;
    private long lastDrop = System.currentTimeMillis();

    public AirdropModule(FrostingCore plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                particleBeam();
            }
        }.runTaskTimer(plugin, 40L, 10L);
    }

    public void tickSecond() {
        long now = System.currentTimeMillis();
        long interval = plugin.getConfig().getLong("airdrop.interval-minutes", 30) * 60_000L;
        if (active == null && announced == null && now - lastDrop >= interval) start();
        if (announced != null && now >= landsAt) land();
        if (active != null && now >= expiresAt) finish(null);
    }

    /** Pick a spot and announce it. */
    public void start() {
        if (active != null || announced != null) return;
        World w = Bukkit.getWorld(plugin.getConfig().getString("airdrop.world", "world"));
        if (w == null) return;
        lastDrop = System.currentTimeMillis();
        int min = plugin.getConfig().getInt("airdrop.min-distance", 500);
        int max = plugin.getConfig().getInt("airdrop.max-distance", 4000);
        SafeLoc.find(plugin, w, 0, 0, min, max, 25, loc -> {
            if (loc == null) return;
            announced = loc;
            int warn = plugin.getConfig().getInt("airdrop.warn-seconds", 60);
            landsAt = System.currentTimeMillis() + warn * 1000L;
            Bukkit.broadcast(Text.c(plugin.prefix() + "&d&l✦ FROSTING DROP &7incoming at &f" + loc.getBlockX() + ", "
                    + loc.getBlockZ() + " &7in &e" + warn + "s&7! First to loot it keeps it."));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Text.c("&d&l✦ FROSTING DROP ✦"),
                        Text.c("&e" + loc.getBlockX() + ", " + loc.getBlockZ() + " &7- lands in " + warn + "s"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
            }
        });
    }

    private void land() {
        Location loc = announced;
        announced = null;
        if (loc == null) return;
        loc.getChunk().load();
        Block b = loc.getBlock();
        b.setType(Material.BARREL);
        if (b.getState() instanceof org.bukkit.block.Barrel barrel) fill(barrel.getInventory());
        active = b.getLocation();
        expiresAt = System.currentTimeMillis() + plugin.getConfig().getLong("airdrop.despawn-minutes", 5) * 60_000L;
        World w = loc.getWorld();
        w.strikeLightningEffect(loc);
        Fireworks.burst(loc.clone().add(0.5, 2, 0.5), Color.ORANGE, Color.YELLOW);
        label = w.spawn(loc.clone().add(0.5, 2.2, 0.5), TextDisplay.class, td -> {
            td.text(Text.c("&d&l✦ FROSTING DROP ✦\n&7Open the barrel!"));
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
        });
        Bukkit.broadcast(Text.c(plugin.prefix() + "&d&l✦ The Frosting Drop has landed at &f" + loc.getBlockX() + ", "
                + loc.getBlockY() + ", " + loc.getBlockZ() + "&6! Go go go!"));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.4f);
    }

    private void fill(Inventory inv) {
        List<Loot> table = parse();
        if (table.isEmpty()) return;
        int totalWeight = table.stream().mapToInt(Loot::weight).sum();
        int rolls = plugin.getConfig().getInt("airdrop.rolls", 8);
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) free.add(i);
        for (int r = 0; r < rolls && !free.isEmpty(); r++) {
            int pick = rng.nextInt(totalWeight);
            for (Loot l : table) {
                pick -= l.weight();
                if (pick < 0) {
                    int amount = l.min() + rng.nextInt(Math.max(1, l.max() - l.min() + 1));
                    int slot = free.remove(rng.nextInt(free.size()));
                    inv.setItem(slot, new ItemStack(l.mat(), Math.min(amount, l.mat().getMaxStackSize())));
                    break;
                }
            }
        }
    }

    /** Loot lines look like  DIAMOND:2-6:10  (item : min-max amount : weight). */
    private List<Loot> parse() {
        List<Loot> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("airdrop.loot")) {
            String[] p = line.split(":");
            if (p.length < 3) continue;
            Material m = Material.matchMaterial(p[0].trim());
            if (m == null) continue;
            try {
                String[] range = p[1].trim().split("-");
                int min = Integer.parseInt(range[0]);
                int max = range.length > 1 ? Integer.parseInt(range[1]) : min;
                out.add(new Loot(m, min, max, Math.max(1, Integer.parseInt(p[2].trim()))));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("airdrop.loot: bad line '" + line + "'");
            }
        }
        return out;
    }

    private void finish(Player looter) {
        if (active == null) return;
        Location loc = active;
        active = null;
        Block b = loc.getBlock();
        if (b.getState() instanceof org.bukkit.block.Barrel barrel) barrel.getInventory().clear();
        b.setType(Material.AIR);
        if (label != null) {
            label.remove();
            label = null;
        }
        loc.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0.5, 0.5, 0.5), 40, 0.4, 0.4, 0.4, 0.05);
        if (looter != null) {
            Bukkit.broadcast(Text.c(plugin.prefix() + "&e" + looter.getName() + " &7looted the Frosting Drop!"));
        } else {
            Bukkit.broadcast(Text.c(plugin.prefix() + "&7The Frosting Drop has vanished."));
        }
    }

    /** A column of light so people can spot it from far away. */
    private void particleBeam() {
        Location loc = active != null ? active : null;
        if (loc == null) return;
        World w = loc.getWorld();
        for (int y = 0; y < 40; y += 2) {
            w.spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 1 + y, 0.5), 1, 0.05, 0, 0.05, 0);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (active == null || e.getInventory().getLocation() == null) return;
        Location l = e.getInventory().getLocation();
        if (!l.getWorld().equals(active.getWorld()) || l.getBlockX() != active.getBlockX()
                || l.getBlockY() != active.getBlockY() || l.getBlockZ() != active.getBlockZ()) return;
        boolean empty = true;
        for (ItemStack it : e.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) {
                empty = false;
                break;
            }
        }
        if (empty && e.getPlayer() instanceof Player p) finish(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (active == null) return;
        Location l = e.getBlock().getLocation();
        if (l.getWorld().equals(active.getWorld()) && l.getBlockX() == active.getBlockX()
                && l.getBlockY() == active.getBlockY() && l.getBlockZ() == active.getBlockZ()) {
            e.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (announced != null) {
            long left = Math.max(0, (landsAt - System.currentTimeMillis()) / 1000);
            plugin.msg(sender, "&6Frosting Drop &7lands at &f" + announced.getBlockX() + ", " + announced.getBlockZ() + " &7in &e" + left + "s&7.");
        } else if (active != null) {
            plugin.msg(sender, "&6Frosting Drop &7is at &f" + active.getBlockX() + ", " + active.getBlockY() + ", " + active.getBlockZ() + "&7!");
        } else {
            long next = Math.max(0, (lastDrop + plugin.getConfig().getLong("airdrop.interval-minutes", 30) * 60_000L - System.currentTimeMillis()) / 1000);
            plugin.msg(sender, "&7No Frosting Drop right now. Next one in about &f" + Fmt.duration(next) + "&7.");
        }
        return true;
    }

    public void shutdown() {
        if (active != null) finish(null);
    }
}
