"""Draws the report's figures from the committed results.

    python -m experiments.analysis.figures

Every figure is drawn from a file this repository already commits and scripts/reproduce.sh
rebuilds: the ladder and sweep reports (whose figures come from metrics.sql) and the
durability model. Nothing is computed here beyond turning a cumulative hazard into a
survival curve. Writes docs/figures/*.png.

All data is synthetic: simulator output, not real passengers. Each figure says so.
"""

from __future__ import annotations

import json
import math
import textwrap
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")  # no display in the research container
import matplotlib.pyplot as plt  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / "experiments" / "reports"
MODELS = ROOT / "intel" / "app" / "hazard" / "models"
OUT = ROOT / "docs" / "figures"

# The current results, as scripts/reproduce.sh rebuilds them.
LADDER = "7a1c0de0-0000-4000-8000-000000000003"
ROBUSTNESS = "7a1c0de0-0000-4000-8000-000000000004"
SWEEPS = [("departure-tolerance", "5eeb0000-0000-4000-8000-000000000004"),
          ("publish-lead", "5eeb0000-0000-4000-8000-000000000005"),
          ("contact-budget", "5eeb0000-0000-4000-8000-000000000003")]
MODEL = "h3"

# Three categorical slots, checked for colour-blind separation; the third is low-contrast
# on white, so wherever it is used its series is labelled directly.
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e5e4e0"
SYNTHETIC = "Synthetic data: simulator output, not real passengers."

plt.rcParams.update({
    "figure.dpi": 100, "savefig.dpi": 200, "font.size": 10,
    "axes.edgecolor": MUTED, "axes.labelcolor": INK, "axes.titlesize": 11, "axes.titleweight": "bold",
    "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.8, "xtick.color": MUTED, "ytick.color": MUTED,
    "legend.frameon": False, "lines.linewidth": 2,
})


def _json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _sweep(name: str, run_id: str) -> dict[str, Any]:
    return _json(REPORTS / f"sweep-{name}-{run_id}.json")


def _finish(fig, name: str, note: str) -> Path:
    # Wrapped to the figure's width, so the note is never cut off.
    wrapped = textwrap.fill(f"{SYNTHETIC} {note}", width=int(fig.get_figwidth() * 15))
    lines = len(wrapped.splitlines())
    fig.text(0.01, 0.01, wrapped, fontsize=7.5, color=MUTED, ha="left", va="bottom")
    fig.tight_layout(rect=(0, 0.035 + 0.03 * lines, 1, 1))
    path = OUT / f"{name}.png"
    # No timestamp in the file, so an unchanged figure is an unchanged file.
    fig.savefig(path, metadata={"Software": None})
    plt.close(fig)
    return path


def _differences(run_id: str, name: str, title: str, order: list[str] | None = None) -> Path:
    """Completed rides per 100 searches, each variant of a run against its baseline, with 95% intervals."""
    report = _json(REPORTS / f"ladder-{run_id}.json")
    names = order or [v for v in report["variants"] if v != report["baseline"]]
    rows = [report["variants"][v]["per_100"]["vs_baseline"] for v in names]
    fig, ax = plt.subplots(figsize=(6.4, 0.9 + 0.5 * len(names)))
    y = range(len(names))[::-1]
    for yi, row in zip(y, rows, strict=True):
        ax.plot([row["ci_low"], row["ci_high"]], [yi, yi], color=BLUE, solid_capstyle="round")
        ax.plot(row["mean_difference"], yi, "o", color=BLUE, markersize=8, markeredgecolor="white", markeredgewidth=2)
        ax.annotate(f"{row['mean_difference']:+.2f}", (row["ci_high"], yi), xytext=(6, 0),
                    textcoords="offset points", va="center", color=INK, fontsize=9)
    ax.axvline(0, color=MUTED, linewidth=1)
    ax.set_yticks(list(y), names)
    ax.set_xlim(left=min(0, min(r["ci_low"] for r in rows)) - 0.05,
                right=max(0, max(r["ci_high"] for r in rows)) + 0.12)
    ax.grid(axis="y", visible=False)
    ax.set_xlabel(f"Difference from {report['baseline']}, completed rides per 100 searches")
    ax.set_title(title, loc="left")
    return _finish(fig, name, f"Run {run_id}, {rows[0]['seeds']} seeds per variant, paired by seed; "
                              "bars are 95% intervals.")


def ladder() -> Path:
    return _differences(LADDER, "fig1-ladder", "Asking passengers (B4, B5) adds nothing to B3")


def robustness() -> Path:
    """Each component removed or replaced, against B3."""
    return _differences(ROBUSTNESS, "fig7-robustness",
                        "No booking features needed; asking adds nothing",
                        order=["B0", "ASK-ONLY", "B3-KM", "B4", "B4-KM", "B5", "B5-RANDOM"])


def refill_timing() -> Path:
    """Share of released seats refilled, on rides whose releases were all early or all late."""
    report = _json(REPORTS / f"ladder-{LADDER}.json")
    names = list(report["variants"])
    fig, ax = plt.subplots(figsize=(6.4, 3.4))
    for i, name in enumerate(names):
        early = report["variants"][name]["early_refill_share"]["mean"]
        late = report["variants"][name]["late_refill_share"]["mean"]
        ax.plot([i, i], [late, early], color=GRID, linewidth=3, zorder=1)
        ax.plot(i, early, "o", color=BLUE, markersize=9, markeredgecolor="white", markeredgewidth=2, zorder=2,
                label="Released 24 h or more before departure" if i == 0 else None)
        ax.plot(i, late, "s", color=ORANGE, markersize=8, markeredgecolor="white", markeredgewidth=2, zorder=2,
                label="Released less than 24 h before" if i == 0 else None)
        if i == 0:
            ax.annotate(f"{early:.0%}", (i, early), xytext=(10, 0), textcoords="offset points", va="center", color=INK)
            ax.annotate(f"{late:.0%}", (i, late), xytext=(10, 0), textcoords="offset points", va="center", color=INK)
    ax.set_xticks(range(len(names)), names)
    ax.set_ylim(0.5, 1.0)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0))
    ax.grid(axis="x", visible=False)
    ax.set_ylabel("Released seats refilled")
    ax.set_title("An early release is refilled far more often than a late one")
    ax.legend(loc="lower right", fontsize=8.5)
    return _finish(fig, "fig2-refill-timing",
                   f"Run {LADDER}, mean of 30 seeds. Rides with both early and late releases are left out.")


SWEEP_TITLES = {"departure-tolerance": ("Passengers' departure window", "hours either side"),
                "publish-lead": ("How early rides are published", "days ahead, at the least"),
                "contact-budget": ("Requests per round", "requests")}


def sweeps() -> Path:
    """The change in completed rides per 100 searches across each sweep's settings."""
    fig, axes = plt.subplots(1, len(SWEEPS), figsize=(9.6, 3.4), sharey=True)
    for ax, (name, run_id) in zip(axes, SWEEPS, strict=True):
        report = _sweep(name, run_id)
        (path, values), = report["parameters"].items()
        cells = {cell["parameters"][path]: cell["measures"]["per_100"]["vs_baseline"] for cell in report["cells"]}
        xs = range(len(values))
        for x, value in zip(xs, values, strict=True):
            row = cells[value]
            ax.plot([x, x], [row["ci_low"], row["ci_high"]], color=BLUE, solid_capstyle="round")
            ax.plot(x, row["mean_difference"], "o", color=BLUE, markersize=8, markeredgecolor="white", markeredgewidth=2)
        ax.axhline(0, color=MUTED, linewidth=1)
        ax.set_xticks(list(xs), [f"{v:g}" for v in values])
        ax.grid(axis="x", visible=False)
        title, unit = SWEEP_TITLES[name]
        variant = report["cells"][0]["variant"].split("[")[0]
        ax.set_title(title, fontsize=10)
        ax.set_xlabel(f"{unit}  ({variant} vs {report['baseline']})", color=MUTED, fontsize=9)
    axes[0].set_ylabel("Change in completed rides\nper 100 searches")
    fig.suptitle("Asking passengers gains nothing at any setting tried, and sometimes costs", fontweight="bold", fontsize=11)
    return _finish(fig, "fig3-sweeps", "30 seeds per setting, paired by seed; bars are 95% intervals.")


def survival() -> Path:
    """The share of bookings not yet cancelled by their passenger, by hours since booking."""
    model = _json(MODELS / f"{MODEL}-km.json")
    times = [0.0, *model["times"]]
    surviving = [1.0, *(math.exp(-h) for h in model["cumulative_hazard"])]
    fig, ax = plt.subplots(figsize=(6.4, 3.4))
    ax.step(times, surviving, where="post", color=BLUE)
    for day in (1, 3, 6):  # past day 6 few bookings are still waiting to depart
        hours = 24 * day
        at = max(i for i, t in enumerate(times) if t <= hours)
        ax.plot(hours, surviving[at], "o", color=BLUE, markersize=7, markeredgecolor="white", markeredgewidth=2)
        ax.annotate(f"{surviving[at]:.0%} after {day} day{'s' if day > 1 else ''}", (hours, surviving[at]),
                    xytext=(8, -26 if day == 1 else 8), textcoords="offset points", color=INK, fontsize=9)
    ax.set_xlim(0, 168)
    ax.set_xticks(range(0, 169, 24), [f"{d}" for d in range(8)])
    ax.set_ylim(0, 1.0)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    ax.set_xlabel("Days since the booking was made")
    ax.set_ylabel("Bookings not cancelled")
    ax.set_title("Bookings not yet cancelled by their passenger", loc="left")
    return _finish(fig, "fig4-survival",
                   f"Kaplan-Meier, model {MODEL}, training seeds only. A booking leaves the curve at departure or when "
                   "its driver withdraws; few were made far enough ahead to reach day 7.")


def calibration() -> Path:
    """Predicted against observed survival to departure, by tenth of prediction."""
    report = _json(REPORTS / f"hazard-{MODEL}.json")
    styles = {"cox_ph": ("Cox PH", BLUE, "o"), "gradient_boosting": ("Gradient boosting", ORANGE, "s")}
    fig, ax = plt.subplots(figsize=(5.2, 4.6))
    ax.plot([0, 1], [0, 1], color=MUTED, linewidth=1, linestyle=(0, (4, 3)))
    ax.annotate("perfect calibration", (0.47, 0.42), rotation=45, color=MUTED, fontsize=8.5)
    for model in report["models"]:
        if model["model"] not in styles:
            continue
        label, colour, marker = styles[model["model"]]
        bins = model["departure"]["calibration"]
        xs = [b["mean_predicted"] for b in bins]
        ys = [b["observed"] for b in bins]
        ax.plot(xs, ys, color=colour, linewidth=1.5, marker=marker, markersize=6,
                markeredgecolor="white", markeredgewidth=1.5,
                label=f"{label} (concordance {model['concordance']:.2f})")
    ax.set_xlim(0.4, 1.0)
    ax.set_ylim(0.4, 1.0)
    ax.set_aspect("equal")
    ax.xaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0))
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0))
    ax.set_xlabel("Predicted chance of lasting to departure")
    ax.set_ylabel("Share that did")
    ax.set_title("Calibrated, though weak at ranking bookings")
    ax.legend(loc="upper left", fontsize=8.5)
    return _finish(fig, "fig5-calibration",
                   f"Model {MODEL}, validation seeds, ten equal groups by prediction.")


def timing() -> Path:
    """Cancellations per seed by hours before departure, for B3, B4 and ORACLE."""
    report = _json(REPORTS / f"timing-{LADDER}.json")
    shown = [("B3", BLUE), ("B4", ORANGE), ("ORACLE", AQUA)]
    # Earliest band on the left, so time runs towards departure.
    bands = report["bands"][::-1]
    width = 0.27
    fig, ax = plt.subplots(figsize=(8.0, 3.8))
    for k, (name, colour) in enumerate(shown):
        means = [row["mean"] for row in report["variants"][name]][::-1]
        xs = [i + (k - 1) * width for i in range(len(bands))]
        # A white edge leaves a gap between neighbouring bars.
        ax.bar(xs, means, width=width, color=colour, edgecolor="white", linewidth=1.5, label=name, zorder=2)
    ax.axvline(len(bands) - 4.5, color=MUTED, linewidth=1, linestyle=(0, (4, 3)))
    ax.set_ylim(0, 80)
    ax.annotate("late: under 24 h  →", (len(bands) - 4.4, 55), color=MUTED, fontsize=8.5)
    ax.set_xticks(range(len(bands)), [f"{b}" for b in bands])
    ax.grid(axis="x", visible=False)
    ax.set_xlabel("Time before departure when the booking was cancelled")
    ax.set_ylabel("Cancellations per seed")
    ax.set_title("Asking (B4) adds cancellations days ahead; the oracle cuts the late ones", loc="left")
    ax.legend(loc="upper right", ncol=3, fontsize=8.5)
    return _finish(fig, "fig6-cancellation-timing",
                   f"Run {LADDER}, mean of 30 seeds; every cancellation, passenger or driver, as in the late share. "
                   "Figures are tabulated in docs/figures/README.md.")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for draw in (ladder, refill_timing, sweeps, survival, calibration, timing, robustness):
        print(f"wrote {draw()}")


if __name__ == "__main__":
    main()
