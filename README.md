# Mobium

**Faster chests, signs and mobs: Mobium draws things that stand still from GPU buffers and lets the GPU write animated
models' vertices, instead of the CPU rebuilding them every frame.**

Mobium is a client-side performance mod for Minecraft (Fabric, Quilt and NeoForge). Vanilla, Iris and Entity Model
Features still decide how everything looks: Mobium changes how geometry reaches the GPU, never what ends up on screen.

> **Beta.** If anything looks different with Mobium than without it, please report it (see *Support*), ideally with a
> screenshot taken with Mobium on and one with it off (A/B key, below).

---

## ✨ Features

### Static cache

Objects that do not change are captured once, the first frame they are seen, through the game's own rendering code
(so resource packs and other mods' changes are captured as well). From the next frame on, Mobium draws them from GPU
buffers, and the game skips building, posing and writing their vertices every frame. As soon as something changes, the
object is captured again.

| Cached | While | Drawn by the game as usual while |
| --- | --- | --- |
| Chests, trapped, copper and ender chests, shulker boxes | Closed | Opening, open or closing |
| Signs and hanging signs, including their text and glow ink | Always | Being edited; obfuscated text |
| Skulls and heads | Always | A powered dragon or piglin head moves its mouth; player heads with a skin |
| Decorated pots | Always | Wobbling after a hit |
| Shelves and their items | The items look the same | An item animates (a spinning compass) |
| Item frames and glow item frames | Item and rotation unchanged | Holding a map; a name is shown; glowing |
| Armor stands | Pose and held items unchanged | Wearing armor; wobbling after a hit; name shown; glowing |
| Item and block display entities | Not moving, `fixed` billboard | Moving, interpolating or turning with the camera |

Everything else is not cached: mobs and other animated models go through the GPU part transform (below). Objects that
are being broken (the cracks), the farthest objects once the GPU memory budget is full, and anything Mobium does not
recognise are drawn by the game as usual.

**What it gains:** in a test room with 1,000 chests and 300 signs (Sodium, 1920×1080, Intel Arc 140V laptop GPU), the
frame time dropped from 3.3 ms to 1.7 ms, about twice the frame rate. In a normal base the gain depends on how many
chests, signs, item frames and displays are on screen; an empty field gets neither faster nor slower.

### GPU part transform

Mobs, armor, saddles, banners, bells, lecterns and everything else the game builds from model parts are posed and
animated exactly as before, by the game, Entity Model Features and Fresh Animations. Only the last step moves: instead
of the CPU working out and writing every corner of every cube, a compute shader on the GPU writes the vertices, exactly
the bytes the game would have written, also in Iris's shader pack formats and its shadow pass. Automated test scenes
compare them byte for byte with the game's own.

Posing and animations stay on the CPU, so the gain is largest where writing the vertices is expensive, as with a
shader pack. Translucent models (unless the game's Improved Transparency is on), the enchantment glint, the
outline of glowing entities and the breaking overlay are drawn by the game as usual. The GPU part transform needs
OpenGL 4.3 compute shaders (current Windows and Linux drivers; not macOS, which stops at OpenGL 4.1); without them the
game writes the vertices itself.

**What it gains:** in 500 walking cows, villagers and zombies with Fresh Animations (Sodium, 1920×1080, Intel Arc 140V
laptop GPU), the frame time dropped from 9.1 ms to 7.2 ms (110 → 138 FPS). With Fresh Animations and Complementary
Reimagined on top, the entities' CPU work per frame fell from 13.7 ms to 7.2 ms, and the frame rate rose from 15 to
18 FPS.

### Coming later

- **Animation LOD (optional, off by default):** far-away mobs reuse last frame's pose.
- **Vulkan:** Mobium needs the OpenGL backend for now (see *FAQ*).
- Armor on armor stands is drawn by the game for now.

---

## 📖 Usage

Mobium works out of the box; the static cache and the GPU part transform are on by default.

- **Settings:** the config button in Mod Menu (Fabric) or the mods list (NeoForge), or Mobium's page in Sodium's video
  settings. Every cached type has its own switch, and the GPU memory budget (default 128 MB) can be changed. The GPU
  part transform has its own switch; to keep single entity types on the game's own path, list them in
  `parts.excludedEntities` in `config/mobium.json` (for example `"minecraft:zombie"`).
- **A/B key:** switches all of Mobium off and on live, so you can compare in the same spot. It is the key right of
  <kbd>P</kbd>: <kbd>]</kbd> on a US keyboard, <kbd>+</kbd> on a German one (rebind it in the controls).
- **F3:** the debug entries `mobium:modules` (what is on, why something is off, and how many parts the GPU wrote last
  frame) and `mobium:timings` (where the frame time goes, with a shader pack also in Iris's shadow pass) can be enabled
  in the debug options.

If something goes wrong inside Mobium, it switches the affected part off for the rest of the session, shows one
message and lets the game draw everything as usual.

---

## 🧩 Compatibility

| Mod | Status |
| --- | --- |
| [Sodium](https://modrinth.com/mod/sodium) 0.9 | Works; Mobium adds a page to Sodium's video settings, and the GPU part transform writes cubes the way Sodium does |
| [Iris](https://modrinth.com/mod/iris) 1.11 with shader packs | Works; cached objects are captured in Iris's formats and captured again when the shader pack or its options change, and the GPU part transform writes Iris's formats, shadow pass included. Tested with Complementary Reimagined |
| [Entity Model Features](https://modrinth.com/mod/entity-model-features) / [Entity Texture Features](https://modrinth.com/mod/entitytexturefeatures), [Fresh Animations](https://modrinth.com/resourcepack/fresh-animations) | Works; models that EMF animates (for example a breathing chest) are not cached, and the GPU part transform writes their vertices while EMF animates them as usual |
| [Mod Menu](https://modrinth.com/mod/modmenu) | Opens Mobium's settings |
| Enhanced Block Entities (Reloaded), Vanillate (CrankShaft), Accelerated Rendering | These do the same job; while one is installed, Mobium switches off its overlapping part and says so in its settings |

Known issue (not caused by Mobium): on NeoForge, Iris 1.11.7 crashes with Complementary Reimagined r5.9.3, also
without Mobium.

The full list, with what was tested how, is in
[docs/compat.md](https://github.com/btwonion/mobium/blob/main/docs/compat.md).

---

## ❓ FAQ

**Do I need to install Mobium on the server?**

No. Mobium is client-side only and works in singleplayer and on any server.

**Does Mobium change how things look?**

It must not. Scripted test scenes compare Mobium's picture pixel by pixel with the game's own, on Fabric and NeoForge
(on Fabric also with a shader pack and with Fresh Animations), and the GPU part transform's vertices byte for byte with
the ones the game writes. If you see a difference, press the A/B key to check whether it goes away with Mobium off,
and please report it.

**Does it help with lots of mobs?**

Yes, since 0.2: that is what the GPU part transform is for. The more mobs on screen, the more CPU work it takes away.

**Does Mobium work with Vulkan?**

Not yet. Mobium needs the OpenGL backend, Minecraft 26.3's default. On the Vulkan renderer, also with Vulkan shader
mods such as Vitrail, Mobium stays switched off and the game draws everything itself (F3 `mobium:modules` says so).
Vulkan support is planned for a later version.

---

## 📦 Installation

Download the file for your loader from [Modrinth](https://modrinth.com/mod/mobium/versions)
or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/mobium-perf/files), put it in your `mods` folder and add
the required dependencies. Mobium 0.2 is for Minecraft 26.3.

| Loader | Required dependencies | Optional |
| --- | --- | --- |
| Fabric / Quilt | [Fabric API](https://modrinth.com/mod/fabric-api), [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | [Mod Menu](https://modrinth.com/mod/modmenu), [Sodium](https://modrinth.com/mod/sodium) |
| NeoForge | [KotlinLangForge](https://modrinth.com/mod/kotlin-lang-forge) | [Sodium](https://modrinth.com/mod/sodium) |

---

## 💬 Support

If you need help with any of my mods, join my [Discord server](https://nyon.dev/discord), or open an issue on
[GitHub](https://github.com/btwonion/mobium/issues).

## License

Mobium is licensed under the
[GNU General Public License v3.0](https://github.com/btwonion/mobium/blob/main/LICENSE).
