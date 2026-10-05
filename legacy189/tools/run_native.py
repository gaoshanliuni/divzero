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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True, type=pathlib.Path)
    parser.add_argument("--java", required=True, type=pathlib.Path)
    parser.add_argument("--mod", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--with-installed-mods", action="store_true")
    parser.add_argument("--java25", type=pathlib.Path)
    args = parser.parse_args()
    instance, java, mod = (p.resolve(strict=True) for p in (args.instance, args.java, args.mod))
    output = args.output.resolve()
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
        if args.with_installed_mods:
            for source in sorted((instance / "mods").glob("*.jar")):
                if "mineagent" in source.name.lower() or "divzero" in source.name.lower():
                    raise ValueError("An existing DivZero Mod must be accounted for explicitly")
                shutil.copy2(source, output / "mods" / source.name)
        shutil.copy2(mod, output / "mods" / mod.name)
        (output / "options.txt").write_text("lang:zh_CN\nrenderDistance:4\nguiScale:2\nfullscreen:false\npauseOnLostFocus:false\nmaxFps:60\nmusic:0.0\nsound:0.2\n")
    installed = output / "mods" / mod.name
    if not installed.is_file() or sha256(installed) != sha256(mod):
        raise ValueError("Fixture Mod differs from the selected build")
    # Minecraft's offline UUID algorithm does not include a UUID namespace prefix.
    player_id = uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:DivZeroFixture").digest(), version=3)
    command = [str(java), "-Xmx2G", "-Dfile.encoding=UTF-8", "-Ddivzero.legacyFixture=true",
               "-Djava.library.path=" + str(natives), "-Dlog4j2.formatMsgNoLookups=true"]
    if args.resume:
        command.append("-Ddivzero.legacyFixtureResume=true")
    if args.java25:
        command.append("-Ddivzero.java25=" + str(args.java25.resolve(strict=True)))
    command += ["-cp", os.pathsep.join(classpath), version["mainClass"], "--username", "DivZeroFixture", "--version", instance.name,
                "--gameDir", str(output), "--assetsDir", str(root / "assets"), "--assetIndex", "1.8", "--uuid", player_id.hex,
                "--accessToken", "0", "--userProperties", "{}", "--userType", "legacy",
                "--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker", "--width", "1100", "--height", "720"]
    log = output / ("resume-console.log" if args.resume else "console.log")
    if log.exists():
        raise ValueError("Fixture log already exists; retain failure evidence and use a new run")
    with log.open("xb") as stream:
        process = subprocess.Popen(command, cwd=output, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
    receipt = {"pid": process.pid, "modSha256": sha256(mod), "source": "FIXTURE_ONLY", "resume": args.resume,
               "installedMods": [p.name for p in sorted((output / "mods").glob("*.jar"))]}
    (output / ("resume-launch.json" if args.resume else "launch.json")).write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
