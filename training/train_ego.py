"""Train the ego-motion-aware "is this object approaching me?" classifier.

Input: CSVs from the app's record mode (Download/nadaka-logs/ego_*.csv), one row per
tracked object per frame, with ego-motion features and a label you toggled with
volume-up while walking. Output: ego_model.json for app/src/main/assets/.

numpy only, so it runs in Termux on the phone or on the laptop.

    python train_ego.py logs/*.csv               # train, compare with the physics rule
    python train_ego.py --selftest               # synthetic walk data, no phone needed
See docs/ego-motion.md for the method.
"""
import argparse
import csv
import json
import sys

import numpy as np

FEATURES = ["growth", "closing", "ego_speed", "abs_yaw", "abs_pitch", "height", "off_center", "obj_speed", "ttc"]
APPROACH_MPS, APPROACH_TTC = 0.5, 4.0  # same thresholds as the app's rule


def load(paths):
    """Returns per-file (X, y) so we can hold out whole walks, not random rows."""
    walks = []
    for p in paths:
        with open(p, newline="") as f:
            rows = list(csv.DictReader(f))
        if not rows:
            continue
        X = np.array([[float(r[k]) for k in FEATURES] for r in rows], dtype=np.float32)
        y = np.array([int(r["label"]) for r in rows], dtype=np.float32)
        walks.append((p, X, y))
    return walks


def rule(X):
    return ((X[:, FEATURES.index("obj_speed")] > APPROACH_MPS) & (X[:, FEATURES.index("ttc")] < APPROACH_TTC)).astype(np.float32)


def fit(X, y, epochs=400, lr=0.1, l2=1e-3):
    mean, std = X.mean(0), X.std(0) + 1e-6
    Z = (X - mean) / std
    pos = max(y.mean(), 1e-3)
    sw = np.where(y == 1, 0.5 / pos, 0.5 / max(1 - pos, 1e-3))  # class balance: approaches are rare
    w, b = np.zeros(Z.shape[1]), 0.0
    for _ in range(epochs):
        p = 1 / (1 + np.exp(-(Z @ w + b)))
        g = sw * (p - y)
        w -= lr * (Z.T @ g / len(y) + l2 * w)
        b -= lr * g.mean()
    return mean, std, w, b


def predict(model, X, threshold=0.5):
    mean, std, w, b = model
    return (1 / (1 + np.exp(-(((X - mean) / std) @ w + b))) >= threshold).astype(np.float32)


def scores(pred, y):
    tp = float(((pred == 1) & (y == 1)).sum())
    fp = float(((pred == 1) & (y == 0)).sum())
    fn = float(((pred == 0) & (y == 1)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    return prec, rec, (2 * prec * rec / (prec + rec) if prec + rec else 0.0)


def synthetic(n_walks=6, seed=0):
    """Walks where static things loom because I walk, and people walk at me. Mirrors TrackerTest."""
    rng = np.random.default_rng(seed)
    walks = []
    for k in range(n_walks):
        rows, labels = [], []
        for _ in range(400):
            ego = rng.choice([0.0, rng.uniform(0.8, 1.4)])
            moving = rng.random() < 0.3
            obj = rng.uniform(0.8, 1.6) if moving else 0.0
            dist = rng.uniform(2, 8)
            closing = ego + obj + rng.normal(0, 0.25)  # box jitter
            growth = closing / dist
            ttc = min(1 / growth, 10) if growth > 0.01 else 10
            h = 1.7 / (dist * 1.17)
            rows.append([growth, closing, ego, abs(rng.normal(0, 0.3)), abs(rng.normal(0, 0.5)), h,
                         abs(rng.normal(0, 0.15)), closing - ego, ttc])
            labels.append(1.0 if moving and ttc < 6 else 0.0)
        walks.append((f"synthetic_{k}", np.array(rows, dtype=np.float32), np.array(labels, dtype=np.float32)))
    return walks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="*")
    ap.add_argument("--out", default="ego_model.json")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    walks = synthetic() if a.selftest else load(a.csv)
    if len(walks) < 2:
        sys.exit("need at least 2 recorded walks (one is held out for testing)")
    train, test = walks[:-1], walks[-1:]
    Xtr = np.concatenate([w[1] for w in train]); ytr = np.concatenate([w[2] for w in train])
    Xte, yte = test[0][1], test[0][2]
    print(f"train {len(ytr)} rows ({int(ytr.sum())} approaching) from {len(train)} walks; test on {test[0][0]}")

    model = fit(Xtr, ytr)
    p_rule, r_rule, f_rule = scores(rule(Xte), yte)
    p_ml, r_ml, f_ml = scores(predict(model, Xte), yte)
    print(f"physics rule : precision {p_rule:.2f} recall {r_rule:.2f} F1 {f_rule:.2f}")
    print(f"trained model: precision {p_ml:.2f} recall {r_ml:.2f} F1 {f_ml:.2f}")
    mean, std, w, b = model
    for name, wi in sorted(zip(FEATURES, w), key=lambda t: -abs(t[1])):
        print(f"  {name:11s} {wi:+.2f}")

    # Retrain on everything for the shipped model.
    Xall = np.concatenate([Xtr, Xte]); yall = np.concatenate([ytr, yte])
    mean, std, w, b = fit(Xall, yall)
    json.dump({"features": FEATURES, "mean": mean.tolist(), "std": std.tolist(), "w": w.tolist(), "b": float(b),
               "threshold": 0.5, "test_f1_model": f_ml, "test_f1_rule": f_rule}, open(a.out, "w"), indent=1)
    print(f"wrote {a.out} -> copy to app/src/main/assets/ego_model.json")

    if a.selftest:
        assert f_ml > 0.8, f"selftest: model F1 {f_ml:.2f} too low"
        assert w[FEATURES.index("ego_speed")] < 0 or w[FEATURES.index("obj_speed")] > 0, "should learn my walking explains looming"
        print("selftest OK")


if __name__ == "__main__":
    main()
