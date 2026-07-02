#!/usr/bin/env python3
import csv
import math
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import landscape, letter
from reportlab.pdfgen import canvas


ALGORITHM_ORDER = ["Attention", "P-MARL"]
COLORS = {
    "Attention": colors.HexColor("#2563eb"),
    "P-MARL": colors.HexColor("#dc2626"),
    "Greedy 2": colors.HexColor("#16a34a"),
    "Greedy 1": colors.HexColor("#6b7280"),
}
TICK_FONT_SIZE = 30
LABEL_FONT_SIZE = 34
LEGEND_FONT_SIZE = 26
LOG_EXPONENT_FONT_SIZE = 20
ERROR_BAR_WIDTH = 3.0
ERROR_BAR_HALO_WIDTH = 6.0
ERROR_CAP_HALF_WIDTH = 12
MARKER_RADIUS = 6.4
T_CRITICAL_95_BY_DF = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
    6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
    11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131,
    16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093, 20: 2.086,
    21: 2.080, 22: 2.074, 23: 2.069, 24: 2.064, 25: 2.060,
    26: 2.056, 27: 2.052, 28: 2.048, 29: 2.045, 30: 2.042,
}

# No original wall-clock log is stored with pretrained/op_dist_50.
# The checkpoint was trained for 100 epochs. As a documented proxy, use the
# published 50-node Attention Model epoch time: 16 minutes 20 seconds.
ATTENTION_PRETRAINING_MS = 100 * ((16 * 60) + 20) * 1000


def read_summary(path):
    with open(path, newline="", encoding="utf-8") as handle:
        rows = []
        for row in csv.DictReader(handle):
            rows.append({
                "budget_miles": int(row["budget_miles"]),
                "algorithm": row["algorithm"],
                "n": int(row["n"]),
                "mean_prize": float(row["mean_prize"]),
                "std_prize": float(row["std_prize"]),
                "mean_runtime_ms": float(row["mean_runtime_ms"]),
                "std_runtime_ms": float(row["std_runtime_ms"]),
            })
        return rows


def nice_linear_ticks(y_max, count=6):
    if y_max <= 0:
        return [0, 1]
    raw_step = y_max / (count - 1)
    exponent = math.floor(math.log10(raw_step))
    base = raw_step / (10 ** exponent)
    if base <= 1:
        nice_base = 1
    elif base <= 2:
        nice_base = 2
    elif base <= 5:
        nice_base = 5
    else:
        nice_base = 10
    step = nice_base * (10 ** exponent)
    top = math.ceil(y_max / step) * step
    ticks = []
    cur = 0
    while cur <= top + 1e-9:
        ticks.append(cur)
        cur += step
    return ticks


def draw_power_of_ten_label(c, right_x, y, exponent):
    base_text = "10"
    exponent_text = str(exponent)
    base_width = c.stringWidth(base_text, "Helvetica", TICK_FONT_SIZE)
    exponent_width = c.stringWidth(exponent_text, "Helvetica", LOG_EXPONENT_FONT_SIZE)
    start_x = right_x - base_width - exponent_width - 1
    c.setFont("Helvetica", TICK_FONT_SIZE)
    c.drawString(start_x, y - 6, base_text)
    c.setFont("Helvetica", LOG_EXPONENT_FONT_SIZE)
    c.drawString(start_x + base_width + 1, y + 6, exponent_text)
    c.setFont("Helvetica", TICK_FONT_SIZE)


def draw_plot(path, rows, metric, std_metric, title, y_label, log_y=False, nice_y=True,
              include_attention_pretraining=False, error_mode="std", value_scale=1.0):
    path.parent.mkdir(parents=True, exist_ok=True)
    page_w, page_h = landscape(letter)
    c = canvas.Canvas(str(path), pagesize=(page_w, page_h))
    c.setTitle(title)

    left, right, top, bottom = 116, 82, 64, 106
    plot_w = page_w - left - right
    plot_h = page_h - top - bottom

    budgets = sorted({row["budget_miles"] for row in rows})
    algorithms = [alg for alg in ALGORITHM_ORDER if any(row["algorithm"] == alg for row in rows)]
    lookup = {(row["budget_miles"], row["algorithm"]): row for row in rows}
    plot_rows = [row for row in rows if row["algorithm"] in algorithms]

    values = []
    lower_values = []
    for row in plot_rows:
        value = adjusted_value(row, metric, include_attention_pretraining) * value_scale
        err = error_amount(row, std_metric, error_mode) * value_scale
        values.append(value)
        values.append(value + err)
        if value - err > 0:
            lower_values.append(value - err)

    if log_y:
        y_min_exp = math.floor(math.log10(min(lower_values)))
        y_max_exp = math.ceil(math.log10(max(values)))
        if include_attention_pretraining:
            y_max_exp = max(y_max_exp, 6)
        y_min = 10 ** y_min_exp
        y_max = 10 ** y_max_exp
        y_ticks = [10 ** exponent for exponent in range(y_min_exp, y_max_exp + 1)]

        def y_for(value):
            value = max(y_min, min(y_max, value))
            return bottom + (math.log10(value) - math.log10(y_min)) / (math.log10(y_max) - math.log10(y_min)) * plot_h

        def y_fmt(value):
            return int(round(math.log10(value)))
    else:
        y_min = 0
        if nice_y:
            y_ticks = nice_linear_ticks(max(values) * 1.08)
            y_max = y_ticks[-1]
        else:
            y_max = max(values) * 1.08
            y_ticks = [y_min + (y_max - y_min) * tick / 5.0 for tick in range(6)]

        def y_for(value):
            return bottom + (value - y_min) / (y_max - y_min) * plot_h

        def y_fmt(value):
            return f"{value:.0f}"

    def x_for(index):
        if len(budgets) == 1:
            return left + plot_w / 2
        return left + index * plot_w / (len(budgets) - 1)

    c.setFillColor(colors.white)
    c.rect(0, 0, page_w, page_h, stroke=0, fill=1)

    c.setStrokeColor(colors.HexColor("#111827"))
    c.setLineWidth(1.1)
    c.line(left, bottom, left, bottom + plot_h)
    c.line(left, bottom, left + plot_w, bottom)

    c.setFont("Helvetica", TICK_FONT_SIZE)
    for tick in y_ticks:
        y = y_for(tick)
        c.setStrokeColor(colors.HexColor("#e5e7eb"))
        c.setLineWidth(0.6)
        c.line(left, y, left + plot_w, y)
        c.setFillColor(colors.HexColor("#4b5563"))
        if log_y:
            draw_power_of_ten_label(c, left - 10, y, y_fmt(tick))
        else:
            c.drawRightString(left - 10, y - 5, y_fmt(tick))

    c.setFillColor(colors.HexColor("#111827"))
    c.setFont("Helvetica", TICK_FONT_SIZE)
    for i, budget in enumerate(budgets):
        x = x_for(i)
        c.setStrokeColor(colors.HexColor("#111827"))
        c.line(x, bottom, x, bottom - 5)
        c.drawCentredString(x, bottom - 32, str(budget))

    c.setFont("Helvetica", LABEL_FONT_SIZE)
    c.drawCentredString(left + plot_w / 2, 26, "Budget (miles)")
    c.saveState()
    c.translate(34, bottom + plot_h / 2)
    c.rotate(90)
    c.drawCentredString(0, 0, y_label)
    c.restoreState()

    legend_item_width = 175
    legend_x = left + plot_w - (legend_item_width * len(algorithms)) - 16
    legend_y = bottom + plot_h - 30
    c.setFillColor(colors.white)
    c.rect(legend_x - 8, legend_y - 15, legend_item_width * len(algorithms) - 20, 34, stroke=0, fill=1)
    c.setFont("Helvetica", LEGEND_FONT_SIZE)
    for i, algorithm in enumerate(algorithms):
        x = legend_x + i * legend_item_width
        color = COLORS[algorithm]
        c.setStrokeColor(color)
        c.setFillColor(color)
        c.setLineWidth(2.8)
        c.line(x, legend_y, x + 26, legend_y)
        c.circle(x + 13, legend_y, 6.0, stroke=0, fill=1)
        c.setFillColor(colors.HexColor("#111827"))
        c.drawString(x + 34, legend_y - 7, algorithm)

    for algorithm in algorithms:
        color = COLORS[algorithm]
        points = []
        for i, budget in enumerate(budgets):
            row = lookup[(budget, algorithm)]
            x = x_for(i)
            y = y_for(adjusted_value(row, metric, include_attention_pretraining) * value_scale)
            points.append((x, y, row))

        c.setStrokeColor(color)
        c.setLineWidth(1.8)
        for (x1, y1, _), (x2, y2, _) in zip(points, points[1:]):
            c.line(x1, y1, x2, y2)

        for x, y, row in points:
            value = adjusted_value(row, metric, include_attention_pretraining) * value_scale
            err = error_amount(row, std_metric, error_mode) * value_scale
            if err > 0:
                y_hi = y_for(value + err)
                y_lo = y_for(max(y_min if log_y else 0, value - err))
                c.setStrokeColor(colors.white)
                c.setLineWidth(ERROR_BAR_HALO_WIDTH)
                c.line(x, y_lo, x, y_hi)
                c.line(x - ERROR_CAP_HALF_WIDTH, y_hi, x + ERROR_CAP_HALF_WIDTH, y_hi)
                c.line(x - ERROR_CAP_HALF_WIDTH, y_lo, x + ERROR_CAP_HALF_WIDTH, y_lo)
                c.setStrokeColor(color)
                c.setLineWidth(ERROR_BAR_WIDTH)
                c.line(x, y_lo, x, y_hi)
                c.line(x - ERROR_CAP_HALF_WIDTH, y_hi, x + ERROR_CAP_HALF_WIDTH, y_hi)
                c.line(x - ERROR_CAP_HALF_WIDTH, y_lo, x + ERROR_CAP_HALF_WIDTH, y_lo)
            c.setFillColor(color)
            c.circle(x, y, MARKER_RADIUS, stroke=0, fill=1)
            c.setStrokeColor(colors.white)
            c.setLineWidth(1.0)
            c.circle(x, y, MARKER_RADIUS, stroke=1, fill=0)

    c.showPage()
    c.save()


def adjusted_value(row, metric, include_attention_pretraining):
    value = row[metric]
    if include_attention_pretraining and metric == "mean_runtime_ms" and row["algorithm"] == "Attention":
        return value + ATTENTION_PRETRAINING_MS
    return value


def error_amount(row, std_metric, error_mode):
    if error_mode == "none":
        return 0.0
    if error_mode == "ci95":
        df = max(row["n"] - 1, 1)
        t_critical = T_CRITICAL_95_BY_DF.get(df, 1.96)
        return t_critical * row[std_metric] / math.sqrt(row["n"])
    return row[std_metric]


def write_pretraining_note(path):
    path.write_text(
        "Fig. 14 plot generation notes\n"
        "-----------------------------------------------------------------\n"
        "The generated plot PDFs compare only Attention and P-MARL.\n"
        "Output PDFs:\n"
        "- figures/prize_collected_vs_budget_pmarl_attention.pdf\n"
        "- figures/training_time_vs_budget_pmarl_attention.pdf\n"
        "No original wall-clock training log is stored with the pretrained op_dist_50 checkpoint.\n"
        "The checkpoint metadata shows 100 training epochs. The execution-time PDF adds a documented proxy\n"
        "for Attention pretraining time: 100 epochs * 16 minutes 20 seconds per epoch = 98,000,000 ms.\n"
        "The 16:20 per-epoch value is the published 50-node Attention Model single-GPU epoch time reported\n"
        "for the Attention, Learn to Solve Routing Problems model family. The route-evaluation inference\n"
        "time from our local run is still included on top of this pretraining proxy.\n"
        "Training-time plots display these runtime values in seconds.\n"
        "Error bars use 95% confidence intervals: mean +/- t * std / sqrt(n).\n",
        encoding="utf-8",
    )


def main():
    root = Path(__file__).resolve().parents[1]
    rows = read_summary(root / "summary" / "comparison_summary_by_budget.csv")
    figures = root / "figures"
    draw_plot(
        figures / "prize_collected_vs_budget_pmarl_attention.pdf",
        rows,
        "mean_prize",
        "std_prize",
        "Collected Prize vs Budget",
        "Prize Collected",
        log_y=False,
        nice_y=True,
        error_mode="ci95",
    )
    draw_plot(
        figures / "training_time_vs_budget_pmarl_attention.pdf",
        rows,
        "mean_runtime_ms",
        "std_runtime_ms",
        "Execution Time vs Budget",
        "Training Time (s)",
        log_y=True,
        include_attention_pretraining=True,
        error_mode="ci95",
        value_scale=0.001,
    )
    write_pretraining_note(figures / "fig14_generation_notes.txt")
    print("Wrote Fig. 14 plot PDFs:")
    print(figures / "prize_collected_vs_budget_pmarl_attention.pdf")
    print(figures / "training_time_vs_budget_pmarl_attention.pdf")


if __name__ == "__main__":
    main()
