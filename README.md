# RoyalGarden

The Garden — a personal farming area of unlockable plots — as an **eco extension of
RoyalSkyblock** rather than a plugin of its own.

Status: **Phase 0.** It loads and does nothing. That is the point of it.

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

Requires JDK 25 — paper-api 26.2 ships Java 25 bytecode.

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
plugin, so it cannot be installed alongside. Without it RoyalSkyblock logs
`Island world backend unavailable` and leaves `islandManager` null — and its accessors assert
non-null, so reaching for one throws. On plain Paper the host aborts before `onAfterLoad`
ever runs, which is why service resolution is still unverified at runtime.

**libreforge is not bundled in a local build.** It lives in `plugins/libreforge/versions/`
and is bootstrapped by an Auxilor addon; the shading that `plugin.yml` describes happens in
CI. A locally-built `RoyalSkyblock.jar` on a bare server fails with
`ClassNotFoundException: com.willfp.libreforge.Plugins` until some eco addon is present to
load libreforge first.

## What is still unproven

Runtime access to the host's services. It compiles, so the API is right, but no server here
runs ASP — so `islandManager` has never been non-null. **Phase 1 needs an ASP server jar.**
