# Workspace audit

Baseline taken 2026-10-10, at commit `c25535c` ("Document the event log").

## State on arrival

The working tree contained only `.git`. All 64 tracked files showed as deleted; no
stash, no untracked files. They were recovered with `git restore .`, which overwrote
nothing. The cause of the deletion is unknown.

## Host tooling

| Tool | Found | Note |
|---|---|---|
| Git | yes | |
| Docker Desktop + Compose v5.3.0 | yes | Daemon was stopped; started manually |
| Node.js / npm | yes | |
| Java | JDK 26.0.1 | Project targets Java 21; builds run in the `maven:3.9-eclipse-temurin-21` container |
| Maven | no | Use `docker compose --profile test run --rm core-api-tests` |
| Python | 3.14.3 | Project targets 3.11; the research tests passed on 3.14 in a throwaway venv, the containers pin 3.11 |

Docker is the supported path. Nothing but Docker needs to be on the host.

## What exists

| Path | Contents |
|---|---|
| `core-api/` | Spring Boot 3 skeleton, Flyway `V1__enable_postgis`, `V2__create_events`, `eventlog` package (service, 22-event vocabulary, run context), 2 test classes |
| `intel/` | FastAPI skeleton with `/health` only |
| `gateway/` | Express + Socket.IO, JWT verification, auth tests |
| `web/` | Vite + React 18 + TypeScript + Tailwind shell, nginx config |
| `vayora_events/` | Python vocabulary and batched `COPY` writer, tests |
| `experiments/` | `analysis/metrics.sql`, `queries.py`, metric acceptance test, `configs/README.md` |
| `infra/` | OSRM corridor preparation, research image, `check-health.sh` |
| `sim/` | README placeholder only |
| `docs/` | `figures/.gitkeep`; this audit and `FEATURE_MATRIX.md` are the first documents |

No user-facing feature of the 95-item backlog is implemented. There are no domain
tables (users, rides, bookings, payments) and no API beyond health endpoints.

## Checks run on 2026-10-10

| Check | Command | Result |
|---|---|---|
| Python event writer and metric queries | `pytest -q` with `VAYORA_DB_URL` pointing at the compose Postgres | 17 passed |
| core-api unit and integration tests | `docker compose --profile test run --rm core-api-tests` | 11 run, 0 failures, BUILD SUCCESS |
| Gateway auth tests | `npm test` in `gateway/` | 6 passed |
| Images build and containers reach healthy | `docker compose up -d --build --wait --no-deps mongo redis core-api intel gateway web` | all healthy |
| Health endpoints | GET on ports 8080, 8000, 3000, 5173 | all `UP`; core-api reports `db` UP |

Not run: OSRM (`osrm-data`, `osrm-build`, `osrm`). It needs a ~220 MB download and was
skipped, so `infra/check-health.sh` as a whole and real routing are unverified. `web` has
no tests.

## Conflicts between the master build specification and CLAUDE.md

CLAUDE.md is binding and wins. These are recorded, not resolved.

| Topic | Specification | CLAUDE.md |
|---|---|---|
| Layout | `frontend/`, `backend/`, `ai-service/`, `realtime-service/`, `research/` | `web/`, `core-api/`, `intel/`, `gateway/`, `sim/`, `experiments/` |
| Pace | Continue through the whole backlog | Eight stages, stop for approval after each |
| Geography | Generic intercity | Kolhapur to Pune only; horizon, span and seeds frozen |
| MongoDB | Optional, if justified | Fixed home of chat, GPS traces and notifications |
| Research records | Separate prediction, intervention and lifecycle tables | One `events` table, frozen 22-event vocabulary |
| Inference service | Stores predictions, runs experiments | `intel/` holds no state |
| Feature tiers | "Preserve the supplied tier classifications" | None were supplied |

## Open questions for the team

1. The definitions of Stages 2 and 4-8. Only Stage 3 (simulator) is named in the repo.
2. The original Tier A/B/C classification of the 95 features.
3. Whether the 95-feature backlog is in scope at all under the eight-stage plan, or only
   the subset the research question needs.
