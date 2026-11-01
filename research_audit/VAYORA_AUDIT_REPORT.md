# VAYORA — Research audit report

| | |
|---|---|
| Audit date | 2026-10-29 |
| Manuscript audited | `E:\B.Tech DS\MEGAPROJECT\VAYORA_Research_Paper.pdf` (8 pp., SHA-256 `0aa3b4c3…e71e962`), with `VAYORA_Report.docx` (project report) for cross-checking |
| Code and evidence audited | `E:\B.Tech DS\MEGAPROJECT\Vayora-main` (current working copy, includes the 2026-10-28 regeneration) and `E:\B.Tech DS\MEGAPROJECT\JOURNEX` (git repository, HEAD `0cbeafb`; reports last committed in `30653cf`, 2026-10-20) |
| Method | Read-only inspection of the manuscript (text extracted with `research_audit/scripts/pdftext.py`), source code, configurations, documents and report files. Read-only SQL over the local `events` table to recompute the headline results. No experiment was rerun, nothing installed, no external service contacted, no project file modified. |
| Companion files | `experiment_coverage.csv`, `verified_results.csv`, `statistical_analysis.md`, `manuscript_revision_plan.md`, `reproducibility_guide.md`; audit scripts and raw outputs in `scripts/` and `data/` |

Evidence labels: **[VERIFIED]** checked against code, raw events or report files; **[REPORTED]** stated in a project document, not recomputed; **[ASSUMPTION]** a modelling choice; **[RECOMMENDATION]** auditor's advice.

Disclosure: the regenerated results (`Vayora-main/experiments/reports`, 2026-10-28) were produced by this assistant in an earlier session at the team's request. This audit recomputed their headline numbers from the raw event log rather than relying on that work.

---

## 1. Executive summary

**The software and the simulation study are considerably stronger than the manuscript, and the manuscript describes a different study.** The paper is a framework-and-protocol paper. It reports no results (Section VI: "It does not yet report experimental results") and promises twelve cumulative configurations (B0–B11), leave-one-out ablations, LightGBM matching models, a Cox/boosting blend, hazard-priced fares, joint CP-SAT recovery, a multi-city simulator and fairness analysis. The repository implements and evaluates a narrower, rigorous study: **four ladder configurations (B0, B3, B4, B5) plus five diagnostic variants on one simulated corridor, eight sweeps, and a real-data check on public hotel cancellations.** That study produced a well-powered, mechanistically explained, largely **negative** result. None of it is in the paper.

Headline judgements:

1. **The paper cannot be submitted in its current form to a reputable journal.** Most of its promised method is absent.
   - Of its twelve configurations, four exist, and two of those (B3, B5) only in part.
   - Of its six stress scenarios, one (the budget) has a current run.
   - The leave-one-out ablation, the fairness analysis, the LightGBM matching models, the Cox/boosting blend, hazard pricing and joint recovery do not exist.

   [`experiment_coverage.csv`](experiment_coverage.csv) lists 44 promised or run items: 18 not implemented, 6 partial. Several statements contradict the implementation, including two that contradict the project's own ethics rules (model-based pricing, eq. 20–22; showing survival scores to users). [VERIFIED]
2. **The evidence that does exist is sound.**
   - Seeds are paired with common random numbers, and training, validation and experiment seed sets are disjoint and enforced in code.
   - Latent passenger types never reach a feature.
   - Every number is a SQL aggregate over an append-only event log.
   - The audit recomputed the ladder from raw events; it matches the reports to every printed digit.
   - All conclusions survive Holm correction and bootstrap intervals, and they are stable across three successive versions of the simulated world (h1, h2, h3).

   [VERIFIED; see `statistical_analysis.md` §3]
3. **What the evidence says** (world h3, 30 paired seeds, completed bookings per 100 searches) [VERIFIED]:
   - Survival-ranked search (B3) beats nearest-time search (B0) by **+0.32** (95% bootstrap CI +0.23 to +0.41; Holm p = 8.3e-6), about 1% of baseline.
   - Asking risky bookings to reconfirm (B4) adds **+0.03** over B3 (−0.02 to +0.07; not significant); an oracle that asks exactly the late cancellers adds **+0.13** (+0.09 to +0.16).
   - The CP-SAT scheduler does no better than random choice (+0.00, ±0.02).
   - A model with **no features** (B3-KM) keeps all of B3's gain (−0.004, ±0.02).
4. **New audit findings the team should know before writing** [VERIFIED unless marked]:
   - **B3's gain is a timing heuristic.** With no features the survival score is a monotone function of hours to departure, so B3-KM is exactly "earliest departure within the ±2 h window" (`intel/app/hazard/model.py`; `sim/simulator.py:_rank`). The survival model is not needed for the only gain the ladder shows.
   - **The intervention null depends on the unit counted.** Measured in seats travelled rather than bookings, B4 − B3 = **+0.138 seats per 100 searches** (+0.092 to +0.184; Holm p = 1.5e-4), and B4-KM +0.164. *Exploratory*: the auditor chose this metric after seeing the data.
   - "Late share of cancellations" includes cancellations caused by **driver withdrawals** (~10% of them), which understates the passenger effect by about 0.01 (`metrics.sql`, `late_cancellation_share`).
   - The h3 model's gain from last-mile features (Cox C 0.621 → 0.638) is **guaranteed by construction**: the generator was changed to make that observable variable shorten time to cancellation (`sim/simulator.py:447–450`).
   - **Reproducibility is currently broken.** `scripts/reproduce.sh` runs the test suite first under `set -e`, and one test fails under h3 (`intel/tests/test_hazard_scoring.py:151`, KM 168-hour survival 0.49 < 0.5). The regenerated h3 models and reports are not committed in `JOURNEX`, and README/EXPERIMENT_PROTOCOL still quote h2 numbers.
   - Two sweeps the protocol relies on for robustness, intervention response and supply density, are **history runs from earlier worlds** and were never rebuilt.
5. **The strongest defensible paper is an empirical simulation study with a negative result and a mechanism**, not a framework paper. It is credible at a specialised transportation or simulation venue. **A journal with JIF > 9 is not a realistic target for simulation-only evidence of this kind.** Such venues normally require real-world data or a field experiment, which is the paper's own stated future work (§6).

---

## 2. Manuscript ↔ implementation conformance

Status: **Implemented**, **Partial**, **Absent**, **Contradicted** (the implementation deliberately does the opposite).

| # | Paper claim (location) | Implementation evidence | Status |
|---|---|---|---|
| 1 | Title says VAYORA; body, figures and tables say JOURNEX throughout | — | Inconsistent naming |
| 2 | "Results are sorted by (13) by default"; search page shows a forward survival curve and expected-completion figure (§III-C) | Platform search sorts TIME / PRICE / RATING / PROXIMITY / BEST_MATCH, default TIME (`web/src/lib/types.ts:97`, `PassengerPages.tsx:221`); "Scores are deliberately not shown to drivers or passengers" (`docs/KNOWN_LIMITATIONS.md:246`) | **Contradicted** |
| 3 | Two-sided score M = P_book × P_accept from LightGBM; class-balanced weights; isotonic calibration (§III-B, eq. 7–8) | No LightGBM, SHAP or calibration code or dependency (`intel/requirements.txt`, `requirements-research.txt`); platform `ride/MatchScore.java` is a rule-based 0–100 score, "never anything predicted"; simulator accepts with fixed probability 0.85 | **Absent** |
| 4 | Covariates: lead time, price, refund tier, payment method, past cancellations, driver reliability, corridor, day of week, chat activity (§III-C) | Eleven features: lead_hours, seats, departure_hour, desired_gap_hours, four history counts, cancel share, two last-mile (`intel/app/hazard/features.py`) | **Partial** (most listed covariates absent) |
| 5 | Cox/GB blend with ω chosen by IBS (eq. 11) | Cox served alone; GB evaluated only, on a 5,000-row sample (`experiments/hazard/train.py`) | **Absent** |
| 6 | Conditional survival S(D \| t0) (eq. 12) | `model.py`, `conditional_survival`; used for live re-scoring | **Implemented** |
| 7 | Driver survival model Ŝ_drv; expected completion (eq. 13) | No driver model; simulator has one driver per ride with no history (`sim/simulator.py:190`) | **Absent** |
| 8 | Per-booking SHAP explanation (abstract, eq. 14) | Exact Cox contributions βⱼ(xⱼ − x̄ⱼ) (`model.py`, `contributions`); no SHAP used, by design (AI_ML_METHODOLOGY.md §6a) | **Partial** (equivalent for Cox; wording wrong) |
| 9 | CP-SAT over four actions plus supply-gap nudges sharing a budget; gain v·[S(D \| a) − S(D)]; exclusion window (eq. 15–19) | One action; worth (1 − S)·seats·min(1, h/168); limits per round, per ride and seats per departure day (`intel/app/scheduler/plan.py`) | **Partial** |
| 10 | Hazard-priced tiers (eq. 20–21), driver reliability bonus from predicted survival (eq. 22) | Not built; the project forbids changing a price on a prediction (IMPLEMENTATION_ROADMAP.md, "What will not be built"); platform's lower fee uses the record only (`payment/KeptRides.java`) | **Contradicted** |
| 11 | Static tiered refund baseline B6, e.g. 24 h / 6 h; "B0–B5 apply a single refund rule" (§III-E, §V-B) | Platform tiers 48 h / 24 h (`application.yml` `journex.refund`); simulator has no prices or refunds at all | **Absent** in evaluation |
| 12 | Joint cascade recovery by CP-SAT; nothing rebooked until the passenger confirms (eq. 23–26) | Per-passenger closest-in-time rebooking, automatic for passengers who pre-chose flexible (`booking/FlexibleRebooking.java`; `sim/simulator.py:_after_withdrawal`); CP-SAT used only in `plan.py` | **Partial / contradicted wording** |
| 13 | Feasibility: relative detour (eq. 5); EV energy with SoC, reserve and charger insertion (eq. 6) | Absolute detour minutes (`ride/DetourService.java`); EV check = each stretch ≤ full-charge range, at publishing only (`ride/RideService.java:558`); simulator has neither | **Partial** (platform) / **Absent** (evaluation) |
| 14 | Demand count model and supply-gap estimate (eq. 27, Phase 7) | FEATURE_MATRIX row 89 "Not started, by decision"; zero-result supply gaps on the platform only | **Absent** |
| 15 | Simulator: multi-city network with OSRM times; non-homogeneous Poisson arrivals with weekday and holiday peaks (§V-A) | One corridor (`sim/frozen.py`); constant-rate Poisson (`world.yaml` demand comment; `sim/simulator.py:238`); no OSRM in the simulator | **Contradicted** |
| 16 | Latent willingness to book and accept; hazards known (§V-A) | Latent types with Weibull hazards, unobservable shocks, last-mile sensitivity (`world.yaml`; `sim/hazard.py`); generator inverse transform checked correct | **Implemented** |
| 17 | Twelve cumulative configurations B0–B11; leave-one-out from B11 (§V-B) | B0, B3, B4, B5 plus B3-KM, B4-KM, ASK-ONLY, B5-RANDOM, ORACLE; B1, B2, B6–B11 and LOO not built (EXPERIMENT_PROTOCOL.md §2) | **Partial** |
| 18 | Robustness: hazards +25%/+50%, demand shift, supply −1/3, monsoon, EV share, budget ×½/×2 (§V-C) | Budget sweep (current); supply density (history only); no hazard, demand, travel-time or EV scenarios | **Partial** |
| 19 | Holm-corrected Wilcoxon; bootstrap CIs (§V-E) | Unadjusted Wilcoxon; paired t intervals (`experiments/analysis/compare.py`) | **Absent** (audit computed both) |
| 20 | Models trained on the first part of each simulated period (§V-E) | Disjoint seed sets, enforced (`experiments/hazard/train.py`, `load_config`) | **Different and stronger**; fix the text |
| 21 | Classifier PR-AUC and ECE; Harrell C; IBS (§V-D) | No classifiers; Harrell C, IBS, departure Brier, decile calibration, Schoenfeld test | **Partial** |
| 22 | Fairness across gender and city (§V-D) | Simulator has no gender or city; no fairness analysis anywhere in `experiments/` or `intel/` | **Absent** |
| 23 | `make reproduce` Makefile target (§IV) | `scripts/reproduce.sh`; no Makefile | **Wrong reference** |
| 24 | Table II: TypeScript gateway; JPA; Recharts; LightGBM and SHAP in intelligence | Gateway is JavaScript (`gateway/src/index.js`); Spring JDBC, no JPA (`core-api/pom.xml`); no Recharts; no LightGBM or SHAP | **Incorrect** |
| 25 | Data stores: "PostgreSQL/PostGIS and Redis" | MongoDB also holds chat, GPS and notifications (CLAUDE.md data placement); the paper never mentions MongoDB | **Incomplete** |
| 26 | Features assigned to tiers A/B/C (§IV) | Every tier is `TBD` (`docs/FEATURE_MATRIX.md:5–7`) | **Not done** |
| 27 | Payments in escrow until both sides confirm completion (§III-E) | Held at booking, captured when the driver completes (`payment/PaymentService.java`; `booking/TripService.java`) | **Contradicted** |
| 28 | Drivers and users can see and question their own reliability estimate (§III-E, §VI) | Model scores never shown to users (`KNOWN_LIMITATIONS.md:246`); drivers see a record-based page | **Contradicted** |
| 29 | CR100: a ride counts only when both sides confirm (eq. 28) | `trip_completed` events, no confirmation step (`metrics.sql`, `primary_metric`) | **Partial** |
| 30 | Bus fallback; last-mile walking / bus / rickshaw options | Platform features 73 and 91 (FEATURE_MATRIX) | **Implemented** (platform only) |

The project report (`VAYORA_Report.docx`) repeats most of these and adds promises that also lack implementations:
- calibration of demand from publicly visible bus-seat availability;
- a "misspecification arm";
- time-dependent concordance;
- a survival-versus-binary-classifier comparison on the hotel data;
- GeoPandas/Shapely.

It also states that outcome results "are not yet available". [VERIFIED by text extraction]

---

## 3. Experimental evidence audit

### 3.1 What was run (details in `experiment_coverage.csv`)

| Block | Runs | Seeds | Current world | Report |
|---|---|---|---|---|
| Ladder | B0, B3, B4, B5, ORACLE | 30 | h3 (regenerated), h2 (committed) | `experiments/reports/ladder-7a1c0de0-…-003.*` |
| Robustness | B0, B3, B4, B5, B3-KM, B4-KM, ASK-ONLY, B5-RANDOM | 30 | h3, h2 | `ladder-…-004.*` |
| Sweeps | contact budget, departure tolerance, publish lead, survival threshold, oracle response, flexible booking | 30 per cell | h3, h2 | `sweep-*-5eeb…-003` to `-008` |
| History sweeps | intervention response (world h1), supply density | 30 per cell | **pre-h2, never rebuilt** | `sweep-*-…-001`, `-002` |
| Durability model | KM, Cox, GB | train 1001–1020, validation 2001–2010 | h3, h2, h1 | `hazard-h*.json` |
| Real-data check | hotel cancellation timing; survival models on hotels | 2 hotels × 3 years; temporal split | unchanged | `hotel-*.json` |

### 3.2 Seeds, pairing and baselines [VERIFIED]

- **Seeds**: 30 per variant (`sim/frozen.py`). Model seeds are disjoint from experiment seeds; training refuses to run otherwise (`experiments/hazard/train.py:43–46`).
- **Pairing**: named random streams per seed for supply, demand, each person and each search (`sim/simulator.py:3–15`, `_stream`). Draws are consumed on arrival whether used or not, so variants face identical worlds, as the tests assert.
- **Baselines**: B0 (nearest preferred time) is a reasonable naive baseline. **Missing: an "earliest departure" heuristic**, which B3-KM is mathematically equivalent to and which would show directly that the ranking gain needs no model ([`statistical_analysis.md`](statistical_analysis.md) §4).
- **Upper bound**: ORACLE reads the hidden cancellation time and is labelled as non-implementable. Good practice.

### 3.3 Ablations and robustness

- **Component removal** around B3–B5 exists and is informative: B3-KM, B4-KM, ASK-ONLY, B5-RANDOM.
- **The paper's leave-one-out from B11 cannot exist**, because B11 does not.
- **Robustness to cancellation intensity is untested.** The project's own hotel check shows the simulated world cancels about twice the city hotel's rate and nearly four times the resort's in the final week (EXPERIMENT_PROTOCOL.md §18). That is the largest unexamined threat to external validity. [VERIFIED]
- **The response-assumption sweep is stale.** It was run under world h1 (`sweep-intervention-response-…-001`), and the protocol still uses it to argue that "the null result is not an artefact of the assumed response". It should be rebuilt under the current world.

### 3.4 Intervals and significance [VERIFIED]

Recomputed in `statistical_analysis.md` §3: t and bootstrap intervals agree, and every significant contrast stays significant after Holm correction over 13 contrasts. The nulls are precise. For B4 − B3 the 90% interval is −0.011 to +0.064, so the paper can bound the intervention's effect rather than merely fail to reject it, provided a margin is pre-declared.

### 3.5 Invalid, inconsistent or missing evidence

| Issue | Evidence | Severity |
|---|---|---|
| Manuscript reports no results although results exist | Paper §VI; `experiments/reports/` | Critical |
| Documents quote h2; current models and reports are h3 and uncommitted; AI_ML_METHODOLOGY quotes h1 | README "What the research found"; EXPERIMENT_PROTOCOL §2 tables; AI_ML_METHODOLOGY §5 cites `hazard-h1.json` | High |
| The h2 evidence's event rows were overwritten in the local database by the regeneration; it survives only as report files and is reproducible from `JOURNEX` HEAD | `scripts/reproduce.sh` `forget` deletes each rebuilt run's events | Medium (disclose) |
| Primary metric's unit (bookings) is not stated; the seat-weighted result differs | `statistical_analysis.md` §5.1 | High (wording) |
| Late share includes driver withdrawals | §5.2 | Low |
| Last-mile feature gain is by construction | §6 | Medium (wording) |
| No intervals on any model metric (simulator or hotel) | `hazard-h3.json`, `hotel-survival.json` | Medium |
| GB vs Cox comparison handicaps GB (5,000-row sample, fixed hyperparameters) | `experiments/configs/hazard.yaml` | Low (wording) |
| Two robustness sweeps from earlier worlds | `sweep-…-001`, `-002` | High for the robustness claim |
| The paper's claim to "check whether CommitmentDecay recovers the hazards" is not carried out | §6 | Medium |

---

## 4. Mathematics, leakage and validity

### 4.1 Mathematical issues (full table in `statistical_analysis.md` §8)

- **Eq. (20)** uses the unconditional cancellation density after t0. For a booking alive at t0 it must be f_b(t)/S_b(t0); as written it understates the expected refund cost by the factor S_b(t0). [VERIFIED by derivation]
- **Eq. (7) and (13)** multiply probabilities without stating the independence they assume: booking vs acceptance, and passenger cancellation vs driver withdrawal vs match.
- **Eq. (5) and (6)** are correct as mathematics but are not what the platform implements.
- **The ground-truth generator is correct.** The post-shock inverse transform in `sim/hazard.py` solves H(t) = E for the piecewise cumulative hazard. [VERIFIED]
- **Eq. (29)** (Harrell's C) is stated correctly. However, administrative censoring at departure is nearly the `lead_hours` feature, so Uno's IPCW concordance and time-dependent AUC should accompany it.

### 4.2 Leakage audit [VERIFIED]

| Check | Result |
|---|---|
| Latent type in features | **No.** Features come only from logged events (`features.py`, `FeatureTracker`); the passenger type is never written to an event (`sim/simulator.py:117`); tests enforce it (AI_ML_METHODOLOGY.md §3). |
| Seed leakage | **No.** Disjoint sets, enforced in code. |
| Booking in its own history | **No.** History is read before the current event is observed (`experiments/hazard/dataset.py`, comment and order of calls). |
| Ground truth in policies | **Only ORACLE**, by design, labelled as an upper bound (`sim/simulator.py:501–505`). The scheduler receives no ground truth (`_within_budget`). |
| Train/serve skew | `desired_gap_hours` varies in training but the platform always sends 0 (KNOWN_LIMITATIONS.md "Model in the platform"). Affects the platform, not the simulation results. |
| Distribution shift | The model is trained under B0 and used inside B3/B4; not evaluated there. |
| Hotel data | Temporal split by arrival year; booking-time features only. `adr` should be checked for post-booking revision. |

### 4.3 Validity

- **Internal validity: high.** Common random numbers, deterministic seeds, invariants tested, and every metric is SQL over events.
- **Construct validity: moderate.** "Completed ride" means a completed booking of any party size. Late share mixes driver and passenger cancellations.
- **External validity: low, and acknowledged.**
  - Every world parameter is an assumption (`world.yaml` header).
  - There is one corridor.
  - Passenger response to being asked cannot be identified from simulation (CLAUDE.md research rule 4).
  - Real hotel cancellations are 2–4× rarer in the final week than simulated ones.

---

## 5. Unsupported claims to remove or rewrite

| Claim in manuscript | Why | Action |
|---|---|---|
| LightGBM two-sided matching with class-balanced weights and isotonic calibration | Not implemented (§2 rows 3, 24) | Remove, or describe the platform's rule-based score and exclude it from evaluation |
| CommitmentDecay blends Cox with boosted survival | Not implemented | Describe the served Cox model; report GB as a compared model |
| Covariates include price, refund tier, payment method, driver reliability, corridor, day of week, chat | Not features | List the eleven actual features |
| Driver-side survival and expected completion E[C] | Not implemented | Remove or move to future work |
| Per-booking SHAP explanation | Exact Cox decomposition, no SHAP | State the exact decomposition |
| Search results sorted by expected completion; survival curve shown to passengers; users see their reliability estimate | Contradicted by design (`KNOWN_LIMITATIONS.md:246`) | Remove; state the ethics choice actually made |
| Hazard-priced tiers and driver reliability bonus | Contradict project rules | Remove from the method; optionally discuss as a rejected design in Ethics |
| Joint CP-SAT recovery; nothing rebooked without confirmation | Sequential rebooking with prior consent | Describe what exists; joint recovery as future work |
| Multi-city, OSRM-timed, peak-hour simulator | One corridor, constant-rate arrivals | Describe the simulator as built |
| Twelve configurations, leave-one-out, six stress scenarios | Not run | Replace with what was run, plus the necessary additions (§7) |
| Holm and bootstrap protocol | Not in the pipeline | Adopt (the audit has computed them) or describe t intervals honestly |
| Fairness comparison across gender and city | Impossible in this simulator | Remove from the evaluation; keep the design rule |
| Tier A/B/C classification of 95 features | Not assigned | Assign or remove |
| `make reproduce` | Does not exist | `scripts/reproduce.sh` |
| "It does not yet report experimental results" | Results exist | Add a Results section |
| Escrow until both sides confirm | Captured on driver completion | Correct |

---

## 6. Reproducibility problems (details and fixes in `reproducibility_guide.md`)

1. **`scripts/reproduce.sh` cannot complete.** Step 1 runs `pytest` under `set -e`, and one test fails under h3 (`intel/tests/test_hazard_scoring.py:151`). The team must decide on that sanity bound or on the world's last-mile sensitivity. [VERIFIED]
2. **No coherent committed state.**
   - `JOURNEX` has staged h3 code with the h1/h2 models deleted and no h3 models or reports.
   - `Vayora-main` has h3 models and regenerated reports but no git history.
   - Both copies' documents quote h2 numbers.

   [VERIFIED]
3. **The final check of `reproduce.sh` needs git** (`git diff --exit-code`). It cannot run in `Vayora-main`. [VERIFIED]
4. **Two sweeps cannot be rebuilt by the script**; they are recorded as history. [VERIFIED; REPORTED in EXPERIMENT_PROTOCOL §4]
5. **External download.** The hotel CSV comes from a GitHub mirror (TidyTuesday), not the original journal supplement. It is SHA-256 pinned (`experiments/configs/hotel.yaml`), so it is reproducible, but the provenance should cite both. [VERIFIED]
6. **Container images are pinned by tag, not digest** (`python:3.11-slim`, `postgis/postgis:16-3.4`, `mongo:7`, …). Python packages are pinned exactly. [VERIFIED]
7. **No archived release** (tag, DOI) and no recorded hardware. Byte-for-byte reproduction was reported on 2026-10-16 and 2026-10-19 [REPORTED], before the h3 change.
8. **The manuscript PDF's text layer is partly corrupt.** Reference author names and typographic quotes extract as shifted glyphs (e.g. "1 $ J D W ]" for "N. Agatz"), which can break indexing and similarity checks. Re-export with fully embedded fonts. [VERIFIED by extraction]

---

## 7. Strongest defensible contributions

Ranked by evidential strength:

1. **A rigorous, reproducible negative result.** On a simulated intercity corridor, survival-targeted reconfirmation requests move cancellations earlier (late share −0.067, all 30 seeds) but do not raise completed bookings (+0.03, bounded above by +0.07). Even an oracle gains only +0.13 per 100 searches. The result is stable across three world versions and survives multiplicity correction. [VERIFIED]
2. **A mechanism for the null.** Late releases are mostly refilled anyway (62% vs 92% for early, h3). Extra refills go to passengers who had alternatives (refill additionality). Two-thirds of empty searches meet full rides. The intervention converts few late releases (EXPERIMENT_PROTOCOL.md §§8–16; regenerated `ladder-…-003`). [VERIFIED from reports]
3. **The ranking gain needs no prediction.** B3-KM ≡ earliest departure in window, a practically useful result for platform design. It needs one confirming baseline (§7 of this report). [VERIFIED analytically]
4. **An external plausibility check with real cancellations.**
   - On public hotel bookings, booking-time features rank cancellations better than timing alone: Cox C 0.597 and gradient boosting 0.653, against 0.544 for lead time alone. That is the opposite of the simulator, which suggests the simulated world understates feature signal.
   - The same data show the simulator over-cancels.

   [VERIFIED read from report; no CIs yet]
5. **A methodological template**: an event-log-to-SQL metric pipeline, CRN seeding, disjoint model seeds, an oracle ceiling, component removal and pre-declared hypotheses, with a written record of revised beliefs.
6. **An exploratory, metric-dependent effect.** Seat-weighted, asking adds +0.14 seats per 100 searches. It should be presented only as hypothesis-generating. [VERIFIED; post hoc]

Not defensible as contributions in their current form: LightGBM matching, the blend, the multi-action scheduler, hazard pricing, joint recovery, EV-aware feasibility in evaluation, and demand calibration.

---

## 8. Publication strategy

- **Paper type.** [RECOMMENDATION] Re-scope to *"a reproducible simulation study of survival-guided interventions in intercity ride-sharing, with a negative result and its mechanism"*. Treat the platform as the instrument and testbed, not the contribution. This keeps the original research question, completed journeys rather than bookings, and the commitment-decay idea.
- **Venue fit.** The evidence is simulation-only on one corridor, with small effects and an honest null. The best fit is specialised transportation, operations-research or simulation journals and conferences that value negative results and reproducibility. Candidates to evaluate:
  - Transportation Research Part C;
  - Transportation Research Part E;
  - Transportation Science;
  - Transportmetrica B;
  - IEEE Transactions on Intelligent Transportation Systems;
  - Simulation Modelling Practice and Theory;
  - Journal of Simulation;
  - EURO Journal on Transportation and Logistics;
  - Transportation Research Record;
  - conferences such as IEEE ITSC, hEART or TRB.

  **The auditor has not verified any impact factor and quotes none; check the current Journal Citation Reports before choosing.**
- **On JIF > 9.** It is **not genuinely appropriate for the current evidence.** Venues at that level typically expect real-world data, a field experiment, or a methodological advance with broad scope. The credible path there is the paper's own future work: a pilot on one corridor with a randomised hold-out group estimating the real response to reconfirmation requests (the parameters `early_release_probability` and `annoyed_cancel_probability`, which simulation cannot identify), plus model calibration on real bookings.

---

## 9. Necessary additional experiments

Only experiments that answer a stated question or close an evidential gap are listed. Compute estimates come from the 2026-10-28 run log on this machine (Docker, 8 GB).

| Priority | Experiment | Why it is necessary | Cost | Needs approval for |
|---|---|---|---|---|
| E1 | `EARLIEST` baseline: rank by ascending departure inside the window | Confirms that B3's gain is a timing heuristic; without it the paper's model-based ranking claim is open to the obvious objection | ~1 min (one variant, 30 seeds) plus one ranking key in `sim/simulator.py` | code change |
| E2 | Rebuild the intervention-response sweep (3 × 3 grid) under the current world | The protocol's main robustness claim rests on an h1-world run | ~10–20 min | — |
| E3 | Cancellation-intensity sweep (all Weibull scales multiplied together, bracketing hotel-like survival) for B3 vs B0 and B4 vs B3 | The largest external-validity threat; the paper promised hazard stress tests | ~30–60 min plus a world-level scale parameter in `world.yaml`/`sim/config.py` | code + config change |
| E4 | Rebuild supply density; add a demand-density sweep | Supply sweep is history; demand never swept | ~30–40 min | — |
| E5 | Model evaluation without new simulation: seed-bootstrap CIs, Uno's C, time-dependent AUC; truth recovery (predicted vs generating survival on validation seeds); calibration of logged `hazard_scored` under B4 | Answers the paper's own question (§V-A) and gives every model number an interval | minutes | analysis script |
| E6 | Pre-register seats per 100 searches as a secondary metric (`metrics.sql`) and report it for every variant | Settles the metric dependence found here | minutes | query addition |
| E7 | Bootstrap CIs on the hotel concordances; optionally the promised survival-vs-binary-classifier comparison | Real-data claim currently has no uncertainty | minutes | analysis script |

Not recommended for this paper: building B1, B2 or B6–B11. They would take weeks, several conflict with the project's rules, and none is needed for the defensible contribution.

---

## 10. The three highest-priority actions

1. **Make the evidence base coherent and reproducible.**
   - Decide on the failing h3 sanity test (loosen the bound with a stated reason, or revisit `last_mile_sensitivity`).
   - Commit the h3 models, regenerated reports, figures and corrected documents in `JOURNEX` and tag the release, so `scripts/reproduce.sh` passes end to end.
   - Archive it with a DOI.
2. **Re-scope the manuscript from a protocol to an empirical study.**
   - Remove or move to "design and future work" every unimplemented component in §5.
   - Correct the implementation facts (Table II, data stores, metrics, split).
   - Add a Results section built only from `verified_results.csv`, using Holm-adjusted tests and intervals, the null framed as a bound, the mechanism, the oracle ceiling, cross-world stability and the hotel check.

   See `manuscript_revision_plan.md`. Do not rewrite until the team approves this audit.
3. **Run E1–E3 and E5–E6.** These are the earliest-departure baseline, the rebuilt response sweep, the cancellation-intensity sweep, the model-evaluation intervals with truth recovery, and the seat-weighted secondary metric. Together they take about two hours of compute and close the gaps a reviewer would raise first.
