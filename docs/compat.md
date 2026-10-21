# Compatibility

What Mobium does next to other mods, and what was tested with which versions.

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
| Sodium 0.9 | Mobium adds a page to its video settings (Sodium Config API). Sodium's own block entity extraction calls `tryExtractRenderState`, so the static cache works the same with it |
| Iris 1.11 | The static cache captures in Iris's extended vertex formats while a shader pack is on and starts over when Iris switches its pipeline (reload, another pack, pack options). Iris's shadow pass draws cached objects through vanilla |
| Entity Model Features, Entity Texture Features | Run unchanged on every path Mobium uses: the cache captures through vanilla's own submit and prepare, so EMF's replaced models and ETF's emissive layers are captured as drawn. Models EMF animates (`containsCustomAnims`) stay on vanilla |
| Fabric API renderer API (Fabric) | Block models (item frames' frames, block displays) arrive as `ExtendedBlockModelSubmit`; the cache lets the feature renderer registered for them (Sodium's or Indigo's) write the captured vertices, like vanilla's own. Items whose model is a renderer API mesh (`ExtendedItemSubmit`, e.g. lanterns and flower pots) stay vanilla: the cache's item comparison cannot see a mesh |
| Entity Culling | Detected; no interaction expected (it decides what is extracted, Mobium how it is drawn) |
| Mod Menu | Opens Mobium's settings |

## Tested (M5, static cache)

How: the dev self-check (`python3 scripts/self_check.py`) compares Mobium's picture with vanilla's from two consecutive
frames of one client tick, in `storage_room` (1,000 chests, 300 signs) and in the `cache_gallery` scene (every cached
type, including item frames, armor stands and display entities, and the variants that stay vanilla), after changes the
cache must notice (lid opened and closed, breaking overlay, light, sign text, block state, an item frame turned and its
item swapped, a display moved, an armor stand's pose changed) and from a moved camera and inside the render-distance
fog. "Identical" means 0 changed pixels. Dev clients run the compat stack of `versions/<target>/gradle.properties`.

| Setup | Fabric | NeoForge |
| --- | --- | --- |
| Sodium 0.9.2 (Fabric) / 0.9.3-beta.1 (NeoForge), Iris 1.11.7 without pack, EMF 3.3.12, ETF 7.2.6 | Identical in every comparison (20 Oct 2026) | Identical in every comparison (20 Oct 2026) |
| + Complementary Reimagined r5.9.3 (TAA and cloud movement off for the comparison) | Captures in Iris's formats (56-byte entity, 52-byte glyph vertices); the picture matches vanilla's up to the pack's own frame-to-frame noise, which is on terrain too | Not tested: Iris 1.11.7 on NeoForge crashes with this pack in `CenterDepthSampler` ("Missing uniform InSampler"), with Mobium switched off as well (`self_check.py neoforge --mode iris-without-mobium`) |
| + Fresh Animations (1.10.3 in the dev clients, 1.11.1 in the benchmark instances) | Identical in every comparison of the gallery (20 Oct 2026); EMF replaces the head models with the pack's, and the one head Fresh Animations animates stays vanilla (`containsCustomAnims`) | Identical in every comparison of the gallery (20 Oct 2026), as on Fabric |
| Entity Culling | Not tested (not in the dev environment); expected to work, see above | Not tested |

## Known differences and limits

- Text drawn with a resource pack's antialiased (TrueType) font: vanilla draws a sign's glyphs in submission order
  among all solid-phase text; overlapping half-transparent glyph edges of the 8× glow-ink outline could blend in a
  different order. Not seen with the default font, whose glyphs are fully opaque or fully transparent.
- Entities are compared with what was captured through their render state (and, for armor and block displays, the
  entity's equipment and block state). Something a mod draws for an entity from data outside those inputs would keep
  its captured picture while the entity stands still: an ETF random or emissive texture whose rule depends on time or
  weather, or a NeoForge mod that draws its own item in a frame through `RenderItemInFrameEvent`. Not seen with Fresh
  Animations; the per-type switches (item frames, armor stands, display entities) turn such cases off.
- Armor stands wearing armor are not cached: their armor layers are drawn in submit orders one after another
  (`EquipmentLayerRenderer`), which the cache does not keep.
- Cached objects are drawn from camera-relative vertices, which the CPU rewrites for the visible buckets whenever the
  camera moves; with a still camera nothing is uploaded (F3 `mobium:modules` shows the bytes per frame).
