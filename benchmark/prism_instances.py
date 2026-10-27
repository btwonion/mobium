#!/usr/bin/env python3
"""Creates or updates Prism Launcher instances for the four benchmark setups of docs/benchmarks.md.

    ./gradlew :26.3-fabric:benchKit            # mods per setup + datapack -> versions/26.3-fabric/build/bench-kit
    ./gradlew :26.3-fabric:runBenchWorld       # once: the benchmark world -> versions/26.3-fabric/build/bench-world
    python3 benchmark/prism_instances.py       # -> four "Mobium Bench" instances in Prism

Run it again after rebuilding Mobium: it replaces the mods, the launcher component list and the benchmark datapack in
the instances' worlds. It refuses a kit that was not built from the checkout's current commit, and a checkout that is
behind its upstream branch (--allow-behind measures an older build on purpose). It also installs and selects the protocol's shader pack (setups B and D) and Fresh Animations
(C and D), downloading them from Modrinth once (skip with --no-download), and tells Mobium which setup each instance is
for (`-Dmobium.bench.expect`), so `/mobium suite` refuses to run with the wrong packs. Other options, worlds and
instance settings stay as you left them. Restart Prism to see new instances.
"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MINECRAFT = "26.3"
FABRIC_LOADER = "0.20.0"
DATA_VERSION = 5023  # Minecraft 26.3, for options.txt

# kit folder -> (instance folder, name, notes, setup name Mobium detects, shader pack?, Fresh Animations?)
SETUPS = {
    "A-sodium": ("mobium-bench-a", "Mobium Bench A (Sodium)", "Sodium only", "sodium", False, False),
    "B-iris": ("mobium-bench-b", "Mobium Bench B (Sodium + Iris)", "Sodium + Iris with the shader pack", "sodium+iris", True, False),
    "C-emf-etf": ("mobium-bench-c", "Mobium Bench C (Sodium + EMF/ETF)", "Sodium + EMF/ETF with Fresh Animations", "sodium+emf+fa", False, True),
    "D-all": ("mobium-bench-d", "Mobium Bench D (all)", "Sodium + Iris + EMF/ETF, shader pack and Fresh Animations", "sodium+iris+emf+fa", True, True),
}

# The packs of the protocol (docs/benchmarks.md, PLAN.md section 11), pinned so every round measures the same thing.
SHADER_PACK = ("complementary-reimagined", "r5.9.3")
FRESH_ANIMATIONS = ("fresh-animations", "1.11.1")
MODRINTH = "https://api.modrinth.com/v2"
USER_AGENT = "btwonion/mobium benchmark/prism_instances.py"

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


def unquote(value: str) -> str:
    """A QSettings INI value as text: Prism quotes values with special characters and escapes quotes and backslashes."""
    if value.startswith('"'):
        value = value[1:-1] if len(value) > 1 and value.endswith('"') else value[1:]
        value = re.sub(r"\\(.)", r"\1", value)
    return value


def quote(value: str) -> str:
    if re.fullmatch(r"[\w.+-]*", value):
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def general_keys(config: Path) -> dict[str, str]:
    """The [General] keys of a Prism instance.cfg (a QSettings INI file); empty if there is none."""
    values = {}
    section = "[General]"
    for line in config.read_text().splitlines() if config.exists() else []:
        if line.startswith("["):
            section = line.strip()
        elif section == "[General]" and "=" in line:
            key, value = line.split("=", 1)
            values[key] = unquote(value)
    return values


def set_general_keys(config: Path, values: dict[str, str]) -> None:
    """Sets keys in the [General] section of a Prism instance.cfg, where Prism reads instance settings. Earlier versions
    of this script appended them to the end of the file, which put them into the section Prism had added last ([UI]),
    where Prism ignores them; those copies are dropped."""
    sections: list[list[str]] = [[]]
    for line in config.read_text().splitlines():
        if line.startswith("["):
            sections.append([line])
        else:
            sections[-1].append(line)
    general = next((section for section in sections[1:] if section[0].strip() == "[General]"), None)
    if general is None:
        general = ["[General]"]
        sections.insert(1, general)
    for section in sections:
        if section is not general:
            section[:] = [line for line in section if line.split("=", 1)[0] not in values]
    for key, value in values.items():
        line = f"{key}={quote(value)}"
        for index, existing in enumerate(general):
            if existing.split("=", 1)[0] == key:
                general[index] = line
                break
        else:
            end = len(general)
            while end > 1 and not general[end - 1].strip():
                end -= 1
            general.insert(end, line)
    config.write_text("\n".join(line for section in sections for line in section) + "\n")


def last_launch(instance: Path) -> int:
    value = general_keys(instance / "instance.cfg").get("lastLaunchTime", "0")
    return int(value) if value.isdigit() else 0


def find_instance(instances: Path, folder: str, name: str) -> Path:
    """The instance's folder. Prism renames an instance's folder to its display name, so folders with that name count
    too; if there are several (a run that made the folder again after Prism had renamed it), the one Prism launched last
    is used and the others are named, so they can be removed in Prism."""
    candidates = [instances / folder] if (instances / folder).exists() else []
    for config in sorted(instances.glob("*/instance.cfg")):
        if config.parent not in candidates and general_keys(config).get("name") == name:
            candidates.append(config.parent)
    if not candidates:
        return instances / folder
    chosen = max(candidates, key=last_launch)
    for other in candidates:
        if other != chosen:
            launched = "never launched" if last_launch(other) == 0 else "launched less recently"
            print(f"  another instance named {name!r} in {other.name}/ ({launched}) is left alone; remove it in Prism")
    return chosen


def git(*args: str) -> str | None:
    result = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


def jar_build(jar: Path) -> str | None:
    """The commit a Mobium jar was built from (mobium-build.properties, written by the build)."""
    with zipfile.ZipFile(jar) as archive:
        if "mobium-build.properties" not in archive.namelist():
            return None
        for line in archive.read("mobium-build.properties").decode().splitlines():
            if line.startswith("commit="):
                return line.removeprefix("commit=").strip()
    return None


def kit_build(kit: Path) -> str | None:
    """The commit the kit's Mobium jar was built from."""
    for jar in sorted((kit / "A-sodium" / "mods").glob("mobium-*.jar")):
        return jar_build(jar)
    return None


def check_build(kit: Path, allow_behind: bool) -> str:
    """Refuses to install a kit that does not match the checkout, or a checkout behind its upstream branch."""
    head = git("describe", "--always", "--abbrev=7")
    built = kit_build(kit)
    if head is not None and (built or "").removesuffix("-dirty") != head:
        raise SystemExit(
            f"The kit's Mobium was built from {built or 'an unknown commit'}, the checkout is at {head}: "
            "run ./gradlew :26.3-fabric:benchKit first"
        )
    if built is not None and built.endswith("-dirty"):
        print(f"note: the kit was built from {built}, i.e. with uncommitted changes")
    upstream = git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
    if upstream is not None and not allow_behind:
        if git("fetch", "--quiet") is None:
            print(f"could not fetch {upstream}; not checking whether the checkout is behind it")
        behind = git("rev-list", "--count", "HEAD..@{u}")
        if behind not in (None, "0"):
            raise SystemExit(
                f"The checkout is {behind} commits behind {upstream}: git pull, run ./gradlew :26.3-fabric:benchKit, "
                "then this script again (or pass --allow-behind to measure this older build)"
            )
    return built or "unknown"


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


def set_keys(file: Path, separator: str, values: dict[str, str]) -> None:
    """Sets `key<separator>value` lines in a flat config file, keeping every other line."""
    lines = file.read_text().splitlines() if file.exists() else []
    for key, value in values.items():
        line = f"{key}{separator}{value}"
        for index, existing in enumerate(lines):
            if existing.startswith(key + separator):
                lines[index] = line
                break
        else:
            lines.append(line)
    file.write_text("\n".join(lines) + "\n")


def fetch_pack(project: str, version: str, cache: Path, download: bool) -> Path | None:
    """The pack's file from Modrinth, downloaded into `cache` once and checked against Modrinth's SHA-512."""
    cache.mkdir(parents=True, exist_ok=True)
    marker = cache / f"{project}-{version}.name"
    if marker.exists() and (cache / marker.read_text().strip()).exists():
        return cache / marker.read_text().strip()
    if not download:
        return None
    query = urllib.parse.urlencode({"version_number": version})
    request = urllib.request.Request(f"{MODRINTH}/project/{project}/version?{query}", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        versions = [v for v in json.load(response) if v["version_number"] == version]
    if not versions:
        raise SystemExit(f"Modrinth has no version {version} of {project}")
    file = next(f for f in versions[0]["files"] if f["primary"])
    target = cache / file["filename"]
    request = urllib.request.Request(file["url"], headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    if hashlib.sha512(data).hexdigest() != file["hashes"]["sha512"]:
        raise SystemExit(f"{file['filename']}: checksum does not match Modrinth's")
    target.write_bytes(data)
    marker.write_text(file["filename"] + "\n")
    print(f"downloaded {file['filename']} ({len(data) // 1024} KiB) from Modrinth")
    return target


def install_shader_pack(game: Path, pack: Path) -> None:
    (game / "shaderpacks").mkdir(exist_ok=True)
    shutil.copy2(pack, game / "shaderpacks" / pack.name)
    set_keys(game / "config" / "iris.properties", "=", {"enableShaders": "true", "shaderPack": pack.name})


def install_resource_pack(game: Path, pack: Path) -> None:
    (game / "resourcepacks").mkdir(exist_ok=True)
    shutil.copy2(pack, game / "resourcepacks" / pack.name)
    # The game re-adds the built-in packs it requires (vanilla, Fabric's mod resources) on its own.
    selected = json.dumps(["vanilla", f"file/{pack.name}"], separators=(",", ":"))
    set_keys(game / "options.txt", ":", {"resourcePacks": selected})


def write_expected_setup(instance: Path, setup: str) -> None:
    """Mobium's benchmark refuses to run when the active setup differs from this (BenchEnvironment.expectedSetup)."""
    config = instance / "instance.cfg"
    args = re.sub(r"\s*-Dmobium\.bench\.expect=\S*", "", general_keys(config).get("JvmArgs", "")).strip()
    set_general_keys(config, {"OverrideJavaArgs": "true", "JvmArgs": f"{args} -Dmobium.bench.expect={setup}".strip()})


def sync_datapack(kit: Path, game: Path) -> bool:
    """Replaces the benchmark datapack in every world of the instance that has one."""
    source = kit / "datapacks" / "mobium-bench"
    updated = False
    for pack in (game / "saves").glob("*/datapacks/mobium-bench"):
        shutil.rmtree(pack)
        shutil.copytree(source, pack)
        updated = True
    return updated


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
    parser.add_argument("--no-download", action="store_true", help="do not download missing packs from Modrinth")
    parser.add_argument("--allow-behind", action="store_true", help="install even if the checkout is behind upstream")
    args = parser.parse_args()

    if not args.kit.is_dir():
        raise SystemExit(f"{args.kit} not found; run ./gradlew :26.3-fabric:benchKit first")
    build = check_build(args.kit, args.allow_behind)
    prism = args.prism or default_prism_dir()
    instances = prism / "instances"
    packs = args.kit.parent / "bench-packs"
    shader_pack = fetch_pack(*SHADER_PACK, packs, not args.no_download)
    fresh_animations = fetch_pack(*FRESH_ANIMATIONS, packs, not args.no_download)

    for setup, (folder, name, notes, expected, wants_shaders, wants_fa) in SETUPS.items():
        instance = find_instance(instances, folder, name)
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
        if sync_datapack(args.kit, game):
            world += ", datapack updated"
        extras = []
        missing = []
        if wants_shaders:
            if shader_pack:
                install_shader_pack(game, shader_pack)
                extras.append(shader_pack.name)
            else:
                missing.append("shader pack")
        if wants_fa:
            if fresh_animations:
                install_resource_pack(game, fresh_animations)
                extras.append(fresh_animations.name)
            else:
                missing.append("Fresh Animations")
        write_expected_setup(instance, expected)
        packs_note = f", packs: {', '.join(extras)}" if extras else ""
        print(f"{'created' if created else 'updated'} {name}: {len(mods)} mods, {world}{packs_note}")
        if missing:
            print(f"  missing {' and '.join(missing)}: run without --no-download; /mobium suite refuses to run until then")

    print(f"Mobium build {build} installed")
    # The process name, not the command line: a shell whose command mentions Prism's data directory matched as well.
    running = subprocess.run(["pgrep", "-ix", "prismlauncher"], capture_output=True).returncode == 0
    if running:
        print("Prism is running: restart it to see new instances.")


if __name__ == "__main__":
    main()
