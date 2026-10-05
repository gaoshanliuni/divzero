"""Inventory the full current source tree before a Forge 1.8.9 backport.

The result describes source coverage, not working features. It deliberately keeps
smoke fixtures visible and keeps both Python editions in scope. No private paths,
configuration, saved worlds, logs, or credentials are read.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
from collections import Counter, defaultdict

MODULES = ("api", "core", "agent", "scripting", "client", "worker", "integrations/ysm", "neoforge")
IMPORT = re.compile(r"(?m)^\s*import\s+(?:static\s+)?([\w.*]+)\s*;")
DECLARATION = re.compile(r'\b(?:tool|definition)\s*\(\s*"([a-z][a-z0-9_]+)"')
DEPENDENCIES = {
    "minecraft": "net.minecraft.", "neoforge": "net.neoforged.",
    "ldlib2": "com.lowdragmc.", "kubejs": "dev.latvian.mods.kubejs.",
    "rhino": "dev.latvian.mods.rhino.", "lwjgl": "org.lwjgl.",
    "blaze3d": "com.mojang.blaze3d.", "joml": "org.joml.",
}


def normalized_bytes(path):
    return path.read_bytes().replace(b"\r\n", b"\n")


def collect(root: pathlib.Path) -> dict:
    root = root.resolve(strict=True)
    rows, declarations, counts, dependencies = [], defaultdict(list), Counter(), defaultdict(set)
    for module in MODULES:
        directory = root / module / "src/main"
        if not directory.is_dir():
            raise ValueError(f"Missing source module: {module}")
        for file in sorted(directory.rglob("*.java")):
            data = normalized_bytes(file)
            source = data.decode("utf-8-sig")
            path = file.relative_to(root).as_posix()
            names = sorted({name for name, prefix in DEPENDENCIES.items()
                            if any(value.startswith(prefix) for value in IMPORT.findall(source))})
            for name in names:
                dependencies[name].add(path)
            rows.append({"path": path, "sha256": hashlib.sha256(data).hexdigest(),
                         "lines": len(data.splitlines()), "platformImports": names,
                         "fixtureNameHint": bool(re.search(r"Smoke|Fixture|Probe", file.stem))})
            counts[module] += 1
            # A declaration-site inventory is deliberately not advertised as a runtime catalog.
            if module in ("core", "worker"):
                for name in sorted(set(DECLARATION.findall(source))):
                    declarations[name].append(path)
    hashes = hashlib.sha256()
    for row in rows:
        hashes.update((row["path"] + "\0" + row["sha256"] + "\n").encode())
    return {
        "schema": 1, "target": "1.8.9-Forge_11.15.1.2318", "requiredScope": "full-modern-backport",
        "sourceTreeSha256": hashes.hexdigest(), "javaSourceFiles": len(rows),
        "moduleCounts": dict(counts), "dependencySourceCounts": {k: len(v) for k, v in sorted(dependencies.items())},
        "staticToolDeclarationSites": dict(sorted(declarations.items())), "sources": rows,
        "runtimeToolCatalogVerified": False, "nativeParityVerified": False,
        "notes": ["Static tool names may include helper declarations; not a runtime execution count.",
                  "No source file is omitted based on its fixture name.",
                  "Shared Java 25 code is not executable in the Java 8 game process without adaptation.",
                  "Source presence, compilation and an exported map are not Native feature acceptance."],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    result = collect(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("javaSourceFiles", "moduleCounts", "dependencySourceCounts", "sourceTreeSha256", "nativeParityVerified")}))


if __name__ == "__main__":
    main()
