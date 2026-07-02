#!/usr/bin/env python3
import argparse
import csv
import json
import math
import os
import pickle
import subprocess
import sys
from pathlib import Path


JAVA_EARTH_RADIUS_MILES = 6371.0 * 0.62
DEFAULT_BUDGETS = [4000, 6000, 8000, 10000]
DEFAULT_DEPOT_COUNT = 20


def build_parser():
    root = Path(__file__).resolve().parents[1]
    attention_dir = root / "source" / "attention-learn-to-route"
    parser = argparse.ArgumentParser(description="Run the Attention OP model on the BC-TSP comparison instances.")
    parser.add_argument("--root", default=str(root), help="Deliverables repository root.")
    parser.add_argument("--attention-dir", default=str(attention_dir), help="Attention model repository.")
    parser.add_argument("--python", default=sys.executable, help="Python executable with torch installed.")
    parser.add_argument("--budgets", default=",".join(str(b) for b in DEFAULT_BUDGETS))
    parser.add_argument("--depot-count", type=int, default=DEFAULT_DEPOT_COUNT)
    parser.add_argument("--model", default="pretrained/op_dist_50")
    parser.add_argument("--decode-strategy", default="greedy", choices=["greedy", "sample", "bs"])
    parser.add_argument("--width", type=int, default=0, help="Sample/beam width. Use 0 for greedy.")
    parser.add_argument("--out-csv", default=str(root / "raw" / "attention_results.csv"))
    parser.add_argument("--dataset-out", default=str(root / "data" / "attention" / "attention_instances.pkl"))
    parser.add_argument("--metadata-out", default=str(root / "data" / "attention" / "attention_instances.metadata.json"))
    parser.add_argument("--results-out", default=str(root / "raw" / "attention_eval_results.pkl"))
    return parser


def parse_budgets(raw):
    return [int(part.strip()) for part in raw.split(",") if part.strip()]


def parse_cities(path):
    cities = []
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 4:
                raise ValueError(f"Cannot parse city line: {raw!r}")
            cities.append({
                "name": parts[0],
                "lat": float(parts[1]),
                "lon": float(parts[2]),
                "prize": int(float(parts[3])),
            })
    return cities


def equirectangular_miles(cities):
    mean_lat = sum(city["lat"] for city in cities) / len(cities)
    cos_lat = math.cos(math.radians(mean_lat))
    deg_to_miles = math.pi / 180.0 * JAVA_EARTH_RADIUS_MILES
    xs = [city["lon"] * cos_lat * deg_to_miles for city in cities]
    ys = [city["lat"] * deg_to_miles for city in cities]
    return xs, ys


def haversine_miles(a, b):
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    h = (
        math.sin((lat2 - lat1) / 2.0) ** 2
        + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2.0) ** 2
    )
    return 2.0 * JAVA_EARTH_RADIUS_MILES * math.asin(math.sqrt(h))


def route_distance(depot_latlon, latlon, indices):
    points = [depot_latlon] + [latlon[i] for i in indices] + [depot_latlon]
    return sum(haversine_miles(points[i], points[i + 1]) for i in range(len(points) - 1))


def repair_to_budget(depot_latlon, latlon, indices, budget):
    repaired = list(indices)
    raw_distance = route_distance(depot_latlon, latlon, repaired)
    while repaired and route_distance(depot_latlon, latlon, repaired) > budget + 1e-6:
        repaired.pop()
    final_distance = route_distance(depot_latlon, latlon, repaired)
    return repaired, raw_distance, final_distance


def build_dataset(cities, budgets, depot_count, dataset_out, metadata_out):
    xs, ys = equirectangular_miles(cities)
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    span = max(max_x - min_x, max_y - min_y)
    if span <= 0:
        raise ValueError("Degenerate coordinates.")

    norm_all = [((x - min_x) / span, (y - min_y) / span) for x, y in zip(xs, ys)]
    instances = []
    metadata = []

    for budget in budgets:
        for idx, city in enumerate(cities[:depot_count]):
            node_indices = [i for i in range(len(cities)) if i != idx]
            depot_xy = list(norm_all[idx])
            loc = [list(norm_all[i]) for i in node_indices]
            prize = [cities[i]["prize"] / 100.0 for i in node_indices]
            max_length = budget / span
            instances.append((depot_xy, loc, prize, max_length))
            metadata.append({
                "dataset": "Capital_Cities.txt",
                "instance_index": idx,
                "depot": city["name"],
                "budget_miles": budget,
                "node_names": [cities[i]["name"] for i in node_indices],
                "node_prizes": [cities[i]["prize"] for i in node_indices],
                "node_latlon": [[cities[i]["lat"], cities[i]["lon"]] for i in node_indices],
                "depot_latlon": [city["lat"], city["lon"]],
                "scale_miles_per_unit": span,
                "max_length_norm": max_length,
                "distance_radius_note": "Distances use the same radius convention as CityNode.java: 6371 km * 0.62 miles/km.",
            })

    dataset_out = Path(dataset_out)
    metadata_out = Path(metadata_out)
    dataset_out.parent.mkdir(parents=True, exist_ok=True)
    metadata_out.parent.mkdir(parents=True, exist_ok=True)
    with open(dataset_out, "wb") as handle:
        pickle.dump(instances, handle, pickle.HIGHEST_PROTOCOL)
    with open(metadata_out, "w", encoding="utf-8") as handle:
        json.dump(metadata, handle, indent=2)
    return metadata


def run_eval(args, dataset_count):
    attention_dir = Path(args.attention_dir)
    cmd = [
        args.python,
        "eval.py",
        str(Path(args.dataset_out).resolve()),
        "--model",
        args.model,
        "--decode_strategy",
        args.decode_strategy,
        "--eval_batch_size",
        "1",
        "--val_size",
        str(dataset_count),
        "--no_cuda",
        "--no_progress_bar",
        "-o",
        str(Path(args.results_out).resolve()),
        "-f",
    ]
    if args.decode_strategy != "greedy":
        cmd.extend(["--width", str(args.width)])
    print("Running:", " ".join(cmd), file=sys.stderr)
    subprocess.run(cmd, cwd=attention_dir, check=True)


def load_eval_results(path):
    with open(path, "rb") as handle:
        loaded = pickle.load(handle)
    return loaded[0] if isinstance(loaded, tuple) else loaded


def write_attention_csv(results, metadata, out_csv):
    out_csv = Path(out_csv)
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "dataset",
        "instance_index",
        "depot",
        "budget_miles",
        "algorithm",
        "collected_prize_excluding_depot",
        "java_raw_prize_including_depot",
        "route_distance_miles",
        "runtime_ms",
        "route",
        "raw_attention_route_distance_miles",
        "repair_applied",
        "feasible_under_java_miles",
    ]
    with open(out_csv, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for result, meta in zip(results, metadata):
            _cost, seq, duration = result
            raw_indices = [int(i) - 1 for i in seq if int(i) > 0]
            repaired, raw_distance, final_distance = repair_to_budget(
                meta["depot_latlon"],
                meta["node_latlon"],
                raw_indices,
                meta["budget_miles"],
            )
            collected = sum(meta["node_prizes"][i] for i in set(repaired))
            route_names = [meta["depot"]] + [meta["node_names"][i] for i in repaired] + [meta["depot"]]
            writer.writerow({
                "dataset": meta["dataset"],
                "instance_index": meta["instance_index"],
                "depot": meta["depot"],
                "budget_miles": meta["budget_miles"],
                "algorithm": "Attention",
                "collected_prize_excluding_depot": collected,
                "java_raw_prize_including_depot": "",
                "route_distance_miles": f"{final_distance:.6f}",
                "runtime_ms": f"{duration * 1000.0:.6f}",
                "route": " -> ".join(route_names),
                "raw_attention_route_distance_miles": f"{raw_distance:.6f}",
                "repair_applied": str(repaired != raw_indices).lower(),
                "feasible_under_java_miles": str(final_distance <= meta["budget_miles"] + 1e-6).lower(),
            })


def main():
    parser = build_parser()
    args = parser.parse_args()
    budgets = parse_budgets(args.budgets)
    root = Path(args.root)
    city_path = root / "source" / "java-bctsp" / "src" / "Capital_Cities.txt"
    cities = parse_cities(city_path)
    metadata = build_dataset(cities, budgets, args.depot_count, args.dataset_out, args.metadata_out)
    run_eval(args, len(metadata))
    results = load_eval_results(args.results_out)
    if len(results) != len(metadata):
        raise RuntimeError(f"Expected {len(metadata)} attention results, got {len(results)}")
    write_attention_csv(results, metadata, args.out_csv)
    print(f"Wrote {args.out_csv}")


if __name__ == "__main__":
    main()
