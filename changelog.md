- static cache: a light update somewhere in the world no longer sends every cached object through its full check;
  only objects in the sections whose light changed read their light again. In normal play, where chunks load and
  torches are placed all the time, cached objects now stay on the fast path
- static cache: it forgets light updates that every cached object has seen, instead of keeping one entry for every
  section that ever had one until the world is left
- only the entities the cache can draw (item frames, armor stands, display entities) are linked to their render state,
  and never in Iris's shadow pass, so no other render state keeps an entity in memory
- `/mobium bench`, the suites and the F3 entry `mobium:timings` also time Iris's shadow pass (new `shadow_*` columns);
  `draw` no longer counts the shadow pass's draws
- bug reports: a form on GitHub asks for what is needed (A/B key result, loader, mods, GPU, the `mobium:modules` lines)
- license: Mobium is now licensed under the GNU General Public License v3.0 instead of the LGPL-3.0
