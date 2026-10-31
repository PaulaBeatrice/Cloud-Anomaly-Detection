import os
import json
from typing import List, Tuple, Dict

import numpy as np
import pandas as pd

from config import Paths
from data import load_anomaly_windows, label_by_windows


def pointwise_metrics(tp: int, fp: int, fn: int, tn: int) -> Dict[str, float]:
    total = tp + fp + fn + tn
    accuracy = (tp + tn) / total if total else 0.0
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    specificity = tn / (tn + fp) if (tn + fp) else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
    balanced_accuracy = (recall + specificity) / 2
    return {
        "accuracy": float(accuracy),
        "precision": float(precision),
        "recall": float(recall),
        "specificity": float(specificity),
        "f1": float(f1),
        "balanced_accuracy": float(balanced_accuracy),
    }

def postprocess_predicted_windows(pred_wins, min_len_points=3, merge_gap_points=2):
    """
    pred_wins: list of (start,end) timestamps (5-min aligned)
    min_len_points: minimum window length in 5-min points (2 => 10 min)
    merge_gap_points: merge windows if gap <= this many points (2 => 10 min)
    """
    if not pred_wins:
        return []

    # sort
    pred_wins = sorted(pred_wins, key=lambda x: x[0])

    # convert gap points to timedeltas
    gap_td = pd.Timedelta(minutes=5 * merge_gap_points)
    min_td = pd.Timedelta(minutes=5 * min_len_points)

    merged = []
    cur_s, cur_e = pred_wins[0]

    for s, e in pred_wins[1:]:
        # if windows overlap or are close enough, merge
        if s <= cur_e + gap_td:
            cur_e = max(cur_e, e)
        else:
            merged.append((cur_s, cur_e))
            cur_s, cur_e = s, e
    merged.append((cur_s, cur_e))

    # filter by minimum duration
    filtered = [(s, e) for s, e in merged if (e - s) >= min_td]

    return filtered


def build_predicted_windows(timestamps: pd.Series, flags: np.ndarray) -> List[Tuple[pd.Timestamp, pd.Timestamp]]:
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


def window_hits_pointwise(timestamps: pd.Series, flags: np.ndarray, truth: pd.DataFrame) -> Tuple[int, int]:
    hits = 0
    for s, e in zip(truth["start"].to_list(), truth["end"].to_list()):
        mask = (timestamps >= s) & (timestamps <= e)
        if mask.any() and flags[mask.to_numpy()].any():
            hits += 1
    return hits, len(truth)


def window_precision(predicted_windows: List[Tuple[pd.Timestamp, pd.Timestamp]], truth: pd.DataFrame) -> float:
    if not predicted_windows:
        return 0.0
    tp = 0
    for ps, pe in predicted_windows:
        if ((truth["start"] <= pe) & (truth["end"] >= ps)).any():
            tp += 1
    return tp / len(predicted_windows)


def eval_level(scored: pd.DataFrame, y_true: np.ndarray, truth: pd.DataFrame, col: str) -> Dict:
    y_pred = scored[col].astype(int).to_numpy()
    flags = y_pred.astype(bool)

    tp = int(((y_pred == 1) & (y_true == 1)).sum())
    fp = int(((y_pred == 1) & (y_true == 0)).sum())
    fn = int(((y_pred == 0) & (y_true == 1)).sum())
    tn = int(((y_pred == 0) & (y_true == 0)).sum())
    pw = pointwise_metrics(tp, fp, fn, tn)

    hits, total = window_hits_pointwise(scored["timestamp"], flags, truth)
    wrec = hits / total if total else 0.0

    # pred_wins = build_predicted_windows(scored["timestamp"], flags)
    pred_wins_raw = build_predicted_windows(scored["timestamp"], flags)

    if col == "is_warning":
        pred_wins = postprocess_predicted_windows(pred_wins_raw, min_len_points=2, merge_gap_points=2)
    else:
        pred_wins = postprocess_predicted_windows(pred_wins_raw, min_len_points=1, merge_gap_points=1)

    wprec = window_precision(pred_wins, truth)

    return {
        "pointwise": {"tp": tp, "fp": fp, "fn": fn, "tn": tn, **pw},
        "window_based": {
            "window_hits": int(hits),
            "window_total": int(total),
            "window_recall": float(wrec),
            "predicted_windows": int(len(pred_wins)),
            "window_precision": float(wprec),
        },
    }


def main():
    paths = Paths()
    os.makedirs("outputs/reports", exist_ok=True)

    scored = pd.read_csv(paths.scored_out)
    scored["timestamp"] = pd.to_datetime(scored["timestamp"], utc=True, errors="coerce")
    scored = scored.dropna(subset=["timestamp"]).sort_values("timestamp").reset_index(drop=True)

    # Ground truth windows
    truth = load_anomaly_windows(paths.anomaly_windows)
    y_true = label_by_windows(pd.DatetimeIndex(scored["timestamp"]), truth)

    # Two-tier evaluation
    warning = eval_level(scored, y_true, truth, "is_warning")
    critical = eval_level(scored, y_true, truth, "is_critical")

    try:
        with open(paths.report_out, "r", encoding="utf-8") as f:
            base = json.load(f)
    except FileNotFoundError:
        base = {}

    base["evaluation_v3_two_tier"] = {
        "warning": warning,
        "critical": critical
    }

    with open(paths.report_out, "w", encoding="utf-8") as f:
        json.dump(base, f, indent=2)

    print("V3 two-tier evaluation saved to metrics.json")
    print(
        f"Warning:  wRec={warning['window_based']['window_recall']:.3f}, "
        f"wPrec={warning['window_based']['window_precision']:.3f}, "
        f"predWins={warning['window_based']['predicted_windows']}"
    )
    print(
        f"Critical: wRec={critical['window_based']['window_recall']:.3f}, "
        f"wPrec={critical['window_based']['window_precision']:.3f}, "
        f"predWins={critical['window_based']['predicted_windows']}"
    )


if __name__ == "__main__":
    main()