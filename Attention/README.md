# BC-TSP Attention Comparison

This repository contains the final Fig. 14 plot files and the original source code used to generate the comparison data for P-MARL and the Attention/NCO routing model.

## Final Paper Figures

The paper-ready PDFs are:

- `figures/prize_collected_vs_budget_pmarl_attention.pdf`
- `figures/training_time_vs_budget_pmarl_attention.pdf`

Both final plots compare only:

- `Attention`
- `P-MARL`

The final plot script reads:

- `summary/comparison_summary_by_budget.csv`

## Repository Layout

```text
.
├── figures/
│   ├── fig14_generation_notes.txt
│   ├── prize_collected_vs_budget_pmarl_attention.pdf
│   └── training_time_vs_budget_pmarl_attention.pdf
├── raw/
│   ├── attention_results.csv
│   └── java_baselines.csv
├── scripts/
│   ├── create_figures.py
│   ├── generate_deliverables.py
│   ├── run_attention_batch.py
│   └── java/ComparisonRunner.java
├── source/
│   ├── attention-learn-to-route/
│   └── java-bctsp/
├── summary/
│   ├── comparison_raw_combined.csv
│   └── comparison_summary_by_budget.csv
├── requirements.txt
└── README.md
```

## Included Original Code

- `source/java-bctsp/`: original Java BC-TSP code used for Greedy 1, Greedy 2, and P-MARL, plus the capital-city input files.
- `source/attention-learn-to-route/`: Attention model source code, dependencies, and the `pretrained/op_dist_50` checkpoint used for the Attention runs.
- `scripts/java/ComparisonRunner.java`: Java wrapper used to export Greedy/P-MARL rows to `raw/java_baselines.csv`.
- `scripts/run_attention_batch.py`: Python wrapper used to convert the capital-city instances, run the Attention checkpoint, repair infeasible routes to the same Java-mile budget convention, and write `raw/attention_results.csv`.
- `scripts/generate_deliverables.py`: combines raw Java and Attention rows into summary CSV files.
- `scripts/create_figures.py`: generates the final professor-requested Fig. 14 PDFs.

Compiled Java classes, Python caches, and intermediate pickle files are intentionally excluded.

## Reproduce Final Figures Only

From the repository root:

```bash
python3 -m pip install -r requirements.txt
python3 scripts/create_figures.py
```

Expected terminal output:

```text
Wrote Fig. 14 plot PDFs:
.../figures/prize_collected_vs_budget_pmarl_attention.pdf
.../figures/training_time_vs_budget_pmarl_attention.pdf
```

## Reproduce The Data Pipeline

These commands regenerate the raw rows, summary CSVs, and final PDFs from the included source.

### 1. Java Baselines And P-MARL

Compile the Java source and wrapper:

```bash
mkdir -p build/java
javac -d build/java \
  source/java-bctsp/Agent.java \
  source/java-bctsp/CityNode.java \
  source/java-bctsp/Graph.java \
  source/java-bctsp/TableData.java \
  source/java-bctsp/main.java \
  scripts/java/ComparisonRunner.java
```

Run the Java comparison wrapper from the Java source directory, because the original Java code reads datasets from `src/` relative to its working directory:

```bash
cd source/java-bctsp
java -cp ../../build/java ComparisonRunner ../../raw/java_baselines.csv 20 4000,6000,8000,10000
cd ../..
```

This regenerates:

- `raw/java_baselines.csv`

### 2. Attention Model Rows

Use a Python environment with the Attention dependencies installed. The Attention source requirements are kept separately from the lightweight plotting requirements:

```bash
python3 -m pip install -r source/attention-learn-to-route/requirements.txt
python3 scripts/run_attention_batch.py --python python3
```

This regenerates:

- `raw/attention_results.csv`
- `raw/attention_eval_results.pkl`
- `data/attention/attention_instances.pkl`
- `data/attention/attention_instances.metadata.json`

The generated pickle and metadata files are intermediate artifacts and are ignored by git.

### 3. Summary Tables

```bash
python3 scripts/generate_deliverables.py
```

This regenerates:

- `summary/comparison_raw_combined.csv`
- `summary/comparison_summary_by_budget.csv`

It may also create optional local audit files such as an Excel workbook, markdown summary, and older SVG plots. Those optional outputs are ignored so the repo stays focused on the final Fig. 14 deliverables.

### 4. Final PDFs

```bash
python3 scripts/create_figures.py
```

This regenerates:

- `figures/prize_collected_vs_budget_pmarl_attention.pdf`
- `figures/training_time_vs_budget_pmarl_attention.pdf`
- `figures/fig14_generation_notes.txt`

## Plot Conventions

- Error bars use 95% confidence intervals: `mean +/- t * std / sqrt(n)`.
- `n = 20` for each budget and algorithm.
- The prize plot y-axis label is `Prize Collected`.
- The runtime plot y-axis label is `Training Time (s)`.
- Runtime values are plotted in seconds.
- The runtime plot uses a log-scale y-axis with superscript powers of 10.
- Attention training time includes the fixed offline training/pretraining value used for the comparison.

## Attention Training Time

The Attention checkpoint metadata uses 100 training epochs. The training time included in the runtime figure is:

```text
100 epochs * 16 minutes 20 seconds per epoch = 98,000,000 ms = 98,000 s
```

That fixed training time is added to Attention inference time. Because the training time is fixed, Attention's confidence interval in the runtime plot only reflects inference-time variation.

## Reproducibility Notes

- P-MARL timing includes `learnQ()` plus `traverseQ()` in `scripts/java/ComparisonRunner.java`.
- Greedy timing includes only the corresponding traversal.
- Attention timing includes the fixed training/pretraining value plus local checkpoint inference time.
- The Java P-MARL implementation uses random exploration during learning, so rerunning the Java wrapper can produce slightly different P-MARL rows.
- Attention decoded routes are repaired by trimming visits from the end until the route fits the same Java-mile budget convention.
