from typing import Iterable, List
import re

_RE_5XX = re.compile(r"_5\d\d_", re.IGNORECASE)

def select_feature_columns(cols: Iterable[str], max_features: int = 800) -> List[str]:
    cols = list(cols)

    count_cols = [c for c in cols if c.lower().endswith("_count")]

    cols_5xx = [c for c in count_cols if _RE_5XX.search(c)]

    selected = cols_5xx if cols_5xx else count_cols
    return selected[:max_features]