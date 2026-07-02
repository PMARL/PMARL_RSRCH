#!/usr/bin/env python
"""
Decode an OP eval.py result for the Capital_Cities instance into a human-readable
route, and report the TRUE route length in miles using the haversine formula on
the original lat/long (option 3: model routes on projected coords, we report in
real miles).

Usage:
    python decode_route.py <results.pkl> <capitals.names.json>

<results.pkl> is what eval.py writes (the -o file, or the auto-named ...-<strategy>.pkl).
It contains (results, parallelism), where results is a list of (cost, seq, duration)
per instance. For OP, seq is the tour as node indices with the depot EXCLUDED and
nodes numbered 1..n (node 0 == depot in the model's internal indexing).
"""
import argparse
import json
import math
import pickle

EARTH_RADIUS_MILES = 3958.7613


def haversine(a, b):
    """Great-circle distance in miles between (lat,lon) a and b."""
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * EARTH_RADIUS_MILES * math.asin(math.sqrt(h))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", help="eval.py results .pkl")
    parser.add_argument("names", help="the .names.json written by txt_to_op.py")
    parser.add_argument("--instance", type=int, default=0,
                        help="Which instance in the results to decode (default 0)")
    args = parser.parse_args()

    with open(args.results, "rb") as f:
        loaded = pickle.load(f)
    results = loaded[0] if isinstance(loaded, tuple) else loaded
    cost, seq, duration = results[args.instance]

    meta = json.load(open(args.names))
    names = meta["names"]
    latlon = meta["latlon"]
    prize_raw = meta["prize_raw"]
    depot_label = meta["depot_label"]
    depot_xy = meta["depot_xy"]

    # The depot's true lat/lon is provided directly by txt_to_op.py (works whether
    # the depot is a named city, kept-as-node or not, or the centroid).
    if "depot_latlon" in meta:
        depot_ll = meta["depot_latlon"]
    elif depot_label.startswith("center"):
        depot_ll = [sum(p[0] for p in latlon) / len(latlon),
                    sum(p[1] for p in latlon) / len(latlon)]
    else:
        depot_ll = latlon[names.index(depot_label)]

    # seq holds model node indices 1..n (depot 0 excluded). Convert to 0-based
    # indices into our cities list.
    visited = [int(i) - 1 for i in seq]

    # --- Build the ordered route with names ---------------------------------
    order = [depot_label] + [names[i] for i in visited] + [depot_label]

    # --- True mileage via haversine on original lat/lon ---------------------
    pts = [depot_ll] + [latlon[i] for i in visited] + [depot_ll]
    miles = sum(haversine(pts[k], pts[k + 1]) for k in range(len(pts) - 1))

    # --- Prize collected (raw, un-normalized) -------------------------------
    collected = sum(prize_raw[i] for i in visited)
    total_prize = sum(prize_raw)

    visited_set = set(visited)
    skipped = [names[i] for i in range(len(names)) if i not in visited_set]

    # --- Report -------------------------------------------------------------
    print("Depot: {}".format(depot_label))
    print("Cities visited: {} of {}".format(len(visited), len(names)))
    print()
    print("Route:")
    print("  " + " -> ".join(order))
    print()
    print("Prize collected: {:.0f} of {:.0f} possible ({:.0%})".format(
        collected, total_prize, collected / total_prize if total_prize else 0))
    print("True route length: {:.0f} miles (haversine)".format(miles))
    print()
    if skipped:
        print("Skipped ({}): {}".format(len(skipped), ", ".join(skipped)))
    else:
        print("Skipped: none")


if __name__ == "__main__":
    main()
