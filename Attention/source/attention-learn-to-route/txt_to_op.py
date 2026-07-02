#!/usr/bin/env python
"""
Convert a Capital_Cities-style .txt file into an Orienteering Problem (.pkl)
instance that the pretrained Attention Model can evaluate with eval.py.

Input line format (one city per non-blank line):
    Name,ST   <lat>  <lon>\t<prize>
e.g.
    Albany,NY          42.652552778 -73.75732222	100

Distance handling (option 3):
  * MODEL INPUT  -> equirectangular projection (lon scaled by cos(mean lat)) into
                    a miles-based plane, then min-max scaled into the [0,1] box
                    the model was trained on. The model does flat Euclidean math
                    on these, matching its training distribution.
  * --max_length -> given in MILES, converted to normalized units using the same
                    scale factor, so the budget lives in the model's space.
  * REPORTING    -> decode_route.py uses the ORIGINAL lat/long + haversine to
                    report the true route length in miles. (We store lat/long in
                    the sidecar JSON for that.)

Outputs:
  * <out>.pkl                : single-instance list [(depot, loc, prize, max_length)]
  * <out>.names.json         : metadata to decode/report the route afterwards
"""
import argparse
import json
import math
import os

from utils.data_utils import save_dataset

EARTH_RADIUS_MILES = 3958.7613


def parse_cities(path):
    """Return list of dicts: {name, lat, lon, prize}."""
    cities = []
    with open(path, "r") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            # The prize is separated from the coords by a tab in the source file,
            # but be robust: split off the LAST whitespace-delimited token as prize.
            parts = line.split()
            # parts = [name, lat, lon, prize]  (name has no internal spaces: "Albany,NY")
            if len(parts) < 4:
                raise ValueError("Cannot parse line (expected name lat lon prize): {!r}".format(raw))
            name = parts[0]
            lat = float(parts[1])
            lon = float(parts[2])
            prize = float(parts[3])
            cities.append({"name": name, "lat": lat, "lon": lon, "prize": prize})
    if not cities:
        raise ValueError("No cities parsed from {}".format(path))
    return cities


def equirectangular_miles(cities):
    """
    Project (lat, lon) -> (x_miles, y_miles) on a local flat plane centered at the
    mean latitude. x = east-west, y = north-south. Distances on this plane
    approximate true ground distance across a region the size of the continental US.
    """
    mean_lat = sum(c["lat"] for c in cities) / len(cities)
    cos_lat = math.cos(math.radians(mean_lat))
    deg_to_miles = math.pi / 180.0 * EARTH_RADIUS_MILES  # miles per degree of latitude
    xs, ys = [], []
    for c in cities:
        x = c["lon"] * cos_lat * deg_to_miles
        y = c["lat"] * deg_to_miles
        xs.append(x)
        ys.append(y)
    return xs, ys


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", help="Path to the Capital_Cities .txt file")
    parser.add_argument("--depot", default="center",
                        help="Depot: a city name exactly as in the file (e.g. 'Denver,CO'), "
                             "or 'center' for the geographic centroid (default).")
    parser.add_argument("--depot-as-node", dest="depot_as_node", action="store_true",
                        default=False,
                        help="When the depot is a named city, ALSO keep it as a collectable "
                             "node (its prize can be re-collected mid-route). Default off, "
                             "i.e. the depot is start/end only (standard OP convention).")
    parser.add_argument("--max_length", type=float, default=None,
                        help="Distance budget T in MILES. If omitted, a suggested value "
                             "(~half the full nearest-neighbour tour) is used.")
    parser.add_argument("--out", default="data/op/capitals.pkl",
                        help="Output .pkl path (default data/op/capitals.pkl)")
    args = parser.parse_args()

    all_cities = parse_cities(args.input)

    # --- Project ALL cities to a miles plane, then min-max scale into [0,1] --
    # (scale is computed over all cities so the geographic box is stable
    # regardless of which city becomes the depot)
    xs, ys = equirectangular_miles(all_cities)
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    span = max(max_x - min_x, max_y - min_y)  # single scale keeps aspect ratio
    if span <= 0:
        raise ValueError("Degenerate city coordinates (zero span).")

    # scale: 1 normalized unit == `span` miles. So miles -> normalized is /span.
    norm_all = [((x - min_x) / span, (y - min_y) / span) for x, y in zip(xs, ys)]

    # --- Resolve depot, and decide which cities remain as nodes -------------
    if args.depot == "center":
        depot_xy = [sum(p[0] for p in norm_all) / len(norm_all),
                    sum(p[1] for p in norm_all) / len(norm_all)]
        depot_label = "center (centroid)"
        node_idx = list(range(len(all_cities)))  # all cities are nodes
    else:
        match = [i for i, c in enumerate(all_cities) if c["name"] == args.depot]
        if not match:
            raise ValueError("Depot city '{}' not found. Names look like 'Denver,CO'.".format(args.depot))
        di = match[0]
        depot_xy = list(norm_all[di])
        depot_label = args.depot
        if args.depot_as_node:
            node_idx = list(range(len(all_cities)))           # keep depot city as a node too
        else:
            node_idx = [i for i in range(len(all_cities)) if i != di]  # depot is start/end only

    # Build the node lists (all parallel: cities, norm coords, prizes)
    cities = [all_cities[i] for i in node_idx]
    norm = [norm_all[i] for i in node_idx]
    n = len(cities)

    loc = [list(p) for p in norm]

    # --- Prizes: divide by 100 (paper convention) ---------------------------
    prize = [c["prize"] / 100.0 for c in cities]

    # --- Suggest a budget: ~half a nearest-neighbour full tour --------------
    def euclid(a, b):
        return math.hypot(a[0] - b[0], a[1] - b[1])

    # nearest-neighbour tour starting from depot, over all nodes, in NORMALIZED units
    unvisited = list(range(n))
    cur = depot_xy
    nn_len = 0.0
    while unvisited:
        j = min(unvisited, key=lambda k: euclid(cur, loc[k]))
        nn_len += euclid(cur, loc[j])
        cur = loc[j]
        unvisited.remove(j)
    nn_len += euclid(cur, depot_xy)  # return to depot
    suggested_norm = nn_len / 2.0
    suggested_miles = suggested_norm * span

    # --- Resolve max_length (miles -> normalized) ---------------------------
    if args.max_length is None:
        max_length_norm = suggested_norm
        chosen_miles = suggested_miles
        used = "suggested"
    else:
        chosen_miles = args.max_length
        max_length_norm = chosen_miles / span
        used = "user"

    frac = chosen_miles / (nn_len * span) if nn_len > 0 else float("nan")

    # --- Write the instance (single-entry list of the 4-tuple) --------------
    instance = (depot_xy, loc, prize, max_length_norm)
    save_dataset([instance], args.out)

    # Depot's true lat/lon for honest mileage reporting:
    #  - named city  -> that city's coords
    #  - center      -> centroid of all cities' lat/lon (no real coords exist)
    if depot_label.startswith("center"):
        depot_latlon = [sum(c["lat"] for c in all_cities) / len(all_cities),
                        sum(c["lon"] for c in all_cities) / len(all_cities)]
    else:
        depot_latlon = [all_cities[di]["lat"], all_cities[di]["lon"]]

    names_path = os.path.splitext(args.out)[0] + ".names.json"
    with open(names_path, "w") as f:
        json.dump({
            "names": [c["name"] for c in cities],
            "latlon": [[c["lat"], c["lon"]] for c in cities],
            "prize_raw": [c["prize"] for c in cities],
            "depot_label": depot_label,
            "depot_xy": depot_xy,
            "depot_latlon": depot_latlon,
            "depot_as_node": (args.depot != "center" and args.depot_as_node),
            "scale_miles_per_unit": span,
            "max_length_miles": chosen_miles,
            "max_length_norm": max_length_norm,
        }, f, indent=2)

    # --- Report -------------------------------------------------------------
    print("Parsed {} cities; {} usable as nodes".format(len(all_cities), n))
    if args.depot != "center":
        print("Depot: {} ({})".format(
            depot_label,
            "kept as collectable node" if args.depot_as_node else "start/end only"))
    else:
        print("Depot: {}".format(depot_label))
    print("Scale: 1 normalized unit = {:.1f} miles".format(span))
    print("Full nearest-neighbour tour ~= {:.0f} miles".format(nn_len * span))
    print("Suggested max_length (~half tour) = {:.0f} miles".format(suggested_miles))
    print("Using {} max_length = {:.0f} miles  ({:.3f} normalized; ~{:.0%} of full tour)".format(
        used, chosen_miles, max_length_norm, frac))
    print("Wrote {}".format(args.out if args.out.endswith('.pkl') else args.out + '.pkl'))
    print("Wrote {}".format(names_path))


if __name__ == "__main__":
    main()
