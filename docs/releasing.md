# Releasing

Releases run from the manually dispatched `release.yml` workflow (`./gradlew releaseMod`), only while the release bar
below holds; the maintainer runs it, never Claude Code. They go to Modrinth `mobium` (`8i5oedw8`) and CurseForge
`mobium-perf` (`1759818`); publishing and the Discord post read `changelog.md`, and `release.yml` also makes
`README.md` the Modrinth and CurseForge description. Between releases only the manually dispatched
`readme-modrinth-sync.yml` does that, so README changes can merge at any time.

The next version follows the latest release (`gh release list`): a new module is a new minor version, betas count up
`mod.beta`, `mod.beta=0` publishes as stable.

## Release bar

Every release, a hard stop: if any of this fails, stop, write up why in the PR and agree on the next step first.

- **Same picture.** The self-check on both loaders, on OpenGL and Vulkan (regular and `--mode offload`), and the
  gametests (CI, on OpenGL and on Vulkan with lavapipe): 0 changed pixels, 0 differing vertices, and on Vulkan no
  validation-layer message from Mobium.
- **Never slower.** `python3 benchmark/bench_full.py` (`docs/benchmarks.md`, *Running everything at once*): in its
  rounds on Vulkan (setups A and C) and OpenGL (A–D), every `/mobium compare` (`mob_pen_live`, `item_drops` and
  `control` with vertex offload, `control` with the static cache) has the frame with the module at most 1.03× the
  frame without it. The script prints the verdict at the end. Its numbers count for a later build too while rendering
  has not changed since.

## Checklist

Copy it into the release's PR and tick it there.

- [ ] The version in `gradle.properties` (`mod.major-version`, `mod.beta`): the next after the latest release.
- [ ] The release bar holds; its numbers in `docs/benchmarks.md`, raw data in `btwonion/mobium-benchmarks`.
- [ ] `changelog.md` holds only this release's notes.
- [ ] `docs/compat.md` updated; `README.md` describes the release.
- [ ] The self-check's `--mode benchmark` on both loaders (the players' `/mobium benchmark`).
- [ ] The maintainer's visual checks of the release's rendering PRs (*How to verify*).
- [ ] The maintainer's go, then `release.yml` on `master`.
- [ ] Afterwards a PR that empties `changelog.md` and updates `PLAN.md`'s *Current state*.
