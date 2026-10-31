from dataclasses import dataclass

@dataclass(frozen=True)
class Paths:
    pivoted_parquet: str = "data/raw/pivoted_data_all.parquet"
    anomaly_windows: str = "data/raw/anomaly_windows.csv"
    model_path: str = "models/iforest.joblib"
    scored_out: str = "outputs/scored/scored.csv"
    report_out: str = "outputs/reports/metrics.json"

@dataclass(frozen=True)
class TrainConfig:
    max_features: int = 800
    contamination: float = 0.01
    n_estimators: int = 300
    max_samples: int = 256
    random_state: int = 42

@dataclass(frozen=True)
class ScoreConfig:
    smooth_window: int = 5
    warn_percentile: float = 92.5
    crit_percentile: float = 99.9