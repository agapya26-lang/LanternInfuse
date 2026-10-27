package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Finds safe standing spots (used by /rtp and airdrops). */
public final class SafeLoc {
    private static final Set<Material> DANGER = EnumSet.of(
            Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE, Material.WITHER_ROSE, Material.COBWEB, Material.BEDROCK);

    private SafeLoc() {
    }

    /** Tries random spots in a ring [min,max] around (cx,cz); calls done with a safe Location or null. */
    public static void find(FrostingCore plugin, World w, int cx, int cz, int min, int max, int attempts, Consumer<Location> done) {
        if (attempts <= 0) {
            done.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        double dist = min + r.nextDouble() * Math.max(1, max - min);
        int x = cx + (int) Math.round(Math.cos(ang) * dist);
        int z = cz + (int) Math.round(Math.sin(ang) * dist);
        if (!w.getWorldBorder().isInside(new Location(w, x, 0, z))) {
            find(plugin, w, cx, cz, min, max, attempts - 1, done);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            Location safe = safeAt(w, x, z);
            if (safe != null) done.accept(safe);
            else find(plugin, w, cx, cz, min, max, attempts - 1, done);
        })).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(null));
            return null;
        });
    }

    private static boolean clear(Block b) {
        return b.isPassable() && !b.isLiquid() && !DANGER.contains(b.getType());
    }

    /** A safe standing location at (x,z), or null. The chunk must be loaded. */
    public static Location safeAt(World w, int x, int z) {
        if (w.getEnvironment() == World.Environment.NETHER) {
            for (int y = 120; y > 32; y--) {
                Block floor = w.getBlockAt(x, y - 1, z);
                if (floor.getType().isSolid() && !DANGER.contains(floor.getType())
                        && clear(w.getBlockAt(x, y, z)) && clear(w.getBlockAt(x, y + 1, z))) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
            return null;
        }
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= w.getMinHeight() + 1) return null;
        Block floor = w.getBlockAt(x, y, z);
        if (!floor.getType().isSolid() || floor.isLiquid() || DANGER.contains(floor.getType())) return null;
        if (!clear(w.getBlockAt(x, y + 1, z)) || !clear(w.getBlockAt(x, y + 2, z))) return null;
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }
}
