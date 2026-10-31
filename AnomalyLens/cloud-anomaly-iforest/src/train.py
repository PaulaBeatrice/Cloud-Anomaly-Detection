import os
import json
import joblib
import pandas as pd
import pyarrow.parquet as pq

from sklearn.pipeline import Pipeline
from sklearn.impute import SimpleImputer
from sklearn.preprocessing import StandardScaler
from sklearn.ensemble import IsolationForest

from config import Paths, TrainConfig
from data import ensure_datetime_index
from features_v3 import build_v3_spec, transform_to_v3_features


def main():
    paths = Paths()
    cfg = TrainConfig()

    os.makedirs("models", exist_ok=True)
    os.makedirs("outputs/reports", exist_ok=True)

    pf = pq.ParquetFile(paths.pivoted_parquet)
    all_cols = pf.schema.names

    spec = build_v3_spec(all_cols, include_4xx=True)

    df = pd.read_parquet(paths.pivoted_parquet, engine="pyarrow", columns=spec.raw_cols)
    df = ensure_datetime_index(df)

    X_v3 = transform_to_v3_features(df.reset_index(), spec)
    X_v3.index = pd.to_datetime(X_v3.index, utc=True)

    print(f"V3 aggregated feature table shape: {X_v3.shape}")
    print(f"Detected datacenters: {len(spec.datacenters)}")

    pipe = Pipeline(steps=[
        ("imputer", SimpleImputer(strategy="median")),
        ("scaler", StandardScaler()),
        ("iforest", IsolationForest(
            n_estimators=cfg.n_estimators,
            max_samples=cfg.max_samples,
            contamination=cfg.contamination,
            random_state=cfg.random_state,
            n_jobs=-1,
        ))
    ])

    pipe.fit(X_v3)

    joblib.dump(
        {"pipeline": pipe, "v3_spec": spec},
        paths.model_path,
    )

    with open(paths.report_out, "w", encoding="utf-8") as f:
        json.dump(
            {
                "n_rows": int(X_v3.shape[0]),
                "n_features_used": int(X_v3.shape[1]),
                "v3_datacenters": spec.datacenters,
            },
            f,
            indent=2,
        )

    print("V3 training completed.")


if __name__ == "__main__":
    main()