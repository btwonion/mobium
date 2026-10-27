- new: **GPU part transform** (Module 2), on by default where OpenGL 4.3 compute shaders are available. Mobs, armour,
  saddles, banners, bells, lecterns and other models built from model parts keep their normal animations (including
  Entity Model Features and Fresh Animations), but the GPU writes their vertices instead of the CPU, exactly as the
  game would have written them, also in Iris's shader pack formats and its shadow pass. In 500 walking mobs
  (1920×1080, Intel Arc 140V laptop GPU) the frame got 1.25× faster with Fresh Animations; with Fresh Animations and
  Complementary Reimagined the entities' CPU work halved and the frame got 1.18× faster
- known limit: on integrated graphics without a shader pack the GPU part transform can cost more GPU time than it saves
  on the CPU, about 0.3–0.4 ms per frame in the benchmark's mob scenes with vanilla models and 0.2–0.3 ms with only a
  few animated models on screen. That shows only at a few hundred FPS; its switch in the settings turns it off
- the GPU part transform has its own switch in the settings; `parts.excludedEntities` in `config/mobium.json` keeps
  chosen entity types (for example `"minecraft:zombie"`) on the game's own path
- `/mobium gatec [seconds] [runs]` measures the live mob pens with the GPU part transform off and on; benchmark setup
  names get `+parts` while it runs
- license: Mobium is now licensed under the GNU General Public License v3.0 instead of the LGPL-3.0
