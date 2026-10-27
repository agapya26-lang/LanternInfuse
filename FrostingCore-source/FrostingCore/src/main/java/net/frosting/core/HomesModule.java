package net.frosting.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** /sethome /home /delhome /homes (menu). Limit = config default, raised by frostingcore.homes.<n>. */
public final class HomesModule implements CommandExecutor, TabCompleter {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    private final FrostingCore plugin;

    public HomesModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    public int limit(Player p) {
        int max = plugin.getConfig().getInt("homes.default", 3);
        for (int n = 1; n <= 100; n++) {
            if (p.hasPermission("frostingcore.homes." + n)) max = Math.max(max, n);
        }
        return max;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        PlayerStore.Data d = plugin.store().get(p.getUniqueId());
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> {
                String name = args.length > 0 ? args[0] : "home";
                if (!NAME.matcher(name).matches()) {
                    plugin.msg(p, "&cHome names can use letters, numbers, - and _ (max 16).");
                    return true;
                }
                boolean exists = d.homes.containsKey(name);
                if (!exists && d.homes.size() >= limit(p)) {
                    plugin.msg(p, "&cYou've reached your home limit (&f" + limit(p) + "&c). Use &f/delhome &cfirst.");
                    return true;
                }
                if (plugin.spawn() != null && plugin.spawn().isProtected(p.getLocation())
                        && !p.hasPermission("frostingcore.bypass.spawn")) {
                    plugin.msg(p, "&cYou can't set a home inside spawn.");
                    return true;
                }
                d.homes.put(name, Locs.enc(p.getLocation()));
                plugin.store().dirty();
                plugin.msg(p, "&aHome &f" + name + " &asaved.");
            }
            case "home" -> {
                if (args.length > 0) {
                    go(p, d, args[0]);
                } else if (d.homes.isEmpty()) {
                    plugin.msg(p, "&cYou have no homes. Use &f/sethome");
                } else if (d.homes.size() == 1) {
                    go(p, d, d.homes.keySet().iterator().next());
                } else if (d.homes.containsKey("home")) {
                    go(p, d, "home");
                } else {
                    openGui(p, d);
                }
            }
            case "delhome" -> {
                if (args.length < 1) {
                    plugin.msg(p, "&cUsage: /delhome <name>");
                } else if (d.homes.remove(args[0]) != null) {
                    plugin.store().dirty();
                    plugin.msg(p, "&aHome &f" + args[0] + " &adeleted.");
                } else {
                    plugin.msg(p, "&cYou have no home called " + args[0] + ".");
                }
            }
            case "homes" -> openGui(p, d);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void go(Player p, PlayerStore.Data d, String name) {
        String enc = d.homes.get(name);
        if (enc == null) {
            plugin.msg(p, "&cYou have no home called &f" + name + "&c.");
            return;
        }
        Location l = Locs.dec(enc);
        if (l == null) {
            plugin.msg(p, "&cThat home's world isn't loaded.");
            return;
        }
        plugin.teleports().teleport(p, l, "Teleporting home", null);
    }

    private void openGui(Player p, PlayerStore.Data d) {
        int rows = Math.max(1, Math.min(6, (d.homes.size() + 8) / 9));
        Gui g = new Gui(rows, "&8Your homes &7(" + d.homes.size() + "/" + limit(p) + ")");
        int slot = 0;
        for (Map.Entry<String, String> en : new ArrayList<>(d.homes.entrySet())) {
            if (slot >= rows * 9) break;
            Location l = Locs.dec(en.getValue());
            String where = l == null ? "&cworld not loaded"
                    : "&f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + " &7(" + l.getWorld().getName() + ")";
            ItemStack bed = Items.named(Material.RED_BED, "&a" + en.getKey(),
                    "&7" + where, "", "&eClick: teleport", "&cShift-click: delete");
            final String name = en.getKey();
            g.set(slot++, bed, e -> {
                p.closeInventory();
                if (e.isShiftClick()) {
                    d.homes.remove(name);
                    plugin.store().dirty();
                    plugin.msg(p, "&aHome &f" + name + " &adeleted.");
                } else {
                    go(p, d, name);
                }
            });
        }
        g.open(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (sender instanceof Player p && args.length == 1 && (n.equals("home") || n.equals("delhome"))) {
            for (String h : plugin.store().get(p.getUniqueId()).homes.keySet()) {
                if (h.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(h);
            }
        }
        return out;
    }
}
