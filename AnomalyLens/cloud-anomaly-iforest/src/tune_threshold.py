import numpy as np
import pandas as pd

from config import Paths
from data import load_anomaly_windows


def build_predicted_windows(timestamps: pd.Series, flags: np.ndarray):
    windows = []
    in_win = False
    start = None
    prev_ts = None
    for ts, flag in zip(timestamps.to_list(), flags.tolist()):
        if flag and not in_win:
            in_win = True
            start = ts
        elif (not flag) and in_win:
            in_win = False
            windows.append((start, prev_ts))
        prev_ts = ts
    if in_win and start is not None and prev_ts is not None:
        windows.append((start, prev_ts))
    return windows


def window_hits_pointwise(timestamps: pd.Series, flags: np.ndarray, truth: pd.DataFrame) -> int:
    hits = 0
    for s, e in zip(truth["start"].to_list(), truth["end"].to_list()):
        mask = (timestamps >= s) & (timestamps <= e)
        if mask.any() and flags[mask.to_numpy()].any():
            hits += 1
    return hits


def window_precision(predicted_windows, truth: pd.DataFrame) -> float:
    if not predicted_windows:
        return 0.0
    tp = 0
    for ps, pe in predicted_windows:
        if ((truth["start"] <= pe) & (truth["end"] >= ps)).any():
            tp += 1
    return tp / len(predicted_windows)


def main():
    paths = Paths()
    scored = pd.read_csv(paths.scored_out)
    scored["timestamp"] = pd.to_datetime(scored["timestamp"], utc=True, errors="coerce")
    scored = scored.dropna(subset=["timestamp"]).sort_values("timestamp").reset_index(drop=True)

    truth = load_anomaly_windows(paths.anomaly_windows)

    scores = scored["anomaly_score"].to_numpy()
    ts = scored["timestamp"]

    percentiles = [95, 97, 98, 99, 99.3, 99.5, 99.7, 99.9]
    results = []

    for p in percentiles:
        thr = np.percentile(scores, p)
        flags = (scores >= thr)

        pred_wins = build_predicted_windows(ts, flags)
        wrec = window_hits_pointwise(ts, flags, truth) / len(truth)
        wprec = window_precision(pred_wins, truth)
        wf1 = (2 * wprec * wrec / (wprec + wrec)) if (wprec + wrec) else 0.0
        results.append((p, thr, wrec, wprec, wf1, len(pred_wins)))

    results.sort(key=lambda x: (x[4], x[2]), reverse=True)

    print("pctl  thr            wRec   wPrec  wF1    predWins")
    for p, thr, wrec, wprec, wf1, nwin in results:
        print(f"{p:>4}  {thr: .3e}   {wrec: .3f}  {wprec: .3f}  {wf1: .3f}   {nwin}")


if __name__ == "__main__":
    main()