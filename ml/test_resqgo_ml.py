"""Run: python ml/test_resqgo_ml.py  (made-up data only; checks the pipeline, not real-world accuracy)"""
import os
import shutil
import tempfile

import resqgo_ml as ml

d = tempfile.mkdtemp()
try:
    ml.demo(d)
    clips = ml.labelled_clips(d)
    by = {lab: [p for p, _, _, l in clips if l == lab] for lab in ("staged_rider_ok", "bump", "rider_ok")}

    # A tip-over ends still and tilted; a pothole keeps shaking and stays upright.
    crash = ml.features(ml.read_clip(by["staged_rider_ok"][0]))
    bump = ml.features(ml.read_clip(by["bump"][0]))
    assert crash[ml.FEATURES.index("orient_deg")] > 45 > bump[ml.FEATURES.index("orient_deg")], (crash, bump)
    assert crash[ml.FEATURES.index("post_std")] < bump[ml.FEATURES.index("post_std")], (crash, bump)

    # Rider IDs with dashes, and labels.csv overrides.
    m = ml.CLIP_NAME.match("R-07-20261102-101500-120-no_response.csv")
    assert (m["rider"], m["label"]) == ("R-07", "no_response")
    with open(os.path.join(d, "labels.csv"), "w") as f:
        f.write("file,label\n" + os.path.basename(by["rider_ok"][0]) + ",crash\n")
    assert sum(1 for p, _, _, l in ml.labelled_clips(d) if l == "crash") == 1
    os.remove(os.path.join(d, "labels.csv"))

    # Real training refuses too little data; demo training keeps every crash above the threshold.
    X, y, riders = ml.training_set(d)
    model = ml.train(d, demo=True)
    oof = ml.out_of_fold(X, y, riders)
    assert all(s >= model["threshold"] for s, t in zip(oof, y) if t)
    assert all(ml.score(model, x) > model["threshold"] for x, t in zip(X, y) if t)

    small = tempfile.mkdtemp()
    shutil.copy(by["staged_rider_ok"][0], small)
    shutil.copy(by["bump"][0], small)
    assert ml.train(small) is None
    shutil.rmtree(small)

    r = ml.report(d)
    assert r["interrupted"] == 1 and r["hours"] > 0
    print("\nAll ML checks passed")
finally:
    shutil.rmtree(d)
