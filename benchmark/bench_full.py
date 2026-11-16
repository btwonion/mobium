#!/usr/bin/env python3
"""Runs every benchmark the gate and the release need, in one go, unattended (docs/benchmarks.md).

    python3 benchmark/bench_full.py             # about 6 hours; nobody may use the computer meanwhile
    python3 benchmark/bench_full.py --dry-run   # the checks and the plans; builds and launches nothing

In order:
  1. ./gradlew :26.3-fabric:benchKit, and :26.3-fabric:runBenchWorld when the benchmark world is missing;
  2. python3 benchmark/prism_instances.py: the kit into the Prism instances "Mobium Bench A–D";
  3. the smoke test on Vulkan (bench_round.py --quick --api vulkan): a short run of each command of the Vulkan round,
     in setups A and C;
  4. the round on Vulkan, setups A and C (bench_round.py --api vulkan), skipped when the smoke test failed;
  5. the round on OpenGL, setups A–D (bench_round.py).
Each round pushes its data to btwonion/mobium-benchmarks when it ends. A failed step after the build does not stop the
next; the summary at the end names each step's outcome and what has to run again, then Gate D's benchmark numbers
from the Vulkan round. Before building it checks mains power and the external monitor, which every round needs.
"""

import argparse
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench_round  # noqa: E402

ROOT = bench_round.ROOT
BENCH = ROOT / "benchmark"
WORLD = ROOT / "versions/26.3-fabric/build/bench-world" / bench_round.WORLD
# Gate D (PLAN.md): with over without, at most this much, for each (setup, file kind, scene, CSV line).
GATE_D_LIMIT = 1.03
GATE_D = [
    ("A", "gatec", "mob_pen_live", "gate_c_frame_with_over_without", "mob_pen_live, Module 2"),
    ("C", "gatec", "mob_pen_live", "gate_c_frame_with_over_without", "mob_pen_live, Module 2"),
    ("A", "gatec", "control", "gate_c_frame_with_over_without", "control, Module 2"),
    ("C", "gatec", "control", "gate_c_frame_with_over_without", "control, Module 2"),
    ("A", "gateb", None, "gate_b_control_cache_over_vanilla", "control, static cache"),
]


def step(title: str, command: list[str]) -> int:
    print(f"\n=== {title}: {' '.join(command)}", flush=True)
    return subprocess.run(command, cwd=ROOT).returncode


def round_command(args: argparse.Namespace, *extra: str) -> list[str]:
    command = [sys.executable, str(BENCH / "bench_round.py"), *extra, "--display", args.display,
               "--machine", args.machine]
    if args.prism:
        command += ["--prism", str(args.prism)]
    if args.data:
        command += ["--data", str(args.data)]
    return command


def gate_file(folder: Path, kind: str, scene: str | None) -> Path | None:
    """The newest `kind` file in the round's setup folder, for `scene` (the first column of its rows)."""
    for file in sorted(folder.glob(f"{kind}-*.csv"), reverse=True):
        rows = [line for line in file.read_text().splitlines()[1:] if line and not line.startswith("#")]
        if scene is None or (rows and rows[0].split(",", 1)[0] == scene):
            return file
    return None


def gate_value(file: Path, key: str) -> float | None:
    for line in file.read_text().splitlines():
        if line.startswith(f"# {key},"):
            return float(line.split(",", 1)[1])
    return None


def gate_d(data: Path, since: float) -> list[str]:
    """Gate D's benchmark numbers from the Vulkan round folder this run pushed (the newest one since `since`)."""
    rounds = [folder for folder in data.glob("*-vulkan*") if folder.is_dir() and folder.stat().st_mtime >= since]
    if not rounds:
        return ["no Vulkan round from this run in " + str(data)]
    folder = max(rounds, key=lambda path: path.stat().st_mtime)
    lines = [f"from {folder.name} (with over without, at most {GATE_D_LIMIT}×):"]
    slow = missing = False
    for letter, kind, scene, key, label in GATE_D:
        file = gate_file(folder / letter, kind, scene)
        value = gate_value(file, key) if file else None
        if value is None:
            missing = True
            lines.append(f"  {letter} {label}: missing")
        else:
            slow = slow or value > GATE_D_LIMIT
            lines.append(f"  {letter} {label}: {value:.3f} {'ok' if value <= GATE_D_LIMIT else 'TOO SLOW'} "
                         f"({file.name})")
    verdict = "NOT passed" if slow else "incomplete: run the Vulkan round again" if missing else "passed"
    lines.append(f"Gate D's benchmark part: {verdict} (its pixel and vertex checks are the self-check's and the "
                 "gametests')")
    return lines


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--display", default="AOC", help="part of the benchmark monitor's name (default: AOC)")
    parser.add_argument("--machine", default="thinkpad-x9", help="machine name for the results folders")
    parser.add_argument("--prism", type=Path, help="Prism data directory (default: flatpak or ~/.local/share)")
    parser.add_argument("--data", type=Path,
                        help="checkout of mobium-benchmarks (default: next to Mobium's checkout, cloned when missing)")
    parser.add_argument("--dry-run", action="store_true", help="only the checks and the plans")
    args = parser.parse_args()

    problems = []
    if not bench_round.on_mains():
        problems.append("the laptop is on battery; plug in the power supply")
    if not bench_round.external_monitor():
        problems.append("no external monitor is connected")
    gradle = [str(ROOT / "gradlew"), "--console=plain", "-q", ":26.3-fabric:benchKit"]
    if not WORLD.is_dir():
        gradle.append(":26.3-fabric:runBenchWorld")
    vulkan_minutes = sum(bench_round.START_MINUTES + sum(bench_round.MINUTES.get(c, 10) for c in plan)
                         for plan in bench_round.PLANS["vulkan"].values())
    opengl_minutes = sum(bench_round.START_MINUTES + sum(bench_round.MINUTES.get(c, 10) for c in plan)
                         for plan in bench_round.PLANS["opengl"].values())
    smoke_minutes = len(bench_round.PLANS["vulkan"]) * (
        bench_round.START_MINUTES + sum(bench_round.MINUTES.get(c, 10) for c in bench_round.QUICK_PLANS["vulkan"]))
    total = smoke_minutes + vulkan_minutes + opengl_minutes + 10
    print(f"Full benchmark, about {total // 60} h {total % 60} min: build the kit ({' '.join(gradle[3:])}), install it "
          f"in Prism, smoke test on Vulkan ({smoke_minutes} min), round on Vulkan A C ({vulkan_minutes} min), round "
          f"on OpenGL A–D ({opengl_minutes} min). Nobody may use the computer until it ends.")

    if args.dry_run:
        for problem in problems:
            print(f"  not ready: {problem}")
        step("Round on Vulkan (dry run, with the instances' current kit)", round_command(args, "--api", "vulkan",
                                                                                         "--dry-run"))
        step("Round on OpenGL (dry run, with the instances' current kit)", round_command(args, "--dry-run"))
        return
    if problems:
        raise SystemExit("Not started:\n  " + "\n  ".join(problems))

    started = time.time()
    if step("Build the bench kit", gradle) != 0:
        raise SystemExit("The build failed; nothing was measured.")
    if step("Install the kit in the Prism instances", [sys.executable, str(BENCH / "prism_instances.py")]) != 0:
        raise SystemExit("prism_instances.py stopped; nothing was measured.")

    outcomes = []
    smoke = step("Smoke test on Vulkan", round_command(args, "--quick", "--api", "vulkan"))
    outcomes.append(("Smoke test on Vulkan", "ok" if smoke == 0 else "FAILED: the Vulkan round was skipped"))
    if smoke == 0:
        time.sleep(30)
        code = step("Round on Vulkan", round_command(args, "--api", "vulkan"))
        outcomes.append(("Round on Vulkan, A C", "ok" if code == 0 else "INCOMPLETE: see its last lines above"))
    time.sleep(30)
    code = step("Round on OpenGL", round_command(args))
    outcomes.append(("Round on OpenGL, A–D", "ok" if code == 0 else "INCOMPLETE: see its last lines above"))

    hours = (time.time() - started) / 3600
    print(f"\n=== Summary ({datetime.now():%H:%M}, {hours:.1f} h)")
    for title, outcome in outcomes:
        print(f"  {title}: {outcome}")
    if smoke == 0:
        print("Gate D on Vulkan, " + "\n".join(gate_d(args.data or bench_round.default_data(), started)))
    sys.exit(0 if all(outcome == "ok" for _, outcome in outcomes) else 1)


if __name__ == "__main__":
    main()
