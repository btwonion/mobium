#!/usr/bin/env python3
"""Runs every benchmark the release bar needs, in one go, unattended (docs/benchmarks.md, docs/releasing.md).

    python3 benchmark/bench_full.py             # about 6.5 hours; nobody may use the computer meanwhile
    python3 benchmark/bench_full.py --dry-run   # the checks and the plans; builds and launches nothing

In order:
  1. ./gradlew :26.3-fabric:benchKit, and :26.3-fabric:runBenchWorld when the benchmark world is missing;
  2. python3 benchmark/prism_instances.py: the kit into the Prism instances "Mobium Bench A–D";
  3. the smoke test on Vulkan (bench_round.py --quick --api vulkan): a short run of each command of the Vulkan round,
     in setups A and C;
  4. the round on Vulkan, setups A and C (bench_round.py --api vulkan), skipped when the smoke test failed;
  5. the round on OpenGL, setups A–D (bench_round.py).
Each round pushes its data to btwonion/mobium-benchmarks when it ends. A failed step after the build does not stop the
next; the summary at the end names each step's outcome and what has to run again, then the release bar's benchmark
numbers from both rounds. Before building it checks mains power and the external monitor, which every round needs.
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
# The release bar (docs/releasing.md): every /mobium compare of a round, the frame with the module over the frame
# without it, at most this much.
RELEASE_BAR = 1.03
# /mobium compare's scene when the command names none (BenchSuite.compareScene).
COMPARE_SCENES = {"static_cache": "storage_room", "vertex_offload": "mob_pen_live"}


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


def compare_file(folder: Path, module: str, scene: str) -> Path | None:
    """The newest /mobium compare file in the round's setup folder for `module` in `scene`."""
    for file in sorted(folder.glob("compare-*.csv"), reverse=True):
        lines = file.read_text().splitlines()
        rows = [line for line in lines[1:] if line and not line.startswith("#")]
        if f"# compare_module,{module}" in lines and rows and rows[0].split(",", 1)[0] == scene:
            return file
    return None


def file_value(file: Path, key: str) -> float | None:
    for line in file.read_text().splitlines():
        if line.startswith(f"# {key},"):
            return float(line.split(",", 1)[1])
    return None


def release_bar(data: Path, since: float) -> list[str]:
    """The release bar's benchmark numbers from the round folders this run pushed (the newest per API since `since`)."""
    lines = []
    slow = missing = False
    for api, plans in bench_round.PLANS.items():
        rounds = [folder for folder in data.glob("[0-9]*") if folder.is_dir() and folder.stat().st_mtime >= since
                  and ("-vulkan" in folder.name) == (api == "vulkan")]
        if not rounds:
            missing = True
            lines.append(f"  {api}: no round from this run in {data}")
            continue
        folder = max(rounds, key=lambda path: path.stat().st_mtime)
        lines.append(f"  {api}, from {folder.name}:")
        for letter, plan in plans.items():
            for command in plan:
                words = command.split()
                if words[1] != "compare":
                    continue
                module = words[2]
                scene = words[5] if len(words) > 5 else COMPARE_SCENES[module]
                file = compare_file(folder / letter, module, scene)
                value = file_value(file, "compare_frame_with_over_without") if file else None
                label = f"{letter} {scene}, {module}"
                if value is None:
                    missing = True
                    lines.append(f"    {label}: missing")
                else:
                    slow = slow or value > RELEASE_BAR
                    lines.append(f"    {label}: {value:.3f} {'ok' if value <= RELEASE_BAR else 'TOO SLOW'} ({file.name})")
    verdict = "NOT passed" if slow else "incomplete: run the missing setups again" if missing else "passed"
    return [f"Release bar, benchmark part (with over without, at most {RELEASE_BAR}×): {verdict}", *lines,
            "Its pixel, vertex and validation-layer checks are the self-check's and the gametests' (docs/releasing.md)."]


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
    print("\n".join(release_bar(args.data or bench_round.default_data(), started)))
    sys.exit(0 if all(outcome == "ok" for _, outcome in outcomes) else 1)


if __name__ == "__main__":
    main()
