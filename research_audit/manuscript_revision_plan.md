# VAYORA — Manuscript revision plan

This is a **plan, not a rewrite**. No manuscript text is changed until the team approves the audit findings (`VAYORA_AUDIT_REPORT.md`) and this plan. It preserves the paper's research meaning: the completed journey, not the booking, is the unit of success, and booking durability is modelled as a time-to-event process acted on before departure. It also preserves the IEEE conference structure, sections I–VII plus Future Direction and References.

Every number proposed for the paper must come from `verified_results.csv` (result IDs `R…` below) or from an experiment still to be run. No result may be quoted from memory, from this plan's prose, or from the superseded h2 documents without saying which world it belongs to.

Priorities:
- **P0**: blocking; the paper is not submittable without it.
- **P1**: needed for a competitive journal submission.
- **P2**: improves quality.

---

## 0. Decisions the team must take first (P0)

| Decision | Options | Auditor's recommendation |
|---|---|---|
| D1. What kind of paper | (a) **Empirical simulation study**: the framework as motivation, the evaluated subset as method, results and mechanism as the contribution. (b) System/framework paper that describes only what is implemented. | (a). The evidence supports it, and it keeps the original research question. (b) would have to drop most of the current Section III anyway. |
| D2. Which world is reported | committed h2 (documents today) or regenerated h3 (current code) | h3, the current code, once committed and reproducible (action 1 in the audit report). Report h1/h2/h3 stability as a robustness result. |
| D3. Unit of "completed ride" | bookings (current `primary_metric`) or passengers/seats | Keep bookings as the pre-specified primary metric (CLAUDE.md freezes it). State the unit explicitly, and report seats as an exploratory secondary outcome (R263–R265, R272–R278). |
| D4. Smallest effect of interest | e.g. 0.05 or 0.10 completed bookings per 100 searches | Choose **before** the next runs; it turns the null into an equivalence statement. |
| D5. Name | VAYORA or JOURNEX | One name throughout text, figures and tables. |
| D6. The failing h3 sanity test | loosen the 0.5 bound with a reason, or revise `last_mile_sensitivity` | Team's call. It blocks `scripts/reproduce.sh`. |

---

## 1. Title, authors, abstract

| Item | Change | Priority | Evidence |
|---|---|---|---|
| Title | Keep "VAYORA" and the intercity framing. Drop components that are not evaluated (two-sided matching, EV-aware), or qualify them as platform features. Signal the study type (simulation study, survival-guided interventions). | P0 | §2 rows 3, 13 of the audit report |
| Abstract | Replace "We specify an evaluation protocol…" with what was done and found. Content, not wording: the corridor simulator with latent types and misspecified Weibull hazards; disjoint model seeds; 30 paired seeds; ranking gain +0.32 (R250); intervention null bounded above by +0.07 (R254); oracle ceiling +0.13 (R255); no-feature ranking matches the model (R258); the hotel check (R247–R249). Remove LightGBM, blend, SHAP, pricing tiers and joint-recovery claims. | P0 | `VAYORA_AUDIT_REPORT.md` §5 |
| Data stores | Add MongoDB (chat, GPS, notifications); the abstract lists only PostgreSQL/PostGIS and Redis | P1 | audit §2 row 25 |
| Keywords | Keep survival analysis, intercity carpooling, constraint programming. Add simulation, negative result, reproducibility. Drop explainable AI unless the exact Cox decomposition is discussed. | P2 | — |

## 2. Section I — Introduction

| Change | Priority | Evidence |
|---|---|---|
| Keep the motivation (the booking-to-departure gap; completed journeys per search). | — | — |
| Rewrite the four contributions to match evidence: (i) a reproducible simulation testbed and event-log metric pipeline; (ii) a well-powered test of survival-guided ranking and reconfirmation with an oracle ceiling; (iii) a mechanism for the null (refill timing, additionality, empty-search reasons); (iv) an external plausibility check on real hotel cancellations. Remove contributions (ii) and (iii) as currently worded (joint CP-SAT over contacts and supply gaps; commitment-aware pricing and joint recovery). | P0 | audit §7 |
| State the question as a hypothesis, with the answer: "Does acting on predicted durability before departure raise completed rides per 100 searches?" | P0 | EXPERIMENT_PROTOCOL.md "Question" |

## 3. Section II — Literature survey

| Change | Priority | Evidence |
|---|---|---|
| Keep the survey and Table I. Rephrase the "gap addressed" column to what the paper now delivers (per-booking timing risk evaluated against completed trips; contact budget across at-risk bookings). Drop the EV and two-sided-learning gaps unless retained as platform-only. | P1 | — |
| Verify every reference before submission (the auditor had no external access). In particular: [9] (2026, IJRIAS; confirm the DOI resolves and judge the venue); [19] (add version, URL and access date for OR-Tools); [15] (confirm volume and pages). | P0 | paper References |
| Add citations for methods the paper uses but does not cite: Kaplan–Meier estimator; Wilcoxon signed-rank; Holm's procedure; scikit-survival (gradient-boosted survival, IPCW concordance); Uno's concordance for censored data (if adopted); the hotel dataset (Antonio, de Almeida and Nunes, *Data in Brief* 22, 2019, doi:10.1016/j.dib.2018.11.126, already cited in the repository's EXPERIMENT_PROTOCOL.md §18). **The team must locate and verify each source; the auditor does not supply bibliographic details it could not check.** | P1 | — |
| Add a short paragraph on simulation-based evaluation and negative results in transportation or OR, from sources the team verifies. | P2 | — |

## 4. Section III — Framework

Principle: keep the lifecycle (Fig. 1) as the conceptual framework, but **mark each phase as evaluated, implemented on the platform only, or designed and not built**. A small status column in a summary table is enough.

| Phase / equations | Change | Priority | Evidence |
|---|---|---|---|
| A. Feasibility (eq. 1–6) | Keep as platform design. Correct eq. (5) to the absolute detour budget the platform uses, or label it design. Correct eq. (6) to the per-stretch full-charge range check at publishing, or label it design. State that the simulator has a single origin–destination and only the time-window and seat conditions apply. | P0 | `ride/DetourService.java`; `RideService.java:558`; `sim/frozen.py` |
| B. Two-sided match (eq. 7–8) | Remove LightGBM, class-balanced weights and isotonic calibration. Either describe the platform's rule-based match score as a platform feature excluded from evaluation, or drop Phase B. If eq. (7) is kept as design, state the conditional form P(book)·P(accept \| request). | P0 | `ride/MatchScore.java` |
| C. CommitmentDecay (eq. 9–14) | Keep eq. (9), (10), (12). Replace the covariate list with the eleven actual features (`intel/app/hazard/features.py`). Remove the blend, eq. (11), or present GB only as a compared model. Remove Ŝ_drv and eq. (13) or label them design. Replace "SHAP" with the exact Cox decomposition, noting its equivalence for a linear predictor. Remove "results are sorted by (13) by default" and the claim that passengers see survival curves: scores are deliberately not shown (`KNOWN_LIMITATIONS.md:246`). Describe censoring as implemented (event = passenger cancellation; censoring at departure or driver withdrawal; clock from acceptance). | P0 | AI_ML_METHODOLOGY.md §§1–6a |
| D. Intervention (eq. 15–19) | Describe the implemented single action (reconfirmation request) and objective worth = (1 − S)·seats·min(1, h/H), with H = 168 h, and its three limits. State that the earliness discount is an assumption. Keep the multi-action formulation, if at all, as design and future work. | P0 | `intel/app/scheduler/plan.py` |
| E. Pricing (eq. 20–22) | **Remove from the method.** These contradict the project's rule that no price changes on a prediction. If discussed in Ethics as a rejected design, fix eq. (20) to use the conditional density f_b(t)/S_b(t0). Describe the static refund tiers as platform policy (48 h / 24 h), not as configuration B6. | P0 | IMPLEMENTATION_ROADMAP.md "What will not be built"; `application.yml` |
| F. Refill and recovery (eq. 23–26) | Describe what exists: immediate resale of released seats (in every variant); retry by unserved passengers; immediate rebooking of passengers who pre-chose flexible, closest in time, within 2 h. Remove "nothing is rebooked until the passenger confirms": consent is given at booking. Joint CP-SAT recovery becomes future work. | P0 | `sim/simulator.py:_after_withdrawal`; `booking/FlexibleRebooking.java` |
| G. Demand calibration (eq. 27) | Remove, or keep one sentence: demand is assumed, not calibrated, by decision (FEATURE_MATRIX row 89). | P0 | — |

## 5. Section IV — System implementation

| Change | Priority | Evidence |
|---|---|---|
| Correct Table II: gateway is JavaScript; Spring uses JDBC, not JPA; no Recharts; intelligence uses lifelines, scikit-survival, OR-Tools and SimPy, not LightGBM or SHAP; add MongoDB. | P0 | `gateway/src/index.js`; `core-api/pom.xml`; `web/package.json`; `intel/requirements.txt` |
| Replace `make reproduce` with `scripts/reproduce.sh` and describe what it rebuilds and checks. | P0 | `scripts/reproduce.sh` |
| Tier A/B/C: assign tiers in FEATURE_MATRIX.md (currently all `TBD`) or remove the claim. | P1 | `docs/FEATURE_MATRIX.md:5–7` |
| Correct "escrow until both sides confirm" to hold at booking and capture on the driver's completion. | P1 | `payment/PaymentService.java` |
| Keep the honest statement about mocked integrations; it matches KNOWN_LIMITATIONS.md. | — | — |

## 6. Section V — Experimental design (rewrite to what was run)

| Subsection | Change | Priority | Evidence |
|---|---|---|---|
| A. Simulator | Single corridor Kolhapur–Pune; 60 days; constant-rate Poisson searches (60/day); 12 rides/day; four latent types driving Weibull cancellation with unobservable shocks and last-mile sensitivity; retry; flexible rebooking; every parameter an assumption (`world.yaml`). Remove the multi-city network, OSRM-in-simulation and peak/holiday claims. | P0 | `sim/simulator.py`; `experiments/configs/world.yaml` |
| B. Configurations | Replace Table III with: B0; B3; B4; B5; diagnostics B3-KM, B4-KM, ASK-ONLY, B5-RANDOM; ORACLE (upper bound); plus EARLIEST once run (E1). Explain why B1, B2 and B6–B11 are not in the evaluation, in one paragraph. | P0 | `experiments/configs/variants/` |
| C. Robustness | List the sweeps actually run (budget, threshold, tolerance, publishing lead, oracle response, flexible share) and the ones to be run (response grid rebuilt, cancellation intensity, supply and demand density). Remove demand-shift, monsoon and EV scenarios. | P0 | `experiments/configs/sweeps/` |
| D. Metrics | Define the primary metric precisely as completed bookings per 100 first searches (repeat searches excluded from the denominator). Define late share; use the passenger-only version or state that withdrawals are included. Add seats per 100 searches as exploratory secondary. Remove average detour, EV violations and classifier PR-AUC/ECE. | P0 | `metrics.sql`; `statistical_analysis.md` §5 |
| E. Statistics | Paired by seed with common random numbers; 95% intervals (t, with bootstrap agreement); Wilcoxon signed-rank; Holm within the primary family; a pre-declared equivalence margin for nulls. Model data: disjoint seed sets (train 1001–1020, validation 2001–2010, experiments 1–30), replacing "first part of each simulated period". | P0 | `statistical_analysis.md` §§2–3 |
| F. Real-data check (new) | Hotel cancellations: SHA-256-pinned source, 7-day window, temporal split, booking-time features; purpose and limits. | P1 | EXPERIMENT_PROTOCOL.md §§18–19 |

## 7. New Section VI — Results (renumber Discussion to VII, Conclusion to VIII)

Each element names its source. All are h3 unless the team chooses otherwise in D2.

| Element | Content | Source | Priority |
|---|---|---|---|
| Table: ladder | B0, B3, B4, B5, ORACLE: completed bookings per 100 searches, differences with 95% intervals, Holm p, seeds up/down | R250–R255; `ladder-…-003.md` | P0 |
| Table: component removal | B3-KM, B4-KM, ASK-ONLY, B5, B5-RANDOM vs B3 | R256–R262; `ladder-…-004.md` | P0 |
| Paragraph: timing explains the ranking | B3-KM ≡ earliest departure; EARLIEST result once run | `statistical_analysis.md` §4; E1 | P0 |
| Figure: mechanism | Refill by release timing (early 0.920 vs late 0.623, R136–R137), additionality, empty-search reasons, cancellation timing | `ladder-…-003.md`; `timing-…-003.json`; `docs/figures/fig2`, `fig6` | P0 |
| Figure: sweeps | Budget, threshold, tolerance, publishing lead, oracle response, flexible share | R222–R242; `docs/figures/fig3-sweeps.png` | P1 |
| Table: model quality | KM / Cox / GB concordance, departure Brier, calibration, with intervals (E5) and Uno's C; the last-mile ablation stated as by construction | R243–R245; `hazard-h3.json` | P0 |
| Table: stability across worlds | h1 / h2 / h3 contrasts | `statistical_analysis.md` §3 | P1 |
| Table: hotel check | daily hazards; concordances with intervals (E7) | R246–R249; `hotel-…json` | P1 |
| Exploratory box | Seat-weighted effect of asking (+0.138, R265 / R273), labelled post hoc | `statistical_analysis.md` §5.1 | P1 |

Figures: the seven figures in `docs/figures/` were regenerated under h3 on 2026-10-28. **Check every caption in `docs/figures/README.md`, which may still quote h2 values.** Fix the JOURNEX labels in Fig. 1 and Fig. 2 of the paper.

## 8. Section VII — Discussion and limitations

| Change | Priority |
|---|---|
| Remove "does not yet report experimental results"; replace with an interpretation of the null, the ceiling and the mechanism. | P0 |
| Limitations: synthetic world; every parameter assumed; single corridor; response parameters unidentifiable (research rule 4); the simulator cancels 2–4× more in the final week than real hotels; metric dependence (bookings vs seats); model trained under B0 and used under interventions. | P0 |
| Ethics: keep the four safeguards that exist (no model-based cancel, price or refusal; explanations logged; protected attributes not used; human decides). **Remove** "users can see and question their own reliability estimate" (not implemented, by design) and the fee-cap argument (no hazard fees exist). | P0 |

## 9. Section VIII — Conclusion and future direction

| Change | Priority |
|---|---|
| Conclude on the evidence: early release matters in principle, but reconfirmation is a weak lever even with perfect targeting; the useful ranking signal is timing. | P0 |
| Future work: a randomised hold-out pilot to estimate real response; joint recovery; driver-side models; calibration of the world to observed corridor data. | P1 |

## 10. Production and integrity checklist

| Item | Priority |
|---|---|
| Re-export the PDF with fully embedded fonts; the current text layer garbles reference names and quotes. | P0 |
| One name (D5) throughout, including figure labels and table captions. | P0 |
| A data and code availability statement pointing to a tagged, archived release (DOI) that reproduces every table with `scripts/reproduce.sh`. | P0 |
| State that all simulation data are synthetic, and the licence and source of the hotel data (CC BY 4.0). | P0 |
| Disclose the use of AI assistance in software, analysis and writing as the target venue's policy requires. | P0 |
| Every number traceable to a result ID in `verified_results.csv` or to a new report file. | P0 |

## 11. Order of work

1. Decisions D1–D6 (team).
2. Make the repository coherent and reproducible (audit action 1).
3. Run E1–E3 and E5–E7 (audit §9), and add their rows to `verified_results.csv`.
4. Revise Sections V–VI first, then III–IV, then I, II, VII and VIII, then the abstract last.
5. An internal review against `VAYORA_AUDIT_REPORT.md` §5 (unsupported claims), item by item.
