# Compatibility

What Mobium does next to other mods. Filled in as the modules arrive; M5 adds the tested matrix for Sodium, Iris
(with named shader packs), EMF/ETF with Fresh Animations and Entity Culling on both loaders.

## Mods that do the same job

While one of these is installed, the Mobium modules it overlaps stay on the vanilla path and say why (settings screen,
F3 `mobium:modules`, log). Mod ids from the mods' own sources, 20 Oct 2026.

| Mod | Mod id | Overlaps | Notes |
| --- | --- | --- | --- |
| Enhanced Block Entities, Enhanced Block Entities Reloaded | `enhancedblockentities` | Static cache | Reloaded (Modrinth `ebe-reloaded`) has 26.3 builds and keeps the original id |
| Vanillate (part of CrankShaft, a Flywheel port) | `vanillate` | Static cache, GPU part transform | CrankShaft itself (`crankshaft`) is only the engine and does not count |
| Accelerated Rendering | `acceleratedrendering` | All three | No 26.x build yet; detected in case one appears |

## Mods Mobium works with

| Mod | Status |
| --- | --- |
| Sodium 0.9 | Required for the benchmarks; Mobium adds a page to its video settings (Sodium Config API) |
| Iris 1.11 | The M3 chest prototype stays off while a shader pack is active; the full static cache (M5) supports shader packs |
| Entity Model Features, Entity Texture Features | Run unchanged on every path Mobium uses (M3 captures through vanilla's own submit and prepare) |
| Entity Culling | Detected; no interaction expected (it decides what is extracted, Mobium how it is drawn) |
| Mod Menu | Opens Mobium's settings |
