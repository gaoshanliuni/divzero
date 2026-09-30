"""Fine-tune a candidate on isolated native duel outcomes. Does not replace shipped weights.
Run isolated selfplay_train first. Both sides of a held-out match stay out of training.
Frozen selfplay_eval runs are never training input; rule-controller runs are historical input only.
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
parser.add_argument("--reference", type=Path)
parser.add_argument("--steps", type=int, default=8000)
parser.add_argument("--seed", type=int, default=20260930)
args = parser.parse_args()
reference_path = Path(__file__).resolve().parents[2] / "core/src/main/resources/dev/mineagent/runtime/policy/pretrained.json"
reference_path = args.reference or reference_path
initial_path = args.initial or reference_path
initial = json.loads(initial_path.read_text())
reference = json.loads(reference_path.read_text())
rng = np.random.default_rng(args.seed)
actors, evidence, groups = [], [], []
for run in args.run:
    arguments = (run / "jvm.args").read_text()
    selfplay = "mineagent.skillSmokeMode=selfplay_train" in arguments
    if not selfplay and "mineagent.skillSmokeMode=policy_train" not in arguments:
        raise ValueError("Only explicit isolated native training runs may be used")
    membership = {}
    if selfplay:
        result = json.loads((run / "game/persistent-skill-smoke/result.json").read_text())
        if result.get("status") != "PASS" or result.get("mode") != "selfplay_train":
            raise ValueError("Self-play setup and lifecycle checks must pass before using its samples")
        matrix = next(e for e in result["evidence"] if e.get("controllers") == "NEURAL_VS_NEURAL")
        if matrix.get("status") != "COMPLETE":
            raise ValueError("Incomplete self-play matrix")
        for match in matrix["matches"]:
            if not match.get("arenaVerified"):
                raise ValueError("Unverified arena is not training data")
            case = match["scenario"]
            if case["scenario"] == "OUTNUMBERED" and not match.get("nativeTeamsVerified"):
                continue  # Earlier target-only groups did not prove native sweep/friendly-fire isolation.
            for fighter in match["fighters"]:
                membership[fighter["agent"]] = {"match": f'{run.name}:{case["wave"]}:{case["lane"]}', "scenario": case["scenario"], "role": fighter["role"], "model": fighter["initialModelHash"], "outcome": match["outcome"]}
    for file in sorted((run / "game/mineagent-runtime-data/policies").glob("*/*.json")):
        if selfplay and file.stem not in membership:
            continue
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
        context = membership.get(file.stem, {"match": f"{run.name}:{file.stem}", "scenario": "HISTORICAL_RULE_DUEL"})
        groups.append(context["match"])
        evidence.append({"run": run.name, "actor": file.stem, "sha256": hashlib.sha256(snapshot).hexdigest(), "samples": len(y), **context})
if len(actors) < 5:
    raise ValueError("Need at least five independent native actor trajectories")
unique_groups = sorted(set(groups))
if len(unique_groups) < 5:
    raise ValueError("Need at least five independent native matches")
prior_report = initial_path.with_suffix(".report.json")
inherited_holdout, previously_seen = set(), set()
if prior_report.exists():
    previous = json.loads(prior_report.read_text())
    if previous.get("sha256") != hashlib.sha256(initial_path.read_bytes()).hexdigest():
        raise ValueError("Initial weight report does not match its model")
    inherited_holdout = set(previous.get("heldOutMatches", []))
    previously_seen = {e["match"] for e in previous.get("sources", []) if "match" in e}
held_groups = inherited_holdout.intersection(unique_groups)
new_groups = [g for g in unique_groups if g not in previously_seen]
if new_groups:
    held_groups.update(rng.permutation(new_groups)[:max(1, len(new_groups) // 5)])
if not held_groups:
    raise ValueError("No independent holdout remains for this warm-started model")
held = {i for i, group in enumerate(groups) if group in held_groups}
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
provenance = "NATIVE_SELF_PLAY_OUTCOME_FINE_TUNING_V3" if all(e["scenario"] != "HISTORICAL_RULE_DUEL" for e in evidence) else "NATIVE_OUTCOME_FINE_TUNING_V3"
model = {**initial, "version": initial["version"] + 1, "hidden": w.tolist(), "bias": b.tolist(), "output": v.tolist(), "outputBias": float(c), "provenance": provenance}
args.output.parent.mkdir(parents=True, exist_ok=True)
raw = (json.dumps(model, separators=(",", ":")) + "\n").encode()
args.output.write_bytes(raw)
report = {"seed": args.seed, "actorTrajectories": len(actors), "independentMatches": len(unique_groups), "trainingSamplesWithRotations": len(train_y), "heldOutSamples": len(valid_y), "heldOutActors": [int(i) for i in sorted(held)], "heldOutMatches": sorted(held_groups), "iterations": best_step, "attemptedIterations": args.steps, "lossBefore": before, "lossAfter": best_loss, "referenceDrift": float(np.mean((predict(best, anchors) - anchor_y) ** 2)), "sha256": hashlib.sha256(raw).hexdigest(), "sources": evidence, "battleAcceptance": "NOT_YET_RUN", "limitations": "Cost fitting is not a competitive win rate. Evaluate frozen neural opponents separately across the mirrored scenarios."}
args.output.with_suffix(".report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in report.items() if k != "sources"}))
