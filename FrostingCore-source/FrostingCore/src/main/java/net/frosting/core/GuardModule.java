package net.frosting.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * FrostGuard - the custom anti-cheat. It watches movement, combat, clicking and building, scores every
 * suspicious thing as "violation levels" (VL) that fade over time, and writes a full proof file (with the
 * movement trail) whenever a player crosses the alert level. Several DIFFERENT checks agreeing is what it
 * treats as real cheating - one odd flag is just lag.
 */
public final class GuardModule implements Listener, CommandExecutor, TabCompleter {
    private static final class Sample {
        final long t;
        final double x;
        final double y;
        final double z;
        final double horiz;

        Sample(long t, double x, double y, double z, double horiz) {
            this.t = t;
            this.x = x;
            this.y = y;
            this.z = z;
            this.horiz = horiz;
        }
    }

    private static final class PlayerGuard {
        final Deque<Sample> trail = new ArrayDeque<>();
        final Map<String, Double> vl = new HashMap<>();
        final Map<String, Long> vlTime = new HashMap<>();
        final Map<String, Long> lastEvidence = new HashMap<>();
        final Map<String, Long> confirmed = new HashMap<>();
        long join = System.currentTimeMillis();
        long lastTeleport;
        long lastVelocity;
        long lastDamage;
        long lastGlide;
        long lastRiptide;
        long lastWorldChange;
        long lastSpecial;
        long lastIce;
        long lastPunish;
        // movement state
        int airTicks;
        int hoverTicks;
        int ascentTicks;
        int groundSpoof;
        double takeoffY;
        Location lastStill;
        int stillAir;
        long moveWindowStart;
        int moveCount;
        int timerStrikes;
        // combat state
        final Deque<Long> clicks = new ArrayDeque<>();
        final Deque<Double> intervals = new ArrayDeque<>();
        long lastClick;
        int cpsStrikes;
        final Deque<Boolean> aim = new ArrayDeque<>();
        final Map<UUID, Long> recentTargets = new HashMap<>();
        // building state
        final Map<Long, Long> breakStart = new HashMap<>();
        final Deque<Long> breaks = new ArrayDeque<>();
        final Deque<Long> places = new ArrayDeque<>();
    }

    private static final Set<Material> SPECIAL = EnumSet.of(
            Material.WATER, Material.LAVA, Material.COBWEB, Material.POWDER_SNOW, Material.HONEY_BLOCK,
            Material.SLIME_BLOCK, Material.BUBBLE_COLUMN, Material.SWEET_BERRY_BUSH, Material.SCAFFOLDING,
            Material.BIG_DRIPLEAF, Material.PISTON_HEAD, Material.MOVING_PISTON, Material.TWISTING_VINES,
            Material.WEEPING_VINES, Material.CAVE_VINES, Material.POINTED_DRIPSTONE);
    private static final Set<Material> ICE = EnumSet.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    private final FrostingCore plugin;
    private final Map<UUID, PlayerGuard> data = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private final File evidenceDir;
    private final Map<Integer, GuardEvidence> memory = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, GuardEvidence> eldest) {
            return size() > 300;
        }
    };
    private final HttpClient http = HttpClient.newHttpClient();
    private int nextId = 1;
    private boolean autoPunish;

    public GuardModule(FrostingCore plugin) {
        this.plugin = plugin;
        this.evidenceDir = new File(plugin.getDataFolder(), "evidence");
        this.autoPunish = plugin.getConfig().getString("guard.punish.mode", "ALERT").equalsIgnoreCase("AUTO");
        scanNextId();
        new BukkitRunnable() {
            @Override
            public void run() {
                tickSecond();
            }
        }.runTaskTimer(plugin, 40L, 20L);
    }

    private void scanNextId() {
        File[] dirs = evidenceDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                int us = f.getName().indexOf('_');
                if (us > 0) {
                    try {
                        nextId = Math.max(nextId, Integer.parseInt(f.getName().substring(0, us)) + 1);
                    } catch (NumberFormatException ignored) {
                        // not ours
                    }
                }
            }
        }
    }

    private boolean on(String key) {
        return plugin.getConfig().getBoolean("guard.checks." + key, true);
    }

    private PlayerGuard g(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new PlayerGuard());
    }

    // =====================================================================================
    //  State tracking
    // =====================================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data.put(e.getPlayer().getUniqueId(), new PlayerGuard());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        g(e.getPlayer()).lastTeleport = System.currentTimeMillis();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        g(e.getPlayer()).lastTeleport = System.currentTimeMillis();
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        g(e.getPlayer()).lastWorldChange = System.currentTimeMillis();
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        g(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    @EventHandler
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) g(p).lastDamage = System.currentTimeMillis();
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) g(p).lastGlide = System.currentTimeMillis();
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        g(e.getPlayer()).lastRiptide = System.currentTimeMillis();
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        pushNearby(e.getLocation());
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        pushNearby(e.getBlock().getLocation());
    }

    private void pushNearby(Location at) {
        long now = System.currentTimeMillis();
        for (Player p : at.getWorld().getNearbyPlayers(at, 14)) g(p).lastVelocity = now;
    }

    // =====================================================================================
    //  Exemptions
    // =====================================================================================

    private boolean exempt(Player p) {
        if (!plugin.getConfig().getBoolean("guard.enabled", true)) return true;
        if (p.hasPermission("frostingcore.guard.bypass")) return true;
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isDead();
    }

    private double tps() {
        double[] t = Bukkit.getTPS();
        return t.length > 0 ? Math.min(20.0, t[0]) : 20.0;
    }

    private boolean laggy(Player p) {
        return tps() < plugin.getConfig().getDouble("guard.min-tps", 18.0)
                || p.getPing() > plugin.getConfig().getInt("guard.max-ping", 600);
    }

    private boolean special(Location l, PlayerGuard pg, long now) {
        World w = l.getWorld();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        int y = (int) Math.floor(l.getY());
        for (int dy = -1; dy <= 2; dy++) {
            Material m = w.getBlockAt(x, y + dy, z).getType();
            if (SPECIAL.contains(m) || Tag.CLIMBABLE.isTagged(m) || Tag.BEDS.isTagged(m)) {
                pg.lastSpecial = now;
                return true;
            }
        }
        return now - pg.lastSpecial < 3000;
    }

    private boolean moveExempt(Player p, PlayerGuard pg, Location at, long now) {
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding() || p.isInsideVehicle()
                || p.isSleeping() || p.isSwimming() || p.isClimbing()) return true;
        if (now - pg.lastTeleport < 1500 || now - pg.lastVelocity < 2200 || now - pg.lastDamage < 1000
                || now - pg.join < 4000 || now - pg.lastWorldChange < 3000 || now - pg.lastGlide < 1500
                || now - pg.lastRiptide < 3000) return true;
        if (laggy(p)) return true;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        PotionEffect jump = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (jump != null && jump.getAmplifier() >= 2) return true;
        return special(at, pg, now);
    }

    private double jumpBonus(Player p) {
        PotionEffect jump = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
        return jump == null ? 0 : (jump.getAmplifier() + 1) * 0.55;
    }

    /** Is there anything solid (or liquid, or a boat...) right under the player? Server-side, can't be spoofed. */
    private boolean nearGround(Location at) {
        World w = at.getWorld();
        int x0 = (int) Math.floor(at.getX() - 0.35);
        int x1 = (int) Math.floor(at.getX() + 0.35);
        int z0 = (int) Math.floor(at.getZ() - 0.35);
        int z1 = (int) Math.floor(at.getZ() + 0.35);
        int y0 = (int) Math.floor(at.getY() - 0.6);
        int y1 = (int) Math.floor(at.getY() + 0.1);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.isPassable() || b.isLiquid()) return true;
                }
            }
        }
        for (Entity en : w.getNearbyEntities(at, 1.3, 1.3, 1.3)) {
            if (en instanceof Boat || en instanceof Shulker || en instanceof Minecart) return true;
        }
        return false;
    }

    // =====================================================================================
    //  Movement checks
    // =====================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) return;
        Player p = e.getPlayer();
        PlayerGuard pg = g(p);
        long now = System.currentTimeMillis();
        double dy = to.getY() - from.getY();
        double horiz = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());

        pg.trail.addLast(new Sample(now, to.getX(), to.getY(), to.getZ(), horiz));
        while (pg.trail.size() > 120) pg.trail.removeFirst();

        if (exempt(p)) {
            resetMove(pg, to);
            return;
        }
        timer(p, pg, now);
        if (moveExempt(p, pg, to, now)) {
            resetMove(pg, to);
            return;
        }
        boolean ground = nearGround(to);
        if (ground) {
            Material below = to.getWorld().getBlockAt(to.getBlockX(), (int) Math.floor(to.getY() - 0.5), to.getBlockZ()).getType();
            if (ICE.contains(below)) pg.lastIce = now;
        }
        fly(p, pg, to, dy, ground);
        groundSpoof(p, pg, ground, dy);
        speed(p, pg, now);
        if (on("step") && dy > 1.4 && !p.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            flag(p, pg, "Step", 3.0, "stepped up " + GuardEvidence.f(dy) + " blocks in a single move");
        }
    }

    private void resetMove(PlayerGuard pg, Location to) {
        pg.airTicks = 0;
        pg.hoverTicks = 0;
        pg.ascentTicks = 0;
        pg.groundSpoof = 0;
        pg.takeoffY = to.getY();
    }

    private void fly(Player p, PlayerGuard pg, Location to, double dy, boolean ground) {
        if (ground) {
            resetMove(pg, to);
            return;
        }
        pg.airTicks++;
        if (Math.abs(dy) < 0.04) pg.hoverTicks++;
        else pg.hoverTicks = 0;
        if (dy > 0.001) pg.ascentTicks++;
        else pg.ascentTicks = 0;
        double rise = to.getY() - pg.takeoffY;
        if (!on("fly")) return;
        if (pg.hoverTicks >= 9) {
            flag(p, pg, "Fly", 3.0, "hovering in the air for " + pg.hoverTicks + " moves (dy " + GuardEvidence.f(dy) + ")");
        } else if (pg.ascentTicks >= 16) {
            flag(p, pg, "Fly", 3.0, "rising for " + pg.ascentTicks + " moves in a row without a jump");
        } else if (rise > 1.35 + jumpBonus(p)) {
            flag(p, pg, "Fly", 2.5, "went " + GuardEvidence.f(rise) + " blocks up from the ground (max a jump allows is ~1.25)");
        }
    }

    private void groundSpoof(Player p, PlayerGuard pg, boolean serverGround, double dy) {
        if (!on("groundspoof")) return;
        if (p.isOnGround() && !serverGround) {
            pg.groundSpoof++;
            if (pg.groundSpoof >= 6) {
                flag(p, pg, "GroundSpoof", 3.0, "client says it's on the ground but there's nothing under it (" + pg.groundSpoof + " moves, dy " + GuardEvidence.f(dy) + ")");
            }
        } else {
            pg.groundSpoof = 0;
        }
    }

    private void speed(Player p, PlayerGuard pg, long now) {
        if (!on("speed")) return;
        double dist = 0;
        long first = now;
        int n = 0;
        Iterator<Sample> it = pg.trail.descendingIterator();
        while (it.hasNext()) {
            Sample s = it.next();
            if (now - s.t > 1000) break;
            dist += s.horiz;
            first = s.t;
            n++;
        }
        long span = now - first;
        if (n < 8 || span < 700) return;
        double bps = dist / (span / 1000.0);
        double allowed = allowedBps(p, pg, now);
        if (bps > allowed * 1.2) {
            flag(p, pg, "Speed", Math.min(4.0, (bps / allowed - 1.0) * 6.0 + 0.5),
                    "moving " + GuardEvidence.f(bps) + " blocks/s, the most possible here is " + GuardEvidence.f(allowed));
        }
    }

    private double allowedBps(Player p, PlayerGuard pg, long now) {
        double base = 7.6; // sprint-jumping is ~7.1 b/s
        AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
        double value = attr == null ? 0.1 : attr.getValue();
        double mult = Math.max(1.0, value / 0.13); // 0.13 = base speed while sprinting
        if (now - pg.lastIce < 3000) mult *= 4.5;
        return base * mult;
    }

    private void timer(Player p, PlayerGuard pg, long now) {
        if (!on("timer")) return;
        if (now - pg.moveWindowStart >= 1000) {
            if (pg.moveCount > 30 && tps() >= 19.0 && p.getPing() < 250) {
                pg.timerStrikes++;
                if (pg.timerStrikes >= 3) {
                    flag(p, pg, "Timer", 2.0, "sent " + pg.moveCount + " movement packets in one second (normal is ~20)");
                }
            } else if (pg.moveCount <= 22) {
                pg.timerStrikes = Math.max(0, pg.timerStrikes - 1);
            }
            pg.moveWindowStart = now;
            pg.moveCount = 0;
        }
        pg.moveCount++;
    }

    /** Every second: catches players who float perfectly still (no move events are sent then). */
    private void tickSecond() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerGuard pg = data.get(p.getUniqueId());
            if (pg == null) continue;
            Location l = p.getLocation();
            if (exempt(p) || moveExempt(p, pg, l, now) || !on("fly")) {
                pg.stillAir = 0;
                pg.lastStill = null;
                continue;
            }
            boolean ground = nearGround(l);
            if (!ground && pg.lastStill != null && pg.lastStill.getWorld().equals(l.getWorld())
                    && pg.lastStill.distanceSquared(l) < 0.0001) {
                pg.stillAir++;
                if (pg.stillAir >= 2) {
                    flag(p, pg, "Fly", 3.0, "floating perfectly still in mid-air for " + pg.stillAir + "s");
                }
            } else {
                pg.stillAir = 0;
            }
            pg.lastStill = l.clone();
        }
    }

    // =====================================================================================
    //  Combat checks
    // =====================================================================================

    private static double distanceToBox(Vector p, BoundingBox bb) {
        double dx = Math.max(Math.max(bb.getMinX() - p.getX(), 0), p.getX() - bb.getMaxX());
        double dy = Math.max(Math.max(bb.getMinY() - p.getY(), 0), p.getY() - bb.getMaxY());
        double dz = Math.max(Math.max(bb.getMinZ() - p.getZ(), 0), p.getZ() - bb.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (exempt(p) || p.hasMetadata("fg_ignore") || p.hasMetadata("lg_ignore") || laggy(p) || p.getPing() > 500) return;
        PlayerGuard pg = g(p);
        long now = System.currentTimeMillis();
        Entity target = e.getEntity();
        Location eye = p.getEyeLocation();
        BoundingBox bb = target.getBoundingBox().expand(0.05);

        // reach
        if (on("reach")) {
            double reach = distanceToBox(eye.toVector(), bb);
            double allowed = 3.0 + 0.5 + Math.min(p.getPing(), 250) / 1000.0 * 5.0;
            if (reach > allowed + 0.35) {
                flag(p, pg, "Reach", Math.min(5.0, (reach - allowed) * 3.0 + 1.5),
                        "hit from " + GuardEvidence.f(reach) + " blocks away (max allowed " + GuardEvidence.f(allowed) + " at " + p.getPing() + "ms)");
            }
        }

        // aim: hitting things you are not looking at
        Vector toCenter = bb.getCenter().subtract(eye.toVector());
        double len = toCenter.length();
        if (on("aim") && len > 1.5) {
            double angle = Math.toDegrees(eye.getDirection().angle(toCenter));
            pg.aim.addLast(angle > 55.0);
            while (pg.aim.size() > 8) pg.aim.removeFirst();
            long bad = pg.aim.stream().filter(b -> b).count();
            if (pg.aim.size() >= 6 && bad >= 5) {
                flag(p, pg, "Aim", 3.0, bad + " of the last " + pg.aim.size() + " hits landed while looking >55 degrees away from the target (last: " + GuardEvidence.f(angle) + ")");
                pg.aim.clear();
            }
        }

        // hitting through walls
        if (on("hitwall") && len > 1.0) {
            RayTraceResult rt = eye.getWorld().rayTraceBlocks(eye, toCenter.clone().normalize(), len, FluidCollisionMode.NEVER, true);
            if (rt != null && rt.getHitBlock() != null && rt.getHitBlock().getType().isOccluding()) {
                double hitDist = rt.getHitPosition().distance(eye.toVector());
                if (hitDist < len - 0.8) {
                    flag(p, pg, "HitWall", 2.5, "hit through a solid " + rt.getHitBlock().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + " block (" + GuardEvidence.f(len - hitDist) + " blocks before the target)");
                }
            }
        }

        // multi-target
        if (on("killaura")) {
            pg.recentTargets.values().removeIf(t -> now - t > 350);
            pg.recentTargets.put(target.getUniqueId(), now);
            if (pg.recentTargets.size() >= 3) {
                flag(p, pg, "KillAura", 4.0, "hit " + pg.recentTargets.size() + " different targets within 350ms");
                pg.recentTargets.clear();
            }
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (exempt(p) || !on("autoclicker")) return;
        PlayerGuard pg = g(p);
        long now = System.currentTimeMillis();
        pg.clicks.addLast(now);
        while (!pg.clicks.isEmpty() && now - pg.clicks.peekFirst() > 1000) pg.clicks.removeFirst();
        if (pg.lastClick != 0) {
            pg.intervals.addLast((double) (now - pg.lastClick));
            while (pg.intervals.size() > 30) pg.intervals.removeFirst();
        }
        pg.lastClick = now;
        int cps = pg.clicks.size();
        if (cps < 12) {
            pg.cpsStrikes = 0;
            return;
        }
        if (p.getTargetBlockExact(5) != null) return; // digging a block also swings every tick
        if (cps > 22) {
            pg.cpsStrikes++;
            if (pg.cpsStrikes >= 2) flag(p, pg, "AutoClicker", 3.0, cps + " clicks per second");
        }
        if (pg.intervals.size() >= 25) {
            double mean = pg.intervals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = pg.intervals.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
            double sd = Math.sqrt(var);
            if (mean < 90 && sd < 6.0) {
                flag(p, pg, "AutoClicker", 3.5, "robotic clicking: " + GuardEvidence.f(1000 / mean) + " cps with only " + GuardEvidence.f(sd) + "ms variation");
            }
            pg.intervals.clear();
        }
    }

    // =====================================================================================
    //  Building checks
    // =====================================================================================

    @EventHandler(ignoreCancelled = true)
    public void onDamageBlock(BlockDamageEvent e) {
        Player p = e.getPlayer();
        if (exempt(p) || e.getInstaBreak()) return;
        PlayerGuard pg = g(p);
        long now = System.currentTimeMillis();
        if (pg.breakStart.size() > 60) pg.breakStart.values().removeIf(t -> now - t > 30_000);
        pg.breakStart.put(e.getBlock().getBlockKey(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (exempt(p)) return;
        PlayerGuard pg = g(p);
        long now = System.currentTimeMillis();
        pg.breaks.addLast(now);
        while (!pg.breaks.isEmpty() && now - pg.breaks.peekFirst() > 1000) pg.breaks.removeFirst();
        if (on("nuker") && pg.breaks.size() >= 10) {
            flag(p, pg, "Nuker", 5.0, "broke " + pg.breaks.size() + " blocks in one second");
        }
        Long start = pg.breakStart.remove(e.getBlock().getBlockKey());
        if (on("fastbreak") && start != null && !laggy(p)) {
            float speed = e.getBlock().getBreakSpeed(p);
            if (speed > 0 && speed < 1) {
                double expectedMs = (1.0 / speed) * 50.0;
                double elapsed = now - start;
                if (expectedMs > 300 && elapsed < expectedMs * 0.5) {
                    flag(p, pg, "FastBreak", 3.0, "broke " + e.getBlock().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + " in " + (long) elapsed + "ms, it should take about " + (long) expectedMs + "ms");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (exempt(p) || !on("fastplace")) return;
        PlayerGuard pg = g(p);
        long now = System.currentTimeMillis();
        pg.places.addLast(now);
        while (!pg.places.isEmpty() && now - pg.places.peekFirst() > 1000) pg.places.removeFirst();
        if (pg.places.size() >= 13) flag(p, pg, "FastPlace", 3.0, "placed " + pg.places.size() + " blocks in one second");
    }

    // =====================================================================================
    //  Flagging, proof and punishment
    // =====================================================================================

    private void flag(Player p, PlayerGuard pg, String check, double add, String detail) {
        if (!on(check.toLowerCase(Locale.ROOT))) return;
        long now = System.currentTimeMillis();
        double decay = plugin.getConfig().getDouble("guard.decay-per-second", 0.15);
        double vl = pg.vl.getOrDefault(check, 0.0);
        long last = pg.vlTime.getOrDefault(check, now);
        vl = Math.max(0, vl - decay * (now - last) / 1000.0) + add;
        pg.vl.put(check, vl);
        pg.vlTime.put(check, now);

        double alertVl = plugin.getConfig().getDouble("guard.alert-vl", 6.0);
        if (vl < alertVl) return;
        if (now - pg.lastEvidence.getOrDefault(check, 0L) < 4000) return;
        pg.lastEvidence.put(check, now);
        pg.confirmed.put(check, now);

        GuardEvidence ev = record(p, pg, check, vl, detail);
        alert(ev);
        webhook(ev);
        punish(p, pg, ev, now);
    }

    private GuardEvidence record(Player p, PlayerGuard pg, String check, double vl, String detail) {
        long now = System.currentTimeMillis();
        List<double[]> trail = new ArrayList<>();
        List<Sample> samples = new ArrayList<>(pg.trail);
        for (int i = Math.max(0, samples.size() - 60); i < samples.size(); i++) {
            Sample s = samples.get(i);
            trail.add(new double[]{now - s.t, s.x, s.y, s.z, s.horiz});
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        String item = held.getType().isAir() ? "nothing" : held.getAmount() + "x " + held.getType().name().toLowerCase(Locale.ROOT);
        List<String> fx = new ArrayList<>();
        for (PotionEffect pe : p.getActivePotionEffects()) {
            fx.add(pe.getType().getKey().getKey() + " " + (pe.getAmplifier() + 1));
        }
        Location l = p.getLocation();
        Map<String, Double> all = new LinkedHashMap<>();
        for (Map.Entry<String, Double> en : pg.vl.entrySet()) all.put(en.getKey(), Math.round(en.getValue() * 100.0) / 100.0);
        GuardEvidence ev = new GuardEvidence(nextId++, p.getUniqueId(), p.getName(), check, vl, detail, now, p.getPing(), tps(),
                l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), item, fx.isEmpty() ? "none" : String.join(", ", fx), all, trail);
        memory.put(ev.id, ev);
        File dir = new File(evidenceDir, p.getName());
        File file = new File(dir, ev.id + "_" + check + ".txt");
        String text = ev.toText();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                dir.mkdirs();
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save evidence #" + ev.id + ": " + ex.getMessage());
            }
        });
        return ev;
    }

    private void alert(GuardEvidence ev) {
        Component msg = Text.c("&8[&cFG&8] &f" + ev.player + " &7flagged &e" + ev.check + " &8(&cVL " + GuardEvidence.f(ev.vl)
                        + "&8) &7ping &f" + ev.ping + "ms &8» &7" + ev.detail + " ")
                .append(Text.c("&a[PROOF]").clickEvent(ClickEvent.runCommand("/fg proof " + ev.id)))
                .append(Text.c(" "))
                .append(Text.c("&b[TP]").clickEvent(ClickEvent.runCommand("/fg tp " + ev.player)));
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.hasPermission("frostingcore.guard") && !alertsOff.contains(s.getUniqueId())) s.sendMessage(msg);
        }
        plugin.getLogger().warning("[FrostGuard] " + ev.summary() + " player=" + ev.player);
    }

    private void punish(Player p, PlayerGuard pg, GuardEvidence ev, long now) {
        if (!autoPunish) return;
        if (ev.vl < plugin.getConfig().getDouble("guard.ban-vl", 25.0)) return;
        long window = plugin.getConfig().getLong("guard.punish.window-seconds", 300) * 1000L;
        int distinct = 0;
        for (long t : pg.confirmed.values()) if (now - t <= window) distinct++;
        if (distinct < plugin.getConfig().getInt("guard.punish.required-checks", 2)) return;
        if (now - pg.lastPunish < 60_000) return;
        pg.lastPunish = now;
        plugin.getLogger().warning("[FrostGuard] AUTO-PUNISH " + p.getName() + " (" + distinct + " checks confirmed, case #" + ev.id + ")");
        for (String cmd : plugin.getConfig().getStringList("guard.punish.commands")) {
            String run = cmd.replace("%player%", p.getName()).replace("%check%", ev.check).replace("%id%", String.valueOf(ev.id));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);
        }
        pg.vl.clear();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void webhook(GuardEvidence ev) {
        String url = plugin.getConfig().getString("guard.discord-webhook", "");
        if (url == null || url.isBlank()) return;
        String json = "{\"embeds\":[{\"title\":\"FrostGuard: " + esc(ev.player) + " flagged " + esc(ev.check) + "\","
                + "\"color\":15158332,\"description\":\"" + esc(ev.detail) + "\",\"fields\":["
                + "{\"name\":\"Violation level\",\"value\":\"" + GuardEvidence.f(ev.vl) + "\",\"inline\":true},"
                + "{\"name\":\"Ping / TPS\",\"value\":\"" + ev.ping + "ms / " + GuardEvidence.f(ev.tps) + "\",\"inline\":true},"
                + "{\"name\":\"Location\",\"value\":\"" + esc(ev.world) + " " + (long) ev.x + " " + (long) ev.y + " " + (long) ev.z + "\",\"inline\":false},"
                + "{\"name\":\"Holding\",\"value\":\"" + esc(ev.item) + "\",\"inline\":true},"
                + "{\"name\":\"All VLs\",\"value\":\"" + esc(String.valueOf(ev.allVl)) + "\",\"inline\":false},"
                + "{\"name\":\"Proof\",\"value\":\"Case #" + ev.id + " - /fg proof " + ev.id + " (file: evidence/" + esc(ev.player) + "/" + ev.id + "_" + esc(ev.check) + ".txt)\",\"inline\":false}"
                + "]}]}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("guard.discord-webhook is not a valid URL.");
        }
    }

    // =====================================================================================
    //  Staff commands: /lg
    // =====================================================================================

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("frostingcore.guard")) {
            plugin.msg(s, "&cYou don't have permission.");
            return true;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "alerts" -> {
                if (!(s instanceof Player p)) return true;
                if (alertsOff.remove(p.getUniqueId())) plugin.msg(p, "&aAnti-cheat alerts &lON&a.");
                else {
                    alertsOff.add(p.getUniqueId());
                    plugin.msg(p, "&cAnti-cheat alerts &lOFF&c.");
                }
            }
            case "status" -> status(s, a);
            case "list" -> list(s, a);
            case "proof" -> proof(s, a);
            case "replay" -> replay(s, a);
            case "tp" -> {
                if (!(s instanceof Player p) || a.length < 2) return true;
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) plugin.msg(p, "&cThat player isn't online.");
                else p.teleportAsync(t.getLocation());
            }
            case "reset" -> {
                if (a.length < 2) return true;
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) plugin.msg(s, "&cThat player isn't online.");
                else {
                    g(t).vl.clear();
                    g(t).confirmed.clear();
                    plugin.msg(s, "&aCleared &f" + t.getName() + "&a's violation levels.");
                }
            }
            case "mode" -> {
                if (a.length < 2) {
                    plugin.msg(s, "&7Mode: " + (autoPunish ? "&cAUTO &7(punishes by itself)" : "&aALERT &7(only warns staff)"));
                } else {
                    autoPunish = a[1].equalsIgnoreCase("auto");
                    plugin.msg(s, "&7Mode set to " + (autoPunish ? "&cAUTO" : "&aALERT") + "&7 until restart. Set &fguard.punish.mode &7in config.yml to keep it.");
                }
            }
            default -> {
                plugin.msg(s, "&d&lFrostGuard &7- staff commands");
                s.sendMessage(Text.c(" &e/fg alerts &8- &7toggle alerts for you"));
                s.sendMessage(Text.c(" &e/fg status [player] &8- &7violation levels"));
                s.sendMessage(Text.c(" &e/fg list [player] &8- &7recent cases"));
                s.sendMessage(Text.c(" &e/fg proof <id> &8- &7show the evidence"));
                s.sendMessage(Text.c(" &e/fg replay <id> &8- &7draw the player's path with particles"));
                s.sendMessage(Text.c(" &e/fg tp <player> &8| &e/fg reset <player> &8| &e/fg mode <alert|auto>"));
            }
        }
        return true;
    }

    private void status(CommandSender s, String[] a) {
        if (a.length < 2) {
            plugin.msg(s, "&7Mode: " + (autoPunish ? "&cAUTO" : "&aALERT") + " &8| &7Cases this session: &f" + memory.size()
                    + " &8| &7TPS: &f" + GuardEvidence.f(tps()));
            return;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) {
            plugin.msg(s, "&cThat player isn't online.");
            return;
        }
        PlayerGuard pg = g(t);
        plugin.msg(s, "&f" + t.getName() + " &7- ping &f" + t.getPing() + "ms");
        if (pg.vl.isEmpty()) s.sendMessage(Text.c(" &aNo violations."));
        for (Map.Entry<String, Double> en : pg.vl.entrySet()) {
            s.sendMessage(Text.c(" &e" + en.getKey() + " &8- &c" + GuardEvidence.f(en.getValue())));
        }
    }

    private void list(CommandSender s, String[] a) {
        String only = a.length > 1 ? a[1] : null;
        List<GuardEvidence> out = new ArrayList<>();
        for (GuardEvidence ev : memory.values()) {
            if (only == null || ev.player.equalsIgnoreCase(only)) out.add(ev);
        }
        if (out.isEmpty()) {
            plugin.msg(s, "&7No cases" + (only == null ? "" : " for " + only) + ".");
            return;
        }
        int from = Math.max(0, out.size() - 10);
        for (int i = from; i < out.size(); i++) {
            GuardEvidence ev = out.get(i);
            s.sendMessage(Text.c(" &f" + ev.player + " &8- ").append(Text.c("&e" + ev.summary())
                    .clickEvent(ClickEvent.runCommand("/fg proof " + ev.id))));
        }
    }

    private void proof(CommandSender s, String[] a) {
        if (a.length < 2) {
            plugin.msg(s, "&cUsage: /fg proof <id>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(a[1].replace("#", ""));
        } catch (NumberFormatException ex) {
            plugin.msg(s, "&cThat isn't a case number.");
            return;
        }
        GuardEvidence ev = memory.get(id);
        String text = ev != null ? ev.toText() : readFromDisk(id);
        if (text == null) {
            plugin.msg(s, "&cNo case #" + id + ".");
            return;
        }
        String[] lines = text.split("\n");
        plugin.msg(s, "&d&lCase #" + id);
        for (int i = 1; i < Math.min(lines.length, 16); i++) s.sendMessage(Text.c(" &7" + lines[i].replace("&", "")));
        if (lines.length > 16) s.sendMessage(Text.c(" &8... full report with the movement trail is in plugins/FrostingCore/evidence/"));
        if (ev != null) s.sendMessage(Text.c(" &b[Replay the path]").clickEvent(ClickEvent.runCommand("/fg replay " + id)));
    }

    private String readFromDisk(int id) {
        File[] dirs = evidenceDir.listFiles(File::isDirectory);
        if (dirs == null) return null;
        for (File d : dirs) {
            File[] files = d.listFiles((dir, name) -> name.startsWith(id + "_"));
            if (files != null && files.length > 0) {
                try (Stream<String> lines = Files.lines(files[0].toPath(), StandardCharsets.UTF_8)) {
                    return String.join("\n", lines.toList());
                } catch (IOException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private void replay(CommandSender s, String[] a) {
        if (!(s instanceof Player viewer)) return;
        if (a.length < 2) {
            plugin.msg(s, "&cUsage: /fg replay <id>");
            return;
        }
        GuardEvidence ev;
        try {
            ev = memory.get(Integer.parseInt(a[1].replace("#", "")));
        } catch (NumberFormatException ex) {
            ev = null;
        }
        World w = ev == null ? null : Bukkit.getWorld(ev.world);
        if (ev == null || w == null || ev.trail.isEmpty()) {
            plugin.msg(s, "&cNo replay data for that case (only cases from this session can be replayed).");
            return;
        }
        final List<double[]> pts = ev.trail;
        plugin.msg(s, "&aDrawing the path of &f" + ev.player + " &afor case #" + ev.id + " (red = path, flame = movement).");
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!viewer.isOnline() || i >= pts.size()) {
                    cancel();
                    return;
                }
                for (int k = 0; k <= i; k++) {
                    double[] t = pts.get(k);
                    viewer.spawnParticle(Particle.DUST, new Location(w, t[1], t[2] + 0.1, t[3]), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.RED, 1.2f));
                }
                double[] cur = pts.get(i);
                viewer.spawnParticle(Particle.FLAME, new Location(w, cur[1], cur[2] + 0.5, cur[3]), 3, 0.05, 0.05, 0.05, 0.0);
                i++;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("alerts", "status", "list", "proof", "replay", "tp", "reset", "mode"));
        else if (a.length == 2 && List.of("status", "list", "tp", "reset").contains(a[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("mode")) out.addAll(List.of("alert", "auto"));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
