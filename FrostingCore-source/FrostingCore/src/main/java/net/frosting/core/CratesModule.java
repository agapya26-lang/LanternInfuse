package net.frosting.core;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Crates + keys + keyall: the system Donut SMP players asked to get back. Crates are defined in config.yml,
 * keys come from /daily, the AFK zone, the shard shop and keyall events, and crates can be placed in the world.
 */
public final class CratesModule implements Listener, CommandExecutor {
    private record Reward(int weight, String type, String arg, String label) {
        Material icon() {
            return switch (type) {
                case "money" -> Material.GOLD_INGOT;
                case "shards" -> Material.AMETHYST_SHARD;
                case "key" -> Material.TRIPWIRE_HOOK;
                case "item" -> {
                    Material m = Material.matchMaterial(arg.split(":")[0]);
                    yield m == null ? Material.CHEST : m;
                }
                default -> Material.NETHER_STAR;
            };
        }
    }

    private final FrostingCore plugin;
    private final Random rng = new Random();
    private final NamespacedKey holoKey;

    public CratesModule(FrostingCore plugin) {
        this.plugin = plugin;
        this.holoKey = new NamespacedKey(plugin, "crate_holo");
    }

    // ================================================================ config helpers

    public List<String> crateIds() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("crates");
        List<String> ids = new ArrayList<>();
        if (s == null) return ids;
        for (String k : s.getKeys(false)) {
            if (s.isConfigurationSection(k)) ids.add(k); // skips settings like broadcast-weight
        }
        return ids;
    }

    private String crateName(String id) {
        return plugin.getConfig().getString("crates." + id + ".name", id);
    }

    private List<Reward> rewards(String id) {
        List<Reward> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("crates." + id + ".rewards")) {
            String[] p = line.split("\\|", 4);
            if (p.length < 4) continue;
            try {
                out.add(new Reward(Math.max(1, Integer.parseInt(p[0].trim())), p[1].trim().toLowerCase(Locale.ROOT), p[2].trim(), p[3]));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("crates." + id + ": bad reward line '" + line + "'");
            }
        }
        return out;
    }

    // ================================================================ keys

    public int keys(Player p, String crate) {
        return plugin.store().get(p.getUniqueId()).keys.getOrDefault(crate, 0);
    }

    public void addKeys(java.util.UUID id, String crate, int amount) {
        plugin.store().get(id).keys.merge(crate, amount, Integer::sum);
        plugin.store().dirty();
    }

    private boolean takeKey(Player p, String crate) {
        PlayerStore.Data d = plugin.store().get(p.getUniqueId());
        int have = d.keys.getOrDefault(crate, 0);
        if (have < 1) return false;
        d.keys.put(crate, have - 1);
        plugin.store().dirty();
        return true;
    }

    /** Everyone online gets keys (the old "keyall"). */
    public void keyAll(String crate, int amount) {
        if (!crateIds().contains(crate)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            addKeys(p.getUniqueId(), crate, amount);
            p.showTitle(Title.title(Text.c("&d&l✦ KEY ALL ✦"), Text.c("&7You got &e" + amount + "x " + crateName(crate) + " &7key"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        }
        Bukkit.broadcast(Text.c(plugin.prefix() + "&d&lKEYALL! &7Everyone online received &e" + amount + "x " + crateName(crate) + " &7key. Use &f/crates&7!"));
    }

    private long lastKeyAll = System.currentTimeMillis();

    public void tickSecond() {
        if (!plugin.getConfig().getBoolean("keyall.enabled", true)) return;
        long every = plugin.getConfig().getLong("keyall.interval-minutes", 60) * 60_000L;
        if (System.currentTimeMillis() - lastKeyAll >= every) {
            lastKeyAll = System.currentTimeMillis();
            if (!Bukkit.getOnlinePlayers().isEmpty()) {
                keyAll(plugin.getConfig().getString("keyall.crate", "common"), plugin.getConfig().getInt("keyall.amount", 1));
            }
        }
    }

    // ================================================================ menus

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("keys")) {
            plugin.msg(p, "&6Your keys:");
            for (String id : crateIds()) p.sendMessage(Text.c(" " + crateName(id) + " &8- &f" + keys(p, id)));
            return true;
        }
        openMain(p);
        return true;
    }

    public void openMain(Player p) {
        List<String> ids = crateIds();
        Gui g = new Gui(3, "&8Crates");
        int[] slots = ids.size() <= 3 ? new int[]{11, 13, 15} : new int[]{10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < Math.min(ids.size(), slots.length); i++) {
            String id = ids.get(i);
            Material icon = Material.matchMaterial(plugin.getConfig().getString("crates." + id + ".icon", "CHEST"));
            g.set(slots[i], Items.named(icon == null ? Material.CHEST : icon, crateName(id),
                    "&7Your keys: &f" + keys(p, id), "", "&eLeft-click: &fopen (uses a key)", "&eRight-click: &fpreview rewards"),
                    e -> {
                        if (e.isRightClick()) openPreview(p, id);
                        else open(p, id);
                    });
        }
        g.fillEmpty(Items.pane());
        g.open(p);
    }

    private void openPreview(Player p, String id) {
        List<Reward> list = rewards(id);
        int total = list.stream().mapToInt(Reward::weight).sum();
        Gui g = new Gui(6, "&8" + crateName(id) + " &7- rewards");
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Reward r = list.get(i);
            g.put(i, Items.named(r.icon(), r.label(), "&7Chance: &f" + String.format(Locale.US, "%.1f", 100.0 * r.weight() / total) + "%"));
        }
        for (int i = 45; i < 54; i++) g.put(i, Items.pane());
        g.set(49, Items.named(Material.BARRIER, "&c← Back"), e -> openMain(p));
        g.open(p);
    }

    // ================================================================ opening

    public void open(Player p, String id) {
        List<Reward> list = rewards(id);
        if (list.isEmpty()) {
            plugin.msg(p, "&cThat crate has no rewards set up.");
            return;
        }
        if (!takeKey(p, id)) {
            plugin.msg(p, "&cYou need a " + crateName(id) + " &ckey. Get them from &f/daily&c, the AFK zone, keyall events or &f/shardshop&c.");
            return;
        }
        Reward win = roll(list);
        grant(p, win); // delivered right away, the animation is only for show (closing the menu never loses a reward)

        // build the strip of icons that scrolls past
        int steps = 24;
        List<Reward> strip = new ArrayList<>();
        for (int i = 0; i < steps + 9; i++) strip.add(roll(list));
        strip.set(steps + 4, win);
        Gui g = new Gui(3, "&8Opening " + crateName(id) + "...");
        g.fillEmpty(Items.pane());
        g.put(4, Items.named(Material.HOPPER, "&e▼"));
        g.put(22, Items.named(Material.HOPPER, "&e▲"));
        g.open(p);
        int[] delays = {2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 9, 10, 12};
        long when = 0;
        for (int step = 0; step <= steps; step++) {
            final int st = step;
            when += st == 0 ? 1 : delays[Math.min(st - 1, delays.length - 1)];
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || p.getOpenInventory().getTopInventory() != g.inventory()) return;
                for (int pos = 0; pos < 9; pos++) {
                    Reward r = strip.get(st + pos);
                    g.put(9 + pos, Items.named(r.icon(), r.label()));
                }
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f + st * 0.03f);
                if (st == steps) {
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    g.put(13, Items.named(win.icon(), "&d&l» " + win.label() + " &d&l«"));
                }
            }, when);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.msg(p, "&aYou won " + win.label() + " &afrom the " + crateName(id) + "&a!");
            int rare = plugin.getConfig().getInt("crates.broadcast-weight", 5);
            if (win.weight() <= rare) {
                Bukkit.broadcast(Text.c(plugin.prefix() + "&e" + p.getName() + " &7won " + win.label() + " &7from the " + crateName(id) + "&7!"));
                if (p.isOnline()) Fireworks.burst(p.getLocation().add(0, 1, 0));
            }
        }, when + 5);
    }

    private Reward roll(List<Reward> list) {
        int total = list.stream().mapToInt(Reward::weight).sum();
        int pick = rng.nextInt(total);
        for (Reward r : list) {
            pick -= r.weight();
            if (pick < 0) return r;
        }
        return list.get(0);
    }

    private void grant(Player p, Reward r) {
        switch (r.type()) {
            case "money" -> plugin.eco().add(p.getUniqueId(), parseNum(r.arg()));
            case "shards" -> plugin.eco().addShards(p.getUniqueId(), parseNum(r.arg()));
            case "key" -> {
                String[] a = r.arg().split(":");
                addKeys(p.getUniqueId(), a[0], a.length > 1 ? (int) parseNum(a[1]) : 1);
            }
            case "item" -> {
                String[] a = r.arg().split(":");
                Material m = Material.matchMaterial(a[0]);
                if (m != null) Items.give(p, new ItemStack(m, a.length > 1 ? (int) parseNum(a[1]) : 1));
            }
            case "cmd" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), r.arg().replace("%player%", p.getName()));
            default -> plugin.getLogger().warning("Unknown crate reward type: " + r.type());
        }
    }

    private double parseNum(String s) {
        double v = Fmt.parse(s);
        return Double.isNaN(v) ? 0 : v;
    }

    // ================================================================ crates in the world

    /** Admin: the block you're looking at becomes this crate. */
    public void place(Player p, String id) {
        if (!crateIds().contains(id)) {
            plugin.msg(p, "&cUnknown crate. Crates: " + String.join(", ", crateIds()));
            return;
        }
        Block b = p.getTargetBlockExact(6);
        if (b == null) {
            plugin.msg(p, "&cLook at a block (within 6 blocks).");
            return;
        }
        plugin.serverData().setLoc("crate." + id, b.getLocation());
        Location holo = b.getLocation().add(0.5, 1.9, 0.5);
        for (Entity e : holo.getWorld().getNearbyEntities(holo, 1, 2, 1)) {
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) e.remove();
        }
        holo.getWorld().spawn(holo, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, id);
            td.text(Text.c(crateName(id) + "\n&7Right-click to open"));
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
        });
        plugin.msg(p, "&a" + id + " crate placed on the block you're looking at.");
    }

    private String crateAt(Block b) {
        for (String id : crateIds()) {
            Location l = plugin.serverData().loc("crate." + id);
            if (l != null && l.getWorld().equals(b.getWorld()) && l.getBlockX() == b.getX()
                    && l.getBlockY() == b.getY() && l.getBlockZ() == b.getZ()) return id;
        }
        return null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        String id = crateAt(e.getClickedBlock());
        if (id == null) return;
        e.setCancelled(true);
        openPreview(e.getPlayer(), id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (crateAt(e.getBlock()) != null && !e.getPlayer().hasPermission("frostingcore.admin")) e.setCancelled(true);
    }
}
