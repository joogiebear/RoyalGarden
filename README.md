# RoyalGarden

The Garden — a personal farming area of unlockable plots — as an **eco extension of
RoyalSkyblock** rather than a plugin of its own.

Status: **Phase 1.** A player gets a Garden world of their own, built on first visit.

---

## Why an extension

Everything The Garden needs already exists in RoyalSkyblock: per-profile worlds through
Advanced Slime Paper, persistence, a menu engine, currencies, a border service. Building
alongside would mean reimplementing all of it and then keeping two copies agreeing about
who owns a player's world.

`RoyalSkyblockPlugin` extends `LibreforgePlugin`, so it is an eco plugin and inherits eco's
extension loader for free. Nothing in the host had to change to support this — every service
already has a public accessor (`islands()`, `worlds()`, `storage()`, `levels()`) even though
the fields behind them are private.

## Building

```powershell
.\tools\install-deps.ps1     # once, and after updating eco or RoyalSkyblock
mvn clean package            # -> target/RoyalGarden.jar
```

Then drop the jar in `plugins/RoyalSkyblock/extensions/` and restart.

Requires an Advanced Slime Paper host on 26.2 or newer, and JDK 25 to build — paper-api 26.2 ships Java 25 bytecode.

---

## What Phase 0 established

**eco loads any jar in `plugins/<EcoPlugin>/extensions/` that carries an `extension.yml`**
at the jar root, with `main`, `name`, `version`, `author` and an optional `plugin-version`.
It loads them quietly: a jar with no manifest is skipped with a log line and no error, so the
console is the only confirmation a build actually took.

**An extension enables inside its host's enable**, via
`EcoPlugin.onEnable → Extension.enable → onEnable`. Two consequences:

- **Anything thrown in `onEnable` disables the host.** The first build of this extension
  reached for `islands()` there, hit a null, and took RoyalSkyblock down with it. An
  extension can break the plugin it extends. Nothing in `onEnable` may throw.
- **Host services do not exist yet** at that point. Service resolution belongs in
  `onAfterLoad()`, and even there it must tolerate absence.

**Advanced Slime Paper is a real prerequisite, not a detail.** ASP is a *server fork*, not a
plugin, so it cannot be installed alongside — it replaces the server jar. Without it
RoyalSkyblock logs `Island world backend unavailable` and leaves `islandManager` null, and its
accessors assert non-null, so reaching for one throws. On plain Paper the host aborts before
`onAfterLoad` ever runs.

**The host's services resolve.** On `AdvancedSlimePaper-26.2` (`26.2-17212-dev`, API
`26.2.build.40-alpha` — the same build this project compiles against):

```
[RoyalSkyblock] Host services resolved — RoyalGarden can build on:
[RoyalSkyblock]   island manager: IslandManager
[RoyalSkyblock]   world service:  AspIslandWorldService
[RoyalSkyblock]   storage:        SqlStorage
```

`AspIslandWorldService` is the ASP-backed implementation, so Garden worlds get the same
SlimeWorld treatment islands do. Both assumptions the design rests on now hold at runtime.

**libreforge must already be on the server as a plugin.** RoyalSkyblock's `plugin.yml` says
it "ships bundled inside this jar", but it does not — neither the local build (272 entries)
nor the published `2026.32.0` release asset (197 entries) contains a single
`com/willfp/libreforge/` class. It lives in `plugins/libreforge/versions/` and is
bootstrapped by an Auxilor eco addon.

So RoyalSkyblock alone on a bare server fails with
`ClassNotFoundException: com.willfp.libreforge.Plugins`, and stays failing even with the
version jars present, until some eco addon is installed to load libreforge first. That is
not a broken build — it is a real deployment requirement, and it applies to production
equally. Any eco addon satisfies it; this repo's test instance uses EcoItems.

## Phase 1

`/garden` gives you a Garden world of your own, created on first use and loaded on every visit
after. `/desk` — and right-clicking Sam in the farmhouse — opens the desk.

**The board.** By default 5×5 plots of 96×96 (`layout:` in `config.yml`), so 480 blocks square, centred on the world origin. The centre
plot is *centred* on `(0, 0)` rather than starting there, which puts the farmhouse at the origin
and makes every plot index read as an offset from the middle. All of that arithmetic lives in
`GardenLayout`, alone, and is plain static maths so it can be reasoned about without a server.

**Every plot is generated up front**, locked ones included, so the board reads as land to earn rather
than holes. Generation goes through one shared build queue with a server-wide block budget per tick
(`generation.blocks-per-tick`), so fifty Gardens created at a contest opening cost the server the same
per tick as one — they just take longer. Unlocking a plot changes where you may go and build, not
whether the ground exists.

**Worlds come from the host.** `GardenWorlds` calls RoyalSkyblock's `IslandWorldService`, which is
name-based rather than island-based — hand it a name, get back a Bukkit `World`. So Gardens get
ASP SlimeWorlds, the unload grace period and the save-on-shutdown guarantees for free, and this
extension never imports an ASP type. Ownership needs no storage either: the world is named
`garden_<uuid>`, so the name *is* the record.

Two things that fell out of building it:

- **The host has no "does this world exist?" call** — `loadIsland` just fails for an unknown world.
  Absence has to be read out of the failure, and it is identified by the exception's *class name*,
  because catching `UnknownWorldException` would mean compiling against the ASP API. That check is
  confined to one method in `GardenWorlds`.
- **The border needs no listener.** RoyalSkyblock's `BorderService` clears the per-player border in
  any non-island world — and clearing a per-player border falls the player back to the *world's*
  own border. Setting the world border to 480 is therefore all it takes; the two systems agree by
  not overlapping.

**Sam is a plain villager with its AI off, not a Citizens NPC.** Citizens would mean a hard
dependency bought for skins and pathing. He is identified by persistent data rather than by name or
position, so he cannot be orphaned into a second Sam, and `ensure()` runs on every load — an entity
can be lost to a rollback, and a Garden with no Sam has no other way to get one back. It runs once
the desk chunk's *entities* have loaded (Paper loads them after the blocks); checking earlier found
nobody and spawned a duplicate.

**Commands are registered through eco**, not `plugin.yml` — an extension has no `plugin.yml` to put
them in. eco registers into the live command map, which is what makes commands possible from an
extension at all. They are registered against the *host* plugin, so `onDisable` has to unregister
them by hand or an extension reload stacks a second copy.

## Test instance

`mcctl` instance `garden`, on `AdvancedSlimePaper-26.2.jar`, running the minimum that loads the
host: `eco`, `EcoItems` (to bootstrap libreforge, see above), `RoyalSkyblock`, `Vault`.

Two console errors there are **gaps in that instance, not defects**:

- `Invalid effect ID specified: multiply_all_stats` / `add_stat` at `upgrade sanctuary tier 5`.
  Those effects are registered by **EcoSkills**, which isn't installed. RoyalSkyblock's shipped
  sanctuary upgrade references them unconditionally.
- Vault loads with no economy provider, so the bank and `coins` upgrades stay disabled.

Neither affects the island services, and neither needs fixing to develop against.

## Configuration

Everything tunable is in `plugins/RoyalSkyblock/extensions/RoyalGarden/`, written on first start and
reloaded with `/garden reload` (or RoyalSkyblock's own reload):

| File | What it holds |
|---|---|
| `config.yml` | board shape, generation speed, plot costs / requirements / rewards, preset rules, world rules, protection switches, Sam, command names, menu icons |
| `levels.yml` | Garden Levels: XP curve, XP sources, level rewards |
| `milestones.yml` | Crop Milestones: crops, tier curves, rewards, the milestones menu |
| `upgrades.yml` | Crop Upgrades: level costs, the libreforge effects each level grants, rewards, the menu |
| `composter.yml` | the Composter: organic matter and fuel values, Compost timing and caps, output, upgrades, rewards, the barn block, the menu |
| `pests.yml` | Pests: spawn chance, cooldown and caps, the libreforge effects of an infested plot, each pest and its EcoMobs spawn command, kill rewards, the menus |
| `visitors.yml` | Visitors: arrival timing, rarities, visitors and their requests, rewards, Copper, SkyMart, where they stand, the menus |
| `messages.yml` | every player-facing line (`&` codes, `&#rrggbb` hex; `""` switches a message off) |
| `presets.yml` | which captured presets players may choose, with name, icon, cost, cooldown and permission; and the default preset new Gardens generate from |

Costs use RoyalSkyblock's own `currencies:` (Vault coins, EcoBits gems, any placeholder + command
currency), written as `5000 coins`. Requirements are PlaceholderAPI placeholders with a minimum.
Rewards are console commands and messages.

**Buying plots** resolves each setting from the most specific layer: `unlock.by-plot.<A1…E5>`, then
`unlock.by-count.<n>` (the n-th purchase), then `unlock.default` with optional `cost-scaling`.
Rewards from every matching layer are all given.

**Presets for players:** capture a plot with `/garden capture <id>`, then set `selectable: true` on its
entry in `presets.yml`. Players right-click a plot at the desk to choose one; it costs what the entry
says, has a per-plot cooldown and needs a confirming second click. By default crops paste at age 0
and melons/pumpkins are never pasted, so a preset is a planted farm rather than a free harvest.

### Clearing plots

Locked plots are **overgrown** — trees, boulders, bushes, fallen logs and undergrowth (`clearing:` in
`config.yml`). Buying a plot is only the first step: it then has to be **cleared** before it can be built
on. Every piece of debris that goes counts towards the plot's clearing percentage — broken by the owner,
or leaves decaying once their tree is felled — and at `required-percent` (90 by default) the plot is
finished and becomes ordinary ground. The desk shows the progress; the action bar shows it as you work.

While a plot is being cleared its owner may break debris there and nothing else: no placing, tilling,
bone meal, buckets or presets. It is shielded from pistons, liquids, growth and explosions like a locked
plot, so debris cannot be added from next door and cleared for free.

- **Debris is planned from a seed per Garden and plot**, so it is the same every time it is asked for,
  and is written through the shared build queue, only into air. It is counted as it is written, so a
  run cut short by an unload and redone later still ends with the right total.
- **Tree leaves carry their real distance from a log** and are not persistent: they hold on a locked
  plot and decay once the trunk is cut. Bushes are persistent leaves and have to be broken.
- **The count is kept by events and checked against the world.** The first time a plot's debris is
  touched after its world loads, the plot is recounted, so a missed save or a rolled-back world can
  neither strand a player at 89% nor hand them progress for debris that came back.
- **Existing Gardens** grow debris on their locked plots on their next load. Plots already bought are
  left alone.

`/garden clear <player> <plot>` finishes a plot's clearing by hand.

Placeholders, for tab lists and scoreboards (online players only):

| Placeholder | Value |
|---|---|
| `%royalgarden_clearing_plot%` | the plot you are standing on |
| `%royalgarden_clearing_percent%` | how clear it is — its % while being cleared, 100 once clear, blank if locked |
| `%royalgarden_clearing_bar%` | a progress bar toward the required % |
| `%royalgarden_clearing_status%` | a ready-made line (`clearing-status` in `messages.yml`), blank unless on a plot being cleared |
| `%royalgarden_clearing_required%` | the % needed to build |
| `%royalgarden_clearing_active%` | how many of your plots are being cleared |
| `%royalgarden_clearing_<plot>%` | one of your plots by name, e.g. `clearing_b2` |

### Crop Milestones

`milestones.yml` gives every player a lifetime total per crop, climbing through tiers. Each tier
pays once, and rewards stack: `rewards.every-tier`, then `rewards.by-tier.<n>`, then the crop's own
`every-tier` and `by-tier.<n>`. Tiers come from a growth curve (`first`, `growth`, `count`,
`round-to`) or an explicit list of totals, either shared (`default-tiers`) or per crop.

Only ripe, naturally grown crops count, and only in Gardens by default. Placed blocks are tracked in
chunk data and follow pistons. Sugar cane and cactus count the whole column that falls. `counting:
blocks` adds each crop's `points` per block. `counting: drops` counts the items that drop, so Fortune
counts.

Players open milestones with the **Milestones** button on the desk or `/garden milestones`. Admins
can use `/garden milestones <player>` to view someone's progress, and
`/garden milestone <player> <crop> <amount>` or `/garden milestone <player> reset` to correct it
(no rewards are given). Progress saves every `save-interval-seconds`, when a player leaves, and on
shutdown.

### Garden Levels

`levels.yml` gives every Garden XP that adds up to levels (15 by default, on a growth curve or an
explicit list). XP comes from crop milestone tiers and plot purchases, both configurable under
`sources:`. Other plugins can award it from the console with `garden xp <player> give <amount>
[silent]`, which is how quests, skills, crates or visitors can pay Garden XP.

Reaching a level pays `rewards.every-level` plus `rewards.by-level.<n>`, once each. Levels gate
things: `garden-level:` on a plot's unlock terms (`default`, `by-count`, `by-plot`) or on a preset in
`presets.yml`. The desk summary shows the level and progress. Players run `/garden level`; admins
run `/garden level <player>` and `/garden xp <player> give|take|set <amount>` (`take` and `set` pay
nothing). XP is saved with the Garden, so `/garden reset` clears it too.

The console can also run `/garden xp`, `level`, `milestone`, `visitor`, `upgrade`, `composter`, `reset`, `unlock`, `lock`,
`info` and `reload`.

### Visitors and Copper

`visitors.yml` sends visitors to the barn over time: one per `arrivals.interval` while the player is
online, up to `arrivals.max-queue` waiting at once (bigger at higher Garden levels with
`max-queue-by-level`). While the queue is full the clock stops. Time offline counts at
`arrivals.offline-rate` (1 = all of it, 0 = none), including server downtime.

Each arrival is picked by weight from the visitors the Garden's level allows (`garden-level:`), with a
visitor's own `weight` or its rarity's. A visitor has one or more `requests`, one picked by weight per
visit; items are written `wheat 64`, `carrot 32-64` or `ecoitems:enchanted_wheat 2-4` (any eco item
lookup). Amounts grow with Garden level by `scaling.per-level`, capped at `scaling.max-multiplier`, and
with `scaling.rewards` so do the Copper and Garden XP paid. Everything is rolled when the visitor
arrives and saved with them, so reopening an offer never re-rolls it.

Visitors stand at the `spots` outside the barn as no-AI mobs (any living mob: `entity: villager`,
`wandering_trader`, `piglin`…; villagers take a `profession` and `villager-type`). They are tagged
like Sam, so they survive restarts without duplicating; more visitors than spots still wait on the
Visitors page. Right-click one, click **Visitors** on the desk, or run `/garden visitors` to see an
offer: accept to hand the items over, or refuse to send them away.

Serving pays the offer's Copper and Garden XP, then stacked rewards: `rewards.every-visit`,
`rewards.first-visit` (the first time a player serves that visitor), `rewards.by-served.<n>` (their
n-th visitor), the rarity's `rewards`, and the visitor's own. Served, refused, different visitors
served and Copper earned are kept per player in `visitors/<uuid>.yml`.

**Copper** is an EcoBits currency, paid by `copper.give-command` (default
`ecobits givesilent %player% copper %amount%`). **SkyMart** — the Copper shop — is an EcoShop shop;
the button on the Visitors page runs `skymart.command`. Ready-made files for both are in
[`examples/`](examples): add `examples/EcoBits/copper.yml`'s currency to EcoBits' `currencies:`,
and copy `examples/EcoShop/shops/skymart.yml` and `examples/EcoShop/categories/skymart.yml` into
`plugins/EcoShop/`. Visitors need the `CUSTOM`
spawn reason in `world.allowed-spawn-reasons` (it is there by default), as Sam does.

The **Visitor Log** (a button on the Visitors page, or `/garden visitors log`) lists every visitor in
`visitors.yml`: the ones a player has served show as their head (`head:` — a base64 skin texture or a
player name like `MHF_Villager`) with how many times they have visited, and the rest are locked grey
dye, with the Garden level they need if the Garden is not there yet.

**Placeholders** (with PlaceholderAPI installed): `%royalgarden_visitors_served%`,
`_visitors_unique` (different visitors served), `_visitors_total` (visitors configured),
`_visitors_refused`, `_visitors_copper` (earned), `_visitors_waiting`, `_visitors_max_queue`,
`_visitors_next` (time to the next arrival), and `%royalgarden_visitor_<id>_served%` per visitor.
Values are plain numbers, for use in conditions, and work for offline players too.

Admins: `/garden visitor <player> add [visitor]` sends one now (ignoring the clock, queue size and
level), `clear` sends everyone waiting away, `stats` shows the counts, `reset-stats` zeroes them. The
console can run it too. `/garden reset` also sends a Garden's waiting visitors away.

### Crop Upgrades

`upgrades.yml` lets players upgrade every crop from `milestones.yml` at the desk (**Crop Upgrades**,
or `/garden upgrades`). Each level costs the next entry of `costs`, in any of RoyalSkyblock's
currencies — Copper by default, which has to be added to the host's `currencies:` first
(`examples/RoyalSkyblock/copper-currency.yml`).

What a level *does* is not implemented here: it gives the player **libreforge effects**, the same way
RoyalSkyblock's island upgrades and every eco plugin do. `effects:` and `conditions:` are a template
filled per crop and level with `%level%`, `%crop%`, `%value%`, `%blocks%` and `%drops%` (the last two
from `milestones.yml`), compiled through the host's own holder compiler. Only the current level's
effects apply, while the player is where `apply-in` says (their own Garden by default). So yield, stats
and skill XP come from libreforge and EcoSkills, and stack with whatever EcoSkills' own skills give:

- the default is Hypixel-like: +5% per level chance of double drops for that crop (`multiply_drops`),
  never on player-placed blocks;
- or have upgrades feed an EcoSkills stat with `add_stat` — `examples/EcoSkills/stats/farming_fortune.yml`
  is a Farming Fortune stat (double-drop chance) that skills, pets and gear can raise too;
- or `skill_xp_multiplier` for faster Farming XP, or anything else in the eco library.

Any crop can have its own `costs`, `effects`, `conditions` and `value-per-level` under `crops.<id>`, or
be left out with `enabled: false`. Buying a level pays Garden XP (`garden-xp`) and stacked rewards
(`rewards.every-level`, `rewards.by-level.<n>`). Levels are per player, like milestones, so
`/garden reset` keeps them. Note: libreforge's `multiply_drops.prevent-duplication` skips solid
blocks such as pumpkins unless they are on its whitelist.

Placeholders: `%royalgarden_upgrade_<crop>_level%` and `%royalgarden_upgrade_<crop>_value%`. Admins:
`/garden upgrade <player> <crop> <level>` or `/garden upgrade <player> reset` (nothing is charged).

### The Composter

`composter.yml` gives every player a Composter that turns **organic matter** (crops) and **fuel** into
**Compost** over time. It makes one Compost every `compost.time` (10 minutes by default) while it
holds `compost.organic-matter` and `compost.fuel` for one and has room to keep it (`compost.max-stored`),
using them as each Compost finishes. It keeps working while the player is offline or their Garden is
unloaded: nothing ticks for them, and the Composter catches up from its saved `last-tick` the next
time it is opened, clicked or read. While it is out of matter or fuel, or full, the clock stops, so
refilling it starts a fresh Compost rather than paying out the time it sat empty.

Organic matter is every crop's drops from `milestones.yml`, at `organic-matter.crop-value` each or a
per-crop value under `organic-matter.crops`, plus anything in `organic-matter.items`
(`"<item> <value>"`, any eco lookup, so `ecoitems:enchanted_wheat 160` works and is matched before
plain wheat). Fuel is `fuel.items` in the same form. Both are held up to a cap.

Open it by right-clicking the composter in the barn (`block:` — by default one the barn already has,
on its east side; it is marked in the chunk so the other composters stay scenery, and put back if it
goes missing), the **Composter** button on the desk, or `/garden composter`. Left-click the matter or
fuel icon to put in one stack, right-click to put in everything that counts. Collecting gives
`output.item` (bone meal by default; `examples/EcoItems/items/compost.yml` is a Compost item that
grows crops when used on them — set `output.item: ecoitems:compost`), runs `output.commands`, pays
`garden-xp.per-compost` and `rewards.collect`. What does not fit in the inventory stays in the
Composter.

**Upgrades** are bought in the menu with any host currency (Copper by default): `speed` (+% speed),
`multi-drop` (+% chance of a bonus Compost that uses nothing), `matter-cap` and `fuel-cap` (more
storage) and `cost-reduction` (-% matter and fuel per Compost). Each has its own `costs` (one per
level), `per-level` and icons. They are properties of the machine rather than player buffs, so they
are plain numbers here rather than libreforge effects. Buying one pays `garden-xp.upgrade` and
`rewards.upgrade`. A Composter belongs to the player, like crop upgrades, so `/garden reset` keeps it.

Data is in `composter/<uuid>.yml`, written on every change. Placeholders:
`%royalgarden_composter_matter%`, `_fuel`, `_stored`, `_next` (time to the next Compost, blank while
stopped), `_matter_cap`, `_fuel_cap`, `_made` and `%royalgarden_composter_<upgrade>_level%`
(`speed`, `multi_drop`, `matter_cap`, `fuel_cap`, `cost_reduction`). They are projected to the
current moment for offline players too, without bonus Compost, which is only rolled when the
Composter is actually caught up. Admins: `/garden composter <player> give-matter|give-fuel <amount>`,
`collect` (into their inventory, so they must be online) and `reset` (empties it and forgets its
upgrades; the file is moved aside). The console can run it too.

### Pests

`pests.yml` brings **pests** onto plots while players farm, like Hypixel's. A harvest that counts toward
Crop Milestones, by the Garden's owner in their own Garden, rolls `spawning.chance` percent
(`spawning.crop-chance.<crop>` per crop) once the Garden is off its `spawning.cooldown`. A hit lands on
the plot being farmed — or, `other-plot-chance` percent of the time or when that plot is full, on another
unlocked plot — within `max-per-plot` and `max-per-garden`, never on the farmhouse unless `farmhouse` is
on. The kind of pest is picked by `weight` from those the Garden's level allows.

The pests are **EcoMobs mobs**. Each entry's `command` is run from the console at a random spot on the
plot — `ecomobs spawn <mob> %x% %y% %z% %world%` by default, EcoMobs' own syntax — and the entity it
spawned (matched by its EcoMobs id, `mob`) is tagged in its persistent data with its kind, plot and
spawn time. `examples/EcoMobs/` has a `garden_pests` category (no natural spawning, persistent) and four
pests: `pest_fly`, `pest_mite`, `pest_beetle` and `pest_rat`. EcoMobs spawns with the `CUSTOM` reason,
which `world.allowed-spawn-reasons` keeps, and its tag spares them from the vanilla-mob sweep.

Pests never leave their plot: every `confine.check-every-seconds`, one found outside it is put back where
it appeared. The Garden keeps its own record of its pests in `pests/<uuid>.yml`, so counts hold while a
plot's chunks, or the whole Garden, are unloaded. As a chunk's entities load, a pest with no record is
taken back in if there is room and removed if not (or if it predates a clear, or its plot is locked),
and a known one is removed if the caps were lowered below what its plot holds.

**Infested plots** are libreforge, not RoyalGarden's own maths. A plot with at least `infested-at` pests
is infested, and while the Garden's owner (or anyone, with `infested-applies-to: everyone`) stands on it
they hold the effects under `infested-effects:`, filled with `%pests%`, `%plot%`, `%crop%` (the crop being
farmed when its newest pest came) and the list variables `%blocks%` and `%drops%`. The default is a
`%pests% × 5`% chance that a crop broken there drops nothing; `pests.yml` shows lowering EcoSkills'
`farming_fortune` stat with `add_stat` instead. Effects are refreshed when a player crosses a plot edge
or teleports within a Garden, and for everyone in a Garden when its pests change.

**Killing** a pest pays its killer — only the Garden's owner, with `kills.owner-only` — the pest's
`copper` (through `visitors.yml` `copper.give-command`), its `garden-xp`, then every stacked reward:
`rewards.every-kill`, `rewards.by-type.<pest>`, `rewards.by-kills.<n>` (the n-th kill) and the pest's own
`rewards`. The example mobs drop nothing so nothing is paid twice; to pay through EcoMobs instead, set
`copper` and `garden-xp` to 0. `examples/EcoItems/items/vacuum.yml` is a vacuum — right-click pulls in
the pests around you and hits them as you — and SkyMart sells it for Copper.

The **Pests** button on the desk and `/garden pests` show the board with each plot's pests; clicking a
plot with pests takes you there (`menu.locate-teleport`). The **Pest Log** (`/garden pests log`) lists
every kind of pest and how many you have killed. `/garden reset` clears a Garden's pests.

Placeholders: `%royalgarden_pests_alive%`, `_infested` (plots), `_killed`,
`%royalgarden_pests_<pest>_killed%` and `%royalgarden_pests_plot_<plot>%` (pests on that plot of the
player's Garden). Admins: `/garden pests <player> spawn [pest|random] [plot]` (off the cooldown and chance
but within the caps; the Garden must be loaded), `clear`, `stats` and `reset-stats`. The console can run
it too.

### Permissions

| Node | Default | |
|---|---|---|
| `royalgarden.garden` | everyone | `/garden` |
| `royalgarden.desk` | everyone | `/desk` |
| `royalgarden.limit.<n>` | — | own up to `n` plots (highest wins) |
| `royalgarden.admin` | op | admin subcommands |
| `royalgarden.bypass` | op | ignore Garden protection |
| `royalgarden.bypass.cooldown` | op | ignore the preset cooldown |

### Admin commands

`/garden capture <id>` · `apply <id>` · `flat` · `bedrock` · `default [id]` · `delete <id>` · `presets` ·
`reload` · `reset <player> [world]` · `unlock <player> <plot>` · `lock <player> <plot>` · `clear <player> <plot>` ·
`visit <player>` · `info <player>` · `milestones <player>` · `milestone <player> <crop> <amount>|reset` ·
`level <player>` · `xp <player> give|take|set <amount>` ·
`visitor <player> add [visitor]|clear|stats|reset-stats` · `upgrade <player> <crop> <level>|reset` ·
`composter <player> give-matter|give-fuel <amount>|collect|reset` ·
`pests <player> spawn [pest] [plot]|clear|stats|reset-stats`

`reset` forgets a player's unlocks and presets (the state file is moved aside, not deleted); with
`world` it also deletes their Garden world so the next `/garden` builds a fresh one — the contest reset.
