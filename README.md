# Mobium

**Faster chests, signs, mobs and dropped items: Mobium draws things that stand still from GPU buffers and lets the GPU
write the vertices of mobs and items, instead of the CPU rebuilding them every frame.**

Mobium is a client-side performance mod for Minecraft (Fabric, Quilt and NeoForge). Vanilla, Iris and Entity Model
Features still decide how everything looks: Mobium changes how geometry reaches the GPU, never what ends up on screen.

---

## ⚡ Performance

| Test scene | Without Mobium | With Mobium | Gain |
| --- | ---: | ---: | ---: |
| 1,000 chests and 300 signs | 300 FPS | 586 FPS | **+95 %** |
| 1,000 dropped items | 83 FPS | 239 FPS | **+188 %** |
| 1,000 dropped items with Complementary Reimagined | 11.7 FPS | 25.9 FPS | **+122 %** |
| 500 mobs | 287 FPS | 307 FPS | **+7 %** |
| 500 mobs with Fresh Animations | 101 FPS | 136 FPS | **+35 %** |
| 500 mobs with Fresh Animations and Complementary Reimagined | 13.6 FPS | 16.4 FPS | **+20 %** |

Average FPS on a laptop with Intel Arc 140V graphics, Sodium, OpenGL, 1920×1080. The more chests, signs, item frames,
mobs and dropped items are on screen, the more Mobium gains; an empty field gets neither faster nor slower. With 500
plain mobs this laptop's graphics chip is the limit, so the gain there follows its clock (+7 % to +19 % between
sessions).

### 📊 Benchmarks wanted

These numbers come from one laptop. To find out how Mobium does on other hardware, we need your results. The
benchmark builds its own scenes, so results from different computers compare, and it takes about six minutes:

1. Create a new world: in the **World** tab set World Type to **Superflat** and the seed to `mobium`, and under
   **More → Data Packs** move **Mobium Benchmark** to the selected packs.
2. Type `/mobium benchmark` and leave the game alone until the result shows in chat. It measures a storage room and
   mob pens with Mobium off and on, and puts your settings back at the end.
3. Send us the file it names (in `mobium-bench` in your game folder; click the name in chat to open the folder): drop
   it in our [Discord server](https://nyon.dev/discord) or attach it to a
   [benchmark report](https://github.com/btwonion/mobium/issues/new?template=benchmark.yml) on GitHub.

The file already names your CPU, GPU, mods, shader pack and screen. The data pack is off in every other world, so the
scenes are only ever built in this one.

---

## ✨ Features

### Static cache

Things that stand still are captured once through the game's own rendering code, resource packs and other mods'
changes included, and from then on drawn from GPU buffers instead of being rebuilt every frame. As soon as something
changes, Mobium captures it again.

- Chests of all kinds and shulker boxes
- Signs and hanging signs, with their text
- Skulls and heads, decorated pots, shelves
- Item frames, armor stands without armor, item and block displays

While a chest opens, a pot wobbles or anything else moves, the game draws it as usual.

### GPU part transform

Mobs, armor, banners and everything else built from model parts are still posed and animated by the game, Entity Model
Features and Fresh Animations. Only the last step moves: instead of the CPU writing every corner of every cube, a
shader on the GPU writes exactly the same vertices, also in Iris's shader pack formats. Items work the same way:
dropped items, items in hands, in frames and on displays keep the game's own models, colours and lighting, and the GPU
writes their faces. Mobium checks this on your GPU and lets the game draw as usual should a graphics driver ever
disagree.

The gain is largest where writing vertices is expensive: many mobs, piles of dropped items, Fresh Animations or a
shader pack. With only a few mobs on screen it can cost slightly more than it saves, which shows only at several
hundred FPS. On OpenGL it needs version 4.3 (current Windows and Linux drivers; not macOS); on Minecraft's Vulkan
renderer it needs a graphics chip with 64-bit floats in shaders (see the FAQ).

### Coming later

- The GPU part transform on **Vulkan** for graphics chips without 64-bit floats in shaders.

---

## 📖 Usage

Mobium works out of the box.

- **Settings:** Mod Menu (Fabric), the mods list (NeoForge) or Mobium's page in Sodium's video settings, with a switch
  for every cached type.
- **A/B key:** switches Mobium off and on live, to compare in the same spot. It is the key right of <kbd>P</kbd>:
  <kbd>]</kbd> on a US keyboard, <kbd>+</kbd> on a German one.
- **F3:** the debug entries `mobium:modules` and `mobium:timings` show what Mobium does and where the frame time goes.
- **`/mobium benchmark`:** measures Mobium off and on in a world made with the Mobium Benchmark data pack (see above).

If something goes wrong inside Mobium, it switches the affected part off, shows one message and lets the game draw as
usual.

---

## 🧩 Compatibility

| Mod | Status |
| --- | --- |
| Sodium | Works; Mobium adds a page to Sodium's video settings |
| Iris with shader packs | Works, shadow pass included; tested with Complementary Reimagined. Iris itself does not run on Vulkan |
| Entity Model Features / Entity Texture Features, Fresh Animations | Works |
| Mod Menu | Opens Mobium's settings |
| Enhanced Block Entities, Vanillate, Accelerated Rendering | These do the same job; Mobium switches off its overlapping part |
| Vitrail (shader packs on Vulkan) | Not tested yet; Mobium stays off while it is installed |

Details, tested versions and known limits:
[docs/compat.md](https://github.com/btwonion/mobium/blob/master/docs/compat.md).

---

## ❓ FAQ

**Do I need to install Mobium on the server?**

No. Mobium is client-side only and works in singleplayer and on any server.

**Does Mobium change how things look?**

It must not. Automated test scenes compare Mobium's picture pixel by pixel with the game's own. If you see a
difference, press the A/B key to check whether it goes away with Mobium off, and please report it, ideally with a
screenshot taken with Mobium on and one with it off.

**Does Mobium work with Vulkan?**

Yes, on OpenGL and on Minecraft's Vulkan renderer alike. Two things differ on Vulkan:

- The GPU part transform needs a graphics chip with 64-bit floats in shaders. Nvidia's and AMD's have them, and so do
  Intel's newest (Arc B-series, Lunar Lake's Arc 130V and 140V). Other recent Intel graphics (Iris Xe, the Arc
  A-series, Arc 130T and 140T), Snapdragon X and Macs do not: there only the static cache runs, and the game writes
  the vertices of mobs and items itself.
- Shader packs: Iris does not run on Vulkan yet. Vitrail, a shader pack mod for the Vulkan renderer, is not tested
  with Mobium yet, so Mobium stays off while Vitrail is installed.

On Vulkan Mobium is tested on one graphics chip so far, an Intel Arc 140V on Linux. F3 `mobium:modules` shows what
runs on yours.

---

## 💬 Support

If you need help with any of my mods, join my [Discord server](https://nyon.dev/discord), or open an issue on
[GitHub](https://github.com/btwonion/mobium/issues).
