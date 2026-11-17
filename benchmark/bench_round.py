#!/usr/bin/env python3
"""Runs a benchmark round unattended in the Prism instances "Mobium Bench A–D" (docs/benchmarks.md).

    python3 benchmark/bench_round.py              # setups A, B, C, D in turn, about 3.5 hours
    python3 benchmark/bench_round.py A D          # some setups only
    python3 benchmark/bench_round.py --dry-run    # checks and plans, launches nothing
    python3 benchmark/bench_round.py --quick A    # smoke test: short runs of the plan's commands, results in build/
    python3 benchmark/bench_round.py --api vulkan # setups A and C on Vulkan, about 2 hours
    python3 benchmark/bench_full.py               # everything the gate and the release need, in one go

--api picks the graphics API (default opengl): for each setup the script sets it in the instance's options.txt
(preferredGraphicsBackend, as a player chooses it in the video settings) and puts the old value back afterwards. On
Vulkan only setups A and C run: B and D need Iris, which draws no shader pack there (PLAN.md, Decisions). Vulkan has
no vertex-stage expand pass, so its plans leave out /mobium stages and measure Gate D's `control` instead. A game that
fails to start on the chosen API carries on with the other one without saying so; the script reads the backend of
every run from summary.csv and names the runs on the wrong one, which then have to run again, as does a setup with a
command that did not start a run. A Vulkan round's folder ends in -vulkan.

For each setup it writes the setup's plan (PLANS) to the instance's mobium-bench/autorun, starts the instance through
Prism's command line straight into the "Mobium Bench" world, and waits until Mobium has run every command and closed
the game (bench/BenchAutorun.kt). As soon as the game has started, Mobium moves the fullscreen window to the monitor
whose name contains --display at 1920×1080, repeats the move until the window is there and stops the setup before
measuring when it never gets there (such a setup has to run again). Before anything starts it checks mains power, an
external monitor, that every instance has the bench kit's Mobium jar (python3 benchmark/prism_instances.py installs
it) and that none of them is running. Afterwards it copies this round's files into <yyyy-mm-dd>-<machine>/<setup>/ of
the data repository btwonion/mobium-benchmarks (per-run CSVs gzipped, summary.csv with this round's rows; -<build> is
appended when that folder holds an earlier round of the day), prints the medians, and commits and pushes the round.
Runs during which the game capped its own frame rate (the window lost focus or was minimized: 10 FPS) are named after
each setup, at the end and in the commit message; such a setup has to run again. Each focus change Mobium noted is
printed with the keyboard or mouse input around it (GNOME's idle monitor, polled every 5 s): without input a program
took the focus, with input someone at the computer. The data repository's checkout sits next to Mobium's
(../mobium-benchmarks, or --data); it is cloned or pulled before the round starts. Nobody may use the computer while it
runs.
"""

import argparse
import csv
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
DATA_URL = "https://github.com/btwonion/mobium-benchmarks.git"
WORLD = "Mobium Bench"
PRISM = ["flatpak", "run", "org.prismlauncher.PrismLauncher"]
IDLE_TIME = ["gdbus", "call", "--session", "--dest", "org.gnome.Mutter.IdleMonitor", "--object-path",
             "/org/gnome/Mutter/IdleMonitor/Core", "--method", "org.gnome.Mutter.IdleMonitor.GetIdletime"]

# Per API and setup: the client commands of the round, in order (docs/benchmarks.md "Tools"). On Vulkan /mobium stages
# is refused (no vertex-stage expand pass); instead its round measures Gate D's `control`: the static cache with over
# without (gateb, setup A) and Module 2 with over without (gatec in control). B and D need Iris, which draws no shader
# pack on Vulkan (PLAN.md, Decisions).
PLANS = {
    "opengl": {
        "A": ["mobium suite", "mobium gatec", "mobium stages", "mobium stages 60 3 display_build",
              "mobium gatec 60 3 item_drops"],
        "B": ["mobium suite", "mobium stages", "mobium gatec 60 3 item_drops"],
        "C": ["mobium suite", "mobium gatec", "mobium stages", "mobium gatec 60 3 item_drops"],
        "D": ["mobium suite", "mobium gatec", "mobium stages", "mobium gatec 60 3 item_drops"],
    },
    "vulkan": {
        "A": ["mobium suite", "mobium gatec", "mobium gatec 60 3 item_drops", "mobium gatec 60 3 control",
              "mobium gateb"],
        "C": ["mobium suite", "mobium gatec", "mobium gatec 60 3 item_drops", "mobium gatec 60 3 control"],
    },
}
# --quick: a short run of each kind of command the API's plans use.
QUICK_PLANS = {
    "opengl": ["mobium stages 5 1 display_build"],
    "vulkan": ["mobium suite 5 1 control", "mobium gatec 5 1", "mobium gateb 5 1"],
}
APIS = tuple(PLANS)
API_OPTION = "preferredGraphicsBackend"
# Each command's length in minutes.
MINUTES = {"mobium suite": 26, "mobium gatec": 8, "mobium stages": 8, "mobium stages 60 3 display_build": 8,
           "mobium gatec 60 3 item_drops": 8, "mobium gatec 60 3 control": 8, "mobium gateb": 16,
           "mobium stages 5 1 display_build": 2, "mobium suite 5 1 control": 2, "mobium gatec 5 1": 2,
           "mobium gateb 5 1": 3}
START_MINUTES = 5
REFUSED = "did not start a run"
NOT_MOVED = "did not reach the monitor"  # BenchAutorun.NOT_MOVED


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


def idle_seconds() -> float | None:
    """Seconds since the last keyboard, mouse or touchpad input (GNOME's idle monitor); None elsewhere."""
    try:
        result = subprocess.run(IDLE_TIME, capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        return None
    # "(uint64 852022,)": milliseconds
    words = result.stdout.split() if result.returncode == 0 else []
    digits = "".join(c for c in words[-1] if c.isdigit()) if words else ""
    return int(digits) / 1000 if digits else None


class InputWatch:
    """Notes when someone used the keyboard, mouse or touchpad: a click takes the focus from the game. Polled every
    few seconds, each poll gives the time of the last input before it."""

    def __init__(self) -> None:
        self.inputs: list[datetime] = []
        self.available = idle_seconds() is not None
        self.last_poll = time.time()

    def poll(self) -> None:
        if not self.available:
            return
        idle = idle_seconds()
        now = time.time()
        if idle is not None and idle < now - self.last_poll + 1:
            moment = datetime.fromtimestamp(now - idle).replace(microsecond=0)
            if not self.inputs or self.inputs[-1] != moment:
                self.inputs.append(moment)
        self.last_poll = now

    def near(self, moment: datetime, seconds: int = 5) -> datetime | None:
        """The input closest to [moment], if one was within [seconds]."""
        close = [i for i in self.inputs if abs((i - moment).total_seconds()) <= seconds]
        return min(close, key=lambda i: abs((i - moment).total_seconds())) if close else None


def focus_report(done: Path, watch: InputWatch) -> list[str]:
    """Every focus change in Mobium's autorun-done, with the keyboard or mouse input around it."""
    lines = []
    for line in done.read_text().splitlines() if done.exists() else []:
        if not line.endswith(("window lost focus", "window focused", "window minimized")):
            continue
        moment = datetime.strptime(line[:19], "%Y-%m-%d %H:%M:%S")
        if not watch.available:
            lines.append(line[11:])
        elif (seen := watch.near(moment)) is not None:
            lines.append(f"{line[11:]}: keyboard or mouse used at {seen:%H:%M:%S}")
        else:
            lines.append(f"{line[11:]}: no keyboard or mouse input within 5 s")
    return lines


def option_value(options: Path, key: str) -> str | None:
    """A key's value in options.txt; None when it is missing."""
    for line in options.read_text().splitlines() if options.exists() else []:
        if line.startswith(key + ":"):
            return line.split(":", 1)[1]
    return None


def choose_api(game: Path, api: str) -> str | None:
    """Sets the graphics API in the instance's options.txt; returns the old value."""
    options = game / "options.txt"
    previous = option_value(options, API_OPTION)
    # After a start that did not end cleanly the game turns vulkan into default (docs/vanilla-notes.md §21).
    prism_instances.set_keys(options, ":", {API_OPTION: f'"{api}"', "startedCleanly": "true"})
    return previous


def wrong_backend_runs(summary: Path, api: str) -> list[str]:
    """The runs in [summary] that did not run on [api] (the game falls back to the other API when one fails)."""
    if not summary.exists():
        return []
    with summary.open(newline="") as file:
        return [f"{row['timestamp']} {row['scene']} ({row['setup']}): {row.get('backend') or 'unknown backend'}"
                for row in csv.DictReader(file) if (row.get("backend") or "").lower() != api]


def launch(folder: str) -> None:
    subprocess.Popen(
        [*PRISM, "--launch", folder, "--world", WORLD],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True,
    )


def run_setup(letter: str, plan: list[str], game: Path, folder: str, display: str) -> bool:
    """Runs one setup's plan; true when Mobium reported it done."""
    bench = game / "mobium-bench"
    bench.mkdir(exist_ok=True)
    done = bench / "autorun-done"
    done.unlink(missing_ok=True)
    request = [f"world={WORLD}", f"display={display}", *[f"command={command}" for command in plan]]
    (bench / "autorun").write_text("\n".join(request) + "\n")
    minutes = START_MINUTES + sum(MINUTES.get(command, 10) for command in plan)
    print(f"{letter}: starting {folder} ({', '.join(plan)}; about {minutes - START_MINUTES} min)", flush=True)
    launch(folder)
    deadline = time.time() + minutes * 1.5 * 60
    started = False
    watch = InputWatch()
    while time.time() < deadline:
        for _ in range(3):
            time.sleep(5)
            watch.poll()
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
    if focus := focus_report(done, watch):
        # Without input a program took the focus (a window or dialog that opened); with input, someone at the computer.
        print(f"  {letter}: the window's focus changed (keyboard and mouse input from GNOME's idle monitor):")
        for line in focus:
            print(f"    {line}")
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
        # Without rows the setup measured nothing, and an empty folder is not published.
        if rows:
            (out / "summary.csv").write_text("\n".join([lines[0], *rows]) + "\n")
    return plans


def refused_commands(done: Path) -> list[str]:
    """The commands Mobium's autorun noted as not starting a run (refused; the reason is in chat and the log)."""
    return [line[20:] for line in done.read_text().splitlines() if line.endswith(f"{REFUSED} (see chat in the log)")] \
        if done.exists() else []


def not_moved(done: Path) -> str | None:
    """Mobium's note that the window never reached the benchmark monitor; the autorun then stopped before measuring."""
    lines = [line[20:] for line in done.read_text().splitlines() if NOT_MOVED in line] if done.exists() else []
    return lines[0] if lines else None


def throttled_runs(summary: Path) -> list[str]:
    """The runs in [summary] during which the game capped its own frame rate (FrameRecorder's throttled_frames)."""
    if not summary.exists():
        return []
    with summary.open(newline="") as file:
        return [f"{row['timestamp']} {row['scene']} ({row['setup']}): "
                f"{row['throttled_frames']} of {row['frames']} frames"
                for row in csv.DictReader(file) if int(row.get("throttled_frames") or 0) > 0]


def default_data() -> Path:
    """The data repository's checkout next to Mobium's main checkout (also when this runs in a worktree)."""
    common = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=ROOT,
                            capture_output=True, text=True, check=True).stdout.strip()
    return Path(common).parent.parent / "mobium-benchmarks"


def update_data(data: Path) -> str | None:
    """Clones the data repository into [data] or pulls it; returns the problem, if any."""
    if data.is_dir():
        command, cwd = ["git", "pull", "-q", "--ff-only"], data
    else:
        command, cwd = ["git", "clone", "-q", DATA_URL, str(data)], None
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        return f"'{' '.join(command)}' failed in {cwd or data.parent}: {result.stderr.strip()}"
    return None


def publish(data: Path, target: Path, message: str) -> None:
    """Commits the round's folder in the data repository and pushes it; on failure says how to finish by hand."""
    folder = str(target.relative_to(data))
    for command in (["git", "add", "--", folder], ["git", "commit", "-q", "-m", message, "--", folder],
                    ["git", "push", "-q"]):
        if subprocess.run(command, cwd=data).returncode != 0:
            print(f"'{' '.join(command)}' failed; the files are in {target}. Finish by hand:\n"
                  f"  cd {data} && git add {folder} && git commit -m '{message}' && git push", flush=True)
            return
    print(f"Pushed {folder} to {DATA_URL.removesuffix('.git')}", flush=True)


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
    parser.add_argument("setups", nargs="*",
                        help=f"setup letters (default: {' '.join(PLANS['opengl'])}; "
                             f"on Vulkan {' '.join(PLANS['vulkan'])})")
    parser.add_argument("--api", choices=APIS, default="opengl", help="graphics API of the round (default: opengl)")
    parser.add_argument("--display", default="AOC", help="part of the benchmark monitor's name (default: AOC)")
    parser.add_argument("--machine", default="thinkpad-x9", help="machine name for the results folder")
    parser.add_argument("--prism", type=Path, help="Prism data directory (default: flatpak or ~/.local/share)")
    parser.add_argument("--kit", type=Path, default=ROOT / "versions/26.3-fabric/build/bench-kit")
    parser.add_argument("--data", type=Path,
                        help="checkout of mobium-benchmarks (default: next to Mobium's checkout, cloned when missing)")
    parser.add_argument("--dry-run", action="store_true", help="only the checks and the plans")
    parser.add_argument("--quick", action="store_true",
                        help="smoke test: short runs of the plan's commands per setup, results in "
                             "versions/26.3-fabric/build/; throttled runs do not fail it")
    args = parser.parse_args()

    vulkan = args.api == "vulkan"
    plans = PLANS[args.api]
    letters = [setup.upper() for setup in args.setups] or list(plans)
    unknown = [letter for letter in letters if letter not in PLANS["opengl"]]
    if unknown:
        parser.error(f"unknown setups {unknown}; use {' '.join(PLANS['opengl'])}")
    if vulkan and (iris := [letter for letter in letters if letter not in plans]):
        parser.error(f"setups {' '.join(iris)} need Iris, which draws no shader pack on Vulkan; use {' '.join(plans)}")
    if args.quick:
        plans = {letter: QUICK_PLANS[args.api] for letter in plans}
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
    data = args.data or default_data()
    if not args.quick:
        if args.dry_run and not data.is_dir():
            print(f"{data} is missing; the round clones {DATA_URL} there")
        elif problem := update_data(data):
            problems.append(problem)
    results = (ROOT / "versions/26.3-fabric/build/bench-round-quick") if args.quick else data
    target = results / f"{datetime.now():%Y-%m-%d}-{args.machine}{'-vulkan' if vulkan else ''}"
    if not args.quick and target.exists():
        # An earlier round of the day: its summary.csv files would be overwritten.
        target = target.with_name(f"{target.name}-{kit}")
        if target.exists():
            problems.append(f"{target} exists already; move it or pass another --machine")
    total = sum(START_MINUTES + sum(MINUTES.get(command, 10) for command in plans[letter]) for letter in letters)
    on = "Vulkan" if vulkan else "OpenGL"
    print(f"Round {' '.join(letters)} with Mobium {kit} on {on}, about {total // 60} h {total % 60} min; "
          f"monitor '{args.display}'; results in {target}")
    for letter in letters:
        print(f"  {letter} ({games[letter][0]}): {', '.join(plans[letter])}")
    if problems:
        raise SystemExit("Not started:\n  " + "\n  ".join(problems))
    if args.dry_run:
        return

    finished = []
    throttled = {}
    wrong_api = {}
    refused = {}
    misplaced = {}
    for letter in letters:
        folder, game = games[letter]
        started = datetime.now()
        previous = choose_api(game, args.api)
        try:
            ok = run_setup(letter, plans[letter], game, folder, args.display)
        finally:
            # The instance's next start, by hand or in another round, runs on the API it had before.
            prism_instances.set_keys(game / "options.txt", ":", {API_OPTION: previous or '"default"'})
        files = collect(letter, game, started, target)
        for file in files:
            print_medians(file)
        summary = target / letter / "summary.csv"
        if runs := throttled_runs(summary):
            throttled[letter] = runs
            print(f"  {letter}: {len(runs)} runs under the game's own frame cap (window lost focus or minimized):")
            for run in runs:
                print(f"    {run}")
        if runs := wrong_backend_runs(summary, args.api):
            wrong_api[letter] = runs
            print(f"  {letter}: {len(runs)} runs not on {on} (the game started on the other API; see "
                  f"{game / 'logs/latest.log'}, 'Using graphics backend'):")
            for run in runs:
                print(f"    {run}")
        if commands := refused_commands(game / "mobium-bench" / "autorun-done"):
            refused[letter] = commands
            print(f"  {letter}: {len(commands)} commands did not start a run (see chat in {game / 'logs/latest.log'}):")
            for command in commands:
                print(f"    {command}")
        if note := not_moved(game / "mobium-bench" / "autorun-done"):
            misplaced[letter] = note
            print(f"  {letter}: measured nothing, {note}")
        if not ok:
            break
        finished.append(letter)
        time.sleep(30)
    print(f"Finished {' '.join(finished) or 'nothing'}; results in {target}")
    # A smoke test asks whether the commands run on this API; a throttled run answers that as well.
    again = sorted(set(wrong_api) | set(refused) | set(misplaced) | (set() if args.quick else set(throttled)))
    if again:
        print(f"Run {' '.join(again)} again: their throttled runs, runs on the other API, commands that did not "
              f"start or a window that did not reach the monitor do not count (python3 benchmark/bench_round.py "
              f"{' '.join(again)}{' --api vulkan' if vulkan else ''}{' --quick' if args.quick else ''})")
    if not args.quick and any(path.is_file() for path in target.rglob("*")):
        stopped = [letter for letter in letters if letter not in finished]
        notes = [f"{' '.join(stopped)} not finished"] if stopped else []
        notes += [f"throttled runs in {' '.join(throttled)}"] if throttled else []
        notes += [f"runs not on {on} in {' '.join(wrong_api)}"] if wrong_api else []
        notes += [f"commands not started in {' '.join(refused)}"] if refused else []
        notes += [f"window not on the monitor in {' '.join(misplaced)}"] if misplaced else []
        publish(data, target, f"{target.name}: {' '.join(letters)} on {on} with build {kit}"
                + (f" ({'; '.join(notes)})" if notes else ""))
    sys.exit(0 if len(finished) == len(letters) and not again else 1)


if __name__ == "__main__":
    main()
