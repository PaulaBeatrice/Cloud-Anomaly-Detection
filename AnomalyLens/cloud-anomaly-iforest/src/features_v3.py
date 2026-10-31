import re
from dataclasses import dataclass
from typing import List, Optional

import pandas as pd

RE_DC = re.compile(r"^(datacenter\d+)_", re.IGNORECASE)
RE_STATUS = re.compile(r"_(\d{3})_", re.IGNORECASE)


@dataclass(frozen=True)
class V3FeatureSpec:
    raw_cols: List[str]
    datacenters: List[str]
    include_4xx: bool = True
    security_4xx_only: bool = False


def extract_datacenter(col: str) -> Optional[str]:
    m = RE_DC.match(col)
    return m.group(1).lower() if m else None


def extract_status(col: str) -> Optional[int]:
    m = RE_STATUS.search(col)
    return int(m.group(1)) if m else None


def build_v3_spec(
    all_cols: List[str],
    include_4xx: bool = True,
    security_4xx_only: bool = False,
) -> V3FeatureSpec:
    count_cols = [c for c in all_cols if c.lower().endswith("_count")]

    datacenters = set()
    cols_5xx = []
    cols_4xx = []

    for c in count_cols:
        status = extract_status(c)
        if status is None:
            continue

        dc = extract_datacenter(c)
        if dc:
            datacenters.add(dc)

        if 500 <= status <= 599:
            cols_5xx.append(c)
        elif include_4xx and 400 <= status <= 499:
            if security_4xx_only:
                if status in (401, 403, 429):
                    cols_4xx.append(c)
            else:
                cols_4xx.append(c)

    raw_cols = ["interval_start"] + sorted(set(cols_5xx + cols_4xx))

    return V3FeatureSpec(
        raw_cols=raw_cols,
        datacenters=sorted(datacenters),
        include_4xx=include_4xx,
        security_4xx_only=security_4xx_only,
    )


def transform_to_v3_features(
    df: pd.DataFrame,
    spec: V3FeatureSpec,
    eps: float = 1e-9,
) -> pd.DataFrame:
    if "interval_start" in df.columns:
        df = df.set_index("interval_start")

    count_cols = [c for c in df.columns if c.lower().endswith("_count")]
    cols_5xx, cols_4xx = [], []

    for c in count_cols:
        status = extract_status(c)
        if status is None:
            continue
        if 500 <= status <= 599:
            cols_5xx.append(c)
        elif spec.include_4xx and 400 <= status <= 499:
            cols_4xx.append(c)

    out = pd.DataFrame(index=df.index)

    out["sum_5xx_all"] = df[cols_5xx].sum(axis=1)
    out["sum_4xx_all"] = df[cols_4xx].sum(axis=1)
    out["sum_err_all"] = out["sum_5xx_all"] + out["sum_4xx_all"]

    out["rate_5xx_all"] = out["sum_5xx_all"] / (out["sum_err_all"] + eps)
    out["rate_4xx_all"] = out["sum_4xx_all"] / (out["sum_err_all"] + eps)

    for dc in spec.datacenters:
        prefix = dc + "_"
        dc_5xx = [c for c in cols_5xx if c.lower().startswith(prefix)]
        dc_4xx = [c for c in cols_4xx if c.lower().startswith(prefix)]

        out[f"sum_5xx_{dc}"] = df[dc_5xx].sum(axis=1)
        out[f"sum_4xx_{dc}"] = df[dc_4xx].sum(axis=1)

        denom = out[f"sum_5xx_{dc}"] + out[f"sum_4xx_{dc}"] + eps
        out[f"rate_5xx_{dc}"] = out[f"sum_5xx_{dc}"] / denom

    return out