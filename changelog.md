- the GPU part transform costs the GPU less without a shader pack: on Intel's Linux driver (Mesa) its compute pass
  split every frame into three GPU submissions that wait for each other, a fixed cost however few models it draws.
  Where the GPU allows it, the pass now runs as a vertex shader inside the frame's normal submission: on an Intel Arc
  140V 0.03 instead of 0.24 ms of GPU time per frame in a scene with only a few animated models, and frames 2–7 %
  faster than with 0.2.0-beta1's compute pass at 1920×1080. With a shader pack it stays a compute pass as in
  0.2.0-beta1 (there both are equally fast at 1920×1080, and the compute pass is faster in a smaller window). Both
  write exactly the same vertices; F3 `mobium:modules` shows which one runs, and `-Dmobium.parts.stage=compute` or
  `vertex` in the Java arguments picks one
- `/mobium stages [seconds] [runs] [scene]` compares the two ways of running the GPU part transform in one session;
  benchmark setup names get `+parts-vertex` while it runs as a vertex shader
- F3 `mobium:modules` and `mobium:timings` keep their lines together in one column, and the static cache's long line
  is split into three, so they no longer overlap the other column
