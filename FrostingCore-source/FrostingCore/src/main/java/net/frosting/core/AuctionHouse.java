package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /ah - player auction house. Items are stored as full NBT bytes (nothing is lost), a listing is removed
 * from the map BEFORE any money or item moves, so a sale can never happen twice (no dupes).
 */
public final class AuctionHouse implements Listener, CommandExecutor, TabCompleter {
    private static final class Listing {
        final long id;
        final UUID seller;
        final String sellerName;
        final ItemStack item;
        final String encoded;
        final double price;
        final long created;
        final long expires;

        Listing(long id, UUID seller, String sellerName, ItemStack item, String encoded, double price, long created, long expires) {
            this.id = id;
            this.seller = seller;
            this.sellerName = sellerName;
            this.item = item;
            this.encoded = encoded;
            this.price = price;
            this.created = created;
            this.expires = expires;
        }
    }

    private enum Sort {
        NEWEST("Newest first"), PRICE_LOW("Price: low to high"), PRICE_HIGH("Price: high to low"), ENDING("Ending soonest");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            Sort[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private final FrostingCore plugin;
    private final Persist persist;
    private final Map<Long, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<String>> claims = new HashMap<>();
    private final Map<UUID, Sort> sorts = new HashMap<>();
    private final Map<UUID, String> filters = new HashMap<>();
    private long nextId = 1;

    public AuctionHouse(FrostingCore plugin) {
        this.plugin = plugin;
        this.persist = new Persist(plugin, "auction.yml");
        load();
    }

    // ================================================================ storage

    private static String encode(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    private static ItemStack decode(String enc) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(enc));
        } catch (Exception ex) {
            return null;
        }
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(persist.file());
        nextId = Math.max(1, y.getLong("next-id", 1));
        ConfigurationSection ls = y.getConfigurationSection("listings");
        if (ls != null) {
            for (String key : ls.getKeys(false)) {
                ConfigurationSection s = ls.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    long id = Long.parseLong(key);
                    String enc = s.getString("item", "");
                    ItemStack item = decode(enc);
                    if (item == null) continue;
                    listings.put(id, new Listing(id, UUID.fromString(s.getString("seller", "")), s.getString("name", "?"),
                            item, enc, s.getDouble("price"), s.getLong("created"), s.getLong("expires")));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("auction.yml: skipped a broken listing (" + key + ")");
                }
            }
        }
        ConfigurationSection cs = y.getConfigurationSection("claims");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    claims.put(UUID.fromString(key), new ArrayList<>(cs.getStringList(key)));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
    }

    private String toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Listing l : listings.values()) {
            String b = "listings." + l.id;
            y.set(b + ".seller", l.seller.toString());
            y.set(b + ".name", l.sellerName);
            y.set(b + ".item", l.encoded);
            y.set(b + ".price", l.price);
            y.set(b + ".created", l.created);
            y.set(b + ".expires", l.expires);
        }
        for (Map.Entry<UUID, List<String>> en : claims.entrySet()) {
            if (!en.getValue().isEmpty()) y.set("claims." + en.getKey(), en.getValue());
        }
        return y.saveToString();
    }

    public void flushAsync() {
        persist.flushAsync(this::toYaml);
    }

    public void flushNow() {
        persist.flushNow(this::toYaml);
    }

    // ================================================================ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            openBrowse(p, 0);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell", "list" -> sell(p, args);
            case "mine", "listings" -> openMine(p);
            case "claim", "claims" -> openClaims(p);
            case "search" -> {
                if (args.length < 2) {
                    filters.remove(p.getUniqueId());
                    plugin.msg(p, "&7Search cleared.");
                } else {
                    filters.put(p.getUniqueId(), String.join(" ", List.of(args).subList(1, args.length)).toLowerCase(Locale.ROOT));
                }
                openBrowse(p, 0);
            }
            default -> plugin.msg(p, "&e/ah &8| &e/ah sell <price> &8| &e/ah search <text> &8| &e/ah mine &8| &e/ah claim");
        }
        return true;
    }

    private void sell(Player p, String[] a) {
        if (a.length < 2) {
            plugin.msg(p, "&cUsage: /ah sell <price>  (hold the item; 1k, 2.5m ... works)");
            return;
        }
        double price = Fmt.parse(a[1]);
        double min = plugin.getConfig().getDouble("auction.min-price", 1);
        double max = plugin.getConfig().getDouble("auction.max-price", 1.0E12);
        if (Double.isNaN(price) || price < min || price > max) {
            plugin.msg(p, "&cPrice must be between &f" + Fmt.money(min) + " &cand &f" + Fmt.money(max) + "&c.");
            return;
        }
        price = Math.round(price * 100.0) / 100.0;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            plugin.msg(p, "&cHold the item you want to sell.");
            return;
        }
        if (plugin.getConfig().getStringList("auction.blacklist").contains(hand.getType().name())) {
            plugin.msg(p, "&cThat item can't be sold on the auction house.");
            return;
        }
        long mine = listings.values().stream().filter(l -> l.seller.equals(p.getUniqueId())).count();
        int limit = plugin.getConfig().getInt("auction.max-listings", 10);
        if (mine >= limit && !p.hasPermission("frostingcore.bypass.ahlimit")) {
            plugin.msg(p, "&cYou already have &f" + limit + " &clistings. Cancel one with &f/ah mine&c.");
            return;
        }
        byte[] bytes;
        try {
            bytes = hand.serializeAsBytes();
        } catch (Exception ex) {
            plugin.msg(p, "&cThat item can't be listed.");
            return;
        }
        if (bytes.length > plugin.getConfig().getInt("auction.max-item-bytes", 20000)) {
            plugin.msg(p, "&cThat item's data is too large to list (crash protection).");
            return;
        }
        ItemStack copy = hand.clone();
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); // take it first, list second
        long now = System.currentTimeMillis();
        long hours = plugin.getConfig().getLong("auction.duration-hours", 48);
        Listing l = new Listing(nextId++, p.getUniqueId(), p.getName(), copy,
                Base64.getEncoder().encodeToString(bytes), price, now, now + hours * 3_600_000L);
        listings.put(l.id, l);
        persist.dirty();
        plugin.msg(p, "&aListed &f" + copy.getAmount() + "x " + pretty(copy) + " &afor &f" + Fmt.money(price) + "&a.");
    }

    // ================================================================ views

    private static String pretty(ItemStack it) {
        return it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private boolean matches(Listing l, String filter) {
        if (filter == null || filter.isEmpty()) return true;
        if (pretty(l.item).contains(filter)) return true;
        if (l.item.hasItemMeta() && l.item.getItemMeta().hasDisplayName()) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(l.item.getItemMeta().displayName()).toLowerCase(Locale.ROOT);
            return plain.contains(filter);
        }
        return false;
    }

    private List<Listing> view(Player p) {
        long now = System.currentTimeMillis();
        String filter = filters.get(p.getUniqueId());
        List<Listing> list = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (l.expires > now && matches(l, filter)) list.add(l);
        }
        switch (sorts.getOrDefault(p.getUniqueId(), Sort.NEWEST)) {
            case NEWEST -> list.sort(Comparator.comparingLong((Listing l) -> l.created).reversed());
            case PRICE_LOW -> list.sort(Comparator.comparingDouble((Listing l) -> l.price));
            case PRICE_HIGH -> list.sort(Comparator.comparingDouble((Listing l) -> l.price).reversed());
            case ENDING -> list.sort(Comparator.comparingLong((Listing l) -> l.expires));
        }
        return list;
    }

    private ItemStack display(Listing l) {
        long left = Math.max(0, (l.expires - System.currentTimeMillis()) / 1000);
        return Items.withLore(l.item, "", "&7Price: &a" + Fmt.money(l.price), "&7Seller: &f" + l.sellerName,
                "&7Expires in: &f" + Fmt.duration(left), "", "&eClick to buy");
    }

    public void openBrowse(Player p, int page) {
        List<Listing> list = view(p);
        int pages = Math.max(1, (list.size() + 44) / 45);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        Sort sort = sorts.getOrDefault(p.getUniqueId(), Sort.NEWEST);
        String filter = filters.get(p.getUniqueId());
        Gui g = new Gui(6, "&8Auction House &7(" + (pg + 1) + "/" + pages + ")");
        for (int i = 0; i < 45; i++) {
            int idx = pg * 45 + i;
            if (idx >= list.size()) break;
            Listing l = list.get(idx);
            g.set(i, display(l), e -> openConfirm(p, l.id));
        }
        for (int i = 45; i < 54; i++) g.put(i, Items.pane());
        if (pg > 0) g.set(45, Items.named(Material.ARROW, "&e← Previous page"), e -> openBrowse(p, pg - 1));
        g.set(46, Items.named(Material.OAK_SIGN, "&eSearch",
                filter == null ? "&7Use &f/ah search <text>" : "&7Filter: &f" + filter, "&7Use &f/ah search &7to clear"), e -> {
            p.closeInventory();
            plugin.msg(p, "&7Type &f/ah search <text>&7.");
        });
        g.set(47, Items.named(Material.HOPPER, "&eSort", "&7Now: &f" + sort.label, "", "&eClick to change"), e -> {
            sorts.put(p.getUniqueId(), sort.next());
            openBrowse(p, 0);
        });
        g.set(49, Items.named(Material.CLOCK, "&eRefresh", "&7" + list.size() + " listings"), e -> openBrowse(p, pg));
        g.set(50, Items.named(Material.CHEST, "&eYour listings", "&7Click to manage"), e -> openMine(p));
        int pending = claims.getOrDefault(p.getUniqueId(), List.of()).size();
        g.set(51, Items.named(Material.ENDER_CHEST, "&eClaim items", "&7Waiting: &f" + pending), e -> openClaims(p));
        if (pg < pages - 1) g.set(53, Items.named(Material.ARROW, "&eNext page →"), e -> openBrowse(p, pg + 1));
        g.open(p);
    }

    private void openConfirm(Player p, long id) {
        Listing l = listings.get(id);
        if (l == null || l.expires <= System.currentTimeMillis()) {
            plugin.msg(p, "&cThat listing is no longer available.");
            openBrowse(p, 0);
            return;
        }
        Gui g = new Gui(3, "&8Confirm purchase");
        g.put(13, display(l));
        g.set(11, Items.named(Material.LIME_CONCRETE, "&a&lBUY", "&7Pay &a" + Fmt.money(l.price)), e -> {
            p.closeInventory();
            buy(p, id);
        });
        g.set(15, Items.named(Material.RED_CONCRETE, "&c&lCANCEL"), e -> openBrowse(p, 0));
        g.fillEmpty(Items.pane());
        g.open(p);
    }

    private void buy(Player p, long id) {
        Listing l = listings.get(id);
        if (l == null || l.expires <= System.currentTimeMillis()) {
            plugin.msg(p, "&cToo late - that listing is gone.");
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            plugin.msg(p, "&cYou can't buy your own listing. Cancel it from &f/ah mine&c.");
            return;
        }
        if (!plugin.eco().has(p.getUniqueId(), l.price)) {
            plugin.msg(p, "&cYou need &f" + Fmt.money(l.price) + "&c. Balance: &f" + Fmt.money(plugin.eco().balance(p.getUniqueId())));
            return;
        }
        if (!Items.canFit(p.getInventory(), l.item)) {
            plugin.msg(p, "&cMake some room in your inventory first.");
            return;
        }
        listings.remove(id); // gone before anything else happens: it can never be bought twice
        plugin.eco().take(p.getUniqueId(), l.price);
        double tax = l.price * plugin.getConfig().getDouble("auction.tax-percent", 0) / 100.0;
        plugin.eco().add(l.seller, Math.round((l.price - tax) * 100.0) / 100.0);
        p.getInventory().addItem(l.item.clone());
        persist.dirty();
        plugin.msg(p, "&aBought &f" + l.item.getAmount() + "x " + pretty(l.item) + " &afor &f" + Fmt.money(l.price) + "&a.");
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            plugin.msg(seller, "&f" + p.getName() + " &abought your &f" + l.item.getAmount() + "x " + pretty(l.item)
                    + " &afor &f" + Fmt.money(l.price - tax) + "&a.");
        }
        plugin.getLogger().info("[AH] " + p.getName() + " bought #" + id + " from " + l.sellerName + " for " + l.price);
    }

    private void openMine(Player p) {
        List<Listing> mine = new ArrayList<>();
        for (Listing l : listings.values()) if (l.seller.equals(p.getUniqueId())) mine.add(l);
        Gui g = new Gui(5, "&8Your listings &7(" + mine.size() + ")");
        for (int i = 0; i < Math.min(36, mine.size()); i++) {
            Listing l = mine.get(i);
            g.set(i, Items.withLore(display(l), "&cShift-click to cancel"), e -> {
                if (!e.isShiftClick()) return;
                if (listings.remove(l.id) == null) return;
                persist.dirty();
                giveOrClaim(p, l.item.clone());
                plugin.msg(p, "&aListing cancelled.");
                openMine(p);
            });
        }
        for (int i = 36; i < 45; i++) g.put(i, Items.pane());
        g.set(40, Items.named(Material.ARROW, "&e← Back"), e -> openBrowse(p, 0));
        g.open(p);
    }

    private void giveOrClaim(Player p, ItemStack item) {
        if (Items.canFit(p.getInventory(), item)) {
            p.getInventory().addItem(item);
        } else {
            claims.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(encode(item));
            persist.dirty();
            plugin.msg(p, "&7Your inventory is full, so it was sent to &f/ah claim&7.");
        }
    }

    private void openClaims(Player p) {
        List<String> mine = claims.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        Gui g = new Gui(5, "&8Claim items &7(" + mine.size() + ")");
        for (int i = 0; i < Math.min(36, mine.size()); i++) {
            ItemStack it = decode(mine.get(i));
            if (it == null) continue;
            final String enc = mine.get(i);
            g.set(i, Items.withLore(it, "", "&eClick to claim"), e -> {
                if (!mine.contains(enc)) return;
                if (!Items.canFit(p.getInventory(), it)) {
                    plugin.msg(p, "&cMake some room in your inventory first.");
                    return;
                }
                mine.remove(enc);
                p.getInventory().addItem(it.clone());
                persist.dirty();
                openClaims(p);
            });
        }
        for (int i = 36; i < 45; i++) g.put(i, Items.pane());
        g.set(40, Items.named(Material.ARROW, "&e← Back"), e -> openBrowse(p, 0));
        g.open(p);
    }

    // ================================================================ upkeep

    /** Called every second; every 30s expired listings go back to their owners' claim box. */
    public void tickSecond(long seconds) {
        if (seconds % 30 != 0) return;
        long now = System.currentTimeMillis();
        for (Listing l : new ArrayList<>(listings.values())) {
            if (l.expires > now) continue;
            listings.remove(l.id);
            claims.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.encoded);
            persist.dirty();
            Player seller = Bukkit.getPlayer(l.seller);
            if (seller != null) plugin.msg(seller, "&7Your listing of &f" + pretty(l.item) + " &7expired. Use &f/ah claim&7.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        List<String> mine = claims.get(e.getPlayer().getUniqueId());
        if (mine != null && !mine.isEmpty()) {
            plugin.msg(e.getPlayer(), "&eYou have &f" + mine.size() + " &eitem(s) waiting in &f/ah claim&e.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("sell", "search", "mine", "claim")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }
}
