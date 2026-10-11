## 2026.41.0 - 2026-10-10

### Features
- ship eight ready-made farm presets (`9941a3f`)
- ship farmbarn.schem as the default farmhouse (`9acf842`)
- expose garden level, xp and max level (`0079e23`)
- build the farmhouse from a WorldEdit schematic (`065dc36`)
- show the visitor's face at the top of their offer (`5088a92`)
- list wanted amounts on the accept button, one plain icon per item (`88c56c7`)
- show a request's real amount on the offer page (`93f9f03`)
- a drive-through window visitors queue and walk up to (`846a365`)
- %royalgarden_clearing_...% placeholders for plot progress (`fd8c60e`)
- overgrown locked plots that must be cleared before building (`ca203e7`)

### Fixes
- lay melon and pumpkin out as a snake of walkways (`2d5ef92`)
- fill the barn's margin so it meets the surrounding ground (`666a07f`)
- count farmable plots, not the farmhouse, in what players see (`9a71e72`)
- find served visitors anywhere in the Garden, not only at the spots (`ffa59f4`)
- turn Sam to face the desk instead of the back wall (`b1bbf06`)
- read clearing settings through the shipped defaults (`bd8d6f4`)

### Refactors
- read the captured preset name and description from config (`8b10888`)
- read all player-facing text from config (`daf558d`)

### Documentation
- add AGENTS.md with the suite's development standards (`6141cb1`)
- clearing tools for EcoItems and a Garden sidebar for TAB (`586dfb3`)

## 2026.39.1 — 2026-09-26

### ✨ Features
- pests that spawn while farming, infest plots and pay when killed (`26c287d`)
- the Composter, turning crops and fuel into Compost over time (`77aadba`)
- crop upgrades at the desk, granting libreforge effects (`8f81133`)
- serve visitor placeholders as %royalgarden_...% through a PlaceholderAPI expansion (`9348f4a`)
- visitor log page and visitor stat placeholders (`c661cc4`)
- visitors and Copper (`686550c`)
- garden levels (`1355771`)
- crop milestones (`467da7d`)
- configurable costs, rewards and presets; harden protection and saves (`7bdaccb`)

### 🐛 Fixes
- detect unknown item lookups without eco's internal empty-item class (`731a7d5`)
- rename a lambda parameter that shadowed a local in the preset picker (`ad4ed22`)
- use the ISO week-year for release versions (`aa8b15b`)

### ♻️ Refactors
- use eco's SlotProvider instead of the builder marked for removal (`2ce6f65`)

### 📝 Documentation
- document config, presets, permissions and admin commands (`79a6f16`)

## 2026.39.0 — 2026-09-23

### 📝 Documentation
- replace stale release-slot comment (`987653f`)

### 🔧 Other
- paper-api 26.2.build.121-stable -> 26.2.build.123-stable (`9d8d89b`)

## 2026.37.0 — 2026-09-13

_Maintenance release._

## 2026.36.0 — 2026-09-06

### ✨ Features
- bedrock base, and clear a plot before applying anything to it (`2885ca0`)
- pace preset pasting at 12 ticks per row, and guard against overlap (`ae32fa7`)
- named plot presets, replacing the single template (`73e11df`)
- capture a plot as a template and stamp it onto every other plot (`225c168`)
- coloured per-player border, exact plot blocking, no vanilla spawns (`c230fd9`)
- generate the whole board, and use a real world border instead of walls (`b376f6b`)
- plot GUI, unlock state, moving boundary walls, bedrock floor, protection (`7889602`)
- Phase 1 - per-player Garden worlds, farmhouse, Sam and the desk (`b0c585c`)
- RoyalGarden phase 0 - an eco extension skeleton of RoyalSkyblock (`388d6cd`)

### 🐛 Fixes
- open the desk once per click on Sam (`7957323`)
- judge plot generation by the bedrock course, not the surface (`8718d64`)
- resolve eco from repo.auxilor.io instead of the local repository (`4e12676`)
- keep extension data in its own folder, not the host folder (`419649c`)
- remove vanilla mobs already saved into a Garden (`84caa53`)
- declare permission nodes so /garden and /desk are not operators-only (`c8a8f9c`)

### 📝 Documentation
- state the Paper 26.2-or-newer requirement (`f73afd6`)
- align docs and desk text with the all-plots-generated design (`8d0a0c3`)
- record Phase 0 service resolution verified on ASP (`1ebfe96`)
- correct the libreforge claim - it is not bundled by CI either (`cf7bb0a`)

