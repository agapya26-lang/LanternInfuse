package net.lanternlight.infuse;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;

public final class LanternInfuse extends JavaPlugin {
    private final EnumMap<InfuseType, EffectSettings> settings = new EnumMap<>(InfuseType.class);
    private Profiles profiles;
    private ItemFactory items;
    private Abilities abilities;
    private Crafting crafting;
    private RecipeMenu recipeMenu;
    private File dataFile;
    private YamlConfiguration data;
    private boolean suppress;
    private long tickCounter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "recipes.yml").exists()) {
            saveResource("recipes.yml", false);
        }
        loadData();
        loadSettings();

        profiles = new Profiles(this);
        items = new ItemFactory(this);
        abilities = new Abilities(this, profiles);
        crafting = new Crafting(this);
        crafting.load();
        recipeMenu = new RecipeMenu(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new Listeners(this), this);
        pm.registerEvents(crafting, this);
        pm.registerEvents(recipeMenu, this);

        Commands cmds = new Commands(this);
        for (String name : List.of("infuse", "lspark", "rspark", "ldrain", "rdrain", "controls", "trust", "untrust", "infuses", "recipes")) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(cmds);
                pc.setTabCompleter(cmds);
            }
        }

        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 5L);
        getLogger().info("LanternInfuse enabled - Server of LanternLight+ team.");
    }

    @Override
    public void onDisable() {
        if (abilities != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                abilities.removeHeart(p);
            }
        }
    }

    private void tick() {
        tickCounter++;
        boolean bar = getConfig().getBoolean("settings.actionbar", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            abilities.tick(p, tickCounter);
            if (bar && tickCounter % 2 == 0) abilities.actionBar(p);
        }
    }

    public void reloadAll() {
        reloadConfig();
        loadSettings();
        crafting.load();
    }

    // ---- settings ----

    private void loadSettings() {
        settings.clear();
        for (InfuseType t : InfuseType.values()) {
            ConfigurationSection s = getConfig().getConfigurationSection("effects." + t.id());
            if (s == null) {
                getLogger().warning("config.yml is missing effects." + t.id() + " - using defaults.");
                s = new YamlConfiguration();
            }
            String bottle = s.getString("bottle", getConfig().getString("settings.bottle", "SPLASH_POTION"));
            Material mat = Material.matchMaterial(bottle);
            if (mat == null || !mat.isItem()) {
                getLogger().warning("Unknown bottle type '" + bottle + "' for " + t.id() + " - using SPLASH_POTION.");
                mat = Material.SPLASH_POTION;
            }
            String hex = s.getString("color", "#FFFFFF");
            int cd = s.getInt("cooldown", 60);
            settings.put(t, new EffectSettings(
                    s.getString("name", t.id()),
                    s.getString("icon", "*"),
                    mat,
                    hex,
                    parseColor(hex),
                    cd,
                    s.getInt("augmented-cooldown", cd / 2),
                    s.getInt("duration", 15),
                    s.getString("passive", ""),
                    s.getString("spark", "")));
        }
    }

    private static Color parseColor(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
        } catch (NumberFormatException ex) {
            return Color.WHITE;
        }
    }

    // ---- crafted counters (server-wide) ----

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    public int crafted(InfuseType t) {
        return data.getInt("crafted." + t.id(), 0);
    }

    public void addCrafted(InfuseType t) {
        data.set("crafted." + t.id(), crafted(t) + 1);
        saveData();
    }

    public void resetCrafted(InfuseType t) {
        data.set("crafted." + t.id(), 0);
        saveData();
    }

    // ---- accessors / helpers ----

    public EffectSettings settings(InfuseType t) {
        return settings.get(t);
    }

    public Profiles profiles() {
        return profiles;
    }

    public ItemFactory items() {
        return items;
    }

    public Abilities abilities() {
        return abilities;
    }

    public Crafting crafting() {
        return crafting;
    }

    public RecipeMenu recipeMenu() {
        return recipeMenu;
    }

    public boolean suppress() {
        return suppress;
    }

    public void setSuppress(boolean value) {
        this.suppress = value;
    }

    public String prefix() {
        return getConfig().getString("prefix", "&8[&6LanternLight+&8] &7");
    }

    public void msg(CommandSender to, String text) {
        to.sendMessage(Text.c(prefix() + text));
    }
}
