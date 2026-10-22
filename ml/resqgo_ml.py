"""RESQGO crash model: Phase 2 report, features, training. Python standard library only.

Usage, from the project folder:
  python ml/resqgo_ml.py demo   ml/demo_data          make made-up clips to try the pipeline
  python ml/resqgo_ml.py report <data folder>         Phase 2 numbers from rides and clips
  python ml/resqgo_ml.py train  <data folder> [--out app/src/main/assets/crash_model.json] [--demo]
  python ml/resqgo_ml.py score  <clip.csv> <model.json>   features and score of one clip

<data folder> is the "RESQGO pilot data" folder downloaded from Drive, or unzipped
"Share pilot data" files, or both (duplicates are skipped).
After you call a rider about a no_response alarm, write the answer in <data folder>/labels.csv:
  file,label
  R03-20261102-101500-120-no_response.csv,crash          (or false_alarm)
"""
import csv
import json
import math
import os
import random
import re
import statistics
import sys
from datetime import datetime, timedelta

G = 9.80665
FEATURES = ["peak_g", "hit_excess", "above2g_frac", "pre_std", "post_std",
            "orient_deg", "speed_before", "speed_after", "has_speed"]
POSITIVE = {"staged_rider_ok", "staged_no_response", "crash"}  # staged = bike tipped over in test mode
NEGATIVE = {"rider_ok", "moved_on", "bump", "false_alarm"}
# Left out of training: no_response (unknown until someone calls the rider),
# staged_bump and staged_moved_on (could be a missed staged crash or just handling the bike).
CLIP_NAME = re.compile(r"^(?P<rider>.+)-(?P<time>\d{8}-\d{6}-\d{3})-(?P<label>.+)\.csv$")
MIN_POSITIVE, MIN_NEGATIVE = 10, 30


# ---------- features (CrashFeatures.kt does the same maths; CrashModelTest checks it) ----------

def read_clip(path):
    """Rows of (t_ms, ax, ay, az, kmh or None). Lines starting with # are notes from the app."""
    with open(path, newline="") as f:
        out = []
        for r in csv.DictReader(line for line in f if not line.startswith("#")):
            kmh = r.get("speed_kmh") or ""
            out.append((float(r["t_ms"]), float(r["ax"]), float(r["ay"]), float(r["az"]),
                        float(kmh) if kmh else None))
        return out


def _std(xs):
    if len(xs) < 2:
        return 0.0
    m = sum(xs) / len(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / len(xs))


def _orient(rows, pre, post):
    """Degrees the phone tilted between before and after the hit (gravity direction)."""
    if not pre or not post:
        return 0.0
    a = [sum(rows[i][c] for i in pre) / len(pre) for c in (1, 2, 3)]
    b = [sum(rows[i][c] for i in post) / len(post) for c in (1, 2, 3)]
    na, nb = math.sqrt(sum(x * x for x in a)), math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    c = sum(x * y for x, y in zip(a, b)) / (na * nb)
    return math.degrees(math.acos(max(-1.0, min(1.0, c))))


def features(rows):
    """Cut every clip the same way around its strongest hit, so the model can't learn the clip type."""
    mag = [math.sqrt(ax * ax + ay * ay + az * az) / G for _, ax, ay, az, _ in rows]
    k = 0
    for i in range(1, len(mag)):
        if mag[i] > mag[k]:
            k = i
    t0 = rows[k][0]
    pre = [i for i, r in enumerate(rows) if t0 - 3000 <= r[0] < t0 - 500]
    hit = [i for i, r in enumerate(rows) if t0 - 500 <= r[0] <= t0 + 500]
    post = [i for i, r in enumerate(rows) if t0 + 1500 < r[0] <= t0 + 3000]
    known = [rows[i][4] for i in pre + hit if rows[i][4] is not None]
    after = [r[4] for r in rows if r[0] <= t0 + 3000 and r[4] is not None]
    return [
        mag[k],
        sum(max(0.0, mag[i] - 1) for i in hit) / len(hit),
        sum(1 for i in hit if mag[i] > 2) / len(hit),
        _std([mag[i] for i in pre]),
        _std([mag[i] for i in post]),
        _orient(rows, pre, post),
        max(known) if known else 0.0,
        after[-1] if after else 0.0,
        1.0 if known else 0.0,
    ]


# ---------- model ----------

def _sigmoid(z):
    return 1 / (1 + math.exp(-z)) if z >= 0 else math.exp(z) / (1 + math.exp(z))


def score(model, f):
    z = model["bias"]
    for w, x, m, s in zip(model["weights"], f, model["mean"], model["std"]):
        z += w * ((x - m) / s)
    return _sigmoid(z)


def fit(X, y, epochs=1500, lr=0.2, l2=0.01):
    """Logistic regression, classes balanced. Small enough to run on any phone without TensorFlow."""
    n, d = len(X), len(X[0])
    mean = [sum(r[j] for r in X) / n for j in range(d)]
    std = [_std([r[j] for r in X]) or 1.0 for j in range(d)]
    Z = [[(r[j] - mean[j]) / std[j] for j in range(d)] for r in X]
    pos = sum(y)
    wpos = (n - pos) / pos if pos else 1.0
    w, b = [0.0] * d, 0.0
    for _ in range(epochs):
        gw, gb, tot = [0.0] * d, 0.0, 0.0
        for z, t in zip(Z, y):
            c = wpos if t else 1.0
            e = c * (_sigmoid(b + sum(wj * zj for wj, zj in zip(w, z))) - t)
            gb += e
            tot += c
            for j in range(d):
                gw[j] += e * z[j]
        b -= lr * gb / tot
        w = [wj - lr * (gj / tot + l2 * wj) for wj, gj in zip(w, gw)]
    return {"mean": mean, "std": std, "weights": w, "bias": b}


# ---------- data ----------

def find_files(folder):
    """ride-*.csv rows and labelled clip paths under folder; the same file name is read once."""
    rides, clips, seen = [], [], set()
    for root, _, names in os.walk(folder):
        for name in sorted(names):
            if name in seen:
                continue
            path = os.path.join(root, name)
            if name.startswith("ride-") and name.endswith(".csv"):
                seen.add(name)
                with open(path, newline="") as f:
                    rides.extend(csv.DictReader(f))
            elif CLIP_NAME.match(name):
                seen.add(name)
                clips.append(path)
    return rides, clips


def labelled_clips(folder):
    """(path, rider, time, label) with labels.csv overrides applied."""
    overrides = {}
    lp = os.path.join(folder, "labels.csv")
    if os.path.exists(lp):
        with open(lp, newline="") as f:
            overrides = {r["file"].strip(): r["label"].strip() for r in csv.DictReader(f)}
    out = []
    for p in find_files(folder)[1]:
        m = CLIP_NAME.match(os.path.basename(p))
        t = datetime.strptime(m["time"], "%Y%m%d-%H%M%S-%f")
        out.append((p, m["rider"], t, overrides.get(os.path.basename(p), m["label"])))
    return out


def training_set(folder):
    clips = labelled_clips(folder)
    # Older app versions also saved a bump clip for the hit that became an alarm; drop those twins.
    events = [(r, t) for _, r, t, lab in clips if lab != "bump"]
    X, y, riders = [], [], []
    for p, rider, t, lab in clips:
        if lab not in POSITIVE and lab not in NEGATIVE:
            continue
        if lab == "bump" and any(r == rider and timedelta(0) < et - t <= timedelta(seconds=90) for r, et in events):
            continue
        X.append(features(read_clip(p)))
        y.append(1 if lab in POSITIVE else 0)
        riders.append(rider)
    return X, y, riders


def out_of_fold(X, y, riders):
    """Each clip scored by a model that never saw it: leave one rider out, or 5 folds if under 3 riders."""
    groups = sorted(set(riders))
    if len(groups) >= 3:
        fold = [groups.index(r) for r in riders]
    else:
        order = list(range(len(X)))
        random.Random(7).shuffle(order)
        fold = [0] * len(X)
        for k, i in enumerate(order):
            fold[i] = k % 5
    scores = [0.0] * len(X)
    for f in sorted(set(fold)):
        train = [i for i in range(len(X)) if fold[i] != f]
        if len({y[i] for i in train}) < 2:
            continue
        m = fit([X[i] for i in train], [y[i] for i in train])
        for i in range(len(X)):
            if fold[i] == f:
                scores[i] = score(m, X[i])
    return scores


def train(folder, out=None, demo=False):
    X, y, riders = training_set(folder)
    pos, neg = sum(y), len(y) - sum(y)
    print(f"Training clips: {pos} crashes, {neg} not crashes, {len(set(riders))} riders")
    if not demo and (pos < MIN_POSITIVE or neg < MIN_NEGATIVE):
        print(f"Not enough data yet: need at least {MIN_POSITIVE} crashes and {MIN_NEGATIVE} non-crashes. No model written.")
        return None
    oof = out_of_fold(X, y, riders)
    # Safety first: the model may only call something a false alarm if it scores well below
    # the lowest-scoring crash we have seen. Every known crash stays above the line.
    threshold = min(s for s, t in zip(oof, y) if t) * 0.5
    filtered = sum(1 for s, t in zip(oof, y) if not t and s < threshold)
    print(f"Threshold {threshold:.3f}: keeps {pos}/{pos} crashes, would filter {filtered}/{neg} "
          f"false alarms and bumps ({100 * filtered / max(neg, 1):.0f}%) in cross-validation")
    model = fit(X, y)
    model.update({
        "version": "demo" if demo else "v1-" + datetime.now().strftime("%Y%m%d"),
        "demo": demo,
        "features": FEATURES,
        "threshold": threshold,
        "trained_on": {"crashes": pos, "not_crashes": neg, "riders": len(set(riders))},
    })
    if out:
        with open(out, "w") as f:
            json.dump(model, f, indent=1)
        print("Model written to", out)
    return model


# ---------- Phase 2 report ----------

def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def report(folder):
    rides, _ = find_files(folder)
    clips = labelled_clips(folder)
    real = [r for r in rides if r.get("test_mode") != "1"]
    hours = sum(_num(r["minutes"]) or 0 for r in real) / 60
    print(f"Rides: {len(real)} ({len(rides) - len(real)} test-mode rides left out), "
          f"{len({r['rider'] for r in real})} riders, {hours:.1f} riding hours")
    if not real:
        return {}

    killed = [r for r in real if r.get("ended") == "interrupted"]
    by_phone = {}
    for r in killed:
        by_phone[r["phone"]] = by_phone.get(r["phone"], 0) + 1
    print(f"Interrupted rides (Android stopped RESQGO): {len(killed)} of {len(real)}"
          + "".join(f"\n  {p}: {n}" for p, n in sorted(by_phone.items())))

    alarms = sum(int(r["alarms"]) for r in real)
    ok = sum(int(r["rider_ok"]) for r in real)
    per100 = 100 * ok / hours if hours else 0
    print(f"Alarms: {alarms}; rider tapped I'm OK (false alarm) {ok}")
    print(f"False alarms per 100 riding hours: {per100:.1f} (target under 2) {'PASS' if per100 < 2 else 'FAIL'}")
    flagged = sum(int(r.get("ml_flagged") or 0) for r in real)
    if any("ml_flagged" in r for r in real):
        print(f"Alarms the ML model scored as false alarms (shadow mode, nothing blocked): {flagged}")

    drain = {}
    for r in real:
        mins, b0, b1 = _num(r["minutes"]), _num(r["battery_start"]), _num(r["battery_end"])
        if mins and mins >= 20 and b0 is not None and b1 is not None and b0 >= b1:
            drain.setdefault(r["phone"], []).append((b0 - b1) / (mins / 60))
    allv = [v for vs in drain.values() for v in vs]
    if allv:
        med = statistics.median(allv)
        print(f"Battery: median {med:.1f}% per hour (target under 5) {'PASS' if med < 5 else 'FAIL'}"
              + "".join(f"\n  {p}: {statistics.median(v):.1f}%/h over {len(v)} rides" for p, v in sorted(drain.items())))

    counts = {}
    for _, _, _, lab in clips:
        counts[lab] = counts.get(lab, 0) + 1
    print("Clips:", ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "none")
    staged = counts.get("staged_rider_ok", 0) + counts.get("staged_no_response", 0)
    if staged or counts.get("staged_bump"):
        print(f"Staged crash tests: {staged} raised the alarm; {counts.get('staged_bump', 0)} staged hits "
              f"stayed below the alarm (check if any were meant as crashes)")
    calls = [os.path.basename(p) for p, _, _, lab in clips if lab == "no_response"]
    if calls:
        print("Call these riders, then label the clip in labels.csv as crash or false_alarm:"
              + "".join("\n  " + c for c in calls))
    return {"hours": hours, "alarms": alarms, "false_per_100h": per100, "interrupted": len(killed)}


# ---------- made-up data, only to try the pipeline ----------

def _clip(kind, rnd):
    """One fake clip. Never train a real model on this."""
    long = kind != "bump"
    hit_at = 15000 if long else 3000
    n = (25000 if long else 6000) // 20
    speed = rnd.uniform(18, 35)
    gps = rnd.random() > 0.1
    tilt = math.radians({"staged_rider_ok": rnd.uniform(60, 100), "rider_ok": rnd.uniform(0, 35)}.get(kind, 0))
    peak = {"staged_rider_ok": rnd.uniform(5, 9), "rider_ok": rnd.uniform(4, 6),
            "moved_on": rnd.uniform(4, 5.5), "bump": rnd.uniform(2.5, 3.8)}[kind]
    width = {"staged_rider_ok": 160, "rider_ok": 60, "moved_on": 80, "bump": 40}[kind]
    stops = kind in ("staged_rider_ok", "rider_ok")
    rows = []
    for i in range(n):
        t = i * 20
        after = t > hit_at + width
        noise = 0.15 if (after and stops) else 2.5
        gy, gx = G, 0.0
        if after:
            gy, gx = G * math.cos(tilt), G * math.sin(tilt)
        ax, ay, az = gx + rnd.gauss(0, noise), gy + rnd.gauss(0, noise), rnd.gauss(0, noise)
        if hit_at <= t <= hit_at + width:
            s = peak * G / math.sqrt(3)
            ax, ay, az = s * rnd.choice((1, -1)), s, s * rnd.choice((1, -1))
        kmh = (0.0 if stops and t > hit_at + 2000 else speed + rnd.gauss(0, 1)) if gps else None
        rows.append((t, ax, ay, az, kmh))
    return rows


def demo(folder, seed=7):
    rnd = random.Random(seed)
    os.makedirs(folder, exist_ok=True)
    start = datetime(2026, 11, 2, 8, 0, 0)
    plan = {"staged_rider_ok": 5, "rider_ok": 4, "moved_on": 3, "bump": 15}
    for r, rider in enumerate(["D01", "D02", "D03", "D04"]):
        t = start + timedelta(days=r)
        for kind, count in plan.items():
            for _ in range(count):
                t += timedelta(minutes=rnd.randint(3, 40))
                name = f"{rider}-{t:%Y%m%d-%H%M%S}-{rnd.randint(0, 999):03d}-{kind}.csv"
                with open(os.path.join(folder, name), "w", newline="") as f:
                    w = csv.writer(f)
                    w.writerow(["t_ms", "ax", "ay", "az", "gx", "gy", "gz", "speed_kmh"])
                    for tm, ax, ay, az, kmh in _clip(kind, rnd):
                        w.writerow([int(tm), round(ax, 2), round(ay, 2), round(az, 2), "", "", "",
                                    "" if kmh is None else round(kmh, 1)])
        for d in range(5):
            mins = rnd.uniform(60, 300)
            name = f"ride-{rider}-{t + timedelta(days=d):%Y%m%d-%H%M%S}.csv"
            with open(os.path.join(folder, name), "w", newline="") as f:
                w = csv.writer(f)
                w.writerow(["rider", "phone", "android", "app", "placement", "test_mode", "gyro", "started",
                            "minutes", "battery_start", "battery_end", "alarms", "rider_ok", "sos", "moved_on",
                            "bumps_seen", "bumps_saved", "ended"])
                b0 = rnd.randint(70, 100)
                w.writerow([rider, "Demo Phone " + rider, "14", "0.3.0", "pocket", 0, 0, f"{t:%Y-%m-%d %H:%M:%S}",
                            round(mins, 1), b0, round(b0 - mins / 60 * rnd.uniform(3, 6)), 1, 1, 0, 0, 30, 15,
                            "interrupted" if d == 4 and r == 3 else "stopped"])
    print("Made-up data written to", folder)


if __name__ == "__main__":
    a = sys.argv[1:]
    if len(a) >= 2 and a[0] == "demo":
        demo(a[1])
    elif len(a) >= 2 and a[0] == "report":
        report(a[1])
    elif len(a) >= 2 and a[0] == "train":
        out = a[a.index("--out") + 1] if "--out" in a else None
        train(a[1], out, demo="--demo" in a)
    elif len(a) == 3 and a[0] == "score":
        with open(a[2]) as fm:
            m = json.load(fm)
        f = features(read_clip(a[1]))
        print(json.dumps({"features": f, "score": score(m, f)}))
    else:
        print(__doc__)
