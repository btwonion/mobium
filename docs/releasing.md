# Releasing

Releases run from the manually dispatched `release.yml` workflow (`./gradlew releaseMod`), only after the matching gate
in `PLAN.md` has passed; the maintainer runs it, never Claude Code. They go to Modrinth `mobium` (`8i5oedw8`) and
CurseForge `mobium-perf` (`1759818`); publishing and the Discord post read `changelog.md`.

The next version follows the latest release (`gh release list`): a new module is a new minor version, betas count up
`mod.beta`, `mod.beta=0` publishes as stable.

## Checklist

Copy it into the release's PR and tick it there.

- [ ] The version in `gradle.properties` (`mod.major-version`, `mod.beta`): the next after the latest release.
- [ ] The gate passed, results in `docs/benchmarks.md`, raw data in `btwonion/mobium-benchmarks`.
- [ ] `changelog.md` holds only this release's notes.
- [ ] `docs/compat.md` updated; `README.md` describes the release. README changes merge right before `release.yml`:
      `readme-modrinth-sync.yml` publishes every README change on `master` to Modrinth at once.
- [ ] Self-check on both loaders (regular, `--mode parts`, `--mode benchmark`) and the gametests; 0 changed pixels,
      0 differing vertices.
- [ ] A benchmark round A–D (`python3 benchmark/bench_round.py`) if rendering changed since the last round.
- [ ] The maintainer's visual checks of the release's rendering PRs (*How to verify*).
- [ ] The maintainer's go, then `release.yml` on `master`.
- [ ] Afterwards a PR that empties `changelog.md` and updates `PLAN.md`'s *Current state*.
