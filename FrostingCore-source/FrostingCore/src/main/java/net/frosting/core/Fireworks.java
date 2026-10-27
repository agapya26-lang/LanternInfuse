package net.frosting.core;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;

/** One-shot firework bursts. */
public final class Fireworks {
    private Fireworks() {
    }

    public static void burst(Location at, Color... colors) {
        if (at.getWorld() == null) return;
        Firework fw = at.getWorld().spawn(at, Firework.class);
        FireworkMeta m = fw.getFireworkMeta();
        m.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(colors.length == 0 ? new Color[]{Color.ORANGE, Color.YELLOW} : colors)
                .withFade(Color.WHITE)
                .trail(true)
                .flicker(true)
                .build());
        m.setPower(0);
        fw.setFireworkMeta(m);
        fw.detonate();
    }
}
