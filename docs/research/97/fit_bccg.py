"""Reproduce sex-specific BCCG curves from Salt et al.'s published Modelling rows.

Usage:
  PYTHONPATH=/path/to/dependencies python fit_bccg.py KGC1_dataset.csv [fig1.png]

Dependencies: numpy, scipy, patsy, matplotlib, Pillow. Individual observations are
read from the source CSV but never written to docs. No additional cleaning is done.
"""
from __future__ import annotations

import csv
import hashlib
import json
import math
from pathlib import Path
import sys

import numpy as np
from patsy import dmatrix, build_design_matrices
from scipy.optimize import minimize
from scipy.special import log_ndtr, ndtr, ndtri
from PIL import Image


OUT = Path(__file__).resolve().parent
SOURCE_SHA256 = "76be97fd71d5139fb648e58c69db58945c221df33f1b7f15fc12e244db90e094"
FIG1_SHA256 = "3bcb996c612387e5903e7de4fd7bdec202f99afbfeff0a574167de5d3b4932fd"
PROBS = np.array([.02, .09, .50, .91, .98])
AGES = np.arange(8., 79.)
AGE_BANDS = ((8, 18), (18, 30), (30, 52), (52, 79))
SPECS = {
    # Candidate complexity is deliberately small. Selection uses grouped holdout
    # likelihood; the smoother model wins when scores are practically tied.
    "simple": dict(df=(6, 4, 4), penalty=(10., 20., 40.)),
    "balanced": dict(df=(8, 5, 4), penalty=(10., 20., 40.)),
    "flexible": dict(df=(10, 6, 5), penalty=(10., 20., 40.)),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_modelling(path: Path):
    assert sha256(path) == SOURCE_SHA256, "Unexpected dataset bytes"
    rows = list(csv.DictReader(path.read_text(encoding="utf-8-sig").splitlines()))
    result = {"F": [], "M": []}
    for row in rows:
        if row["DataSet"] in ("Modelling",) and row["Sex"] in result:
            # Preserve every published Modelling row. These values are only parsed,
            # not filtered or winsorised. Display output is restricted to 8--78 wk.
            result[row["Sex"]].append((
                float(row["Visit_Age_Yrs"]) * 365.25 / 7,
                float(row["Weight_kg"]), row["Cat_ID"],
            ))
    return {k: (np.array([r[0] for r in v]), np.array([r[1] for r in v]),
                np.array([r[2] for r in v])) for k, v in result.items()}


def basis(x, df, design_info=None):
    transformed = np.asarray(x) ** .1
    if design_info is None:
        value = dmatrix(f"bs(x, df={df}, degree=3, include_intercept=True) - 1",
                        {"x": transformed})
        return np.asarray(value), value.design_info
    return np.asarray(build_design_matrices([design_info], {"x": transformed})[0])


def unpack(theta, bases):
    sizes = [b.shape[1] for b in bases]
    a, b = sizes[0], sizes[0] + sizes[1]
    mu = np.exp(np.clip(bases[0] @ theta[:a], -5, 4))
    sigma = np.exp(np.clip(bases[1] @ theta[a:b], -5, 1))
    nu = np.clip(bases[2] @ theta[b:], -3, 3)
    return mu, sigma, nu


def logpdf(y, mu, sigma, nu):
    near = np.abs(nu) < 1e-5
    z = np.empty_like(y)
    z[near] = np.log(y[near] / mu[near]) / sigma[near]
    z[~near] = np.expm1(nu[~near] * np.log(y[~near] / mu[~near])) / (nu[~near] * sigma[~near])
    log_norm = np.zeros_like(y)
    nz = ~near
    log_norm[nz] = log_ndtr(1 / (sigma[nz] * np.abs(nu[nz])))
    return (-.5 * z * z - .5 * math.log(2 * math.pi) - np.log(sigma)
            + (nu - 1) * np.log(y) - nu * np.log(mu) - log_norm)


def cdf(y, mu, sigma, nu):
    near = np.abs(nu) < 1e-5
    z = np.where(near, np.log(y / mu) / sigma,
                 np.expm1(nu * np.log(y / mu)) / (nu * sigma))
    result = ndtr(z)
    pos = nu >= 1e-5
    neg = nu <= -1e-5
    if np.any(pos):
        lo = ndtr(-1 / (sigma[pos] * nu[pos]))
        result[pos] = (result[pos] - lo) / (1 - lo)
    if np.any(neg):
        hi = ndtr(-1 / (sigma[neg] * nu[neg]))
        result[neg] /= hi
    return np.clip(result, 0, 1)


def quantile(p, mu, sigma, nu):
    p = np.broadcast_to(np.asarray(p), np.broadcast_shapes(np.shape(p), np.shape(mu)))
    mu, sigma, nu = (np.broadcast_to(v, p.shape) for v in (mu, sigma, nu))
    adjusted = p.copy()
    pos = nu >= 1e-5
    neg = nu <= -1e-5
    adjusted[pos] = ndtr(-1 / (sigma[pos] * nu[pos])) + p[pos] * ndtr(1 / (sigma[pos] * nu[pos]))
    adjusted[neg] = p[neg] * ndtr(-1 / (sigma[neg] * nu[neg]))
    z = ndtri(np.clip(adjusted, 1e-12, 1 - 1e-12))
    near = np.abs(nu) < 1e-5
    q = np.empty_like(z)
    q[near] = mu[near] * np.exp(sigma[near] * z[near])
    q[~near] = mu[~near] * np.maximum(1e-10, 1 + nu[~near] * sigma[~near] * z[~near]) ** (1 / nu[~near])
    return q


def fit(age, weight, spec, start=None):
    bases, infos = [], []
    for df in spec["df"]:
        value, info = basis(age, df)
        bases.append(value); infos.append(info)
    if start is None:
        beta, *_ = np.linalg.lstsq(bases[0], np.log(weight), rcond=None)
        theta = np.r_[beta, np.full(bases[1].shape[1], math.log(.16)),
                      np.zeros(bases[2].shape[1])]
    else:
        theta = start
    def objective(t):
        mu, sigma, nu = unpack(t, bases)
        value = -np.sum(logpdf(weight, mu, sigma, nu))
        offset = 0
        for b, lam in zip(bases, spec["penalty"]):
            coefs = t[offset:offset + b.shape[1]]; offset += b.shape[1]
            value += .5 * lam * np.sum(np.diff(coefs, n=2) ** 2)
        return value
    result = minimize(objective, theta, method="L-BFGS-B", options={"maxiter": 1000, "ftol": 1e-10})
    if not result.success:
        raise RuntimeError(result.message)
    return result.x, bases, infos, float(result.fun), int(result.nit)


def model_values(theta, infos, spec, ages):
    bases = [basis(ages, df, info) for df, info in zip(spec["df"], infos)]
    return unpack(theta, bases)


def digitize_fig1(path: Path):
    """Digitise blue curves for the five requested probabilities; comparison only."""
    assert sha256(path) == FIG1_SHA256, "Unexpected Fig.1 bytes"
    rgb = np.asarray(Image.open(path).convert("RGB"))
    blue = (rgb[:, :, 2] > 160) & (rgb[:, :, 2] > rgb[:, :, 0] * 1.8) & (rgb[:, :, 2] > rgb[:, :, 1] * 1.8)
    # Pixel calibration from labelled 20/40/60/80-week and 0/2/4/6-kg grid lines.
    panels = {"M": (532, 973), "F": (2672, 3114)}
    requested_indices = {2: 1, 9: 2, 50: 4, 91: 6, 98: 7}  # bottom-to-top among 9 curves
    out = []
    for sex, (x20, x40) in panels.items():
        for week in range(8, 79):
            x = round(x20 + (week - 20) * (x40 - x20) / 20)
            ys = np.where(np.any(blue[:, max(0, x - 3):x + 4], axis=1))[0]
            groups = []
            for y in ys:
                if not groups or y > groups[-1][-1] + 3: groups.append([y])
                else: groups[-1].append(y)
            centers = [float(np.mean(g)) for g in groups if len(g) >= 2]
            if len(centers) != 9: continue
            centers.sort(reverse=True)
            for p, idx in requested_indices.items():
                kg = (2443 - centers[idx]) * 2 / 638
                out.append((sex, week, p, kg))
    return out


def main():
    source = Path(sys.argv[1]); fig1 = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    groups = read_modelling(source)
    all_curves, models, diagnostics = [], {}, {"source_sha256": SOURCE_SHA256, "sex": {}}
    fitted = {}
    for sex, (age, weight, cats) in groups.items():
        holdout = np.array([int(hashlib.sha256(c.encode()).hexdigest()[:8], 16) % 5 == 0 for c in cats])
        candidates = []
        for name, spec in SPECS.items():
            theta, _, infos, objective, iterations = fit(age[~holdout], weight[~holdout], spec)
            mu, sigma, nu = model_values(theta, infos, spec, age[holdout])
            score = float(-np.mean(logpdf(weight[holdout], mu, sigma, nu)))
            candidates.append((score, sum(spec["df"]), name, theta, infos, objective, iterations))
        best_score = min(c[0] for c in candidates)
        eligible = [c for c in candidates if c[0] <= best_score + .002]
        chosen = min(eligible, key=lambda c: c[1])
        _, _, name, _, _, _, _ = chosen
        spec = SPECS[name]
        theta, bases, infos, objective, iterations = fit(age, weight, spec)
        fitted[sex] = (theta, infos, spec)
        mu, sigma, nu = model_values(theta, infos, spec, AGES)
        for i, week in enumerate(AGES.astype(int)):
            qs = quantile(PROBS, np.repeat(mu[i], len(PROBS)), np.repeat(sigma[i], len(PROBS)), np.repeat(nu[i], len(PROBS)))
            assert np.all(np.isfinite(qs)) and np.all(qs > 0) and np.all(np.diff(qs) > 0)
            all_curves.append([sex, week, mu[i], sigma[i], nu[i], *qs])
        cal = []
        muo, sigo, nuo = model_values(theta, infos, spec, age)
        pit = cdf(weight, muo, sigo, nuo)
        for lo, hi in AGE_BANDS:
            mask = (age >= lo) & (age < hi)
            cal.append({"weeks": f"{lo}-{hi}", "rows": int(mask.sum()),
                        **{f"below_p{int(p*100):02}": round(float(np.mean(pit[mask] <= p)), 4) for p in PROBS}})
        diagnostics["sex"][sex] = {
            "published_modelling_rows": len(age), "published_modelling_cats": len(set(cats)),
            "chosen": name, "selection_rule": "lowest complexity within 0.002 mean holdout NLL of best",
            "candidates": [{"name": c[2], "holdout_mean_nll": round(c[0], 6), "parameters": c[1]} for c in candidates],
            "iterations": iterations, "penalized_objective": objective,
            "age_range_weeks": [float(age.min()), float(age.max())], "calibration": cal,
        }
        models[sex] = {"family": "BCCG", "age_transform": "weeks^0.1", "basis": "cubic B-spline",
                       "df_mu_sigma_nu": spec["df"], "lambda_mu_sigma_nu": spec["penalty"],
                       "coefficients": [round(float(x), 12) for x in theta]}
    with (OUT / "bccg-curves.csv").open("w", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(["sex", "week", "mu", "sigma", "nu", "p02", "p09", "p50", "p91", "p98"])
        writer.writerows([[r[0], r[1], *[f"{x:.6f}" for x in r[2:]]] for r in all_curves])
    (OUT / "bccg-models.json").write_text(json.dumps(models, indent=2) + "\n")
    if fig1:
        digitized = digitize_fig1(fig1)
        lookup = {(r[0], r[1], p): r[5 + i] for r in all_curves for i, p in enumerate((2, 9, 50, 91, 98))}
        diffs = {}
        for sex in ("F", "M"):
            items = [abs(kg - lookup[(s, w, p)]) for s, w, p, kg in digitized if s == sex and (s, w, p) in lookup]
            diffs[sex] = {"digitized_points": len(items), "mae_kg": round(float(np.mean(items)), 4),
                          "max_abs_kg": round(float(np.max(items)), 4)}
        diagnostics["fig1"] = {"sha256": FIG1_SHA256, "method": "blue-pixel digitisation; approximate, not source data", **diffs}
    (OUT / "bccg-diagnostics.json").write_text(json.dumps(diagnostics, indent=2) + "\n")
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(13, 6), sharex=True, sharey=True)
    for ax, sex in zip(axes, ("M", "F")):
        rows = [r for r in all_curves if r[0] == sex]
        x = [r[1] for r in rows]
        ax.fill_between(x, [r[5] for r in rows], [r[9] for r in rows], alpha=.22, label="P2–P98")
        ax.fill_between(x, [r[6] for r in rows], [r[8] for r in rows], alpha=.35, label="P9–P91")
        ax.plot(x, [r[7] for r in rows], lw=2, label="P50")
        ax.set_title("Male" if sex == "M" else "Female"); ax.set_xlabel("Age (weeks)")
        ax.grid(alpha=.25); ax.legend()
    axes[0].set_ylabel("Weight (kg)"); axes[0].set_ylim(0, 8)
    fig.suptitle("Independent BCCG reproduction — published Modelling rows, no additional cleaning")
    fig.tight_layout(); fig.savefig(OUT / "bccg-curves.png", dpi=160); plt.close(fig)
    print(json.dumps(diagnostics, indent=2))


if __name__ == "__main__":
    main()
