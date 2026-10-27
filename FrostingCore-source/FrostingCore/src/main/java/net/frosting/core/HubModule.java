package net.frosting.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The Frosting Hub: a generated spawn platform plus live leaderboard holograms. */
public final class HubModule {
    private record Op(int x, int y, int z, Material mat) {
    }

    private static final List<String> TYPES = List.of("welcome", "money", "kills", "playtime", "shards");

    private final FrostingCore plugin;
    private final NamespacedKey holoKey;
    private boolean building;

    public HubModule(FrostingCore plugin) {
        this.plugin = plugin;
        this.holoKey = new NamespacedKey(plugin, "hologram");
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, 20L * 30);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ambient, 60L, 20L);
    }

    // ================================================================ admin entry point

    public void handle(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            plugin.msg(s, "&cPlayers only.");
            return;
        }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "build" -> {
                int radius = 26;
                String theme = "frost";
                for (int i = 2; i < a.length; i++) {
                    if (a[i].equalsIgnoreCase("frost") || a[i].equalsIgnoreCase("donut")) {
                        theme = a[i].toLowerCase(Locale.ROOT);
                    } else {
                        try {
                            radius = Math.max(12, Math.min(40, Integer.parseInt(a[i])));
                        } catch (NumberFormatException ex) {
                            plugin.msg(p, "&cUsage: /fc hub build [radius 12-40] [frost|donut]");
                            return;
                        }
                    }
                }
                build(p, radius, theme);
            }
            case "holo" -> {
                String type = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                if (!TYPES.contains(type)) {
                    plugin.msg(p, "&cTypes: " + String.join(", ", TYPES));
                    return;
                }
                plugin.serverData().setLoc("holo." + type, p.getLocation().add(0, 1.5, 0));
                refresh();
                plugin.msg(p, "&aHologram &f" + type + " &aplaced here.");
            }
            case "clear" -> {
                int n = 0;
                for (World w : Bukkit.getWorlds()) {
                    for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                        if (td.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) {
                            td.remove();
                            n++;
                        }
                    }
                }
                plugin.msg(p, "&aRemoved &f" + n + " &ahologram(s) from loaded chunks.");
            }
            default -> plugin.msg(p, "&e/fc hub build [radius] &8| &e/fc hub holo <type> &8| &e/fc hub clear");
        }
    }

    // ================================================================ the build

    private void build(Player p, int R, String theme) {
        if (building) {
            plugin.msg(p, "&cA build is already running.");
            return;
        }
        Location c = p.getLocation();
        World w = c.getWorld();
        int cx = c.getBlockX();
        int cy = c.getBlockY() - 1;
        int cz = c.getBlockZ();
        List<Op> ops = plan(cx, cy, cz, R, theme.equals("frost"));
        building = true;
        plugin.serverData().setStr("hub.theme", theme);
        plugin.msg(p, "&eBuilding the Frosting Hub (" + theme + ") (&f" + ops.size() + " &eblocks)...");

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(ops.size(), i + 500);
                for (; i < end; i++) {
                    Op op = ops.get(i);
                    Block b = w.getBlockAt(op.x(), op.y(), op.z());
                    if (op.mat() == Material.AIR && b.getType() == Material.AIR) continue;
                    if (b.getType() != op.mat()) b.setType(op.mat(), op.mat() != Material.AIR);
                }
                if (i >= ops.size()) {
                    cancel();
                    building = false;
                    finish(p, w, cx, cy, cz, R);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finish(Player p, World w, int cx, int cy, int cz, int R) {
        Location spawn = new Location(w, cx + 0.5, cy + 1, cz + 9.5, 180f, 0f);
        plugin.serverData().setLoc("spawn", spawn);
        plugin.serverData().setLoc("holo.welcome", new Location(w, cx + 0.5, cy + 8.5, cz + 0.5));
        double rr = Math.max(8, R * 0.5);
        double d = rr * 0.7071;
        plugin.serverData().setLoc("holo.money", new Location(w, cx + 0.5 + d, cy + 3.5, cz + 0.5 - d));
        plugin.serverData().setLoc("holo.kills", new Location(w, cx + 0.5 - d, cy + 3.5, cz + 0.5 - d));
        plugin.serverData().setLoc("holo.playtime", new Location(w, cx + 0.5 - d, cy + 3.5, cz + 0.5 + d));
        plugin.serverData().setLoc("holo.shards", new Location(w, cx + 0.5 + d, cy + 3.5, cz + 0.5 + d));
        refresh();
        p.teleportAsync(spawn);
        plugin.msg(p, "&a&lFrosting Hub built! &7Spawn was set to the hub. Protection radius: &f"
                + plugin.getConfig().getDouble("spawn.protection-radius", 40) + "&7 (change it in config.yml).");
        Fireworks.burst(spawn.clone().add(0, 3, 0));
    }

    private List<Op> plan(int cx, int cy, int cz, int R, boolean frost) {
        List<Op> ops = new ArrayList<>();
        Material base = frost ? Material.PACKED_ICE : Material.QUARTZ_BLOCK;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > R) continue;
                int x = cx + dx;
                int z = cz + dz;
                ops.add(new Op(x, cy - 1, z, base));
                ops.add(new Op(x, cy, z, floor(dx, dz, r, R, frost)));
                for (int h = 1; h <= 16; h++) ops.add(new Op(x, cy + h, z, Material.AIR)); // clear the space
            }
        }
        // glowing wall around the edge
        for (int deg = 0; deg < 360; deg++) {
            double a = Math.toRadians(deg);
            int x = cx + (int) Math.round(Math.cos(a) * (R - 0.5));
            int z = cz + (int) Math.round(Math.sin(a) * (R - 0.5));
            ops.add(new Op(x, cy + 1, z, frost ? Material.BLUE_ICE : Material.WHITE_CONCRETE));
            ops.add(new Op(x, cy + 2, z, frost ? Material.WHITE_CONCRETE : Material.PINK_CONCRETE));
            if (deg % 10 == 0) ops.add(new Op(x, cy + 3, z, Material.SEA_LANTERN));
        }
        // eight pillars: ice spires (frost) or candy canes (donut)
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(k * 45 + 22.5);
            int x = cx + (int) Math.round(Math.cos(a) * (R - 5));
            int z = cz + (int) Math.round(Math.sin(a) * (R - 5));
            if (frost) {
                for (int h = 1; h <= 2; h++) {
                    ops.add(new Op(x, cy + h, z, Material.BLUE_ICE));
                    ops.add(new Op(x + 1, cy + h, z, Material.BLUE_ICE));
                    ops.add(new Op(x - 1, cy + h, z, Material.BLUE_ICE));
                    ops.add(new Op(x, cy + h, z + 1, Material.BLUE_ICE));
                    ops.add(new Op(x, cy + h, z - 1, Material.BLUE_ICE));
                }
                for (int h = 3; h <= 6; h++) ops.add(new Op(x, cy + h, z, h % 2 == 0 ? Material.PACKED_ICE : Material.BLUE_ICE));
                ops.add(new Op(x, cy + 7, z, Material.SEA_LANTERN));
                ops.add(new Op(x, cy + 8, z, Material.LIGHT_BLUE_STAINED_GLASS));
                ops.add(new Op(x, cy + 9, z, Material.LIGHT_BLUE_STAINED_GLASS));
            } else {
                for (int h = 1; h <= 6; h++) ops.add(new Op(x, cy + h, z, h % 2 == 0 ? Material.PINK_CONCRETE : Material.WHITE_CONCRETE));
                ops.add(new Op(x, cy + 7, z, Material.SEA_LANTERN));
                ops.add(new Op(x, cy + 8, z, Material.LIGHT_BLUE_STAINED_GLASS));
            }
        }
        // the giant frosted donut in the middle (icy blue frosting in the frost theme, pink in the donut theme)
        Material frosting = frost ? Material.LIGHT_BLUE_CONCRETE : Material.PINK_CONCRETE;
        Material[] sprinkles = {Material.WHITE_CONCRETE, Material.YELLOW_CONCRETE, Material.PINK_CONCRETE,
                Material.LIME_CONCRETE, Material.CYAN_CONCRETE};
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                int x = cx + dx;
                int z = cz + dz;
                if (r >= 2.6 && r <= 4.4) ops.add(new Op(x, cy + 1, z, Material.ORANGE_TERRACOTTA));
                if (r >= 2.0 && r <= 5.0) {
                    ops.add(new Op(x, cy + 2, z, Material.ORANGE_TERRACOTTA));
                    ops.add(new Op(x, cy + 3, z, frosting));
                }
                if (r >= 2.6 && r <= 4.4) {
                    boolean sprinkle = Math.floorMod(dx * 31 + dz * 17, 5) == 0;
                    ops.add(new Op(x, cy + 4, z, sprinkle ? sprinkles[Math.floorMod(dx + dz, sprinkles.length)] : frosting));
                }
            }
        }
        return ops;
    }

    private Material floor(int dx, int dz, double r, int R, boolean frost) {
        if (r < 1.5) return Material.SEA_LANTERN;
        if (r < 5.5) {
            int sector = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 4));
            if (frost) return sector % 2 == 0 ? Material.LIGHT_BLUE_CONCRETE : Material.WHITE_CONCRETE;
            return sector % 2 == 0 ? Material.PINK_CONCRETE : Material.WHITE_CONCRETE;
        }
        if (r >= R - 3.5) return frost ? Material.PACKED_ICE : Material.WHITE_CONCRETE;
        if (r >= R - 4.5) return Material.SEA_LANTERN;
        if (Math.abs(r - R * 0.5) < 0.6) return frost ? Material.BLUE_ICE : Material.LIGHT_BLUE_CONCRETE;
        if (frost) return (dx + dz) % 2 == 0 ? Material.SNOW_BLOCK : Material.WHITE_CONCRETE;
        return (dx + dz) % 2 == 0 ? Material.WHITE_CONCRETE : Material.PINK_TERRACOTTA;
    }

    /** Snowfall (frost) or sparkles (donut) around anyone standing in the spawn. */
    private void ambient() {
        SpawnModule sp = plugin.spawn();
        if (sp == null) return;
        boolean frost = plugin.serverData().str("hub.theme", "frost").equals("frost");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!sp.isProtected(p.getLocation())) continue;
            if (frost) p.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 4, 0), 40, 7, 2, 7, 0.02);
            else p.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 3, 0), 10, 7, 2, 7, 0.01);
        }
    }

    // ================================================================ holograms

    private void refresh() {
        for (String type : TYPES) {
            Location loc = plugin.serverData().loc("holo." + type);
            if (loc == null || loc.getWorld() == null) continue;
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            TextDisplay td = ensure(type, loc);
            td.text(content(type));
            float scale = type.equals("welcome") ? 2.2f : 1.0f;
            td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        }
    }

    private TextDisplay ensure(String type, Location loc) {
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 1.5, 3, 1.5)) {
            if (e instanceof TextDisplay td
                    && type.equals(td.getPersistentDataContainer().get(holoKey, PersistentDataType.STRING))) {
                return td;
            }
        }
        return loc.getWorld().spawn(loc, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, type);
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            td.setShadowed(true);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setPersistent(true);
        });
    }

    private Component content(String type) {
        if (type.equals("welcome")) {
            return Text.c("&b&l❄ &d&lFROSTING SMP &b&l❄\n&7The sweetest SMP around\n\n"
                    + "&b/rtp &8• &b/shop &8• &b/ah &8• &b/crates &8• &b/daily &8• &b/discord\n&7Players online: &f" + Bukkit.getOnlinePlayers().size());
        }
        String title = switch (type) {
            case "money" -> "&a&l$ TOP MONEY";
            case "kills" -> "&c&l⚔ TOP KILLS";
            case "playtime" -> "&e&l⌚ TOP PLAYTIME";
            default -> "&b&l✦ TOP SHARDS";
        };
        List<Map.Entry<UUID, PlayerStore.Data>> list = new ArrayList<>(plugin.store().all().entrySet());
        list.removeIf(en -> en.getValue().name.isEmpty());
        list.sort(Comparator.comparingDouble((Map.Entry<UUID, PlayerStore.Data> en) -> value(en.getValue(), type)).reversed());
        StringBuilder sb = new StringBuilder(title).append("\n");
        int n = Math.min(5, list.size());
        for (int i = 0; i < n; i++) {
            PlayerStore.Data d = list.get(i).getValue();
            sb.append("\n&e#").append(i + 1).append(" &f").append(d.name).append(" &8- ").append(show(d, type));
        }
        if (n == 0) sb.append("\n&7Nobody yet!");
        return Text.c(sb.toString());
    }

    private double value(PlayerStore.Data d, String type) {
        return switch (type) {
            case "kills" -> d.kills;
            case "playtime" -> d.playSeconds;
            case "shards" -> d.shards;
            default -> d.money;
        };
    }

    private String show(PlayerStore.Data d, String type) {
        return switch (type) {
            case "kills" -> "&c" + d.kills;
            case "playtime" -> "&e" + Fmt.duration(d.playSeconds);
            case "shards" -> "&b" + Fmt.shortNum(d.shards);
            default -> "&a" + Fmt.money(d.money);
        };
    }
}
