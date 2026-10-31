import numpy as np
import pandas as pd

from config import Paths
from data import load_anomaly_windows

def build_predicted_windows(timestamps, flags):
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

def window_hits_pointwise(timestamps, flags, truth):
    hits = 0
    for s, e in zip(truth["start"].to_list(), truth["end"].to_list()):
        mask = (timestamps >= s) & (timestamps <= e)
        if mask.any() and flags[mask.to_numpy()].any():
            hits += 1
    return hits

def window_precision(predicted_windows, truth):
    if not predicted_windows:
        return 0.0
    tp = 0
    for ps, pe in predicted_windows:
        if ((truth["start"] <= pe) & (truth["end"] >= ps)).any():
            tp += 1
    return tp / len(predicted_windows)

def debounce_k_consecutive(flags: np.ndarray, k: int) -> np.ndarray:
    if k <= 1:
        return flags.astype(bool)

    flags = flags.astype(int)
    debounced = np.zeros_like(flags)
    run = 0
    for i, v in enumerate(flags):
        if v == 1:
            run += 1
        else:
            if run >= k:
                debounced[i-run:i] = 1
            run = 0
    if run >= k:
        debounced[len(flags)-run:len(flags)] = 1
    return debounced.astype(bool)

def main():
    paths = Paths()
    scored = pd.read_csv(paths.scored_out)
    scored["timestamp"] = pd.to_datetime(scored["timestamp"], utc=True, errors="coerce")
    scored = scored.dropna(subset=["timestamp"]).sort_values("timestamp").reset_index(drop=True)

    truth = load_anomaly_windows(paths.anomaly_windows)

    scores = scored["anomaly_score"].to_numpy()
    ts = scored["timestamp"]

    percentiles = [95, 97, 98, 99, 99.3, 99.5, 99.7, 99.9]
    ks = [1, 2, 3]

    results = []
    for p in percentiles:
        thr = np.percentile(scores, p)
        base_flags = (scores >= thr)
        for k in ks:
            flags = debounce_k_consecutive(base_flags, k)
            pred_wins = build_predicted_windows(ts, flags)
            wrec = window_hits_pointwise(ts, flags, truth) / len(truth)
            wprec = window_precision(pred_wins, truth)
            wf1 = (2*wprec*wrec/(wprec+wrec)) if (wprec+wrec) else 0.0
            results.append((p, k, thr, wrec, wprec, wf1, len(pred_wins)))

    # sort after window_f1, then recall
    results.sort(key=lambda x: (x[5], x[3]), reverse=True)

    print("pctl  k   thr                wRec   wPrec  wF1    predWins")
    for r in results[:20]:
        p,k,thr,wrec,wprec,wf1,nw = r
        print(f"{p:>4}  {k}  {thr: .3e}   {wrec: .3f}  {wprec: .3f}  {wf1: .3f}   {nw}")

if __name__ == "__main__":
    main()