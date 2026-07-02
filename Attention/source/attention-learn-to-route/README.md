# Orienteering Problem on Real Cities (Capital_Cities)

This fork has been trimmed to focus on the **Orienteering Problem (OP)** and extended
to run the pretrained Attention Model on a **real-world list of cities** (e.g. US state
capitals) instead of randomly generated points.

## What the OP is here

Given a depot, a set of cities each with a **prize**, and a maximum route **distance
budget**, find a single loop (depot → cities → depot) that **maximizes total prize
collected** without exceeding the budget. Visiting every city is optional — the model
decides which cities are worth the detour.

> Note on `max_length`: it is a **distance budget**, not a number of cities. How many
> cities get visited is an *output* that depends on the budget, the geography, and the
> prizes.

## Setup

```bash
# from the repo root
python3 -m venv venv
source venv/bin/activate          # re-run this each new terminal session
pip install -r requirements.txt
```

Requires Python ≥ 3.8 and PyTorch ≥ 1.7 (see `requirements.txt`). On a machine without
an NVIDIA GPU, add `--no_cuda` to the `eval.py` command below.

## Input file format

A plain-text file, one city per non-blank line:

```
Name,ST          <lat> <lon>\t<prize>
Albany,NY        42.652552778 -73.75732222	100
```

`Capital_Cities.txt` (48 US state capitals) is included as an example.

## Workflow (3 steps)

**1 — Convert the .txt into an OP instance** (`txt_to_op.py`)

```bash
python txt_to_op.py Capital_Cities.txt --depot "Denver,CO" --max_length 6600 --out data/op/capitals.pkl
```

- `--depot` — a city name exactly as in the file (e.g. `"Denver,CO"`), or `center`
  for the geographic centroid (default).
- `--depot-as-node` — *(optional)* keep the depot city as a collectable node too. By
  **default the depot is start/end only** (standard OP); without this flag the model
  will not waste distance revisiting the depot mid-route.
- `--max_length` — distance budget **in miles**. If omitted, a value ≈ half the full
  nearest-neighbour tour is suggested automatically. The script prints the scale, the
  full-tour estimate, and what fraction of the full tour your budget represents.
- Writes `data/op/capitals.pkl` (the instance) and `data/op/capitals.names.json`
  (city names + original lat/lon, used for decoding).

How distances are handled (so results are honest):
- **Model input** — coordinates are projected (equirectangular, longitude scaled by
  cos(latitude)) into the `[0,1]` box the model was trained on. `--max_length` miles
  are converted into that same normalized space.
- **Reporting** — the final route length is measured back in **real miles using the
  haversine formula** on the original lat/lon (see step 3).

**2 — Run the pretrained model** (existing `eval.py`)

```bash
python eval.py data/op/capitals.pkl --model pretrained/op_dist_50 --decode_strategy greedy -o results.pkl -f
```

- `pretrained/op_dist_50` is the OP checkpoint (distance-based prizes). `op_const_*`
  and `op_unif_*` are also available.
- `-f` overwrites an existing `results.pkl`. Add `--no_cuda` if you have no GPU.
- `--decode_strategy sample --width 1280 --eval_batch_size 1` reports the best of 1280
  samples (usually a bit better than `greedy`).

**3 — Decode the route into city names + real miles** (`decode_route.py`)

```bash
python decode_route.py results.pkl data/op/capitals.names.json
```

Prints the route (`Denver,CO -> SaintPaul,MN -> ... -> Denver,CO`), prize collected vs.
possible, the **true route length in miles (haversine)**, and the list of skipped cities.

## Notes / caveats

- **The model is a strong heuristic, not a proven optimum.** The pretrained weights were
  learned on random uniform points; real geography is out-of-distribution, so the route
  is a good guess rather than guaranteed optimal. For a reference solution, run a
  classical baseline (`problems/op/op_baseline.py`, e.g. Tsiligirides) on the same `.pkl`.
- **Sweep the budget** to see behaviour: lower `--max_length` visits fewer, higher-value,
  well-placed cities; higher visits more. ≈ half the full tour is the hardest/most
  balanced setting (≈ 50% of cities).
- This fork keeps only the OP problem; `txt_to_op.py`, `decode_route.py`,
  `requirements.txt`, and `Capital_Cities.txt` were added on top of the original repo.

---


# Attention, Learn to Solve Routing Problems!

Attention based model for learning to solve the Travelling Salesman Problem (TSP) and the Vehicle Routing Problem (VRP), Orienteering Problem (OP) and (Stochastic) Prize Collecting TSP (PCTSP). Training with REINFORCE with greedy rollout baseline.

![TSP100](images/tsp.gif)

## Paper
For more details, please see our paper [Attention, Learn to Solve Routing Problems!](https://openreview.net/forum?id=ByxBFsRqYm) which has been accepted at [ICLR 2019](https://iclr.cc/Conferences/2019). If this code is useful for your work, please cite our paper:

```
@inproceedings{
    kool2018attention,
    title={Attention, Learn to Solve Routing Problems!},
    author={Wouter Kool and Herke van Hoof and Max Welling},
    booktitle={International Conference on Learning Representations},
    year={2019},
    url={https://openreview.net/forum?id=ByxBFsRqYm},
}
``` 

## Dependencies

* Python>=3.8
* NumPy
* SciPy
* [PyTorch](http://pytorch.org/)>=1.7
* tqdm
* [tensorboard_logger](https://github.com/TeamHG-Memex/tensorboard_logger)
* Matplotlib (optional, only for plotting)

## Quick start

For training TSP instances with 20 nodes and using rollout as REINFORCE baseline:
```bash
python run.py --graph_size 20 --baseline rollout --run_name 'tsp20_rollout'
```

## Usage

### Generating data

Training data is generated on the fly. To generate validation and test data (same as used in the paper) for all problems:
```bash
python generate_data.py --problem all --name validation --seed 4321
python generate_data.py --problem all --name test --seed 1234
```

### Training

For training TSP instances with 20 nodes and using rollout as REINFORCE baseline and using the generated validation set:
```bash
python run.py --graph_size 20 --baseline rollout --run_name 'tsp20_rollout' --val_dataset data/tsp/tsp20_validation_seed4321.pkl
```

#### Multiple GPUs
By default, training will happen *on all available GPUs*. To disable CUDA at all, add the flag `--no_cuda`. 
Set the environment variable `CUDA_VISIBLE_DEVICES` to only use specific GPUs:
```bash
CUDA_VISIBLE_DEVICES=2,3 python run.py 
```
Note that using multiple GPUs has limited efficiency for small problem sizes (up to 50 nodes).

#### Warm start
You can initialize a run using a pretrained model by using the `--load_path` option:
```bash
python run.py --graph_size 100 --load_path pretrained/tsp_100/epoch-99.pt
```

The `--load_path` option can also be used to load an earlier run, in which case also the optimizer state will be loaded:
```bash
python run.py --graph_size 20 --load_path 'outputs/tsp_20/tsp20_rollout_{datetime}/epoch-0.pt'
```

The `--resume` option can be used instead of the `--load_path` option, which will try to resume the run, e.g. load additionally the baseline state, set the current epoch/step counter and set the random number generator state.

### Evaluation
To evaluate a model, you can add the `--eval-only` flag to `run.py`, or use `eval.py`, which will additionally measure timing and save the results:
```bash
python eval.py data/tsp/tsp20_test_seed1234.pkl --model pretrained/tsp_20 --decode_strategy greedy
```
If the epoch is not specified, by default the last one in the folder will be used.

#### Sampling
To report the best of 1280 sampled solutions, use
```bash
python eval.py data/tsp/tsp20_test_seed1234.pkl --model pretrained/tsp_20 --decode_strategy sample --width 1280 --eval_batch_size 1
```
Beam Search (not in the paper) is also recently added and can be used using `--decode_strategy bs --width {beam_size}`.

#### To run baselines
Baselines for different problems are within the corresponding folders and can be ran (on multiple datasets at once) as follows
```bash
python -m problems.tsp.tsp_baseline farthest_insertion data/tsp/tsp20_test_seed1234.pkl data/tsp/tsp50_test_seed1234.pkl data/tsp/tsp100_test_seed1234.pkl
```
To run baselines, you need to install [Compass](https://github.com/bcamath-ds/compass) by running the `install_compass.sh` script from within the `problems/op` directory and [Concorde](http://www.math.uwaterloo.ca/tsp/concorde.html) using the `install_concorde.sh` script from within `problems/tsp`. [LKH3](http://akira.ruc.dk/~keld/research/LKH-3/) should be automatically downloaded and installed when required. To use [Gurobi](http://www.gurobi.com), obtain a ([free academic](http://www.gurobi.com/registration/academic-license-reg)) license and follow the [installation instructions](https://www.gurobi.com/documentation/8.1/quickstart_windows/installing_the_anaconda_py.html).

### Other options and help
```bash
python run.py -h
python eval.py -h
```

### Example CVRP solution
See `plot_vrp.ipynb` for an example of loading a pretrained model and plotting the result for Capacitated VRP with 100 nodes.

![CVRP100](images/cvrp_0.png)

## Acknowledgements
Thanks to [pemami4911/neural-combinatorial-rl-pytorch](https://github.com/pemami4911/neural-combinatorial-rl-pytorch) for getting me started with the code for the Pointer Network.

This repository includes adaptions of the following repositories as baselines:
* https://github.com/MichelDeudon/encode-attend-navigate
* https://github.com/mc-ride/orienteering
* https://github.com/jordanamecler/PCTSP
* https://github.com/rafael2reis/salesman
