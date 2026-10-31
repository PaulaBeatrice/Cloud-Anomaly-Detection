import re
import argparse
import pandas as pd
import pyarrow.parquet as pq

# Heuristic for status codes in column names (based on dataset description: statusCode embedded in pivoted columns)
CODES_5XX = ["_500_", "_502_", "_503_", "_504_", "_520_", "_521_", "_522_", "_524_", "_530_"]
CODES_4XX = ["_400_", "_401_", "_403_", "_404_", "_408_", "_409_", "_410_", "_412_", "_413_", "_421_", "_422_", "_424_", "_429_"]

def is_5xx(col: str) -> bool:
    return any(code in col for code in CODES_5XX)

def is_4xx(col: str) -> bool:
    return any(code in col for code in CODES_4XX)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parquet", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=500, help="How many rows to export (top N)")
    ap.add_argument("--timestamp", default="interval_start")
    ap.add_argument("--fast", action="store_true",
                    help="Faster: compute rates using (4xx+5xx) denominator instead of total counts. "
                         "Use only if your machine cannot handle reading all _count columns.")
    args = ap.parse_args()

    # Read parquet schema first (cheap) to find relevant columns
    pf = pq.ParquetFile(args.parquet)
    schema_cols = pf.schema.names

    # Ensure timestamp present (dataset uses interval_start as index/column) [1](https://www.linkedin.com/posts/slashdot_amazon-stuck-with-months-of-repairs-after-activity-7456183794082033664-PCjt)[2](https://www.msn.com/en-us/news/technology/drones-attack-several-aws-middle-east-region-data-centers-amid-iran-war-leading-to-outages/ar-AA1XsbW6)
    if args.timestamp not in schema_cols:
        # If parquet stores it as index in pandas, we'll handle after reading
        pass

    # Choose count columns
    count_cols = [c for c in schema_cols if c.endswith("_count")]

    # Detect datacenters from prefixes like datacenter1_, datacenter2_ ... (demo shows datacenter1..7) [2](https://www.msn.com/en-us/news/technology/drones-attack-several-aws-middle-east-region-data-centers-amid-iran-war-leading-to-outages/ar-AA1XsbW6)[1](https://www.linkedin.com/posts/slashdot_amazon-stuck-with-months-of-repairs-after-activity-7456183794082033664-PCjt)
    dcs = sorted({m.group(1) for c in count_cols for m in [re.match(r"(datacenter\d+)_", c)] if m})

    # For V3 we need: all 4xx/5xx _count columns (and optionally all _count columns for exact rates)
    cols_5xx = [c for c in count_cols if is_5xx(c)]
    cols_4xx = [c for c in count_cols if is_4xx(c)]

    if args.fast:
        # Only read timestamp + 4xx + 5xx columns
        cols_to_read = [args.timestamp] + cols_4xx + cols_5xx
    else:
        # Exact: need timestamp + ALL _count columns to compute total denominator
        cols_to_read = [args.timestamp] + count_cols

    # Read only selected columns (still can be heavy, so keep n small if needed)
    df = pd.read_parquet(args.parquet, columns=[c for c in cols_to_read if c in schema_cols])

    # If timestamp wasn't included as a column (stored as index), try reset_index
    if args.timestamp not in df.columns:
        df = df.reset_index()

    # Fill NaNs
    for c in df.columns:
        if c != args.timestamp:
            df[c] = df[c].fillna(0)

    # Build denominators
    if args.fast:
        total_all = (df[cols_4xx].sum(axis=1) + df[cols_5xx].sum(axis=1)).replace(0, 1)
    else:
        # total counts across all _count cols
        total_all = df[[c for c in df.columns if c.endswith("_count")]].sum(axis=1).replace(0, 1)

    sum_5xx_all = df[cols_5xx].sum(axis=1)
    sum_4xx_all = df[cols_4xx].sum(axis=1)
    sum_err_all = sum_5xx_all + sum_4xx_all

    out = pd.DataFrame({
        args.timestamp: df[args.timestamp],
        "sum_5xx_all": sum_5xx_all,
        "sum_4xx_all": sum_4xx_all,
        "sum_err_all": sum_err_all,
        "rate_5xx_all": sum_5xx_all / total_all,
        "rate_4xx_all": sum_4xx_all / total_all,
    })

    # Per datacenter aggregates
    for dc in dcs:
        dc_cols = [c for c in df.columns if c.startswith(dc + "_") and c.endswith("_count")]
        if not dc_cols:
            continue

        dc_5xx_cols = [c for c in dc_cols if is_5xx(c)]
        dc_4xx_cols = [c for c in dc_cols if is_4xx(c)]

        dc_5xx = df[dc_5xx_cols].sum(axis=1) if dc_5xx_cols else 0
        dc_4xx = df[dc_4xx_cols].sum(axis=1) if dc_4xx_cols else 0

        if args.fast:
            dc_total = (dc_4xx + dc_5xx).replace(0, 1)
        else:
            dc_total = df[dc_cols].sum(axis=1).replace(0, 1)

        out[f"sum_5xx_{dc}"] = dc_5xx
        out[f"sum_4xx_{dc}"] = dc_4xx
        out[f"rate_5xx_{dc}"] = dc_5xx / dc_total

    # Export first N rows (small sample)
    out = out.head(args.n)
    out.to_csv(args.out, index=False)
    print(f"Wrote {args.out} with rows={len(out)} cols={len(out.columns)} dcs={len(dcs)} fast={args.fast}")

if __name__ == "__main__":
    main()