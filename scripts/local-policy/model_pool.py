"""Build a checksum-pinned pool of distinct neural weights for isolated self-play.
Example: --model baseline=baseline.json --model candidate=candidate.json --output pool.json
This does not install or promote any model.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--model", action="append", required=True, help="label=local JSON path")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
models, labels, weights = [], set(), set()
for entry in args.model:
    label, name = entry.split("=", 1)
    if not label or len(label) > 64 or label in labels:
        raise ValueError("Model labels must be unique")
    source = Path(name).read_bytes()
    if len(source) > 131072:
        raise ValueError("Model is too large")
    data = json.loads(source)
    if data["schema"] != "divzero-admissible-action-value/2" or len(data["hidden"]) != 24 or any(len(r) != 16 for r in data["hidden"]) or len(data["bias"]) != 24 or len(data["output"]) != 24:
        raise ValueError("Model shape mismatch")
    values = [float(v) for row in data["hidden"] for v in row] + [float(v) for v in data["bias"] + data["output"]] + [float(data["outputBias"])]
    if any(not math.isfinite(v) or abs(v) > 64 for v in values):
        raise ValueError("Invalid weight")
    weights.add(tuple(values))
    labels.add(label)
    models.append({"id": label, "sha256": hashlib.sha256(source).hexdigest(), "source": source.decode("utf-8")})
if not 2 <= len(models) <= 8 or len(weights) < 2:
    raise ValueError("At least two numerically distinct neural models are required")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps({"schema": 1, "models": models}, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
print(json.dumps({"pool": str(args.output), "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(), "models": [{"id": m["id"], "sha256": m["sha256"]} for m in models]}))
