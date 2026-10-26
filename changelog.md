- new: **GPU part transform** (Module 2), on by default where OpenGL 4.3 compute shaders are available. Mobs, armour,
  saddles, banners, bells, lecterns and other models built from model parts keep their normal animations (including
  Entity Model Features and Fresh Animations), but the GPU writes their vertices instead of the CPU, exactly as the
  game would have written them, also in Iris's shader pack formats and its shadow pass. In 500 walking mobs (dev
  laptop, Intel Arc 140V) the frame got about 1.2× faster with vanilla models and with Fresh Animations, and the
  entities' CPU work with Fresh Animations and Complementary Reimagined dropped to less than half
- the GPU part transform has its own switch in the settings; `parts.excludedEntities` in `config/mobium.json` keeps
  chosen entity types (for example `"minecraft:zombie"`) on the game's own path
- `/mobium gatec [seconds] [runs]` measures the live mob pens with the GPU part transform off and on; benchmark setup
  names get `+parts` while it runs
- license: Mobium is now licensed under the GNU General Public License v3.0 instead of the LGPL-3.0
