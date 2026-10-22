# LanternInfuse
Custom Infuse-style effects for the **LanternLight+** team. Server by PixelForge.gg.
Built for Paper 26.1.2 (Java 25).

## Build
**Easiest (no install):** push this folder to a GitHub repo. The included Actions workflow
(`.github/workflows/build.yml`) builds the jar. Download it from the Actions tab > Artifacts.

**Locally:** install JDK 25 + Maven, then run `mvn package`. The jar lands in `target/`.

## Install
1. Remove the old `Infuse` plugin (its `/infuse`, `/trust` etc. commands would clash).
2. Drop `LanternInfuse-1.0.0.jar` in `plugins/` and restart.
3. Edit `plugins/LanternInfuse/config.yml` and `recipes.yml`, then `/infuse reload`.

## How it works
- 12 effects. You have 2 slots, and ANY effect can go in either one (no duplicates).
- Effect bottles are a configurable bottle type (default splash potion, tinted per effect). Change `settings.bottle` in config.yml.
- Right-click an effect bottle to equip it. `/ldrain` and `/rdrain` turn it back into an item.
- Each effect has a passive and a **spark** ability. Press **F** (slot 1) or **Shift+F** (slot 2),
  or use `/lspark` and `/rspark`. `/controls` switches between the two styles.
- Cooldowns/durations come from config.yml (cooldown / augmented-cooldown / duration, in seconds).
- The first craft of each effect is **Augmented** (half cooldown, stronger spark), then 3 normal crafts, then it can't be crafted again.
- Crafting is announced server-wide (with coordinates, configurable).
- `/trust <player>`: your abilities never hit trusted players, and Regen spark heals them.
- On death, equipped effects drop as items (config: DROP / KEEP / LOSE) and cooldowns reset.
- `/recipes` opens a menu of every effect bottle; click one to see its 3x3 recipe. `/infuses [effect]` prints the same info in chat.

## Admin
`/infuse give <player> <effect> [aug]`, `/infuse clear <player>`, `/infuse resetcd <player>`,
`/infuse resetcrafts <effect|all>`, `/infuse reload` (permission `lanterninfuse.admin`).
