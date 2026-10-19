#!/usr/bin/env python3
"""Creates or updates Prism Launcher instances for the four benchmark setups of docs/benchmarks.md.

    ./gradlew :26.3-fabric:benchKit            # mods per setup + datapack -> versions/26.3-fabric/build/bench-kit
    ./gradlew :26.3-fabric:runBenchWorld       # once: the benchmark world -> versions/26.3-fabric/build/bench-world
    python3 benchmark/prism_instances.py       # -> four "Mobium Bench" instances in Prism

Run it again after rebuilding Mobium: it replaces the mods and the launcher component list and leaves everything you
may have changed alone (options, worlds, resource and shader packs, instance settings). Restart Prism to see new
instances.
"""

import argparse
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MINECRAFT = "26.3"
FABRIC_LOADER = "0.20.0"
DATA_VERSION = 5023  # Minecraft 26.3, for options.txt

SETUPS = {
    "A-sodium": ("mobium-bench-a", "Mobium Bench A (Sodium)", "Sodium only"),
    "B-iris": ("mobium-bench-b", "Mobium Bench B (Sodium + Iris)", "Sodium + Iris: put the shader pack into shaderpacks/ and select it"),
    "C-emf-etf": ("mobium-bench-c", "Mobium Bench C (Sodium + EMF/ETF)", "Sodium + EMF/ETF: put Fresh Animations into resourcepacks/ and enable it"),
    "D-all": ("mobium-bench-d", "Mobium Bench D (all)", "Sodium + Iris + EMF/ETF: shader pack and Fresh Animations as in B and C"),
}

# docs/benchmarks.md "Settings"; only written when the instance has no options.txt yet.
OPTIONS = {
    "version": str(DATA_VERSION),
    "fullscreen": "true",
    "renderDistance": "12",
    "simulationDistance": "12",
    "fov": "0.0",
    "maxFps": "260",
    "enableVsync": "false",
    "entityDistanceScaling": "1.0",
    "improvedTransparency": "false",
    "graphicsPreset": '"fancy"',
    "inactivityFpsLimit": '"minimized"',
    "pauseOnLostFocus": "false",
    "onboardAccessibility": "false",
    "tutorialStep": "none",
    "skipMultiplayerWarning": "true",
    "joinedFirstServer": "true",
}


def default_prism_dir() -> Path:
    for candidate in (
        Path.home() / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher",
        Path.home() / ".local/share/PrismLauncher",
    ):
        if candidate.is_dir():
            return candidate
    raise SystemExit("Prism Launcher data directory not found; pass --prism")


def write_pack(instance: Path) -> None:
    components = [
        {"uid": "net.minecraft", "version": MINECRAFT, "important": True},
        {"uid": "net.fabricmc.intermediary", "version": MINECRAFT, "dependencyOnly": True},
        {"uid": "net.fabricmc.fabric-loader", "version": FABRIC_LOADER},
    ]
    (instance / "mmc-pack.json").write_text(json.dumps({"components": components, "formatVersion": 1}, indent=4) + "\n")


def write_config(instance: Path, name: str, notes: str) -> None:
    config = instance / "instance.cfg"
    if config.exists():
        return
    config.write_text(
        "[General]\n"
        "ConfigVersion=1.3\n"
        "InstanceType=OneSix\n"
        f"name={name}\n"
        "iconKey=default\n"
        "AutomaticJava=true\n"
        "OverrideMemory=true\n"
        "MinMemAlloc=1024\n"
        "MaxMemAlloc=6144\n"
        f"notes={notes}. Benchmark protocol: docs/benchmarks.md in the Mobium repository.\n"
    )


def sync_mods(source: Path, target: Path) -> list[str]:
    target.mkdir(parents=True, exist_ok=True)
    for old in target.glob("*.jar"):
        old.unlink()
    names = []
    for jar in sorted(source.glob("*.jar")):
        shutil.copy2(jar, target / jar.name)
        names.append(jar.name)
    return names


def write_options(game: Path) -> None:
    options = game / "options.txt"
    if options.exists():
        return
    options.write_text("".join(f"{key}:{value}\n" for key, value in OPTIONS.items()))


def copy_world(world: Path | None, game: Path) -> str:
    if world is None or not world.is_dir():
        return "no world (run ./gradlew :26.3-fabric:runBenchWorld)"
    target = game / "saves" / world.name
    if target.exists():
        return f"kept existing world '{world.name}'"
    shutil.copytree(world, target, ignore=shutil.ignore_patterns("session.lock"))
    return f"copied world '{world.name}'"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--prism", type=Path, help="Prism data directory (default: flatpak or ~/.local/share)")
    parser.add_argument("--kit", type=Path, default=ROOT / "versions/26.3-fabric/build/bench-kit")
    parser.add_argument("--world", type=Path, default=ROOT / "versions/26.3-fabric/build/bench-world/Mobium Bench")
    args = parser.parse_args()

    if not args.kit.is_dir():
        raise SystemExit(f"{args.kit} not found; run ./gradlew :26.3-fabric:benchKit first")
    prism = args.prism or default_prism_dir()
    instances = prism / "instances"

    for setup, (folder, name, notes) in SETUPS.items():
        instance = instances / folder
        game = instance / "minecraft"
        created = not instance.exists()
        game.mkdir(parents=True, exist_ok=True)
        write_config(instance, name, notes)
        write_pack(instance)
        mods = sync_mods(args.kit / setup / "mods", game / "mods")
        write_options(game)
        for directory in ("resourcepacks", "shaderpacks", "config"):
            (game / directory).mkdir(exist_ok=True)
        world = copy_world(args.world, game)
        print(f"{'created' if created else 'updated'} {name}: {len(mods)} mods, {world}")

    running = subprocess.run(["pgrep", "-fi", "prismlauncher"], capture_output=True).returncode == 0
    if running:
        print("Prism is running: restart it to see new instances.")


if __name__ == "__main__":
    main()
