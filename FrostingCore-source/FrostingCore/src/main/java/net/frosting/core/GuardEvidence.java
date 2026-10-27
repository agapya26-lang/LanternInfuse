package net.frosting.core;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One piece of proof: what was flagged, when, where, and the movement trail leading up to it. */
public final class GuardEvidence {
    public final int id;
    public final UUID uuid;
    public final String player;
    public final String check;
    public final double vl;
    public final String detail;
    public final long time;
    public final int ping;
    public final double tps;
    public final String world;
    public final double x;
    public final double y;
    public final double z;
    public final String item;
    public final String effects;
    public final Map<String, Double> allVl;
    /** Each entry: {ms before the flag, x, y, z, horizontal distance of that move}. */
    public final List<double[]> trail;

    public GuardEvidence(int id, UUID uuid, String player, String check, double vl, String detail, long time, int ping,
                         double tps, String world, double x, double y, double z, String item, String effects,
                         Map<String, Double> allVl, List<double[]> trail) {
        this.id = id;
        this.uuid = uuid;
        this.player = player;
        this.check = check;
        this.vl = vl;
        this.detail = detail;
        this.time = time;
        this.ping = ping;
        this.tps = tps;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.item = item;
        this.effects = effects;
        this.allVl = allVl;
        this.trail = trail;
    }

    public static String f(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** The full report, as saved to disk. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FROSTGUARD EVIDENCE #").append(id).append(" ===\n");
        sb.append("Player:   ").append(player).append(" (").append(uuid).append(")\n");
        sb.append("Check:    ").append(check).append("   Violation level: ").append(f(vl)).append('\n');
        sb.append("When:     ").append(Instant.ofEpochMilli(time)).append('\n');
        sb.append("Detail:   ").append(detail).append('\n');
        sb.append("Ping:     ").append(ping).append(" ms   Server TPS: ").append(f(tps)).append('\n');
        sb.append("Position: ").append(world).append(' ').append(f(x)).append(' ').append(f(y)).append(' ').append(f(z)).append('\n');
        sb.append("Holding:  ").append(item).append('\n');
        sb.append("Effects:  ").append(effects).append('\n');
        sb.append("All violation levels at this moment: ").append(allVl).append('\n');
        sb.append("\nMovement trail (oldest first): ms-before-flag, x, y, z, horizontal-move\n");
        for (double[] t : trail) {
            sb.append(String.format(Locale.US, "  -%5.0f  %9.2f %7.2f %9.2f   %.3f%n", t[0], t[1], t[2], t[3], t[4]));
        }
        sb.append("\nHow to read this: a single flag can be lag. Several different checks flagging the same player\n");
        sb.append("in a short time, with a normal ping and normal TPS, is what real cheating looks like.\n");
        return sb.toString();
    }

    /** The short version for chat. */
    public String summary() {
        return "#" + id + " " + check + " VL " + f(vl) + " - " + detail + " (ping " + ping + "ms, tps " + f(tps) + ")";
    }
}
