# FrostingCore - Frosting SMP
One plugin that replaces a pile of separate ones. Server by PixelForge.gg. Paper 26.1.2, Java 25.

## Build
Push this folder (or the zip) to a GitHub repo and let the included Actions workflow build it:
Actions tab > latest run > Artifacts > download the jar. Or run `mvn package` with JDK 25.

## Install
1. Remove plugins that do the same job (their commands would clash). On your server that means
   DonutSMP-Homes, DonutSMP-RTP, DonutTab (FrostingCore has its own tab list + scoreboard), CombatLog, Spawn, Warp, JustTPA, SlashString.
   You can also switch any system off in `config.yml > modules`.
2. Drop the jar in `plugins/`, restart.
3. Stand where you want spawn and run `/fc hub build` (an icy hub with a giant frosted donut, ice spires, snowfall, spawn + leaderboard holograms; default radius 26).
   Options: `/fc hub build 30 frost` or `/fc hub build 22 donut` (pink version).
4. Optional: `/setafk` for the AFK zone, `/fc crate common|prime|frosted` while looking at a block to place crates.

## What's in it
**Economy & stats** - /bal /pay /baltop /top /stats /shards, kills/deaths/playtime, kill streaks, sidebar scoreboard (/sb).
**Movement** - /spawn (+ spawn protection: no building, no fighting, no damage at all), /rtp menu, /sethome /home /homes, /tpa /tpahere /tpaccept /tpdeny, combat timer.
**Markets** - /ah auction house (search, sort, claim box, crash-safe), /sell (menu, hand, all), /shop, /orders (buy requests), /bounty.
**Brought back (things Donut SMP players asked for)** - the /shop, crates + keys + keyall, the shard shop (/shardshop), AFK zone shards and keys.
**Tab list & extras** - tab header/footer with ranks (permission nodes `frostingcore.rank.owner|admin|mod|helper|vip`), `/discord` (invite link in config.yml).
**Frosting SMP originals** - the Frosting Hub spawn (giant donut + leaderboard holograms), Frosting Drops (airdrops), /lottery, /daily streak rewards, kill effects, welcome titles, /nv, /hidechat.
**FrostGuard anti-cheat** - Fly, GroundSpoof, Speed, Step, Timer, Reach, Aim, HitWall, KillAura, AutoClicker, FastBreak, Nuker, FastPlace.
  Every alert saves a proof file (movement trail, ping, TPS, items, effects) in `plugins/FrostingCore/evidence/`, can be posted to Discord,
  and can be replayed with particles (`/fg replay <id>`). Starts in ALERT mode; switch `guard.punish.mode` to AUTO once you trust it.

## Admin
`/fc reload` `/fc give|take|set <player> <money|shards> <amount>` `/fc hub build|holo|clear` `/fc crate <id>` `/fc key <player> <crate> [n]`
`/fc keyall <crate> [n]` `/fc airdrop` `/fc lottery draw` `/setspawn` `/setafk` `/fg ...` (anti-cheat staff commands).
