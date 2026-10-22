package net.lanternlight.infuse;

import net.lanternlight.infuse.InfuseType.Slot;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import java.util.Set;
import java.util.UUID;

public final class Commands implements CommandExecutor, TabCompleter {
    private static final List<String> ADMIN = List.of("give", "clear", "resetcd", "resetcrafts", "reload");

    private final LanternInfuse plugin;

    public Commands(LanternInfuse plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("infuse")) return admin(sender, args);

        if (!(sender instanceof Player p)) {
            sender.sendMessage("This command is for players only.");
            return true;
        }
        switch (name) {
            case "lspark" -> plugin.abilities().trySpark(p, Slot.ONE);
            case "rspark" -> plugin.abilities().trySpark(p, Slot.TWO);
            case "ldrain" -> drain(p, Slot.ONE);
            case "rdrain" -> drain(p, Slot.TWO);
            case "controls" -> controls(p);
            case "trust" -> trust(p, args, true);
            case "untrust" -> trust(p, args, false);
            case "infuses" -> infuses(p, args);
            case "recipes" -> plugin.recipeMenu().openMain(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- player commands

    private void drain(Player p, Slot slot) {
        Profiles pr = plugin.profiles();
        InfuseType t = pr.get(p, slot);
        if (t == null) {
            plugin.msg(p, "&cNothing equipped in slot " + slot.number() + ".");
            return;
        }
        if (pr.isActive(p, slot)) {
            plugin.msg(p, "&cYou can't drain while the spark is active.");
            return;
        }
        boolean aug = pr.isAug(p, slot);
        pr.clear(p, slot);
        give(p, plugin.items().create(t, aug));
        plugin.msg(p, "You drained your " + plugin.settings(t).name() + " Effect &7back into an item.");
    }

    private void controls(Player p) {
        boolean def = plugin.getConfig().getString("settings.default-controls", "offhand").equalsIgnoreCase("offhand");
        boolean now = plugin.profiles().offhandControls(p, def);
        plugin.profiles().setOffhandControls(p, !now);
        if (!now) {
            plugin.msg(p, "Controls: &aOffhand &7- press &fF &7for slot 1, &fShift+F &7for slot 2.");
        } else {
            plugin.msg(p, "Controls: &aCommands &7- use &f/lspark &7and &f/rspark&7.");
        }
    }

    private void trust(Player p, String[] args, boolean add) {
        if (args.length < 1) {
            Set<UUID> set = plugin.profiles().trusted(p);
            List<String> names = new ArrayList<>();
            for (UUID id : set) {
                String n = Bukkit.getOfflinePlayer(id).getName();
                names.add(n == null ? id.toString() : n);
            }
            plugin.msg(p, "Trusted: &f" + (names.isEmpty() ? "nobody" : String.join(", ", names)));
            plugin.msg(p, "Usage: &f/" + (add ? "trust" : "untrust") + " <player>");
            return;
        }
        Set<UUID> set = plugin.profiles().trusted(p);
        if (add) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                plugin.msg(p, "&cThat player must be online to be trusted.");
                return;
            }
            if (target.equals(p)) {
                plugin.msg(p, "&cYou already trust yourself.");
                return;
            }
            set.add(target.getUniqueId());
            plugin.profiles().setTrusted(p, set);
            plugin.msg(p, "You now trust &f" + target.getName() + "&7. Your abilities won't hit them.");
        } else {
            UUID found = null;
            for (UUID id : set) {
                String n = Bukkit.getOfflinePlayer(id).getName();
                if (n != null && n.equalsIgnoreCase(args[0])) found = id;
            }
            if (found == null) {
                plugin.msg(p, "&cYou don't trust anyone called " + args[0] + ".");
                return;
            }
            set.remove(found);
            plugin.profiles().setTrusted(p, set);
            plugin.msg(p, "You no longer trust &f" + args[0] + "&7.");
        }
    }

    private void infuses(Player p, String[] args) {
        if (args.length == 0) {
            plugin.msg(p, "&6&lEffects &8(use &f/infuses <effect> &8for details)");
            for (InfuseType t : InfuseType.values()) {
                EffectSettings s = plugin.settings(t);
                p.sendMessage(Text.c(" &" + s.hex() + s.icon() + " " + s.name() + " &8- &7" + s.passive()));
            }
            return;
        }
        InfuseType t = InfuseType.byId(args[0]);
        if (t == null) {
            plugin.msg(p, "&cUnknown effect. Try &f/infuses");
            return;
        }
        EffectSettings s = plugin.settings(t);
        String icon = "&" + s.hex() + s.icon();
        plugin.msg(p, icon + " " + s.name() + " Effect");
        p.sendMessage(Text.c(" &9Passive: &7" + s.passive()));
        p.sendMessage(Text.c(" &9Spark: &7" + s.spark()));
        p.sendMessage(Text.c(" &8Duration &b" + s.duration() + "s &8| Cooldown &b" + s.cooldown()
                + "s &8| Augmented cooldown &6" + s.augCooldown() + "s"));
        int made = plugin.crafted(t);
        int max = plugin.crafting().maxCrafts();
        p.sendMessage(Text.c(" &8Crafted so far: &f" + made + "&8/&f" + max));
        p.sendMessage(Text.c(" &9Recipe:"));
        for (String row : plugin.crafting().rows(t)) {
            p.sendMessage(Text.c("  &8[ &f" + row + " &8]"));
        }
    }

    // ---------------------------------------------------------------- admin

    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("lanterninfuse.admin")) {
            plugin.msg(s, "&cYou don't have permission.");
            return true;
        }
        if (a.length == 0) {
            plugin.msg(s, "&e/infuse give <player> <effect> [aug] &8| &e/infuse clear <player> &8| &e/infuse resetcd <player>");
            plugin.msg(s, "&e/infuse resetcrafts <effect|all> &8| &e/infuse reload");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (a.length < 3) {
                    plugin.msg(s, "&cUsage: /infuse give <player> <effect> [aug]");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                InfuseType t = InfuseType.byId(a[2]);
                if (target == null || t == null) {
                    plugin.msg(s, "&cUnknown player or effect.");
                    return true;
                }
                boolean aug = a.length > 3 && (a[3].equalsIgnoreCase("aug") || a[3].equalsIgnoreCase("true"));
                give(target, plugin.items().create(t, aug));
                plugin.msg(s, "Gave &f" + target.getName() + " &7a " + plugin.settings(t).name() + " Effect" + (aug ? " &6(Augmented)" : "") + "&7.");
            }
            case "clear" -> {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player pl ? pl : null);
                if (target == null) {
                    plugin.msg(s, "&cPlayer not found.");
                    return true;
                }
                plugin.profiles().clear(target, Slot.ONE);
                plugin.profiles().clear(target, Slot.TWO);
                plugin.msg(s, "Cleared effects for &f" + target.getName() + "&7.");
            }
            case "resetcd" -> {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player pl ? pl : null);
                if (target == null) {
                    plugin.msg(s, "&cPlayer not found.");
                    return true;
                }
                plugin.profiles().resetCooldowns(target);
                plugin.msg(s, "Reset cooldowns for &f" + target.getName() + "&7.");
            }
            case "resetcrafts" -> {
                if (a.length < 2) {
                    plugin.msg(s, "&cUsage: /infuse resetcrafts <effect|all>");
                    return true;
                }
                if (a[1].equalsIgnoreCase("all")) {
                    for (InfuseType t : InfuseType.values()) plugin.resetCrafted(t);
                    plugin.msg(s, "Reset the craft counter for &fall &7effects.");
                } else {
                    InfuseType t = InfuseType.byId(a[1]);
                    if (t == null) {
                        plugin.msg(s, "&cUnknown effect.");
                        return true;
                    }
                    plugin.resetCrafted(t);
                    plugin.msg(s, "Reset the craft counter for " + plugin.settings(t).name() + "&7.");
                }
            }
            case "reload" -> {
                plugin.reloadAll();
                plugin.msg(s, "&aReloaded config and recipes.");
            }
            default -> plugin.msg(s, "&cUnknown subcommand.");
        }
        return true;
    }

    private void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack extra : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), extra);
        }
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("infuse") && sender.hasPermission("lanterninfuse.admin")) {
            if (args.length == 1) out.addAll(ADMIN);
            else if (args.length == 2 && args[0].equalsIgnoreCase("resetcrafts")) {
                out.add("all");
                effectIds(out);
            } else if (args.length == 2) players(out);
            else if (args.length == 3 && args[0].equalsIgnoreCase("give")) effectIds(out);
            else if (args.length == 4 && args[0].equalsIgnoreCase("give")) out.add("aug");
        } else if (name.equals("trust") && args.length == 1) {
            players(out);
        } else if (name.equals("infuses") && args.length == 1) {
            effectIds(out);
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private void players(List<String> out) {
        for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
    }

    private void effectIds(List<String> out) {
        for (InfuseType t : InfuseType.values()) out.add(t.id());
    }
}
