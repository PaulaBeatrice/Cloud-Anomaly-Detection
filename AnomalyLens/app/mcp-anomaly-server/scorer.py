import argparse
import os
import sys

import pandas as pd
import joblib


def extract_estimator(loaded_obj):
    """Returnează estimatorul sklearn dintr-un obiect joblib încărcat."""
    # Dacă e deja estimator (are decision_function sau score_samples), îl returnăm direct
    if hasattr(loaded_obj, "decision_function") or hasattr(loaded_obj, "score_samples"):
        return loaded_obj

    # Dacă e dict, încercăm chei comune
    if isinstance(loaded_obj, dict):
        print(f"[INFO] Loaded model artifact is dict with keys: {list(loaded_obj.keys())}")

        for k in ["model", "estimator", "pipeline", "clf", "iforest", "detector"]:
            if k in loaded_obj:
                cand = loaded_obj[k]
                if hasattr(cand, "decision_function") or hasattr(cand, "score_samples"):
                    print(f"[INFO] Using estimator from key='{k}' type={type(cand)}")
                    return cand

        # fallback: caută primul obiect din values care pare estimator
        for k, v in loaded_obj.items():
            if hasattr(v, "decision_function") or hasattr(v, "score_samples"):
                print(f"[INFO] Using estimator from key='{k}' type={type(v)}")
                return v

        raise RuntimeError(
            "Loaded artifact is a dict, but none of its values look like a scikit-learn estimator "
            "(missing decision_function/score_samples)."
        )

    raise RuntimeError(f"Loaded artifact type={type(loaded_obj)} is not an estimator and not a dict.")


def get_feature_names(estimator):
    """Returnează lista de feature-uri așteptate de model, dacă există."""
    if hasattr(estimator, "feature_names_in_"):
        return list(estimator.feature_names_in_)
    if hasattr(estimator, "steps") and hasattr(estimator.steps[-1][1], "feature_names_in_"):
        return list(estimator.steps[-1][1].feature_names_in_)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--warn", type=float, default=92.5)
    ap.add_argument("--crit", type=float, default=99.9)
    ap.add_argument("--timestamp-col", default="interval_start")
    args = ap.parse_args()

    try:
        loaded = joblib.load(args.model)
        estimator = extract_estimator(loaded)
        df = pd.read_csv(args.input)
    except Exception as e:
        print(f"[ERROR] Failed to load model/input: {e}", file=sys.stderr)
        sys.exit(1)

    # Aliniere feature-uri V3 (recomandat)
    feature_names = get_feature_names(estimator)

    if feature_names is not None:
        missing = [c for c in feature_names if c not in df.columns]
        if missing:
            print(f"[WARN] Missing {len(missing)} features, filling with 0. First 10: {missing[:10]}", file=sys.stderr)
            for c in missing:
                df[c] = 0
        X = df[feature_names].copy()
    else:
        X = df.drop(columns=[args.timestamp_col], errors="ignore").copy()

    X = X.apply(pd.to_numeric, errors="coerce").fillna(0)

    try:
        # IsolationForest: decizie -> scor anomalii
        if hasattr(estimator, "decision_function"):
            scores = -estimator.decision_function(X)
        else:
            # fallback (unele modele au score_samples)
            scores = -estimator.score_samples(X)
    except Exception as e:
        print(f"[ERROR] Model scoring failed: {e}", file=sys.stderr)
        sys.exit(1)

    s = pd.Series(scores)
    warn_th = s.quantile(args.warn / 100.0)
    crit_th = s.quantile(args.crit / 100.0)

    ts = df[args.timestamp_col] if args.timestamp_col in df.columns else df.index

    out = pd.DataFrame({
        args.timestamp_col: ts,
        "anomaly_score": scores,
        "is_warning": (scores >= warn_th).astype(int),
        "is_critical": (scores >= crit_th).astype(int),
    })

    try:
        out.to_csv(args.output, index=False)
        out_size = os.path.getsize(args.output)
        print(f"rows_in={len(df)} rows_out={len(out)} out_path={args.output} out_size={out_size}")
        print(f"warn_th={warn_th:.6f} crit_th={crit_th:.6f}")
        if out_size == 0:
            print("[ERROR] Output file is 0 bytes after to_csv.", file=sys.stderr)
            sys.exit(2)
    except Exception as e:
        print(f"[ERROR] Failed to write output: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()