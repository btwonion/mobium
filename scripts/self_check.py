#!/usr/bin/env python3
"""Runs Mobium's in-game self-check (dev environments only) on one or both loaders and prints the report.

    python3 scripts/self_check.py              # Fabric, then NeoForge
    python3 scripts/self_check.py neoforge     # one loader

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
LOADERS = {
    "fabric": (":26.3-fabric:runClient", ROOT / "versions/26.3-fabric/run"),
    "neoforge": (":26.3-neoforge:runMainClient", ROOT / "run"),
}
TIMEOUT_SECONDS = 20 * 60


def run(loader: str) -> bool:
    task, game = LOADERS[loader]
    saves = game / "saves"
    saves.mkdir(parents=True, exist_ok=True)
    if not (saves / WORLD.name).exists():
        if not WORLD.is_dir():
            raise SystemExit(f"{WORLD} not found; run ./gradlew :26.3-fabric:runBenchWorld first")
        shutil.copytree(WORLD, saves / WORLD.name, ignore=shutil.ignore_patterns("session.lock"))
    directory = game / "mobium-selfcheck"
    shutil.rmtree(directory, ignore_errors=True)
    directory.mkdir(parents=True)
    (directory / "request").touch()

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
    args = parser.parse_args()
    unknown = [loader for loader in args.loaders if loader not in LOADERS]
    if unknown:
        parser.error(f"unknown loader {', '.join(unknown)}; use {' or '.join(LOADERS)}")
    results = [run(loader) for loader in (args.loaders or list(LOADERS))]
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
