# VAYORA — Reproducibility guide

How to rebuild every research result from source, what currently prevents a clean rebuild, and how to verify the outputs byte for byte. Labels: **[VERIFIED]** checked by the auditor; **[REPORTED]** from project documents; **[RECOMMENDATION]** advice.

## 1. Current state of the repository (read first)

| Copy | What it holds | Usable as the reproducible source? |
|---|---|---|
| `E:\B.Tech DS\MEGAPROJECT\JOURNEX` (git, branch `stage-2-3-booking-core-and-simulator`, HEAD `0cbeafb`) | Committed h2 reports (`30653cf`, 2026-10-20) and h2 documents; **staged, uncommitted** h3 code (last-mile world, 11 features) with the h1/h2 model files staged for deletion and **no h3 model or report** | Not as it stands: code and results disagree |
| `E:\B.Tech DS\MEGAPROJECT\Vayora-main` (no git) | Same code as `JOURNEX`'s working tree, plus the 2026-10-28 additions: h3 models, regenerated reports and figures, test fixes, pay-after-ride feature, documentation edits | Code and results agree, but there is no history, no tag, and documents still quote h2 |

[VERIFIED] (`git status`, `git log`, `diff -rq --strip-trailing-cr`; see the audit report §6)

**[RECOMMENDATION] Before anyone reproduces anything for the paper**, create one coherent, tagged state:

1. Decide the failing sanity test (§4, blocker 1).
2. Bring `Vayora-main`'s changes into `JOURNEX` (models, reports, figures, `web/src/data`, code and tests). The pay-after-ride feature is a separate concern and can be a separate commit.
3. Update README, EXPERIMENT_PROTOCOL.md and AI_ML_METHODOLOGY.md to the numbers of the chosen world.
4. Run `scripts/reproduce.sh` (§3) and require "Every result reproduced exactly."
5. Tag the commit (e.g. `paper-v1`) and archive it with a DOI. Record the hardware and Docker version.

## 2. Environment

| Item | Value | Where pinned |
|---|---|---|
| Container runtime | Docker with Compose v2. Observed: Docker 29.8.2, Compose v5.6.0, Docker Desktop on Windows 11, 20 CPUs, 7.6 GiB VM | — |
| Python (research, intel) | 3.11 (`python:3.11-slim`) | `infra/research/Dockerfile`, `intel/Dockerfile` (tag, not digest) |
| Python packages | Exact pins: SimPy 4.1.2, numpy 2.4.6, pandas 2.3.3, scipy 1.17.1, scikit-learn 1.9.1, lifelines 0.30.3, scikit-survival 0.28.0, OR-Tools 9.14.6206, matplotlib 3.10.3, psycopg 3.3.6, SQLAlchemy 2.1.4 | `requirements-research.txt` |
| Database | PostgreSQL 16 + PostGIS 3.4 (`postgis/postgis:16-3.4`); schema by Flyway in core-api (migrations V1–V36; `events` is created by V2) | `docker-compose.yml` |
| Java (schema owner) | Temurin 21, Maven 3.9 | `core-api/Dockerfile` |
| External data | Hotel bookings CSV, SHA-256 `7c2ae42a7353905ea136e5c2287f17c92c5435826598bfbb8491c6f0c7b1fc06`, from the TidyTuesday mirror of Antonio, de Almeida and Nunes (2019), CC BY 4.0 | `experiments/configs/hotel.yaml` |

[RECOMMENDATION] For an archival capsule, pin images by digest, and include a copy of `hotels.csv` (the licence allows redistribution with attribution) so the rebuild needs no network.

## 3. Rebuilding everything

From the repository root, with Docker running:

```sh
scripts/reproduce.sh              # tests, model, ladder, robustness, hotel, sweeps, web data, figures
scripts/reproduce.sh --no-sweeps  # the same without the six sweeps
```

What the script does, in order (`scripts/reproduce.sh`) [VERIFIED by reading]:

| Step | Command (inside the `research` container) | Output | Observed duration (2026-10-28) |
|---|---|---|---|
| 0 | `docker compose up -d --wait postgres core-api` (Flyway creates the schema, including `events`) | database | first start: image build |
| 1 | `pytest -q` | test results; **aborts the script on any failure** (`set -e`) | ~70 s |
| 2 | `python -m experiments.hazard.train` | `intel/app/hazard/models/<v>-km.json`, `-cox.json`; `experiments/reports/hazard-<v>.json` | 62 s |
| 3 | B0, B3, B4, B5, ORACLE × 30 seeds, then `analysis.compare` and `analysis.timing` | `ladder-…-003.*`, `timing-…-003.json` | ~3 min |
| 3b | B0, B3, B4, B3-KM, B4-KM, ASK-ONLY, B5, B5-RANDOM × 30 seeds, then `compare` vs B3 | `ladder-…-004.*` | ~14 min |
| 3c | hotel load, report and survival | `hotel-407e1000-…json/.md`, `hotel-survival.json` | ~1 min |
| 4 | six sweeps (`experiments/sweep.py`) | `sweep-*-5eeb0000-…-003` to `-008` | ~53 min |
| 5 | `analysis.export_web` | `web/src/data/` | seconds |
| 6 | `analysis.figures` | `docs/figures/fig1…fig7.png` | seconds |
| check | `git diff --exit-code` on models, reports, web data and figures | "Every result reproduced exactly." or failure | — |

Durations were measured while other jobs shared the machine, so treat them as upper bounds. The whole rebuild took about 75 minutes.

**Destructive step, by design.** Before rebuilding a run, the script deletes that run's rows from `events` (`forget`). Any earlier events under the same run id are lost. That is why the committed h2 events no longer exist in the local database: the 2026-10-28 h3 rebuild replaced them. [VERIFIED]

**Not rebuilt.** Ladders `…001` and `…002` and sweeps `…001` (intervention response, world h1) and `…002` (supply density) are history made with earlier code (EXPERIMENT_PROTOCOL.md §4). They cannot be reproduced by the current code. [VERIFIED]

### Determinism [VERIFIED by reading; REPORTED byte-for-byte runs on 2026-10-16 and 2026-10-19]

- Random streams are `random.Random(f"{seed}:{name}")`; string seeds are hashed with SHA-512, so they are stable across runs and platforms (`sim/simulator.py:651`).
- Draws for a search are consumed on arrival whether or not they are used, so variants see identical worlds.
- CP-SAT runs on one worker with a fixed seed; ties go to the lower booking id (`intel/app/scheduler/plan.py`).
- Gradient boosting uses `random_state: 0` and a sampled subset (`experiments/configs/hazard.yaml`); lifelines Cox is deterministic.
- B5-RANDOM shuffles with a stream named by the simulated time.

## 4. Blockers today, and how to clear them

| # | Blocker | Effect | Fix |
|---|---|---|---|
| 1 | `intel/tests/test_hazard_scoring.py:151` asserts 0.5 < S(168 h) for the h3 Kaplan–Meier model; it is 0.4905 | Step 1 fails, so `reproduce.sh` stops before producing anything [VERIFIED: 207 of 208 Python tests pass] | Team decision: loosen the bound with a documented reason, or revise `last_mile_sensitivity` in `world.yaml` (that changes every result, which must then be rebuilt) |
| 2 | The final check uses `git diff` | Fails outside a git repository (e.g. `Vayora-main`) | Run in `JOURNEX`, or compare SHA-256 against §5 |
| 3 | Code and results disagree in `JOURNEX`; documents quote h2 | A reproduction cannot match the paper's numbers | §1 procedure |
| 4 | First run downloads `hotels.csv` from GitHub | Needs network; a changed mirror would fail the SHA check (safe failure) | Place the verified file in `experiments/data/hotels.csv` beforehand |
| 5 | Docker Desktop and the stack do not restart on their own after a reboot | Commands fail with "failed to connect to the docker API" | Start Docker Desktop; `docker compose up -d --wait postgres core-api` |

## 5. Checksums of the current results (`Vayora-main`, h3, 2026-10-28)

SHA-256, from `sha256sum` [VERIFIED]:

| File | SHA-256 |
|---|---|
| `intel/app/hazard/models/h3-cox.json` | `9d780636550081a3c90c071c51c0340753f8cfeabf2c7bde7d4c31c8331e29ef` |
| `intel/app/hazard/models/h3-km.json` | `434ef5a2a2987c2a97a29a4ebcff5c3c8ad7f8d5cf28e55723be5bb8a07f046a` |
| `experiments/reports/hazard-h3.json` | `8bd5f03a2ab3308666ac3378d7a18d7ce9292d1ac0098e77c25443032dd12437` |
| `experiments/reports/ladder-7a1c0de0-0000-4000-8000-000000000003.json` | `828066cba4a36c7b59c5acdafa893d25f1e2638e432d7ac2cd3f9bb809839066` |
| `experiments/reports/ladder-7a1c0de0-0000-4000-8000-000000000004.json` | `a4022d8302bce984b94779195ad3e9e7fc39988266fea4c2dcb3b26cd7b2873f` |
| `experiments/reports/timing-7a1c0de0-0000-4000-8000-000000000003.json` | `3bf8a9ae60fda532f6dd8b978e6f5f413c109cd211e0ffad38f3ce7b1ed34d5d` |
| `experiments/reports/sweep-contact-budget-5eeb0000-0000-4000-8000-000000000003.json` | `07c6011f895e4091523de353001dc0b7f2ffd7dac976f391f59214eefe8bfc3f` |
| `experiments/reports/sweep-departure-tolerance-5eeb0000-0000-4000-8000-000000000004.json` | `224610940c5fe12c9917b4f6471e0fe3f76e0f448d3c28d70f4057454c7a4ad9` |
| `experiments/reports/sweep-publish-lead-5eeb0000-0000-4000-8000-000000000005.json` | `cbea9b1d6c1b0ea81c000f9f3f4c134dfe7609000d5ce058cb454fbb27c22c86` |
| `experiments/reports/sweep-survival-threshold-5eeb0000-0000-4000-8000-000000000006.json` | `fc4798a30fd63dfc5209827789a70b155d037c4f927534f72d2a3d70411e07b1` |
| `experiments/reports/sweep-oracle-response-5eeb0000-0000-4000-8000-000000000007.json` | `65723ea5c310fe804f0e3ab83a6322d577294d09431bb3bb1a394a4c36621e52` |
| `experiments/reports/sweep-flexible-booking-5eeb0000-0000-4000-8000-000000000008.json` | `77797d4ea9ba6e1028bc33ed83349f339abcc4c5d08e5ef3dd338dfa169d5472` |
| `experiments/reports/hotel-407e1000-0000-4000-8000-000000000001.json` | `a5cfe774d28c11291f3f571b57ad3c9fcc5851416ace97e5d1a31d907f000068` |
| `experiments/reports/hotel-survival.json` | `00920350a84ca9d7b6eb2f29b4faead86bb9911c47a0c73e86b9ce3d985af343` |
| `..\VAYORA_Research_Paper.pdf` (manuscript audited) | `0aa3b4c34e04c0d3f1fb2a1b0cfccd40abbbffecf841c4efa999a9cfde71e962` |
| `..\VAYORA_Report.docx` (project report audited) | `4f9a79aa6786f923efa107f687324c50c3e5c30f02c5dc3e22dca9301093d651` |

A rebuild from the same code and configuration should reproduce these files exactly. If it doesn't, compare first `experiments/configs/` and the model files.

## 6. Reproducing this audit

Everything the audit computed can be regenerated read-only from the event log of runs `…003` and `…004`. Those rows must be present; rebuild steps 3 and 3b if not.

```sh
# Copy the two statistics scripts to an empty folder: they read and write /audit.
mkdir -p /tmp/vayora-audit
cp research_audit/scripts/audit_stats.py research_audit/scripts/audit_seats.py /tmp/vayora-audit/

docker compose up -d --wait postgres
docker compose --profile research run --rm --no-deps -v /tmp/vayora-audit:/audit research python /audit/audit_stats.py
docker compose --profile research run --rm --no-deps -v /tmp/vayora-audit:/audit research python /audit/audit_seats.py
# -> /tmp/vayora-audit/audit_stats.json and audit_seats.json; compare with research_audit/data/

node research_audit/scripts/build_verified_results.cjs <path to JOURNEX> <path to Vayora-main>
# -> research_audit/verified_results.csv

python research_audit/scripts/pdftext.py ../VAYORA_Research_Paper.pdf paper.txt
# standard-library PDF text extraction used to read the manuscript
```

On Windows Git Bash, prefix the `docker compose … -v` commands with `MSYS_NO_PATHCONV=1` and pass a Windows path (`cygpath -w`) for the mounted folder.

Fixed choices in the audit scripts:
- 20,000 bootstrap resamples, generator seed 20261029;
- Holm over the 13 primary contrasts and, separately, over the 7 seat-weighted robustness contrasts;
- MDE at α = 0.05 two-sided, 80% power, paired t.

The audit's SQL only reads (`SELECT`); it opens no transaction that writes.

## 7. What a reviewer will be able to check after the fixes

- One command rebuilds every number in the paper from a tagged commit, and fails if any file differs.
- Every number is a named SQL query over `events` (`experiments/analysis/metrics.sql`), so a reviewer can re-query the log.
- Model training refuses overlapping seed sets; the latent passenger type never appears in an event or a feature (tests in `sim/tests/test_simulator.py` and `experiments/tests/test_hazard_training.py`).
- The external dataset is checksum-verified.
