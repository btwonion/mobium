#!/usr/bin/env python3
"""Runs a benchmark round unattended in the Prism instances "Mobium Bench A–D" (docs/benchmarks.md).

    python3 benchmark/bench_round.py              # setups A, B, C, D in turn, about 2.5 hours
    python3 benchmark/bench_round.py A D          # some setups only
    python3 benchmark/bench_round.py --dry-run    # checks and plans, launches nothing
    python3 benchmark/bench_round.py --quick A    # smoke test: one short /mobium stages, results in build/

For each setup it writes the setup's plan (PLANS) to the instance's mobium-bench/autorun, starts the instance through
Prism's command line straight into the "Mobium Bench" world, and waits until Mobium has run every command and closed
the game (bench/BenchAutorun.kt). Mobium puts the fullscreen window on the monitor whose name contains --display at
1920×1080. Before anything starts it checks mains power, an external monitor, that every instance has the bench kit's
Mobium jar (python3 benchmark/prism_instances.py installs it) and that none of them is running. Afterwards it copies
this round's files into docs/benchmarks/results/<yyyy-mm-dd>-<machine>/<setup>/ (per-run CSVs gzipped, summary.csv
with this round's rows) and prints the medians. Nobody may use the computer while it runs.
"""

import argparse
import gzip
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prism_instances  # noqa: E402

ROOT = prism_instances.ROOT
WORLD = "Mobium Bench"
PRISM = ["flatpak", "run", "org.prismlauncher.PrismLauncher"]

# Per setup: the client commands of the round, in order (docs/benchmarks.md "Tools"), and their length in minutes.
PLANS = {
    "A": ["mobium suite", "mobium gatec", "mobium stages", "mobium stages 60 3 display_build"],
    "B": ["mobium suite", "mobium stages"],
    "C": ["mobium suite", "mobium gatec", "mobium stages"],
    "D": ["mobium suite", "mobium gatec", "mobium stages"],
}
QUICK_PLAN = ["mobium stages 5 1 display_build"]
MINUTES = {"mobium suite": 26, "mobium gatec": 8, "mobium stages": 8, "mobium stages 60 3 display_build": 8,
           QUICK_PLAN[0]: 2}
START_MINUTES = 5


def setups_by_letter() -> dict[str, tuple[str, str]]:
    """Setup letter -> (instance folder, instance name) from prism_instances.SETUPS."""
    return {kit[0]: (folder, name) for kit, (folder, name, *_rest) in prism_instances.SETUPS.items()}


def on_mains() -> bool:
    supplies = Path("/sys/class/power_supply")
    for supply in supplies.glob("*"):
        kind = (supply / "type").read_text().strip() if (supply / "type").exists() else ""
        if kind == "Mains" and (supply / "online").exists() and (supply / "online").read_text().strip() == "1":
            return True
    return not any(supplies.glob("*"))


def external_monitor() -> bool:
    for connector in Path("/sys/class/drm").glob("card*-*"):
        name = connector.name.split("-", 1)[1]
        if not name.startswith(("eDP", "LVDS", "DSI")) and (connector / "status").exists():
            if (connector / "status").read_text().strip() == "connected":
                return True
    return False


def installed_build(mods: Path) -> str | None:
    """The commit the Mobium jar in [mods] was built from."""
    for jar in sorted(mods.glob("mobium-*.jar")):
        return prism_instances.jar_build(jar)
    return None


def running_games(game: Path) -> list[int]:
    """Processes whose command line names [game]'s instance (Prism starts Java with paths inside it)."""
    instance = str(game.parent)
    pids = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        try:
            command = (entry / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
        except OSError:
            continue
        if "java" in command and instance in command:
            pids.append(int(entry.name))
    return pids


def launch(folder: str) -> None:
    subprocess.Popen(
        [*PRISM, "--launch", folder, "--world", WORLD],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True,
    )


def run_setup(letter: str, game: Path, folder: str, display: str) -> bool:
    """Runs one setup's plan; true when Mobium reported it done."""
    bench = game / "mobium-bench"
    bench.mkdir(exist_ok=True)
    done = bench / "autorun-done"
    done.unlink(missing_ok=True)
    request = [f"world={WORLD}", f"display={display}", *[f"command={command}" for command in PLANS[letter]]]
    (bench / "autorun").write_text("\n".join(request) + "\n")
    minutes = START_MINUTES + sum(MINUTES.get(command, 10) for command in PLANS[letter])
    print(f"{letter}: starting {folder} ({', '.join(PLANS[letter])}; about {minutes - START_MINUTES} min)", flush=True)
    launch(folder)
    deadline = time.time() + minutes * 1.5 * 60
    started = False
    while time.time() < deadline:
        time.sleep(15)
        running = bool(running_games(game))
        started = started or running
        if done.exists() and not running:
            break
        if started and not running and not done.exists():
            print(f"{letter}: the game closed without finishing; see {game / 'logs/latest.log'}", flush=True)
            (bench / "autorun").unlink(missing_ok=True)
            return False
    else:
        print(f"{letter}: no result after {minutes * 1.5:.0f} min; stopping the round", flush=True)
        return False
    for line in done.read_text().splitlines():
        print(f"  {line}")
    return True


def collect(letter: str, game: Path, since: datetime, target: Path) -> list[Path]:
    """Copies the files this round wrote into target/<letter>/; returns the plan files (suite, gates, stages)."""
    bench = game / "mobium-bench"
    out = target / letter
    out.mkdir(parents=True, exist_ok=True)
    plans = []
    stamp = since.strftime("%Y%m%d-%H%M%S")
    for file in sorted(bench.glob("*.csv")):
        if file.name.startswith("summary") or datetime.fromtimestamp(file.stat().st_mtime) < since:
            continue
        if file.name.split("-", 1)[0] in ("suite", "gatea", "gateb", "gatec", "stages"):
            shutil.copy2(file, out / file.name)
            plans.append(out / file.name)
        else:
            with file.open("rb") as source, gzip.open(out / (file.name + ".gz"), "wb") as packed:
                shutil.copyfileobj(source, packed)
    summary = bench / "summary.csv"
    if summary.exists():
        lines = summary.read_text().splitlines()
        rows = [line for line in lines[1:] if line.split(",", 1)[0] >= stamp]
        (out / "summary.csv").write_text("\n".join([lines[0], *rows]) + "\n")
    return plans


def print_medians(plan: Path) -> None:
    lines = plan.read_text().splitlines()
    header = lines[0].split(",")
    columns = [header.index(name) for name in ("scene", "setup", "runs", "avg_ms", "prepare_ms", "shadow_prepare_ms")]
    print(f"  {plan.name}")
    for line in lines[1:]:
        if line.startswith("#"):
            print(f"    {line[2:]}")
        else:
            fields = line.split(",")
            print("    " + "  ".join(fields[i] for i in columns))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("setups", nargs="*", default=list(PLANS), help="setup letters (default: A B C D)")
    parser.add_argument("--display", default="AOC", help="part of the benchmark monitor's name (default: AOC)")
    parser.add_argument("--machine", default="thinkpad-x9", help="machine name for the results folder")
    parser.add_argument("--prism", type=Path, help="Prism data directory (default: flatpak or ~/.local/share)")
    parser.add_argument("--kit", type=Path, default=ROOT / "versions/26.3-fabric/build/bench-kit")
    parser.add_argument("--dry-run", action="store_true", help="only the checks and the plans")
    parser.add_argument("--quick", action="store_true",
                        help=f"smoke test: only '{QUICK_PLAN[0]}' per setup, results in versions/26.3-fabric/build/")
    args = parser.parse_args()

    letters = [setup.upper() for setup in args.setups]
    if args.quick:
        for letter in PLANS:
            PLANS[letter] = QUICK_PLAN
    unknown = [letter for letter in letters if letter not in PLANS]
    if unknown:
        parser.error(f"unknown setups {unknown}; use {' '.join(PLANS)}")
    instances = (args.prism or prism_instances.default_prism_dir()) / "instances"
    found = setups_by_letter()
    kit = prism_instances.kit_build(args.kit) if args.kit.is_dir() else None

    problems = []
    if not on_mains():
        problems.append("the laptop is on battery; plug in the power supply")
    if not external_monitor():
        problems.append("no external monitor is connected")
    games = {}
    for letter in letters:
        folder, name = found[letter]
        instance = prism_instances.find_instance(instances, folder, name)
        game = instance / "minecraft"
        games[letter] = (instance.name, game)
        built = installed_build(game / "mods")
        if kit is None or built != kit:
            problems.append(f"{letter} has Mobium {built or 'missing'}, the kit {kit or 'missing'}: run "
                            "./gradlew :26.3-fabric:benchKit && python3 benchmark/prism_instances.py --no-download")
        if not (game / "saves" / WORLD).is_dir():
            problems.append(f"{letter} has no world '{WORLD}'")
        if running_games(game):
            problems.append(f"{letter} is running; close it first")
    total = sum(START_MINUTES + sum(MINUTES.get(command, 10) for command in PLANS[letter]) for letter in letters)
    print(f"Round {' '.join(letters)} with Mobium {kit}, about {total // 60} h {total % 60} min; monitor '{args.display}'")
    for letter in letters:
        print(f"  {letter} ({games[letter][0]}): {', '.join(PLANS[letter])}")
    if problems:
        raise SystemExit("Not started:\n  " + "\n  ".join(problems))
    if args.dry_run:
        return

    since = datetime.now()
    results = ROOT / ("versions/26.3-fabric/build/bench-round-quick" if args.quick else "docs/benchmarks/results")
    target = results / f"{since:%Y-%m-%d}-{args.machine}"
    finished = []
    for letter in letters:
        folder, game = games[letter]
        started = datetime.now()
        ok = run_setup(letter, game, folder, args.display)
        plans = collect(letter, game, started, target)
        for plan in plans:
            print_medians(plan)
        if not ok:
            break
        finished.append(letter)
        time.sleep(30)
    print(f"Finished {' '.join(finished) or 'nothing'}; results in {target.relative_to(ROOT)}")
    sys.exit(0 if len(finished) == len(letters) else 1)


if __name__ == "__main__":
    main()
