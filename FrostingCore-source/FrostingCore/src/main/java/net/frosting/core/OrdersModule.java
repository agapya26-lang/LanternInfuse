package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /orders: players post "I'll pay $X each for N of this item" (the money is held up front) and anyone can fill it.
 * The buyer collects the delivered items from /orders mine.
 */
public final class OrdersModule implements CommandExecutor, TabCompleter {
    private static final class Order {
        final long id;
        final UUID owner;
        final String ownerName;
        final Material mat;
        int remaining;
        int delivered;
        final double each;
        final long created;

        Order(long id, UUID owner, String ownerName, Material mat, int remaining, int delivered, double each, long created) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.mat = mat;
            this.remaining = remaining;
            this.delivered = delivered;
            this.each = each;
            this.created = created;
        }
    }

    private final FrostingCore plugin;
    private final Persist persist;
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private long nextId = 1;

    public OrdersModule(FrostingCore plugin) {
        this.plugin = plugin;
        this.persist = new Persist(plugin, "orders.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(persist.file());
        nextId = Math.max(1, y.getLong("next-id", 1));
        ConfigurationSection s = y.getConfigurationSection("orders");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection o = s.getConfigurationSection(key);
                if (o == null) continue;
                try {
                    Material m = Material.matchMaterial(o.getString("item", ""));
                    if (m == null) continue;
                    long id = Long.parseLong(key);
                    orders.put(id, new Order(id, UUID.fromString(o.getString("owner", "")), o.getString("name", "?"), m,
                            o.getInt("remaining"), o.getInt("delivered"), o.getDouble("each"), o.getLong("created")));
                } catch (IllegalArgumentException ignored) {
                    // skip broken entries
                }
            }
        }
    }

    private String toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Order o : orders.values()) {
            String b = "orders." + o.id;
            y.set(b + ".owner", o.owner.toString());
            y.set(b + ".name", o.ownerName);
            y.set(b + ".item", o.mat.name());
            y.set(b + ".remaining", o.remaining);
            y.set(b + ".delivered", o.delivered);
            y.set(b + ".each", o.each);
            y.set(b + ".created", o.created);
        }
        return y.saveToString();
    }

    public void flushAsync() {
        persist.flushAsync(this::toYaml);
    }

    public void flushNow() {
        persist.flushNow(this::toYaml);
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ================================================================ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "create", "new" -> create(p, args);
            case "mine" -> openMine(p);
            case "" -> openBrowse(p, 0);
            default -> plugin.msg(p, "&e/orders &8| &e/orders create <amount> <price each> &7(hold the item) &8| &e/orders mine");
        }
        return true;
    }

    private void create(Player p, String[] a) {
        if (a.length < 3) {
            plugin.msg(p, "&cUsage: /orders create <amount> <price each>  (hold the item you want)");
            return;
        }
        Material m = p.getInventory().getItemInMainHand().getType();
        if (m.isAir()) {
            plugin.msg(p, "&cHold one of the item you want to buy.");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(a[1]);
        } catch (NumberFormatException ex) {
            plugin.msg(p, "&cAmount must be a whole number.");
            return;
        }
        int maxAmount = plugin.getConfig().getInt("orders.max-amount", 20000);
        double each = Fmt.parse(a[2]);
        double min = plugin.getConfig().getDouble("orders.min-price-each", 0.1);
        if (amount < 1 || amount > maxAmount || Double.isNaN(each) || each < min) {
            plugin.msg(p, "&cAmount must be 1-" + maxAmount + " and the price at least &f" + Fmt.money(min) + " &ceach.");
            return;
        }
        each = Math.round(each * 100.0) / 100.0;
        long mine = orders.values().stream().filter(o -> o.owner.equals(p.getUniqueId())).count();
        if (mine >= plugin.getConfig().getInt("orders.max-per-player", 5)) {
            plugin.msg(p, "&cYou have too many open orders. Cancel one in &f/orders mine&c.");
            return;
        }
        double total = Math.round(amount * each * 100.0) / 100.0;
        if (!plugin.eco().take(p.getUniqueId(), total)) {
            plugin.msg(p, "&cThat order costs &f" + Fmt.money(total) + " &cup front.");
            return;
        }
        Order o = new Order(nextId++, p.getUniqueId(), p.getName(), m, amount, 0, each, System.currentTimeMillis());
        orders.put(o.id, o);
        persist.dirty();
        plugin.msg(p, "&aOrder posted: &f" + amount + "x " + pretty(m) + " &aat &f" + Fmt.money(each) + " &aeach (&f" + Fmt.money(total) + " &aheld).");
    }

    // ================================================================ browse / fill

    private void openBrowse(Player p, int page) {
        List<Order> list = new ArrayList<>();
        for (Order o : orders.values()) if (o.remaining > 0 && !o.owner.equals(p.getUniqueId())) list.add(o);
        list.sort(Comparator.comparingDouble((Order o) -> o.each * o.remaining).reversed());
        int pages = Math.max(1, (list.size() + 44) / 45);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        Gui g = new Gui(6, "&8Orders &7(" + (pg + 1) + "/" + pages + ")");
        for (int i = 0; i < 45; i++) {
            int idx = pg * 45 + i;
            if (idx >= list.size()) break;
            Order o = list.get(idx);
            g.set(i, Items.named(o.mat, "&e" + pretty(o.mat),
                    "&7Wants: &f" + o.remaining + "x", "&7Pays: &a" + Fmt.money(o.each) + " &7each",
                    "&7Total: &a" + Fmt.money(o.each * o.remaining), "&7Buyer: &f" + o.ownerName, "",
                    "&eClick to deliver what you have"), e -> deliver(p, o.id, pg));
        }
        for (int i = 45; i < 54; i++) g.put(i, Items.pane());
        g.set(49, Items.named(Material.CHEST, "&eYour orders", "&7Collect items / cancel"), e -> openMine(p));
        g.set(48, Items.named(Material.WRITABLE_BOOK, "&eNew order", "&7/orders create <amount> <price each>", "&7while holding the item"), null);
        if (pg > 0) g.set(45, Items.named(Material.ARROW, "&e← Previous"), e -> openBrowse(p, pg - 1));
        if (pg < pages - 1) g.set(53, Items.named(Material.ARROW, "&eNext →"), e -> openBrowse(p, pg + 1));
        g.open(p);
    }

    private int take(Player p, Material m, int want) {
        int taken = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && taken < want; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m || !Items.isPlain(it)) continue;
            int n = Math.min(it.getAmount(), want - taken);
            taken += n;
            if (n >= it.getAmount()) p.getInventory().setItem(i, null);
            else {
                it.setAmount(it.getAmount() - n);
                p.getInventory().setItem(i, it);
            }
        }
        return taken;
    }

    private void deliver(Player p, long id, int returnPage) {
        Order o = orders.get(id);
        if (o == null || o.remaining <= 0) {
            plugin.msg(p, "&cThat order was already filled.");
            openBrowse(p, returnPage);
            return;
        }
        int n = take(p, o.mat, o.remaining);
        if (n <= 0) {
            plugin.msg(p, "&cYou don't have any plain &f" + pretty(o.mat) + "&c to deliver.");
            return;
        }
        double pay = Math.round(n * o.each * 100.0) / 100.0;
        o.remaining -= n;
        o.delivered += n;
        plugin.eco().add(p.getUniqueId(), pay);
        persist.dirty();
        plugin.msg(p, "&aDelivered &f" + n + "x " + pretty(o.mat) + " &aand earned &f" + Fmt.money(pay) + "&a.");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner != null) {
            plugin.msg(owner, "&f" + p.getName() + " &adelivered &f" + n + "x " + pretty(o.mat) + " &ato your order. Collect it in &f/orders mine&a.");
        }
        openBrowse(p, returnPage);
    }

    // ================================================================ my orders

    private void openMine(Player p) {
        List<Order> mine = new ArrayList<>();
        for (Order o : orders.values()) if (o.owner.equals(p.getUniqueId())) mine.add(o);
        Gui g = new Gui(5, "&8Your orders &7(" + mine.size() + ")");
        for (int i = 0; i < Math.min(36, mine.size()); i++) {
            Order o = mine.get(i);
            double refund = Math.round(o.remaining * o.each * 100.0) / 100.0;
            g.set(i, Items.named(o.mat, "&e" + pretty(o.mat),
                    "&7Still wanted: &f" + o.remaining + "x &7at &a" + Fmt.money(o.each),
                    "&7Waiting to collect: &f" + o.delivered + "x", "",
                    "&eClick: &fcollect delivered items", "&cShift-click: &fcancel (refund " + Fmt.money(refund) + ")"),
                    e -> {
                        if (e.isShiftClick()) cancel(p, o);
                        else collect(p, o);
                        openMine(p);
                    });
        }
        for (int i = 36; i < 45; i++) g.put(i, Items.pane());
        g.set(40, Items.named(Material.ARROW, "&e← Back"), e -> openBrowse(p, 0));
        g.open(p);
    }

    private void collect(Player p, Order o) {
        if (!orders.containsKey(o.id) || o.delivered <= 0) return;
        int n = Math.min(o.delivered, Items.capacityFor(p.getInventory(), o.mat));
        if (n <= 0) {
            plugin.msg(p, "&cNo room in your inventory.");
            return;
        }
        int left = n;
        while (left > 0) {
            int s = Math.min(left, o.mat.getMaxStackSize());
            p.getInventory().addItem(new ItemStack(o.mat, s));
            left -= s;
        }
        o.delivered -= n;
        if (o.delivered == 0 && o.remaining == 0) orders.remove(o.id);
        persist.dirty();
        plugin.msg(p, "&aCollected &f" + n + "x " + pretty(o.mat) + "&a.");
    }

    private void cancel(Player p, Order o) {
        if (!orders.containsKey(o.id)) return;
        double refund = Math.round(o.remaining * o.each * 100.0) / 100.0;
        plugin.eco().add(p.getUniqueId(), refund);
        o.remaining = 0;
        if (o.delivered == 0) orders.remove(o.id);
        persist.dirty();
        plugin.msg(p, "&aOrder cancelled. Refunded &f" + Fmt.money(refund) + "&a." + (o.delivered > 0 ? " Collect the delivered items first." : ""));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("create", "mine")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }
}
