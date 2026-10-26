#!/usr/bin/env python3
"""Runs Mobium's in-game self-check (dev environments only) on one or both loaders and prints the report.

    python3 scripts/self_check.py              # Fabric, then NeoForge
    python3 scripts/self_check.py neoforge     # one loader
    python3 scripts/self_check.py fabric --mode gallery    # only the cache_gallery checks, for quick iterations
    python3 scripts/self_check.py neoforge --mode iris-without-mobium   # shader pack with Mobium off (crash triage)
    python3 scripts/self_check.py fabric --mode profile    # storage_room under JFR -> mobium-selfcheck/*.jfr
    python3 scripts/self_check.py fabric --mode profile-mobs   # mob_pen_live without packs, in setups C and D under JFR
    python3 scripts/self_check.py fabric --mode profile-mobs --scene storage_room   # another scene, cache off
    python3 scripts/self_check.py fabric --mode parts      # Module 2: GPU vertices against the CPU's, pictures

The self-check walks through the current PR's "How to verify" inside the dev client (settings opened the loader's way,
both languages, Sodium's page, and in the benchmark world's storage_room the A/B key, F3 and a forced module failure,
with pixel comparisons against vanilla), saves screenshots and report.txt to <game dir>/mobium-selfcheck/ and closes
the game. A game window opens while it runs; leave it alone. Needs the benchmark world from
./gradlew :26.3-fabric:runBenchWorld. Exit code 0 when every check passed.
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


def run(loader: str, mode: str) -> bool:
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

    print(f"{loader}: running {task} (a game window opens and closes by itself)", flush=True)
    try:
        subprocess.run([str(ROOT / "gradlew"), task, "-q"], cwd=ROOT, timeout=TIMEOUT_SECONDS,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        print(f"{loader}: no result after {TIMEOUT_SECONDS // 60} min")
        return False
    report = directory / "report.txt"
    if not report.exists():
        print(f"{loader}: no report; see {game / 'logs/latest.log'}")
        return False
    lines = report.read_text().splitlines()
    for line in lines:
        if not line.startswith("PASS"):
            print(f"  {line}")
    print(f"  screenshots: {directory / 'screenshots'}")
    return lines[-1].startswith("ALL")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("loaders", nargs="*", help=f"any of {', '.join(LOADERS)} (default: all)")
    parser.add_argument("--mode", default="", choices=["", "gallery", "iris-without-mobium", "profile", "profile-mobs", "parts", "gateb"],
                        help="gallery: only the cache_gallery checks (quicker); "
                             "iris-without-mobium: only the first shader pack in shaderpacks/, with Mobium off; "
                             "profile: storage_room under Java Flight Recorder, with the cache and without Mobium; "
                             "profile-mobs: mob_pen_live under Java Flight Recorder without packs, with Fresh Animations (setup C), "
                             "then also with the first shader pack (setup D); "
                             "parts: Module 2 in mob_pen_live, GPU vertices against the CPU's and pictures against vanilla, "
                             "with vanilla models, Fresh Animations and the first shader pack; "
                             "gateb: /mobium gateb 20 2 in the dev client's window plus display_build and map_wall with the cache off "
                             "and on (a hint, not the protocol)")
    parser.add_argument("--scene", default="", help="profile-mobs only: another benchmark scene than mob_pen_live "
                        "(vanilla models only, static cache off)")
    args = parser.parse_args()
    unknown = [loader for loader in args.loaders if loader not in LOADERS]
    if unknown:
        parser.error(f"unknown loader {', '.join(unknown)}; use {' or '.join(LOADERS)}")
    mode = f"{args.mode} {args.scene}" if args.scene else args.mode
    results = [run(loader, mode) for loader in (args.loaders or list(LOADERS))]
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
