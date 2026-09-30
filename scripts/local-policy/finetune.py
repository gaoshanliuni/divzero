"""Fine-tune a candidate on isolated native duel outcomes. Does not replace shipped weights.
Run policy_train first. Held-out actors never supply training samples. Win-rate acceptance
is performed separately by policy_eval10 in Minecraft with frozen candidate weights.
"""
import os
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
from pathlib import Path
import argparse, hashlib, json
import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument("--run", action="append", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--initial", type=Path)
parser.add_argument("--steps", type=int, default=1600)
parser.add_argument("--seed", type=int, default=20260930)
args = parser.parse_args()
reference_path = Path(__file__).resolve().parents[2] / "core/src/main/resources/dev/mineagent/runtime/policy/pretrained.json"
initial = json.loads((args.initial or reference_path).read_text())
reference = json.loads(reference_path.read_text())
rng = np.random.default_rng(args.seed)
actors, evidence = [], []
for run in args.run:
    if "mineagent.skillSmokeMode=policy_train" not in (run / "jvm.args").read_text():
        raise ValueError("Only explicit isolated native policy_train runs may be used")
    for file in sorted((run / "game/mineagent-runtime-data/policies").glob("*/*.json")):
        snapshot = file.read_bytes()
        checkpoint = json.loads(snapshot)
        records = checkpoint.get("replay", []) if checkpoint.get("outcomeSchema") == 2 else []
        if not records:
            continue
        x = np.array([r["features"] for r in records], dtype=float)
        y = np.array([r["cost"] for r in records], dtype=float)
        if x.shape[1:] != (16,) or not np.isfinite(y).all() or (y < 0).any() or (y > 1).any():
            raise ValueError("Invalid native training sample")
        actors.append((np.clip(np.nan_to_num(x, nan=0, posinf=0, neginf=0), -2, 2), y))
        evidence.append({"run": run.name, "actor": file.stem, "sha256": hashlib.sha256(snapshot).hexdigest(), "samples": len(y)})
if len(actors) < 5:
    raise ValueError("Need at least five independent native actor trajectories")
order = rng.permutation(len(actors))
held = set(order[:max(1, len(actors) // 5)])
train_x = np.concatenate([x for i, (x, y) in enumerate(actors) if i not in held])
train_y = np.concatenate([y for i, (x, y) in enumerate(actors) if i not in held])
valid_x = np.concatenate([x for i, (x, y) in enumerate(actors) if i in held])
valid_y = np.concatenate([y for i, (x, y) in enumerate(actors) if i in held])
# Rotation of a relative horizontal route leaves these aggregate state features invariant.
x_parts, y_parts = [], []
for turns in range(4):
    x = train_x.copy()
    for _ in range(turns):
        x[:, 8], x[:, 9] = -x[:, 9].copy(), x[:, 8].copy()
    x_parts.append(x)
    y_parts.append(train_y)
train_x, train_y = np.concatenate(x_parts), np.concatenate(y_parts)
def unpack(model):
    return [np.array(model["hidden"], dtype=float), np.array(model["bias"], dtype=float), np.array(model["output"], dtype=float), np.array(model["outputBias"], dtype=float)]
def predict(parameters, x):
    w, b, v, c = parameters
    return 1 / (1 + np.exp(-np.clip(np.tanh(x @ w.T + b) @ v + c, -30, 30)))
params, baseline = unpack(initial), unpack(reference)
anchors = rng.uniform(0, 1, (4096, 16))
anchors[:, 5] = rng.uniform(-1, 1, len(anchors))
anchors[:, 8:10] = rng.uniform(-1, 1, (len(anchors), 2))
anchors[:, [3, 11, 13]] = anchors[:, [3, 11, 13]] > .5
anchor_y = predict(baseline, anchors)
m, q = [np.zeros_like(p) for p in params], [np.zeros_like(p) for p in params]
before = float(np.mean((predict(params, valid_x) - valid_y) ** 2))
best, best_loss, best_step = [p.copy() for p in params], before, 0
for step in range(1, args.steps + 1):
    chosen = rng.integers(0, len(train_x), 128)
    anchor = rng.integers(0, len(anchors), 128)
    x = np.concatenate([train_x[chosen], anchors[anchor]])
    y = np.concatenate([train_y[chosen], anchor_y[anchor]])
    w, b, v, c = params
    h = np.tanh(x @ w.T + b)
    predicted = 1 / (1 + np.exp(-np.clip(h @ v + c, -30, 30)))
    g = (predicted - y) / len(y)
    dh = g[:, None] * v * (1 - h * h)
    gradients = [dh.T @ x, dh.sum(0), h.T @ g, g.sum()]
    for index, (p, gradient) in enumerate(zip(params, gradients)):
        m[index] = .9 * m[index] + .1 * gradient
        q[index] = .999 * q[index] + .001 * gradient * gradient
        p -= .0007 * (m[index] / (1 - .9 ** step)) / (np.sqrt(q[index] / (1 - .999 ** step)) + 1e-8)
        np.clip(p, -8, 8, out=p)
    if step % 20 == 0:
        loss = float(np.mean((predict(params, valid_x) - valid_y) ** 2))
        drift = float(np.mean((predict(params, anchors) - anchor_y) ** 2))
        if loss < best_loss and drift <= .006:
            best, best_loss, best_step = [p.copy() for p in params], loss, step
if best_step == 0:
    raise ValueError("Candidate did not improve held-out native cost loss within the reference drift bound")
w, b, v, c = best
model = {**initial, "version": initial["version"] + 1, "hidden": w.tolist(), "bias": b.tolist(), "output": v.tolist(), "outputBias": float(c), "provenance": "NATIVE_DUEL_OUTCOME_FINE_TUNING_20260930"}
args.output.parent.mkdir(parents=True, exist_ok=True)
raw = (json.dumps(model, separators=(",", ":")) + "\n").encode()
args.output.write_bytes(raw)
report = {"seed": args.seed, "actorTrajectories": len(actors), "trainingSamplesWithRotations": len(train_y), "heldOutSamples": len(valid_y), "heldOutActors": [int(i) for i in sorted(held)], "iterations": best_step, "lossBefore": before, "lossAfter": best_loss, "referenceDrift": float(np.mean((predict(best, anchors) - anchor_y) ** 2)), "sha256": hashlib.sha256(raw).hexdigest(), "sources": evidence, "battleAcceptance": "NOT_YET_RUN", "limitations": "Cost fitting is not proof of nine wins in ten bouts. Run the frozen-weight native evaluation separately."}
args.output.with_suffix(".report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in report.items() if k != "sources"}))
