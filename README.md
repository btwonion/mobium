# Mobium

**Faster chests, signs and mobs: Mobium draws things that stand still from GPU buffers and lets the GPU write animated
models' vertices, instead of the CPU rebuilding them every frame.**

Mobium is a client-side performance mod for Minecraft (Fabric, Quilt and NeoForge). Vanilla, Iris and Entity Model
Features still decide how everything looks: Mobium changes how geometry reaches the GPU, never what ends up on screen.

> **Beta.** If anything looks different with Mobium than without it, please report it, ideally with a screenshot taken
> with Mobium on and one with it off.

---

## ⚡ Performance

| Test scene | Without Mobium | With Mobium | Gain |
| --- | ---: | ---: | ---: |
| 1,000 chests and 300 signs | 300 FPS | 586 FPS | **+95 %** |
| 500 mobs | 282 FPS | 335 FPS | **+19 %** |
| 500 mobs with Fresh Animations | 101 FPS | 137 FPS | **+36 %** |
| 500 mobs with Fresh Animations and Complementary Reimagined | 15 FPS | 18 FPS | **+20 %** |

Average FPS on a laptop with Intel Arc 140V graphics, Sodium, 1920×1080. The more chests, signs, item frames and mobs
are on screen, the more Mobium gains; an empty field gets neither faster nor slower.

### 📊 Benchmarks wanted

These numbers come from one laptop. To find out how Mobium does on other hardware, we need your results, and a run
takes two minutes:

1. Set Max Framerate to Unlimited and turn VSync off, then stand still somewhere with lots of chests, signs or mobs.
2. Type `/mobium bench test 30` and wait until the result shows in chat.
3. Press the A/B key to switch Mobium off (the key right of <kbd>P</kbd>: <kbd>]</kbd> on a US keyboard,
   <kbd>+</kbd> on a German one), then type `/mobium bench test 30` again.
4. Send us `mobium-bench/summary.csv` from your game folder: drop it in our
   [Discord server](https://nyon.dev/discord) or attach it to a
   [benchmark report](https://github.com/btwonion/mobium/issues/new?template=benchmark.yml) on GitHub.

The file already names your GPU, mods and shader pack and whether Mobium was on; your CPU and a screenshot of the
spot are all we still need.

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
shader on the GPU writes exactly the same vertices, also in Iris's shader pack formats. Mobium checks this on your GPU
and lets the game draw as usual should a graphics driver ever disagree.

The gain is largest where writing vertices is expensive: many mobs, Fresh Animations or a shader pack. With only a few
mobs on screen it can cost slightly more than it saves, which shows only at several hundred FPS. It needs OpenGL 4.3
(current Windows and Linux drivers; not macOS).

### Coming later

- **Animation LOD (optional):** far-away mobs reuse last frame's pose.
- **Vulkan** support.

---

## 📖 Usage

Mobium works out of the box.

- **Settings:** Mod Menu (Fabric), the mods list (NeoForge) or Mobium's page in Sodium's video settings, with a switch
  for every cached type.
- **A/B key:** switches Mobium off and on live, to compare in the same spot. It is the key right of <kbd>P</kbd>:
  <kbd>]</kbd> on a US keyboard, <kbd>+</kbd> on a German one.
- **F3:** the debug entries `mobium:modules` and `mobium:timings` show what Mobium does and where the frame time goes.

If something goes wrong inside Mobium, it switches the affected part off, shows one message and lets the game draw as
usual.

---

## 🧩 Compatibility

| Mod | Status |
| --- | --- |
| Sodium | Works; Mobium adds a page to Sodium's video settings |
| Iris with shader packs | Works, shadow pass included; tested with Complementary Reimagined |
| Entity Model Features / Entity Texture Features, Fresh Animations | Works |
| Mod Menu | Opens Mobium's settings |
| Enhanced Block Entities, Vanillate, Accelerated Rendering | These do the same job; Mobium switches off its overlapping part |

---

## ❓ FAQ

**Do I need to install Mobium on the server?**

No. Mobium is client-side only and works in singleplayer and on any server.

**Does Mobium change how things look?**

It must not. Automated test scenes compare Mobium's picture pixel by pixel with the game's own. If you see a
difference, press the A/B key to check whether it goes away with Mobium off, and please report it.

**Does Mobium work with Vulkan?**

Not yet. Mobium needs OpenGL, the game's default renderer; on Vulkan it stays off and the game draws everything itself.
Vulkan support is planned.

---

## 💬 Support

If you need help with any of my mods, join my [Discord server](https://nyon.dev/discord), or open an issue on
[GitHub](https://github.com/btwonion/mobium/issues).
