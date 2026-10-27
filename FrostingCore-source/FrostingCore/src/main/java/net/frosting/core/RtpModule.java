package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** /rtp: a menu of worlds, safe-location search, cooldown, countdown. */
public final class RtpModule implements CommandExecutor, TabCompleter {
    private record WorldCfg(String key, String name, Material icon, int min, int max) {
    }

    private final FrostingCore plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();

    public RtpModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    private List<WorldCfg> worlds() {
        List<WorldCfg> out = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("rtp.worlds");
        if (s == null) return out;
        for (String key : s.getKeys(false)) {
            if (Bukkit.getWorld(key) == null) continue;
            ConfigurationSection w = s.getConfigurationSection(key);
            if (w == null) continue;
            Material icon = Material.matchMaterial(w.getString("icon", "GRASS_BLOCK"));
            if (icon == null) icon = Material.GRASS_BLOCK;
            out.add(new WorldCfg(key, w.getString("name", key), icon, w.getInt("min", 500), w.getInt("max", 5000)));
        }
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        List<WorldCfg> list = worlds();
        if (list.isEmpty()) {
            plugin.msg(p, "&cRTP isn't set up (no loaded worlds in config.yml > rtp.worlds).");
            return true;
        }
        if (args.length > 0) {
            String want = args[0].toLowerCase(Locale.ROOT);
            for (WorldCfg c : list) {
                if (c.key().equalsIgnoreCase(want) || alias(c).equals(want)) {
                    start(p, c);
                    return true;
                }
            }
            plugin.msg(p, "&cUnknown RTP world. Try &f/rtp");
            return true;
        }
        if (list.size() == 1) {
            start(p, list.get(0));
            return true;
        }
        openMenu(p, list);
        return true;
    }

    private String alias(WorldCfg c) {
        World w = Bukkit.getWorld(c.key());
        if (w == null) return "";
        return switch (w.getEnvironment()) {
            case NORMAL -> "overworld";
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "";
        };
    }

    private void openMenu(Player p, List<WorldCfg> list) {
        Gui g = new Gui(3, "&8Random Teleport");
        int n = Math.min(list.size(), 5);
        int[] slots = switch (n) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{11, 15};
            case 3 -> new int[]{11, 13, 15};
            case 4 -> new int[]{10, 12, 14, 16};
            default -> new int[]{9, 11, 13, 15, 17};
        };
        for (int i = 0; i < n; i++) {
            WorldCfg c = list.get(i);
            g.set(slots[i], Items.named(c.icon(), c.name(),
                    "&7Teleport to a random spot",
                    "&7between &f" + c.min() + " &7and &f" + c.max() + " &7blocks away.",
                    "", "&eClick to teleport"), e -> {
                p.closeInventory();
                start(p, c);
            });
        }
        g.fillEmpty(Items.pane());
        g.open(p);
    }

    private void start(Player p, WorldCfg c) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = cooldown.get(id);
        if (until != null && now < until && !p.hasPermission("frostingcore.bypass.cooldown")) {
            plugin.msg(p, "&cYou can use &f/rtp &cagain in &f" + Fmt.duration((until - now + 999) / 1000) + "&c.");
            return;
        }
        if (searching.contains(id)) return;
        World w = Bukkit.getWorld(c.key());
        if (w == null) {
            plugin.msg(p, "&cThat world isn't loaded.");
            return;
        }
        searching.add(id);
        p.sendActionBar(Text.c("&7Searching for a safe location..."));
        SafeLoc.find(plugin, w, 0, 0, c.min(), c.max(), 20, loc -> {
            searching.remove(id);
            if (!p.isOnline()) return;
            if (loc == null) {
                plugin.msg(p, "&cCouldn't find a safe spot. Try again!");
                return;
            }
            plugin.teleports().teleport(p, loc, "Random teleport", () -> {
                cooldown.put(id, System.currentTimeMillis() + plugin.getConfig().getInt("rtp.cooldown", 30) * 1000L);
                if (plugin.getConfig().getBoolean("rtp.show-coordinates", true)) {
                    plugin.msg(p, "&aTeleported to &f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "&a.");
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (WorldCfg c : worlds()) {
                out.add(c.key());
                out.add(alias(c));
            }
            out.removeIf(s -> s.isEmpty() || !s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
