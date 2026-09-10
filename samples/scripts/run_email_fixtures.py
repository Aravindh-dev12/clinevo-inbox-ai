#!/usr/bin/env python3
"""Classify the deterministic synthetic email fixtures and record exact-match evidence."""
from __future__ import annotations

import argparse
import csv
import json
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / "expected" / "email_manifest.json").read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8000")
    parser.add_argument("--out", default=str(ROOT / "outputs"))
    parser.add_argument(
        "--require-exact",
        action="store_true",
        help="exit non-zero when any synthetic email classification differs from the manifest",
    )
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, object]] = []

    for item in MANIFEST:
        started = time.perf_counter()
        response = requests.post(
            f"{args.url.rstrip('/')}/process-text",
            json={"source_name": item["id"], "text": item["body"]},
            timeout=30,
        )
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        response.raise_for_status()
        payload = response.json()
        predicted = [entry["category"] for entry in payload.get("classifications", [])]
        expected = item["expected"]
        exact = set(predicted) == set(expected)
        rows.append(
            {
                "id": item["id"],
                "subject": item["subject"],
                "expected": "|".join(expected),
                "predicted": "|".join(predicted),
                "elapsed_ms_client": elapsed_ms,
                "elapsed_ms_service": payload.get("processing_ms", ""),
                "match": exact,
            }
        )
        print(f"{item['id']}: {elapsed_ms} ms -> {predicted}")

    exact_matches = sum(1 for row in rows if row["match"])
    summary = {
        "emails": len(rows),
        "classification_exact_matches": exact_matches,
        "classification_exact_rate": round(exact_matches / len(rows), 4),
        "mean_client_ms": round(sum(int(row["elapsed_ms_client"]) for row in rows) / len(rows), 1),
        "max_client_ms": max(int(row["elapsed_ms_client"]) for row in rows),
        "note": "Synthetic data only. Exact match is fixture acceptance evidence, not clinical validation.",
    }

    report = {"summary": summary, "items": rows}
    (out / "email_classification_report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    with (out / "email_classification_report.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    print(json.dumps(summary, indent=2))
    if args.require_exact and exact_matches != len(rows):
        failures = ", ".join(str(row["id"]) for row in rows if not row["match"])
        raise SystemExit(
            f"email classification regression: {exact_matches}/{len(rows)} exact; mismatches: {failures}"
        )


if __name__ == "__main__":
    main()
