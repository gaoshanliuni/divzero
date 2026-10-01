"""Validate real human series before exposing any samples to the trainer."""
import hashlib
import json
import math


def load_series(run):
    process = json.loads((run / "process.json").read_text(encoding="utf-8-sig"))
    source, artifact = process.get("source", ""), process.get("artifactSha256", "").lower()
    if process.get("mode") != "human_duel" or len(source) != 40 or len(artifact) != 64 or any(c not in "0123456789abcdef" for c in source + artifact):
        raise ValueError("Human data requires an explicitly identified human run and executor")
    files = list((run / "game/human-duel").glob("*/series.json"))
    if len(files) != 1:
        raise ValueError("Select one isolated five-round human series")
    raw = files[0].read_bytes()
    data = json.loads(raw)
    if data.get("source") != "HUMAN_DUEL" or data.get("status") != "COMPLETE" or data.get("completedRounds") != 5:
        raise ValueError("Fixture, partial and cancelled series are not completed human training data")
    if data.get("boost") is not False or data.get("onlineUpdates") is not False or data.get("roundLimitSeconds") != 180:
        raise ValueError("Human baseline requires frozen normal-mode weights and a 180-second limit")
    if data.get("equipment") != ["minecraft:diamond_sword", "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots"]:
        raise ValueError("Unexpected human baseline loadout")
    model = data["model"]
    model_hash = hashlib.sha256(model.encode()).hexdigest()
    if model_hash != data.get("initialModelHash"):
        raise ValueError("Initial model hash mismatch")
    matches = data.get("matches", [])
    if len(matches) != 5 or len({m["match"] for m in matches}) != 5 or [m["round"] for m in matches] != [1, 2, 3, 4, 5]:
        raise ValueError("Five distinct, ordered matches are required")
    rows = []
    for match in matches:
        if match.get("outcome") not in {"HUMAN_WON", "AI_WON", "TIME_LIMIT_DRAW", "DOUBLE_KO"}:
            raise ValueError("Unknown human match outcome")
        if not 0 < match.get("seconds", 0) <= 180 or match.get("initialModelHash") != model_hash or match.get("finalModelHash") != model_hash:
            raise ValueError("Match duration or frozen model identity failed")
        samples = match.get("samples", [])
        if not samples or not match.get("frames"):
            raise ValueError("Human match has no real native trajectory")
        for sample in samples:
            features, cost = sample.get("features", []), sample.get("cost", float("nan"))
            if len(features) != 16 or not all(math.isfinite(v) for v in features) or not math.isfinite(cost) or not 0 <= cost <= 1:
                raise ValueError("Invalid human policy outcome sample")
        rows.append((samples, {"run": run.name, "actor": match["ai"]["agent"], "match": data["series"] + ":" + match["match"], "scenario": "HUMAN_V_NEURAL", "role": "MELEE", "model": model_hash, "outcome": match["outcome"], "samples": len(samples), "sha256": hashlib.sha256(raw).hexdigest(), "sourceCommit": source, "artifactSha256": artifact}))
    return json.loads(model), rows
