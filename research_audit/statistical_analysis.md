# VAYORA — Statistical analysis audit

Audit date: 2026-10-29. Auditor's evidence labels used throughout:

- **[VERIFIED]** checked by the auditor against code, the raw event log or report files, with the location given.
- **[REPORTED]** stated in a project report or document and not independently recomputed.
- **[ASSUMPTION]** a modelling choice in the project, not evidence.
- **[RECOMMENDATION]** the auditor's advice.

All simulation results are synthetic. Nothing here is evidence about real passengers.

## 1. Which numbers exist

| Evidence set | Where | World / model | Status |
|---|---|---|---|
| committed_h2 | `JOURNEX/experiments/reports/` (git HEAD `30653cf`, 2026-10-20) | repeat search on; model `h2` | Quoted by README, EXPERIMENT_PROTOCOL, AI_ML_METHODOLOGY (partly `h1`) |
| regenerated_h3 | `Vayora-main/experiments/reports/` (rebuilt 2026-10-28) | adds the last-mile effect; model `h3` | Not committed; not yet quoted by any document |
| audit_h3 | `research_audit/data/audit_stats.json`, `audit_seats.json` | the regenerated runs' raw events | Recomputed by this audit, read-only SQL |
| history | sweeps `…001` (intervention response, world `h1`) and `…002` (supply density) | earlier code | Not rebuilt by `scripts/reproduce.sh` |

**The manuscript (`VAYORA_Research_Paper.pdf`) reports no results at all** ("It does not yet report experimental results", Section VI). Every number below is therefore new to the paper.

The regenerated set was produced on 2026-10-28 in an earlier session with this same assistant, at the team's request; this audit discloses that rather than treating those files as independent.

## 2. The design as implemented

| Element | Paper (Section V) | Implementation | Verdict |
|---|---|---|---|
| Replicates | 30 seeds per configuration | 30 seeds (`sim/frozen.py:SEEDS_PER_VARIANT`) | [VERIFIED] matches |
| Pairing | same seed, same searches and latent behaviour | Separate named RNG streams per seed (`sim/simulator.py:651`, `_stream`); tests assert identical searches and rides across variants (`sim/tests/test_simulator.py`) | [VERIFIED] matches; common random numbers correctly implemented |
| Test | Wilcoxon signed-rank with Holm over 11 comparisons | Wilcoxon, **unadjusted** (`experiments/analysis/compare.py:58`, `paired`); EXPERIMENT_PROTOCOL.md s.3 states no correction | [VERIFIED] mismatch |
| Interval | bootstrap 95% | paired **t** interval (`compare.py:66`) | [VERIFIED] mismatch |
| Model data split | "first part of each simulated period" | disjoint seed sets: train 1001–1020, validation 2001–2010, experiments 1–30, enforced (`experiments/hazard/train.py:43`, `load_config`) | [VERIFIED] implementation is stronger than the paper states |
| Primary metric | completed rides / searches, ride counted only when both sides confirm | `trip_completed` events / `search_issued` events per seed (`experiments/analysis/metrics.sql`, `primary_metric`); no confirmation step in the simulator | [VERIFIED] definitional mismatch (Section 5) |

## 3. Primary contrasts recomputed from the raw event log

Method [VERIFIED]: per-seed values recomputed by `research_audit/scripts/audit_stats.py` with read-only SQL over the `events` table for runs `7a1c0de0-…-003` (ladder) and `…-004` (robustness). The recomputed variant means equal the regenerated ladder report to every printed digit (B0 30.22, B3 30.54, B4 30.56, B5 30.53, ORACLE 30.66). Bootstrap: 20,000 percentile resamples of the 30 per-seed differences, generator seed 20261029. Holm is applied over the 13 contrasts in the table. d_z is the mean difference over the standard deviation of differences. MDE is the smallest true mean difference a two-sided paired t test at α = 0.05 detects with 80% power, given the observed spread.

Unit: completed bookings per 100 first searches (the project's primary metric).

| Contrast | Run | Mean diff | 95% t CI | 95% bootstrap CI | 90% t CI | Wilcoxon p | Holm p | d_z | MDE (80%) | Relative | Seeds up/down |
|---|---|---|---|---|---|---|---|---|---|---|---|
| B3 − B0 | …003 | +0.319 | +0.220 to +0.417 | +0.227 to +0.411 | +0.237 to +0.401 | 6.9e-7 | 8.3e-6 | +1.21 | 0.140 | +1.05% | 26/4 |
| B4 − B0 | …003 | +0.345 | +0.249 to +0.441 | +0.255 to +0.437 | +0.266 to +0.425 | 5.3e-6 | 5.3e-5 | +1.34 | 0.136 | +1.14% | 26/2 |
| B5 − B0 | …003 | +0.315 | +0.219 to +0.411 | +0.225 to +0.406 | +0.235 to +0.395 | 1.5e-5 | 0.0001 | +1.22 | 0.137 | +1.04% | 26/2 |
| ORACLE − B0 | …003 | +0.444 | +0.360 to +0.529 | +0.365 to +0.523 | +0.374 to +0.514 | 1.9e-9 | 2.4e-8 | +1.97 | 0.120 | +1.47% | 30/0 |
| B4 − B3 | …003 | +0.027 | −0.018 to +0.072 | −0.015 to +0.070 | −0.011 to +0.064 | 0.2905 | 1.0000 | +0.22 | 0.064 | +0.09% | 17/10 |
| ORACLE − B3 | …003 | +0.126 | +0.085 to +0.166 | +0.089 to +0.164 | +0.092 to +0.159 | 1.7e-5 | 0.0001 | +1.16 | 0.057 | +0.41% | 25/3 |
| B0 − B3 | …004 | −0.319 | −0.417 to −0.220 | −0.412 to −0.226 | −0.401 to −0.237 | 6.9e-7 | 8.3e-6 | −1.21 | 0.140 | −1.04% | 4/26 |
| B4 − B3 | …004 | +0.027 | −0.018 to +0.072 | −0.016 to +0.069 | −0.011 to +0.064 | 0.2905 | 1.0000 | +0.22 | 0.064 | +0.09% | 17/10 |
| B3-KM − B3 | …004 | −0.004 | −0.026 to +0.018 | −0.025 to +0.017 | −0.022 to +0.014 | 0.7578 | 1.0000 | −0.06 | 0.031 | −0.01% | 10/12 |
| B4-KM − B3 | …004 | +0.012 | −0.035 to +0.059 | −0.032 to +0.055 | −0.027 to +0.051 | 0.6480 | 1.0000 | +0.09 | 0.066 | +0.04% | 16/11 |
| ASK-ONLY − B3 | …004 | −0.306 | −0.407 to −0.205 | −0.401 to −0.211 | −0.390 to −0.222 | 3.4e-5 | 0.0002 | −1.13 | 0.143 | −1.00% | 4/24 |
| B5 − B3 | …004 | −0.004 | −0.031 to +0.023 | −0.029 to +0.021 | −0.026 to +0.019 | 0.7570 | 1.0000 | −0.05 | 0.038 | −0.01% | 13/12 |
| B5-RANDOM − B3 | …004 | +0.002 | −0.017 to +0.021 | −0.017 to +0.020 | −0.014 to +0.018 | 0.9696 | 1.0000 | +0.04 | 0.027 | +0.01% | 13/13 |

Runs …003 and …004 share seeds and variants B0, B3, B4 and B5, so their rows for those variants are the same data, not replications. [VERIFIED]

**Findings**

1. **Every conclusion survives Holm correction and bootstrap intervals.** t and bootstrap intervals agree to within 0.01. The pipeline's lack of these is a reporting gap, not an error in any conclusion. [VERIFIED]
2. **The ranking gain is large in standardised terms but small in absolute terms.** d_z ≈ 1.2 (consistent across seeds), yet +0.32 bookings per 100 searches is about 1% of the baseline.
3. **The nulls are precise, so they can be stated as upper bounds.** For B4 − B3 the 90% interval is −0.011 to +0.064, so a two one-sided test (TOST) would establish equivalence for any pre-declared margin above about 0.065 per 100 searches. B3-KM − B3 is bounded within ±0.022, B5-RANDOM − B3 within ±0.018. [RECOMMENDATION] The team should **pre-declare a smallest effect size of interest** before the next run. Choosing a margin after seeing these intervals would be circular.
4. **Power is adequate for the questions asked.** With 30 seeds the paired MDE is 0.03–0.14 per 100 searches, smaller than the oracle ceiling (+0.126). More seeds would not change any conclusion.

### Stability across world versions

The same contrasts were run under three successive simulated worlds. The conclusions do not move:

| Contrast | World h1, run …001 [REPORTED] | World h2, committed [REPORTED] | World h3, regenerated [VERIFIED] |
|---|---|---|---|
| B3 − B0 | +0.30 (+0.24 to +0.36) | +0.34 (+0.25 to +0.42) | +0.32 (+0.22 to +0.42) |
| B4 − B3 | 0.00 (29.89 vs 29.89) | −0.01 (−0.04 to +0.03) | +0.03 (−0.02 to +0.07) |
| ORACLE − B3 | not run | +0.10 (+0.07 to +0.13) | +0.13 (+0.09 to +0.17) |
| B3-KM − B3 | not run | −0.01 (−0.04 to +0.01) | −0.00 (−0.03 to +0.02) |
| B5-RANDOM − B3 | not run | −0.00 (−0.03 to +0.02) | +0.00 (−0.02 to +0.02) |

Sources: EXPERIMENT_PROTOCOL.md s.5 (h1); `JOURNEX/experiments/reports/ladder-…003/004.json` (h2); audit (h3). All six regenerated sweeps keep their conclusions. One cell (publish lead = 7 days) prints an upper bound of −0.00 because of rounding; its exact value is −0.0033, so it is still significant. [VERIFIED]

This stability is genuine robustness evidence and should be reported as such.

## 4. A mechanical explanation of the ranking gain [VERIFIED]

`intel/app/hazard/model.py` scores a candidate ride by S(h | x) = exp(−H0(h)·exp(βᵀ(x − x̄))), where h is the hours from now to departure. For the Kaplan–Meier model every coefficient is zero, so S depends only on h through H0, which never decreases. `sim/simulator.py:_rank` sorts by −S, then by distance from the preferred time. **B3-KM is therefore exactly "earliest departure within the ±2 h window"**, apart from ties inside one step of H0. The Cox model adds βᵀx, but H0(h) is shared and dominates (the paired difference B3-KM − B3 is −0.004, CI ±0.02).

Implication: B3's gain (+0.32) is a **scheduling effect** that a one-line heuristic reproduces. It does not depend on survival modelling.

[RECOMMENDATION] Add an explicit `EARLIEST` baseline (rank by ascending departure inside the window) and report it beside B0 and B3. This needs one new variant file and a ranking key. If it equals B3-KM, as the mathematics predicts, the paper must say that the survival model contributes nothing beyond timing in this world.

## 5. Metric definitions

### 5.1 The primary metric counts bookings, not people [VERIFIED]

`metrics.sql` `primary_metric` counts `trip_completed` events. One event per booking, whatever its party size (1–3 seats, `world.yaml` `party_size`). The audit computed a seat-weighted version: seats on completed bookings per 100 first searches.

| Contrast | Measure | Mean diff | 95% t CI | Wilcoxon p | Holm p (family) |
|---|---|---|---|---|---|
| B3 − B0 (…003) | seats per 100 | +0.545 | +0.406 to +0.683 | 3.5e-6 | not adjusted |
| B4 − B0 (…003) | seats per 100 | +0.683 | +0.555 to +0.811 | 1.9e-9 | not adjusted |
| B4 − B3 (…003) | seats per 100 | +0.138 | +0.092 to +0.184 | 3.8e-5 | not adjusted |
| B0 − B3 (…004) | seats per 100 | −0.545 | −0.683 to −0.406 | 3.5e-6 | 2.5e-5 (7 contrasts) |
| B4 − B3 (…004) | seats per 100 | +0.138 | +0.092 to +0.184 | 3.8e-5 | 0.0002 (7 contrasts) |
| B3-KM − B3 (…004) | seats per 100 | +0.020 | −0.009 to +0.049 | 0.2180 | 0.3127 (7 contrasts) |
| B4-KM − B3 (…004) | seats per 100 | +0.164 | +0.118 to +0.210 | 7.2e-6 | 4.3e-5 (7 contrasts) |
| ASK-ONLY − B3 (…004) | seats per 100 | −0.429 | −0.567 to −0.291 | 2.2e-5 | 0.0001 (7 contrasts) |
| B5 − B3 (…004) | seats per 100 | +0.026 | −0.007 to +0.058 | 0.1563 | 0.3127 (7 contrasts) |
| B5-RANDOM − B3 (…004) | seats per 100 | +0.029 | +0.005 to +0.054 | 0.0284 | 0.0853 (7 contrasts) |

**Finding (EXPLORATORY).** Counted in seats, asking passengers to reconfirm has a small positive effect: B4 − B3 = +0.138 seats per 100 searches (27 of 29 non-tied seeds up). It is about the size of the oracle's booking-level ceiling, and it holds with the no-feature model (B4-KM +0.164). Counted in bookings, the same comparison is null (+0.027). Asking shifts completed bookings toward larger parties: mean seats per completed booking is 1.2918 under B4 against 1.2884 under B3 (run …004). A plausible mechanism, **not tested**, is that seats released earlier let multi-seat parties find a ride with enough room together.

**Status.** The seat-weighted metric was **not pre-specified**. The auditor chose it after seeing the data, so it is hypothesis-generating. It cannot overturn the pre-specified primary result. It does show that the manuscript's claim "asking does not add completed rides" is **metric-dependent** and must be qualified. [RECOMMENDATION] For any further run, pre-register seats per 100 searches as a secondary outcome, and state in the paper which unit, a booking or a person, "completed ride" means.

### 5.2 "Late share of cancellations" includes driver withdrawals [VERIFIED]

`metrics.sql` `late_cancellation_share` counts every `booking_cancelled` event, including those with `cancelled_by = driver` (a withdrawn ride cancels its bookings). Driver-caused cancellations are about 10% of all cancellations (B0 0.104, B3 0.101, B4 0.096).

| Contrast | Late share, all cancellations | Late share, passenger only |
|---|---|---|
| B4 − B0 | −0.061 (−0.070 to −0.051) | −0.072 (−0.081 to −0.062) |
| B4 − B3 | −0.067 (−0.074 to −0.059) | −0.075 (−0.083 to −0.067) |
| ORACLE − B3 | −0.106 (−0.113 to −0.100) | −0.119 (−0.126 to −0.111) |

The direction and significance are unchanged, but the reported figure **understates** the intervention's effect on passenger behaviour by about 0.01. [RECOMMENDATION] Report the passenger-only share, or define the metric explicitly as including withdrawals.

### 5.3 Levels (regenerated world, run …003, mean ± SD over 30 seeds) [VERIFIED]

| Variant | Bookings completed per 100 | Seats per 100 | Late share, all | Late share, passenger | Driver share of cancellations | Seats per completed booking |
|---|---|---|---|---|---|---|
| B0 | 30.22 ± 1.13 | 38.80 ± 1.60 | 0.361 | 0.351 | 0.104 | 1.2839 (…004) |
| B3 | 30.54 ± 1.18 | 39.34 ± 1.66 | 0.368 | 0.354 | 0.101 | 1.2884 (…004) |
| B4 | 30.56 ± 1.17 | 39.48 ± 1.62 | 0.301 | 0.279 | 0.096 | 1.2918 (…004) |
| B5 | 30.53 ± 1.17 | 39.37 ± 1.64 | 0.351 | 0.335 | 0.099 | 1.2894 (…004) |
| ORACLE | 30.66 ± 1.16 | 39.57 ± 1.64 | 0.261 | 0.235 | 0.100 | — |

### 5.4 Denominator

Repeat searches are logged as `recovery_search`, not `search_issued` (`sim/simulator.py:321`), so the denominator counts each need once. This matches the paper's intent ("a search counts once per passenger session"). [VERIFIED] The paper's further requirement that a ride counts only "when both sides confirm it" has no counterpart in the simulator, which emits `trip_completed` directly. It also has none on the platform, where completion is the driver's action (`booking/TripService.java`, `complete`).

## 6. Durability-model evaluation

| Item | Finding | Label |
|---|---|---|
| Discrimination | Harrell C, validation seeds: h3 Cox 0.638, GB 0.621, KM 0.500 (`experiments/reports/hazard-h3.json`); h2 Cox 0.595 | [VERIFIED] read from report |
| Uncertainty | No interval on any concordance, Brier or calibration figure, for the simulator models or the hotel models | [VERIFIED] gap |
| Censoring | Administrative censoring at departure is almost the `lead_hours` feature (AI_ML_METHODOLOGY.md s.2). Harrell's C is known to depend on the censoring distribution when censoring relates to covariates. The IBS here assumes independent censoring and ranks KM best, the opposite of the departure Brier. | [VERIFIED] the documentation states this; [RECOMMENDATION] report Uno's IPCW C and time-dependent AUC at fixed horizons (scikit-survival `concordance_index_ipcw`, `cumulative_dynamic_auc`, already a dependency) |
| Comparison fairness | Gradient boosting is fitted on a 5,000-row sample (`experiments/configs/hazard.yaml`, `max_train_rows`) against Cox on all ~27,000 rows, with fixed hyperparameters. "GB did not beat Cox" is therefore not a fair comparison. | [VERIFIED] |
| Blend (paper eq. 11) | Not implemented. No ω, no blended model, GB not served. | [VERIFIED] |
| Proportional hazards | Schoenfeld test rejects for `lead_hours` (p = 0.0076), `prior_cancellations` (0.0033) and `last_mile_distance_m` (1.1e-6) in h3. Expected by design (research integrity rule 2). | [VERIFIED] |
| Calibration | Departure calibration, max decile gap 0.021 (h3 Cox); mean predicted 0.829 vs observed 0.825 | [VERIFIED] |
| Last-mile gain | Cox C rises 0.621 → 0.638 with the two last-mile features (`hazard-h3.json`, `last_mile_ablation`). The generator was changed to make last-mile distance shorten time to cancellation (`sim/simulator.py:447–450`, `world.yaml` `last_mile_sensitivity`), so this gain is **guaranteed by construction** and cannot be presented as a discovery. | [VERIFIED] |
| Truth recovery | Paper s.V-A promises to check "whether CommitmentDecay recovers the hazards". The simulator knows each booking's true survival (`cancel_exposure`, type, shock), but no comparison is made. | [VERIFIED] gap; [RECOMMENDATION] compute it: cheap and directly answers a stated question |
| Deployment shift | The model is trained under B0 and used inside B3/B4, whose rankings and interventions change the booking population. No calibration check on the `hazard_scored` events of B4 runs. | [VERIFIED] gap; [RECOMMENDATION] calibration of logged scores against realised outcomes, per variant |
| Hotel models | Temporal split (2015–16 train, 2017 validation), features restricted to booking time (`experiments/hotel/survival.py`; EXPERIMENT_PROTOCOL.md s.19). Sound design. No CIs; with 435 validation events a concordance CI is likely several hundredths wide. `adr` (average daily rate) should be checked for post-booking revision. | [VERIFIED] design; [RECOMMENDATION] bootstrap CI |

## 7. Multiplicity and analytic flexibility

- The project ran ~11 measures × 5–8 variants × 2 runs plus 8 sweeps. Its documents label each sweep as a test of a stated hypothesis and record wrong guesses (EXPERIMENT_PROTOCOL.md s.8 "Corrected by section 9"). That is good practice, and the history is unusually transparent. [VERIFIED]
- The primary metric was frozen in advance (CLAUDE.md), which protects the main conclusion against forking paths. [VERIFIED]
- [RECOMMENDATION] In the paper, declare three families:
  - the primary-metric ladder contrasts, Holm-adjusted (Section 3 above);
  - the mechanism measures (late share, refills, additionality), descriptive with intervals;
  - sweeps, descriptive, with no p-values in the abstract.

## 8. Mathematics in the manuscript

| Eq. | Content | Verdict |
|---|---|---|
| (1)–(4) | Request, feasible set, spatial and seat conditions | Correct as notation. The simulator implements only the time window and seats (single origin–destination). |
| (5) | Relative detour [T(π⊕q) − T(π)]/T(π) ≤ δ | Correct as written; **the platform uses an absolute minutes budget** shared by all pickups (`ride/DetourService.java`, `minutesLeft`). Not implemented as stated. |
| (6) | κ·L·(1+ε) ≤ SoC·B − E_res | Dimensionally consistent (kWh). **Not implemented**: the platform compares each stretch with the full-charge range at publishing (`RideService.java:558`), with no SoC, reserve or charger insertion. |
| (7) | M = P_book × P_accept | Treats booking and acceptance as independent given features; should be P(book) · P(accept \| requested). Not implemented. |
| (8) | Class-balanced weight α_c = (1 − β)/(1 − β^{n_c}) | Matches Cui et al. [20]. Not implemented. |
| (9), (10) | Cox hazard; S(t) = exp(−∫h) | Correct. |
| (11) | Ŝ = ωŜ_Cox + (1 − ω)Ŝ_GB | A convex mixture of survival functions is a valid survival function. **Not implemented**; ω by IBS would be unreliable here (Section 6). |
| (12) | S(D \| t0) = S(D)/S(t0) | Correct; implemented (`model.py`, `conditional_survival`). |
| (13) | E[C] = M · Ŝ_b(D \| t0) · Ŝ_drv(D) | Assumes passenger cancellation, driver withdrawal and match are independent. State the assumption. Not implemented (no M, no driver model). |
| (14) | Linear-predictor attribution φ | For a Cox model, βⱼ(xⱼ − x̄ⱼ) is exact, and the code computes exactly that (`model.py`, `contributions`). Calling it "SHAP" is acceptable only with that equivalence stated; no SHAP library is used. |
| (15)–(19) | Multi-action CP-SAT with gap nudges | Formulation coherent, but Δ needs Ŝ(D \| a), which the paper admits is unknown. **Implementation differs**: one action, worth (1 − S)·seats·min(1, h/168) (`intel/app/scheduler/plan.py`, `worth`), constraints per round, per ride and seats per departure day. |
| (20) | EC_k = p_b ∫_{t0}^{D_b} f_b(t)·ρ_k(D_b − t) dt | **Error**: for a booking already alive at t0 the density must be conditional, f_b(t)/S_b(t0). As written it understates expected refund cost by the factor S_b(t0). It is also a refund-*share* expectation only if ρ is the refunded fraction of p_b, which should be said. Not implemented. |
| (21), (22) | Tier fee; driver bonus from predicted survival | Well-formed, but **contradict the project's rule** that no price changes on a predicted risk (IMPLEMENTATION_ROADMAP.md "What will not be built"). Not implemented. |
| (23)–(26) | Joint recovery assignment | Well-formed. Not implemented (`FlexibleRebooking.java` rebooks each passenger to the closest ride in time). |
| (27) | Supply gap | Not implemented as a demand model (FEATURE_MATRIX row 89 "Not started, by decision"). |
| (28) | CR100 | Matches the code up to the confirmation clause (Section 5.4). |
| (29) | Harrell C | Correct form (comparable pairs i, j with T_i < T_j and δ_i = 1). |

## 9. Recommendations, in priority order

1. Report the existing results with Holm-adjusted p-values and bootstrap (or t) intervals, as in Section 3. Frame the nulls as bounds with a pre-declared margin. No new simulation needed.
2. Add the `EARLIEST` heuristic baseline (one variant, ~1 minute of compute per 30 seeds) to establish that the ranking gain is timing.
3. Report the seat-weighted outcome as an exploratory secondary result, and qualify the "asking adds nothing" claim.
4. Give every model metric an interval: seed bootstrap for the simulator models, case bootstrap for the hotel models. Add Uno's C and time-dependent AUC.
5. Compare predicted with true survival in the simulator (truth recovery), and check calibration of logged scores under B4.
6. Use a passenger-only late share or define the metric; fix equation (20).
