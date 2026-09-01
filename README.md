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

**The board.** 5×5 plots of 96×96, so 480 blocks square, centred on the world origin. The centre
plot is *centred* on `(0, 0)` rather than starting there, which puts the farmhouse at the origin
and makes every plot index read as an offset from the middle. All of that arithmetic lives in
`GardenLayout`, alone, and is plain static maths so it can be reasoned about without a server.

**Only the centre plot is generated.** The other twenty-four are empty sky. That is the design —
an unbought plot should look unbought, not merely fenced — and it is also the cheap option: one
plot is 9,216 columns against 230,400 for the board. **Generating a plot is what unlocking one
will mean.**

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
can be lost to a rollback, and a Garden with no Sam has no other way to get one back.

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

## What is still unproven

Nothing from Phase 0. Phase 1 is the next unknown: creating a Garden SlimeWorld through
`AspIslandWorldService` rather than reading it.
