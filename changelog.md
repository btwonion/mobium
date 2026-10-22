- first beta: the **static cache** draws objects that stand still from GPU buffers instead of rebuilding them every
  frame
  - chests (also trapped, copper and ender chests) and shulker boxes while closed, signs and hanging signs including
    their text, skulls and heads, decorated pots, shelves and their items
  - item frames (not with maps), armor stands without armor, and item and block display entities that do not move
  - objects are captured through the game's own rendering code, so resource packs, Iris shader packs and Entity Model
    Features / Fresh Animations are captured as they draw; a change captures the object again
  - in a test room with 1,000 chests and 300 signs the frame time roughly halves
- every cached type has its own switch, plus a GPU memory budget (default 128 MB); settings in Mod Menu, the NeoForge
  mods list and Mobium's page in Sodium's video settings
- the A/B key (right of P: `]` on US, `+` on German keyboards) switches Mobium off and on live for comparisons
- F3 debug entries `mobium:modules` and `mobium:timings`; `/mobium bench` records frame times
- if something fails inside Mobium, the affected part switches off for the session and the game draws everything as
  usual
