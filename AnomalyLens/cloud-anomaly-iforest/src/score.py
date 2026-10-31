import os
import time
import joblib
import numpy as np
import pandas as pd

from config import Paths, ScoreConfig
from data import ensure_datetime_index
from features_v3 import transform_to_v3_features


def main():
    paths = Paths()
    scfg = ScoreConfig()
    os.makedirs("outputs/scored", exist_ok=True)

    bundle = joblib.load(paths.model_path)
    pipe = bundle["pipeline"]
    spec = bundle["v3_spec"]

    # Load only required raw columns for V3 aggregation
    t0 = time.perf_counter()
    df = pd.read_parquet(paths.pivoted_parquet, engine="pyarrow", columns=spec.raw_cols)
    df = ensure_datetime_index(df)  # interval_start -> UTC DatetimeIndex

    X_v3 = transform_to_v3_features(df.reset_index(), spec)
    X_v3.index = pd.to_datetime(X_v3.index, utc=True, errors="coerce")

    print(f"V3 scoring input shape: {X_v3.shape} (rows, features)")
    print(f"Load+aggregate time: {time.perf_counter() - t0:.1f}s")

    # Score
    t1 = time.perf_counter()
    signed = pipe.named_steps["iforest"].decision_function(pipe[:-1].transform(X_v3))
    anomaly_score = -signed  # higher => more anomalous
    print(f"Raw scoring time: {time.perf_counter() - t1:.1f}s")

    # Smoothing
    if scfg.smooth_window > 1:
        anomaly_score = (
            pd.Series(anomaly_score)
            .rolling(window=scfg.smooth_window, min_periods=1)
            .mean()
            .to_numpy()
        )

    # Two-tier thresholds
    thr_warn = np.percentile(anomaly_score, scfg.warn_percentile)
    thr_crit = np.percentile(anomaly_score, scfg.crit_percentile)

    timestamp_str = X_v3.index.tz_convert("UTC").strftime("%Y-%m-%dT%H:%M:%SZ")

    out = pd.DataFrame({
        "timestamp": timestamp_str,
        "anomaly_score": anomaly_score,
        "is_warning": (anomaly_score >= thr_warn).astype(int),
        "is_critical": (anomaly_score >= thr_crit).astype(int),
    })

    out.to_csv(paths.scored_out, index=False)
    print(f"Wrote V3 scores to: {paths.scored_out} ({len(out)} rows)")
    print(f"Thresholds: warn(p{scfg.warn_percentile})={thr_warn:.3e}, crit(p{scfg.crit_percentile})={thr_crit:.3e}")


if __name__ == "__main__":
    main()