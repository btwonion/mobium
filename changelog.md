- the GPU part transform costs the GPU less without a shader pack: on Intel's Linux driver (Mesa) its compute pass
  split every frame into three GPU submissions that wait for each other, a fixed cost however few models it draws.
  Where the GPU allows it, the pass now runs as a vertex shader inside the frame's normal submission (in a scene with
  only a few animated models, 0.03 instead of 0.24 ms of GPU time per frame on an Intel Arc 140V). With a shader pack
  it stays as in 0.2.0-beta1, which is faster there. Both write exactly the same vertices; F3 `mobium:modules` shows
  which one runs, and `-Dmobium.parts.stage=compute` or `vertex` in the Java arguments picks one
- `/mobium stages [seconds] [runs] [scene]` compares the two ways of running the GPU part transform in one session;
  benchmark setup names get `+parts-vertex` while it runs as a vertex shader
- F3 `mobium:modules` and `mobium:timings` keep their lines together in one column, and the static cache's long line
  is split into three, so they no longer overlap the other column
