#!/usr/bin/env python3
"""Runs Mobium's in-game self-check (dev environments only) on one or both loaders and prints the report.

    python3 benchmark/self_check.py              # Fabric, then NeoForge
    python3 benchmark/self_check.py neoforge     # one loader
    python3 benchmark/self_check.py fabric --mode gallery    # only the cache_gallery checks, for quick iterations
    python3 benchmark/self_check.py neoforge --mode iris-without-mobium   # shader pack with Mobium off (crash triage)
    python3 benchmark/self_check.py fabric --mode profile    # storage_room under JFR -> mobium-selfcheck/*.jfr
    python3 benchmark/self_check.py fabric --mode profile-mobs   # mob_pen_live without packs, setups C and D under JFR
    python3 benchmark/self_check.py fabric --mode profile-mobs --scene storage_room   # another scene, cache off
    python3 benchmark/self_check.py fabric --mode parts      # Module 2: GPU vertices against the CPU's, pictures
    python3 benchmark/self_check.py fabric --mode expand-cost   # Module 2's fixed cost per frame in display_build
    python3 benchmark/self_check.py --mode benchmark   # /mobium benchmark in a world made with the built-in pack
    python3 benchmark/self_check.py fabric --api vulkan   # on Vulkan, with Khronos' validation layer
    python3 benchmark/self_check.py fabric --api vulkan --from-options   # Vulkan chosen in options.txt, Iris installed
    python3 benchmark/self_check.py fabric --api vulkan --vitrail --mode parts   # with Vitrail; packs load through it

The self-check walks through the current PR's "How to verify" inside the dev client (settings opened the loader's way,
both languages, Sodium's page, and in the benchmark world's storage_room the A/B key, F3 and a forced module failure,
with pixel comparisons against vanilla), saves screenshots and report.txt to <game dir>/mobium-selfcheck/ and closes
the game. A game window opens while it runs; leave it alone. Needs the benchmark world from
./gradlew :26.3-fabric:runBenchWorld. Exit code 0 when every check passed.

--api picks the graphics API (Gradle property mobium.graphicsBackend); the self-check fails when the game runs on the
other one. On Vulkan the checking modes also enable the validation layer (mobium.vulkanValidation; Fedora package
vulkan-validation-layers) and fail on any of its messages that come from Mobium (validation.txt lists them all); the
measuring modes run without it, as does --no-validation.

--from-options chooses the API the way a player does, in options.txt (preferredGraphicsBackend) instead of by launch
argument, and keeps Iris on the dev classpath on Vulkan too: Iris 1.11.7 reads the option before the game starts and,
on Vulkan, switches its own mixins off (docs/vanilla-notes.md §21). The option's old value is put back afterwards.
"""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORLD = ROOT / "versions/26.3-fabric/build/bench-world/Mobium Bench"
PACK = ROOT / "benchmark/mobium-bench"
LOADERS = {
    "fabric": (":26.3-fabric:runClient", ROOT / "versions/26.3-fabric/run"),
    "neoforge": (":26.3-neoforge:runMainClient", ROOT / "run"),
}
TIMEOUT_SECONDS = 20 * 60
# Modes that measure time: the validation layer would distort them.
MEASURING = {"profile", "profile-mobs", "expand-cost", "gateb"}


def set_options(options: Path, values: dict[str, str]) -> dict[str, str | None]:
    """Sets keys in options.txt; returns their previous values (None: the key was missing)."""
    lines = options.read_text().splitlines() if options.exists() else []
    previous: dict[str, str | None] = {key: None for key in values}
    for i, line in enumerate(lines):
        key, _, value = line.partition(":")
        if key in values:
            previous[key] = value
            lines[i] = f"{key}:{values[key]}"
    lines += [f"{key}:{value}" for key, value in values.items() if previous[key] is None]
    options.write_text("\n".join(lines) + "\n")
    return previous


def run(loader: str, mode: str, api: str, validation: bool, from_options: bool, extra: list[str]) -> bool:
    task, game = LOADERS[loader]
    saves = game / "saves"
    saves.mkdir(parents=True, exist_ok=True)
    if not (saves / WORLD.name).exists():
        if not WORLD.is_dir():
            raise SystemExit(f"{WORLD} not found; run ./gradlew :26.3-fabric:runBenchWorld first")
        shutil.copytree(WORLD, saves / WORLD.name, ignore=shutil.ignore_patterns("session.lock"))
    # The world keeps its own copy of the datapack; the scenes may have changed since it was made.
    pack = saves / WORLD.name / "datapacks" / PACK.name
    shutil.rmtree(pack, ignore_errors=True)
    shutil.copytree(PACK, pack)
    directory = game / "mobium-selfcheck"
    shutil.rmtree(directory, ignore_errors=True)
    directory.mkdir(parents=True)
    (directory / "request").write_text(mode)

    properties = [f"-Pmobium.graphicsBackend={api}"] + (["-Pmobium.vulkanValidation=true"] if validation else []) + extra
    on = f"{api}{' with validation' if validation else ''}{' with Vitrail' if extra else ''}"
    restore: dict[str, str | None] = {}
    if from_options:
        properties.append("-Pmobium.graphicsBackendFrom=options")
        on += " from options.txt"
        # The game turns the option back to default or opengl after a start that did not end cleanly (§21).
        previous = set_options(game / "options.txt", {"preferredGraphicsBackend": f'"{api}"', "startedCleanly": "true"})
        restore = {"preferredGraphicsBackend": previous["preferredGraphicsBackend"] or '"default"'}
    print(f"{loader}: running {task} on {on} (a game window opens and closes by itself)", flush=True)
    try:
        subprocess.run([str(ROOT / "gradlew"), task, "-q", *properties], cwd=ROOT, timeout=TIMEOUT_SECONDS,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        print(f"{loader}: no result after {TIMEOUT_SECONDS // 60} min")
        return False
    finally:
        # Left at vulkan, the option would switch Iris's mixins off in later runs on OpenGL too.
        if restore:
            set_options(game / "options.txt", restore)
    report = directory / "report.txt"
    if not report.exists():
        print(f"{loader}: no report; see {game / 'logs/latest.log'}")
        return False
    lines = report.read_text().splitlines()
    for line in lines:
        if not line.startswith("PASS"):
            print(f"  {line}")
    print(f"  screenshots: {directory / 'screenshots'}")
    if (directory / "validation.txt").exists():
        print(f"  validation messages: {directory / 'validation.txt'}")
    return lines[-1].startswith("ALL")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("loaders", nargs="*", help=f"any of {', '.join(LOADERS)} (default: all)")
    parser.add_argument("--mode", default="", choices=["", "gallery", "iris-without-mobium", "profile", "profile-mobs", "parts", "gateb", "expand-cost",
                                                     "benchmark"],
                        help="gallery: only the cache_gallery checks (quicker); "
                             "iris-without-mobium: only the first shader pack in shaderpacks/, with Mobium off; "
                             "profile: storage_room under Java Flight Recorder, with the cache and without Mobium; "
                             "profile-mobs: mob_pen_live under Java Flight Recorder without packs, with Fresh Animations (setup C), "
                             "then also with the first shader pack (setup D); "
                             "parts: Module 2 in mob_pen_live, GPU vertices against the CPU's and pictures against vanilla, "
                             "with vanilla models, Fresh Animations and the first shader pack; "
                             "expand-cost: Module 2 off, with its compute dispatch and with its vertex-stage variant in display_build "
                             "(--scene mob_pen_live: setups A, C, D; --scene \"mob_pen_live stages\": only the two stages, setups A to D; "
                             "--scene \"display_build items\": Module 2 off and on with its items off and on), "
                             "CPU time per step of the expand pass; "
                             "gateb: /mobium gateb 20 2 in the dev client's window plus display_build and map_wall with the cache off "
                             "and on (a hint, not the protocol); "
                             "benchmark: the players' /mobium benchmark with 5-second runs, in a Superflat world made through "
                             "Create New World with the built-in Mobium Benchmark pack, plus /mobium stop, leaving the world, "
                             "the refusal without the pack and the pack staying off elsewhere")
    parser.add_argument("--api", default="opengl", choices=["opengl", "vulkan"],
                        help="graphics API of the dev client (default: opengl)")
    parser.add_argument("--from-options", action="store_true",
                        help="choose the API in options.txt instead of by launch argument; on Vulkan Iris stays installed")
    parser.add_argument("--no-validation", action="store_true",
                        help="vulkan: without the validation layer (the measuring modes never use it)")
    parser.add_argument("--vitrail", action="store_true",
                        help="vulkan: with Vitrail on the dev classpath (mobium.vitrail); the shader pack steps then load "
                             "the pack through it")
    parser.add_argument("--vitrail-cache", action="store_true",
                        help="with --vitrail: the static cache runs beside Vitrail too (mobium.vitrailCache, dev clients only)")
    parser.add_argument("--scene", default="", help="profile-mobs: another benchmark scene than mob_pen_live "
                        "(vanilla models only, static cache off); expand-cost: another scene than display_build")
    args = parser.parse_args()
    unknown = [loader for loader in args.loaders if loader not in LOADERS]
    if unknown:
        parser.error(f"unknown loader {', '.join(unknown)}; use {' or '.join(LOADERS)}")
    mode = f"{args.mode} {args.scene}" if args.scene else args.mode
    validation = args.api == "vulkan" and not args.no_validation and args.mode not in MEASURING
    if args.vitrail and args.api != "vulkan":
        parser.error("--vitrail needs --api vulkan: Vitrail draws on Vulkan only")
    if args.vitrail_cache and not args.vitrail:
        parser.error("--vitrail-cache needs --vitrail")
    extra = (["-Pmobium.vitrail=true"] if args.vitrail else []) + (["-Pmobium.vitrailCache=true"] if args.vitrail_cache else [])
    results = [run(loader, mode, args.api, validation, args.from_options, extra)
               for loader in (args.loaders or list(LOADERS))]
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
