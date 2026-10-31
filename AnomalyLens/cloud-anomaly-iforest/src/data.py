import pandas as pd
import numpy as np

import pandas as pd
import numpy as np

def _to_datetime_utc(series: pd.Series) -> pd.Series:
    """
    Convert a timestamp-like Series to tz-aware UTC datetimes.
    Handles:
      - already datetime dtype
      - ISO strings
      - numeric epoch in s/ms/us/ns (auto-detected)
    """
    # Already datetime?
    if pd.api.types.is_datetime64_any_dtype(series):
        s = pd.to_datetime(series, utc=True, errors="coerce")
        return s

    if pd.api.types.is_object_dtype(series) or pd.api.types.is_string_dtype(series):
        s = pd.to_datetime(series, utc=True, errors="coerce")
        if s.notna().mean() > 0.9:
            return s

    # Numeric epoch auto-detection
    s_num = pd.to_numeric(series, errors="coerce")
    v = s_num.dropna()
    if v.empty:
        return pd.to_datetime(series, utc=True, errors="coerce")

    med = float(v.median())

    # Heuristic thresholds:
    # epoch seconds ~ 1e9 (2024 ~ 1.7e9)
    # epoch ms      ~ 1e12
    # epoch us      ~ 1e15
    # epoch ns      ~ 1e18
    if med < 1e11:
        unit = "s"
    elif med < 1e14:
        unit = "ms"
    elif med < 1e17:
        unit = "us"
    else:
        unit = "ns"

    return pd.to_datetime(s_num, unit=unit, utc=True, errors="coerce")

def ensure_datetime_index(df: pd.DataFrame) -> pd.DataFrame:
    if "interval_start" in df.columns:
        df["interval_start"] = _to_datetime_utc(df["interval_start"])
        df = df.set_index("interval_start")
    else:
        if not isinstance(df.index, pd.DatetimeIndex):
            df.index = pd.to_datetime(df.index, utc=True, errors="coerce")
        else:
            if df.index.tz is None:
                df.index = df.index.tz_localize("UTC")
            else:
                df.index = df.index.tz_convert("UTC")
    return df

def _to_utc(ts) -> pd.Timestamp:
    """
    Force any datetime-like value to UTC tz-aware Timestamp.
    Handles tz-naive strings/np.datetime64/Timestamps gracefully.
    """
    t = pd.to_datetime(ts, errors="coerce")
    if pd.isna(t):
        return pd.NaT
    if getattr(t, "tzinfo", None) is None and getattr(t, "tz", None) is None:
        # tz-naive -> localize as UTC
        return pd.Timestamp(t).tz_localize("UTC")
    # tz-aware -> convert to UTC
    return pd.Timestamp(t).tz_convert("UTC")

def load_anomaly_windows(path: str) -> pd.DataFrame:
    aw = pd.read_csv(path)

    start_col = next((c for c in aw.columns if "start" in c.lower()), None)
    end_col   = next((c for c in aw.columns if "end" in c.lower()), None)
    if not start_col or not end_col:
        raise ValueError(f"Could not find start/end columns in anomaly_windows.csv: {aw.columns.tolist()}")

    aw[start_col] = aw[start_col].apply(_to_utc)
    aw[end_col]   = aw[end_col].apply(_to_utc)

    aw = aw.dropna(subset=[start_col, end_col]).sort_values(start_col)
    aw = aw.rename(columns={start_col: "start", end_col: "end"})
    return aw[["start", "end"]]

def label_by_windows(index: pd.DatetimeIndex, windows: pd.DataFrame) -> np.ndarray:
    # Asigurăm index UTC tz-aware
    if index.tz is None:
        index = index.tz_localize("UTC")
    else:
        index = index.tz_convert("UTC")

    y = np.zeros(len(index), dtype=int)

    for s, e in zip(windows["start"].to_list(), windows["end"].to_list()):
        if pd.isna(s) or pd.isna(e):
            continue
        mask = (index >= s) & (index <= e)
        y[mask] = 1

    return y