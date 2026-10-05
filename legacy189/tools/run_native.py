"""Launch the built development adapter in an isolated copy of the installed 1.8.9 runtime.

Compiles nothing. Reuses installed libraries/assets/natives read-only. Does not copy
worlds, accounts, API credentials or production configuration. Fixture output is
explicitly marked as automated data and cannot certify modern-combat parity.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import uuid
import zipfile


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def library_allowed(library):
    rules = library.get("rules")
    if rules is None:
        return True
    allowed = False
    for rule in rules:
        system = rule.get("os", {})
        if system.get("name", "windows") != "windows":
            continue
        if system.get("arch", "x86_64") not in ("x86_64", "amd64"):
            continue
        if "version" in system or rule.get("features"):
            raise ValueError("Unreviewed launcher rule")
        allowed = rule["action"] == "allow"
    return allowed


def coordinate_path(name):
    parts = name.split(":")
    if len(parts) not in (3, 4):
        raise ValueError("Unsupported Maven coordinate")
    group, artifact, version = parts[:3]
    filename = f"{artifact}-{version}" + (f"-{parts[3]}" if len(parts) == 4 else "") + ".jar"
    return pathlib.Path(group.replace(".", "/")) / artifact / version / filename


def clean_arena_files(archive):
    from nbt import decompress, parse
    from package_arena import NAME, read_chunk
    expected = {"level.dat", "data/divzero_legacy_world.dat", "region/r.-1.1.mca", "region/r.0.1.mca", "arena-template.json"}
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if len(entries) != len(expected) or {entry.filename for entry in entries} != {NAME + "/" + name for name in expected}:
            raise ValueError("Unexpected clean-map archive layout")
        if sum(entry.file_size for entry in entries) > 64 * 1024 * 1024:
            raise ValueError("Clean-map archive is too large")
        files = {name: source.read(NAME + "/" + name) for name in expected}
    manifest = json.loads(files["arena-template.json"])
    if manifest["kind"] != "legacy189-arena-development-template" or manifest["fullModParity"] is not False:
        raise ValueError("Unrecognized arena template")
    for name in expected - {"arena-template.json"}:
        if hashlib.sha256(files[name]).hexdigest() != manifest["files"][name]:
            raise ValueError("Arena template digest mismatch")
    level = parse(decompress(files["level.dat"], 1)).plain()["Data"]
    identity = parse(decompress(files["data/divzero_legacy_world.dat"], 1)).plain()["data"]
    if "Player" in level or level["allowCommands"] or identity["agents"] or identity["enabled"]:
        raise ValueError("Arena still contains player or operator state")
    for x in range(-2, 2):
        for z in range(47, 52):
            if read_chunk(files[f"region/r.{x // 32}.1.mca"], x, z, clean=False).plain()["Level"]["Entities"]:
                raise ValueError("Arena still contains entities")
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True, type=pathlib.Path)
    parser.add_argument("--java", required=True, type=pathlib.Path)
    parser.add_argument("--mod", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--with-installed-mods", action="store_true")
    parser.add_argument("--exclude-installed-mod-sha256", action="append", default=[],
                        help="Explicitly account for an installed DivZero build without copying it into the fixture")
    parser.add_argument("--java25", type=pathlib.Path)
    parser.add_argument("--arena", type=pathlib.Path)
    parser.add_argument("--packed-arena", type=pathlib.Path)
    parser.add_argument("--combo-fixture", action="store_true")
    parser.add_argument("--training-fixture", action="store_true")
    parser.add_argument("--advanced-fixture", action="store_true")
    parser.add_argument("--policy-checkpoint", type=pathlib.Path)
    parser.add_argument("--gui-scale", type=int, choices=(1, 2, 3, 4), default=2)
    parser.add_argument("--width", type=int, default=1100)
    parser.add_argument("--height", type=int, default=720)
    args = parser.parse_args()
    if args.advanced_fixture and (not args.packed_arena or args.combo_fixture or args.training_fixture):
        raise ValueError("Advanced combat fixtures require their own clean packed arena")
    if args.combo_fixture and not args.packed_arena:
        raise ValueError("Combo fixtures require a clean packed arena")
    if args.training_fixture and (not args.packed_arena or args.combo_fixture):
        raise ValueError("Self-play fixtures require their own clean packed arena")
    if any(len(value) != 64 or any(c not in "0123456789abcdef" for c in value) for value in args.exclude_installed_mod_sha256):
        raise ValueError("Excluded Mod hashes must be lowercase SHA-256")
    if args.exclude_installed_mod_sha256 and (not args.with_installed_mods or args.resume):
        raise ValueError("Explicit Mod exclusions apply only to a fresh installed-Mod fixture")
    if not 640 <= args.width <= 3840 or not 480 <= args.height <= 2160:
        raise ValueError("Fixture window size is outside the supported range")
    instance, java, mod = (p.resolve(strict=True) for p in (args.instance, args.java, args.mod))
    output = args.output.resolve()
    arena = args.arena.resolve(strict=True) if args.arena else None
    if arena:
        from map_blueprint import validate
        validate(json.loads(arena.read_text(encoding="utf-8")))
    if args.packed_arena and (arena or args.resume):
        raise ValueError("Clean-template validation requires a fresh isolated run")
    packed = clean_arena_files(args.packed_arena.resolve(strict=True)) if args.packed_arena else None
    policy_bytes = None
    if args.policy_checkpoint:
        if not packed or args.resume:
            raise ValueError("A model checkpoint requires a fresh clean-map fixture")
        policy_bytes = args.policy_checkpoint.resolve(strict=True).read_bytes()
        if len(policy_bytes) > 1024 * 1024:
            raise ValueError("Model checkpoint too large")
        checkpoint = json.loads(policy_bytes)
        if checkpoint.get("schema") != 1 or hashlib.sha256(checkpoint["model"].encode("utf-8")).hexdigest() != checkpoint["sha256"]:
            raise ValueError("Model checkpoint digest or schema mismatch")
    if os.name != "nt":
        raise ValueError("This local fixture launcher targets Windows x64")
    if output.is_relative_to(instance) or instance.is_relative_to(output):
        raise ValueError("Fixture must be separate from the production instance")
    version_file = instance / (instance.name + ".json")
    version = json.loads(version_file.read_text(encoding="utf-8-sig"))
    if version.get("mainClass") != "net.minecraft.launchwrapper.Launch" or not any(
        library["name"].startswith("net.minecraftforge:forge:1.8.9-11.15.1.2318") for library in version["libraries"]
    ):
        raise ValueError("Expected the installed Forge 1.8.9 / 11.15.1.2318 runtime")
    root = instance.parent.parent
    classpath = []
    for library in version["libraries"]:
        if not library_allowed(library):
            continue
        artifact = library.get("downloads", {}).get("artifact", {})
        if library.get("natives") and (not artifact or (artifact.get("size") == 22 and artifact.get("sha1") == "b04f3ee8f5e43fa3b162981b50bb72fe1acabb33")):
            # Native-only classifier JARs are already unpacked in the installed
            # natives directory and do not provide a regular classpath artifact.
            continue
        relative = pathlib.Path(artifact["path"]) if artifact.get("path") else coordinate_path(library["name"])
        path = (root / "libraries" / relative).resolve()
        if not path.is_relative_to(root / "libraries") or not path.is_file():
            raise ValueError(f"Missing or invalid installed library: {library['name']}")
        if artifact.get("sha1"):
            with path.open("rb") as stream:
                if hashlib.file_digest(stream, "sha1").hexdigest() != artifact["sha1"]:
                    raise ValueError(f"Installed library digest mismatch: {library['name']}")
        classpath.append(str(path))
    game_jar = instance / (instance.name + ".jar")
    if not game_jar.is_file():
        raise ValueError("Installed Minecraft client JAR is missing")
    classpath.append(str(game_jar))
    natives = instance / (instance.name + "-natives")
    if not (natives / "lwjgl64.dll").is_file():
        raise ValueError("Installed Windows x64 LWJGL natives are missing")
    marker = output / "divzero-native-fixture-allow"
    if args.resume:
        if not marker.is_file() or marker.read_text() != "DIVZERO_LEGACY189_ISOLATED_FIXTURE\n":
            raise ValueError("Resume requires this launcher's existing fixture directory")
        if not (output / "divzero-native-result.json").is_file():
            raise ValueError("Resume requires the baseline receipt")
    else:
        if output.exists():
            raise ValueError("Initial fixture output must be a new directory")
        output.mkdir(parents=True)
        marker.write_text("DIVZERO_LEGACY189_ISOLATED_FIXTURE\n")
        (output / "mods").mkdir()
        excluded_mods = {}
        if args.with_installed_mods:
            for source in sorted((instance / "mods").glob("*.jar")):
                if "mineagent" in source.name.lower() or "divzero" in source.name.lower():
                    digest = sha256(source)
                    if digest not in args.exclude_installed_mod_sha256:
                        raise ValueError("An existing DivZero Mod must be accounted for explicitly")
                    excluded_mods[source.name] = digest
                    continue
                shutil.copy2(source, output / "mods" / source.name)
            if set(excluded_mods.values()) != set(args.exclude_installed_mod_sha256):
                raise ValueError("An explicitly excluded Mod was not found in the installed instance")
        shutil.copy2(mod, output / "mods" / mod.name)
        if arena:
            (output / "divzero-import").mkdir()
            shutil.copy2(arena, output / "divzero-import/pvp-arena-transfer.json")
        if packed:
            for name, data in packed.items():
                destination = output / "saves/DivZero PvP 1.8.9" / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with destination.open("xb") as stream: stream.write(data)
            if policy_bytes:
                checkpoint_file = output / "saves/DivZero PvP 1.8.9/data/divzero-policy/active.json"
                checkpoint_file.parent.mkdir(parents=True)
                with checkpoint_file.open("xb") as stream: stream.write(policy_bytes)
        (output / "options.txt").write_text(f"lang:zh_CN\nrenderDistance:4\nguiScale:{args.gui_scale}\nfullscreen:false\npauseOnLostFocus:false\nmaxFps:60\nmusic:0.0\nsound:0.2\n")
    installed = output / "mods" / mod.name
    if not installed.is_file() or sha256(installed) != sha256(mod):
        raise ValueError("Fixture Mod differs from the selected build")
    # Minecraft's offline UUID algorithm does not include a UUID namespace prefix.
    player_id = uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:DivZeroFixture").digest(), version=3)
    command = [str(java), "-Xmx2G", "-Dfile.encoding=UTF-8", "-Ddivzero.legacyFixture=true",
               "-Djava.library.path=" + str(natives), "-Dlog4j2.formatMsgNoLookups=true"]
    if args.resume:
        command.append("-Ddivzero.legacyFixtureResume=true")
    if packed:
        command.append("-Ddivzero.legacyPackedFixture=true")
        command.append("-Ddivzero.fixtureExpectedScale=" + str(args.gui_scale))
    if args.combo_fixture:
        command.append("-Ddivzero.legacyComboFixture=true")
    if args.training_fixture:
        command.append("-Ddivzero.legacyTrainingFixture=true")
    if args.advanced_fixture:
        command.append("-Ddivzero.legacyAdvancedFixture=true")
    if args.java25:
        command.append("-Ddivzero.java25=" + str(args.java25.resolve(strict=True)))
    command += ["-cp", os.pathsep.join(classpath), version["mainClass"], "--username", "DivZeroFixture", "--version", instance.name,
                "--gameDir", str(output), "--assetsDir", str(root / "assets"), "--assetIndex", "1.8", "--uuid", player_id.hex,
                "--accessToken", "0", "--userProperties", "{}", "--userType", "legacy",
                "--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker", "--width", str(args.width), "--height", str(args.height)]
    log = output / ("resume-console.log" if args.resume else "console.log")
    if log.exists():
        raise ValueError("Fixture log already exists; retain failure evidence and use a new run")
    with log.open("xb") as stream:
        process = subprocess.Popen(command, cwd=output, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
    receipt = {"pid": process.pid, "modSha256": sha256(mod), "source": "FIXTURE_ONLY", "resume": args.resume,
               "installedMods": [p.name for p in sorted((output / "mods").glob("*.jar"))]}
    if not args.resume:
        receipt["explicitlyExcludedInstalledMods"] = excluded_mods
    (output / ("resume-launch.json" if args.resume else "launch.json")).write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
