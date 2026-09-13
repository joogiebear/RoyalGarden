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

