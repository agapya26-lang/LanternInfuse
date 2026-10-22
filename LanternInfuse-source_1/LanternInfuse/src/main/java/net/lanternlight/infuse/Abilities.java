package net.lanternlight.infuse;

import net.lanternlight.infuse.InfuseType.Slot;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** All passive + spark behaviour lives here. */
public final class Abilities {
    private final LanternInfuse plugin;
    private final Profiles profiles;
    private final NamespacedKey heartKey;
    private final Random rng = new Random();

    public Abilities(LanternInfuse plugin, Profiles profiles) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.heartKey = new NamespacedKey(plugin, "heart_bonus");
    }

    // =====================================================================
    //  Spark activation
    // =====================================================================

    public void trySpark(Player p, Slot slot) {
        InfuseType t = profiles.get(p, slot);
        if (t == null) {
            plugin.msg(p, "&cNothing equipped in slot " + slot.number() + ".");
            return;
        }
        EffectSettings s = plugin.settings(t);
        long now = System.currentTimeMillis();
        long cdUntil = profiles.cooldownUntil(p, slot);
        if (now < cdUntil) {
            p.sendActionBar(Text.c(s.name() + " &7is on cooldown: &f" + secs(cdUntil - now) + "s"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            return;
        }
        boolean aug = profiles.isAug(p, slot);
        long activeEnd = now + s.duration() * 1000L;
        profiles.setActiveUntil(p, slot, activeEnd);
        profiles.setCooldownUntil(p, slot, activeEnd + (aug ? s.augCooldown() : s.cooldown()) * 1000L);
        start(p, t, aug);
    }

    private void start(Player p, InfuseType t, boolean aug) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        Location mid = loc.clone().add(0, 1, 0);
        double radius = plugin.getConfig().getDouble("settings.spark-radius", 16.0);

        w.playSound(loc, startSound(t), 1.0f, 1.0f);
        if (aug) w.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 0.6f);

        switch (t) {
            case STRENGTH -> burst(mid, Color.fromRGB(178, 25, 32), 50);
            case THUNDER -> w.strikeLightningEffect(loc);
            case HASTE -> burst(mid, Color.fromRGB(217, 141, 50), 50);
            case FROST -> w.spawnParticle(Particle.SNOWFLAKE, mid, 90, 1.5, 1.0, 1.5, 0.02);
            case HEART -> w.spawnParticle(Particle.HEART, loc.clone().add(0, 2, 0), 14, 0.6, 0.3, 0.6, 0);
            case REGEN -> w.spawnParticle(Particle.HEART, mid, 10, 1.0, 0.6, 1.0, 0);
            case FEATHER -> featherBurst(p, aug);
            case INVIS -> {
                burst(mid, Color.fromRGB(159, 74, 193), 60);
                ring(loc, radius, Color.fromRGB(100, 40, 140));
            }
            case EMERALD -> w.spawnParticle(Particle.HAPPY_VILLAGER, mid, 40, 1.0, 1.0, 1.0, 0);
            case SPEED -> dash(p, aug);
            case FIRE -> fireBlast(p, aug);
            case OCEAN -> {
                double r = plugin.getConfig().getDouble("settings.ocean-radius", 15.0);
                ring(loc, r, Color.fromRGB(63, 115, 204));
            }
        }
    }

    private Sound startSound(InfuseType t) {
        return switch (t) {
            case STRENGTH -> Sound.ENTITY_RAVAGER_ROAR;
            case THUNDER -> Sound.ENTITY_LIGHTNING_BOLT_THUNDER;
            case HASTE -> Sound.BLOCK_BEACON_POWER_SELECT;
            case FROST -> Sound.ENTITY_PLAYER_HURT_FREEZE;
            case HEART -> Sound.ITEM_TOTEM_USE;
            case REGEN -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            case FEATHER -> Sound.ENTITY_FIREWORK_ROCKET_LAUNCH;
            case INVIS -> Sound.ENTITY_ILLUSIONER_CAST_SPELL;
            case EMERALD -> Sound.ENTITY_VILLAGER_YES;
            case SPEED -> Sound.ENTITY_ENDER_DRAGON_FLAP;
            case FIRE -> Sound.ENTITY_BLAZE_SHOOT;
            case OCEAN -> Sound.ITEM_TRIDENT_RIPTIDE_3;
        };
    }

    private void featherBurst(Player p, boolean aug) {
        int seconds = Math.max(1, plugin.settings(InfuseType.FEATHER).duration());
        p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, seconds * 20, aug ? 34 : 29, false, true, true));
        for (LivingEntity le : hostiles(p, 8.0, true)) {
            le.setVelocity(le.getVelocity().add(new Vector(0, 1.1, 0)));
        }
        ring(p.getLocation(), 6.0, Color.fromRGB(190, 163, 202));
    }

    private void dash(Player p, boolean aug) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir = dir.normalize().multiply(aug ? 3.2 : 2.4).setY(0.25);
        p.setVelocity(dir);
        p.setFallDistance(0f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.5, 0), 25, 0.3, 0.3, 0.3, 0.05);
    }

    private void fireBlast(Player p, boolean aug) {
        double strength = plugin.getConfig().getDouble("settings.fire-launch-strength", 1.8) * (aug ? 1.2 : 1.0);
        Location loc = p.getLocation();
        p.setVelocity(new Vector(0, strength, 0));
        p.setFallDistance(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true));
        for (LivingEntity le : hostiles(p, 6.0, false)) {
            le.setFireTicks(100 + (aug ? 60 : 0));
        }
        for (double y = 0; y < 6.0; y += 0.4) {
            loc.getWorld().spawnParticle(Particle.FLAME, loc.clone().add(0, y, 0), 6, 0.35, 0.1, 0.35, 0.02);
        }
    }

    // =====================================================================
    //  Per-tick logic (called every 5 ticks per online player)
    // =====================================================================

    public void tick(Player p, long n) {
        InfuseType a = profiles.get(p, Slot.ONE);
        InfuseType b = profiles.get(p, Slot.TWO);
        if (a != InfuseType.HEART && b != InfuseType.HEART) removeHeart(p);
        if (a != null) run(p, a, profiles.isAug(p, Slot.ONE), profiles.isActive(p, Slot.ONE), n);
        if (b != null) run(p, b, profiles.isAug(p, Slot.TWO), profiles.isActive(p, Slot.TWO), n);
    }

    private void run(Player p, InfuseType t, boolean aug, boolean on, long n) {
        double radius = plugin.getConfig().getDouble("settings.spark-radius", 16.0);
        Location mid = p.getLocation().add(0, 1, 0);

        switch (t) {
            case STRENGTH -> {
                if (on && n % 2 == 0) {
                    dust(mid, Color.fromRGB(178, 25, 32), 5);
                }
            }
            case THUNDER -> {
                if (on && n % 4 == 0) {
                    double dmg = plugin.getConfig().getDouble("settings.thunder-smite-damage", 3.0) * (aug ? 1.5 : 1.0);
                    for (LivingEntity le : hostiles(p, radius, true)) {
                        Location l = le.getLocation();
                        l.getWorld().strikeLightningEffect(l);
                        hurt(le, dmg, p);
                    }
                }
            }
            case HASTE -> potion(p, PotionEffectType.HASTE, on ? (aug ? 7 : 5) : (aug ? 2 : 1), true);
            case FROST -> {
                Material under = p.getLocation().subtract(0, 0.2, 0).getBlock().getType();
                Material feet = p.getLocation().getBlock().getType();
                if (isIce(under)) {
                    potion(p, PotionEffectType.SPEED, on ? 7 : (aug ? 5 : 4), true);
                } else if (isSnow(under) || isSnow(feet)) {
                    potion(p, PotionEffectType.SPEED, on ? 4 : (aug ? 3 : 2), true);
                }
                if (on && n % 2 == 0) {
                    p.getWorld().spawnParticle(Particle.SNOWFLAKE, mid, 6, 0.5, 0.7, 0.5, 0.01);
                }
            }
            case HEART -> applyHeart(p, on ? (aug ? 24 : 20) : (aug ? 14 : 10));
            case REGEN -> {
                if (on && n % 4 == 0) {
                    int amp = aug ? 2 : 1;
                    p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, amp, false, true, true));
                    p.getWorld().spawnParticle(Particle.HEART, mid, 3, 0.5, 0.5, 0.5, 0);
                    for (Player ally : allies(p, radius)) {
                        ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, amp, false, true, true));
                        ally.getWorld().spawnParticle(Particle.HEART, ally.getLocation().add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0);
                    }
                }
            }
            case FEATHER -> {
                if (on && n % 2 == 0) {
                    p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 8, 0.3, 0.1, 0.3, 0.02);
                }
            }
            case INVIS -> {
                potion(p, PotionEffectType.INVISIBILITY, 0, false);
                if (on) {
                    for (LivingEntity le : hostiles(p, radius, true)) {
                        le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false, false));
                    }
                    if (n % 2 == 0) dust(mid, Color.fromRGB(159, 74, 193), 5);
                }
            }
            case EMERALD -> {
                potion(p, PotionEffectType.HERO_OF_THE_VILLAGE, on ? 255 : 2, true);
                if (on && n % 3 == 0) {
                    p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, mid, 4, 0.5, 0.7, 0.5, 0);
                }
            }
            case SPEED -> potion(p, PotionEffectType.SPEED, aug ? 1 : 0, true);
            case FIRE -> potion(p, PotionEffectType.FIRE_RESISTANCE, 0, true);
            case OCEAN -> {
                potion(p, PotionEffectType.DOLPHINS_GRACE, 0, true);
                potion(p, PotionEffectType.CONDUIT_POWER, 0, true);
                if (on) oceanSpark(p, aug, n);
            }
        }
    }

    private void oceanSpark(Player p, boolean aug, long n) {
        double r = plugin.getConfig().getDouble("settings.ocean-radius", 15.0);
        double dmg = plugin.getConfig().getDouble("settings.ocean-drown-damage", 1.0) * (aug ? 1.5 : 1.0);
        Vector center = p.getLocation().add(0, 1, 0).toVector();

        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (e.getLocation().distanceSquared(p.getLocation()) > r * r) continue;
            boolean pull;
            if (e instanceof Item) {
                pull = true;
            } else if (e instanceof LivingEntity liv && !(e instanceof ArmorStand)) {
                pull = isEnemy(p, liv);
            } else {
                pull = false;
            }
            if (!pull) continue;

            Vector dir = center.clone().subtract(e.getLocation().toVector());
            if (dir.lengthSquared() > 2.25) {
                e.setVelocity(dir.normalize().multiply(0.55));
            }
            if (e instanceof LivingEntity victim && n % 2 == 0) {
                hurt(victim, dmg, p);
                victim.getWorld().spawnParticle(Particle.BUBBLE_POP, victim.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
            }
        }
        if (p.isInWater() && n % 4 == 0) {
            double max = p.getAttribute(Attribute.MAX_HEALTH) != null ? p.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;
            p.setHealth(Math.min(max, p.getHealth() + (aug ? 2.0 : 1.0)));
        }
        if (n % 2 == 0) {
            p.getWorld().spawnParticle(Particle.BUBBLE_POP, p.getLocation().add(0, 1, 0), 10, 0.6, 0.8, 0.6, 0.02);
        }
    }

    // =====================================================================
    //  Hit hooks (damage bonuses / on-hit procs)
    // =====================================================================

    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent e, boolean melee) {
        double bonus = 0;
        for (Slot slot : Slot.values()) {
            InfuseType t = profiles.get(attacker, slot);
            if (t == null) continue;
            boolean aug = profiles.isAug(attacker, slot);
            boolean on = profiles.isActive(attacker, slot);
            switch (t) {
                case STRENGTH -> {
                    if (melee) bonus += on ? (aug ? 5.0 : 3.5) : (aug ? 3.0 : 2.0);
                }
                case FROST -> bonus += frostHit(victim, on, aug);
                case THUNDER -> thunderHit(attacker, victim, aug, on);
                case REGEN -> attacker.addPotionEffect(
                        new PotionEffect(PotionEffectType.REGENERATION, 60, aug ? 2 : 1, false, true, true));
                case FIRE -> {
                    if (melee && attacker.getFireTicks() > 0) bonus += 1.0;
                }
                case OCEAN -> {
                    if (melee && attacker.isInWater()) bonus += 2.0;
                }
                default -> {
                }
            }
        }
        if (bonus > 0) e.setDamage(e.getDamage() + bonus);
    }

    private double frostHit(LivingEntity victim, boolean on, boolean aug) {
        double bonus = 0;
        if (victim.getFreezeTicks() > 0) bonus += on ? 2.5 : 1.0;
        double chance = on ? 1.0 : (aug ? 0.15 : 0.10);
        if (rng.nextDouble() < chance) {
            victim.setFreezeTicks(victim.getMaxFreezeTicks() + 40);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true, true));
            victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.02);
            victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6f, 1.6f);
        }
        return bonus;
    }

    private void thunderHit(Player attacker, LivingEntity victim, boolean aug, boolean on) {
        double chance = on ? 0.5 : (aug ? 0.30 : 0.20);
        if (rng.nextDouble() >= chance) return;
        double dmg = plugin.getConfig().getDouble("settings.thunder-smite-damage", 3.0) * (aug ? 1.5 : 1.0);
        Location l = victim.getLocation();
        l.getWorld().strikeLightningEffect(l);
        // Run next tick so we never nest damage inside the damage event that triggered us.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (victim.isValid() && !victim.isDead()) {
                victim.setNoDamageTicks(0);
                hurt(victim, dmg, attacker);
            }
        });
    }

    // =====================================================================
    //  Heart modifier
    // =====================================================================

    private void applyHeart(Player p, double extra) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (m.getKey().equals(heartKey)) {
                if (m.getAmount() == extra) return;
                inst.removeModifier(m);
            }
        }
        inst.addTransientModifier(new AttributeModifier(heartKey, extra, AttributeModifier.Operation.ADD_NUMBER));
    }

    public void removeHeart(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        boolean removed = false;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (m.getKey().equals(heartKey)) {
                inst.removeModifier(m);
                removed = true;
            }
        }
        if (removed && p.getHealth() > inst.getValue()) {
            p.setHealth(inst.getValue());
        }
    }

    // =====================================================================
    //  HUD
    // =====================================================================

    public void actionBar(Player p) {
        if (profiles.get(p, Slot.ONE) == null && profiles.get(p, Slot.TWO) == null) return;
        p.sendActionBar(Text.c(part(p, Slot.ONE) + "  &8|  " + part(p, Slot.TWO)));
    }

    private String part(Player p, Slot slot) {
        InfuseType t = profiles.get(p, slot);
        if (t == null) return "&8Slot " + slot.number() + ": &7empty";
        EffectSettings s = plugin.settings(t);
        long now = System.currentTimeMillis();
        long act = profiles.activeUntil(p, slot);
        long cd = profiles.cooldownUntil(p, slot);
        String state;
        if (now < act) state = "&a&lACTIVE &f" + secs(act - now) + "s";
        else if (now < cd) state = "&c" + secs(cd - now) + "s";
        else state = "&a&lREADY";
        String star = profiles.isAug(p, slot) ? "&6✦ " : "";
        return "&7[" + slot.number() + "] " + star + "&" + s.hex() + s.icon() + " " + s.name() + " &8» " + state;
    }

    private static long secs(long ms) {
        return (ms + 999) / 1000;
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /** Is this entity somebody our abilities should hit? */
    public boolean isEnemy(Player owner, LivingEntity le) {
        if (le.equals(owner) || le.isDead() || le instanceof ArmorStand) return false;
        if (le instanceof Player tp) {
            if (profiles.trusts(owner, tp)) return false;
            GameMode gm = tp.getGameMode();
            return gm != GameMode.SPECTATOR && gm != GameMode.CREATIVE;
        }
        return true;
    }

    private List<LivingEntity> hostiles(Player p, double r, boolean playersOnly) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (playersOnly && !(e instanceof Player)) continue;
            if (!isEnemy(p, le)) continue;
            if (le.getLocation().distanceSquared(p.getLocation()) > r * r) continue;
            out.add(le);
        }
        return out;
    }

    private List<Player> allies(Player p, double r) {
        List<Player> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (e instanceof Player o && profiles.trusts(p, o)
                    && o.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                out.add(o);
            }
        }
        return out;
    }

    private void hurt(LivingEntity target, double amount, Player source) {
        plugin.setSuppress(true);
        try {
            target.damage(amount, source);
        } finally {
            plugin.setSuppress(false);
        }
    }

    private void potion(Player p, PotionEffectType type, int amp, boolean icon) {
        p.addPotionEffect(new PotionEffect(type, 40, amp, true, false, icon));
    }

    private static boolean isIce(Material m) {
        return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE;
    }

    private static boolean isSnow(Material m) {
        return m == Material.SNOW || m == Material.SNOW_BLOCK || m == Material.POWDER_SNOW;
    }

    private void dust(Location loc, Color color, int count) {
        loc.getWorld().spawnParticle(Particle.DUST, loc, count, 0.4, 0.7, 0.4, 0,
                new Particle.DustOptions(color, 1.3f));
    }

    private void burst(Location loc, Color color, int count) {
        loc.getWorld().spawnParticle(Particle.DUST, loc, count, 0.7, 0.9, 0.7, 0,
                new Particle.DustOptions(color, 1.6f));
        loc.getWorld().spawnParticle(Particle.END_ROD, loc, 20, 0.5, 0.8, 0.5, 0.05);
    }

    private void ring(Location center, double radius, Color color) {
        World w = center.getWorld();
        Particle.DustOptions opt = new Particle.DustOptions(color, 1.5f);
        for (int i = 0; i < 48; i++) {
            double ang = (Math.PI * 2 * i) / 48.0;
            Location l = center.clone().add(Math.cos(ang) * radius, 0.2, Math.sin(ang) * radius);
            w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, opt);
        }
    }
}
