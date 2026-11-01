# Datasets

**Every dataset the project produces is synthetic.** It is produced by the simulator in
`sim/` from assumptions in `experiments/configs/world.yaml`. No data about real
passengers has been collected or used. The one real dataset is a public set of hotel
bookings, used only to set real cancellation timing beside the simulator's (the last
section below); it is not about passengers either. Nothing in this document, or any
result built on it, is evidence about how real passengers behave.

## Where the data comes from

| Dataset | Produced by | Kept in |
|---|---|---|
| Simulation event logs | `python -m sim.run` and the experiment runners, one run per variant and seed | `events` table in PostgreSQL, tagged with `run_id`, `variant`, `seed` |
| Model training and validation sets | `experiments/hazard/train.py`, which simulates the training and validation seeds in memory and turns their events into rows with `experiments/hazard/dataset.py` | In memory only; never stored |
| Fitted models | `experiments/hazard/train.py` | `intel/app/hazard/models/*.json` |
| Experiment reports | `experiments/analysis/compare.py`, `experiments/sweep.py` | `experiments/reports/*.json` and `.md` |
| Platform use | core-api, from people using the app | The same `events` table, with no `run_id` |
| Public hotel bookings (real) | Downloaded by `experiments/hotel/load.py`, checked by SHA-256 | The file in `experiments/data/` (not committed); its bookings in the `events` table under run `407e1000-…-001` |

Platform rows exist only from development and testing on the authors' machines. None
has been used for any result.

## The event log

One table, `events`, holds both simulated and platform events, with the same vocabulary
of 22 types and the same payload conventions.

| Column | Meaning |
|---|---|
| `run_id`, `variant`, `seed` | Which simulated run produced the row. Empty for platform rows |
| `sim_time` | When it happened: simulated time for a simulation, wall-clock time for the platform |
| `actor_type`, `actor_id` | `passenger`, `driver` or `platform`, and who. `platform` rows have no id |
| `event_type` | One of the 22 types in `vayora_events/vocabulary.py`, mirrored in core-api |
| `payload` | JSON, snake_case keys, timestamps in ISO-8601 with offset |

The vocabulary is frozen. Of the 22 types, these are written today: `search_issued`,
`candidates_returned`, `candidate_ranked`, `booking_requested`, `booking_accepted`,
`booking_rejected`, `booking_cancelled`, `seat_refilled`, `recovery_search`,
`recovery_booked`, `hazard_scored`, `intervention_issued`, `intervention_responded`,
`trip_started`, `trip_completed`, `trip_failed`, and, from core-api only,
`tier_selected` (a passenger choosing a flexible tier; the default is not logged),
`supply_gap_detected`, `nudge_issued` and `nudge_converted` (feature 93). Two are defined
but not written by anything: `driver_tier_selected` and `nudge_dismissed`.

In the simulator, `candidates_returned` also carries `rides_in_window` (from 2026-10-17):
the published rides leaving in the passenger's time window, before the seat check, so an
empty answer says whether no ride ran then or every one was full (EXPERIMENT_PROTOCOL.md
section 16). Runs made before then lack it, and the query reports their split as unknown.
The platform's own rows do not carry it.

On the platform, `intervention_issued` is also written for each seat-release reminder
(feature 79), with `action` `release_reminder` and `chosen_by`. Targeted (from 2026-10-20),
`chosen_by` is `scheduler` and `survival_probability` is the booking's latest score; with
targeting off, `chosen_by` is `everyone` and there is no `survival_probability`. It is a
platform row, outside every run.

When a driver withdraws and a passenger is rebooked at once (feature 88, simulated from
2026-10-19 when `demand.flexible_booking.share` is above 0), the new `booking_requested`
carries the same `search_id` and the same instant as the driver's `booking_cancelled`;
no other event marks it, as on the platform.

On the platform, a booking made on a ride already under way (feature 84) is followed at
once by its `trip_started`, written when the booking is made rather than when the
passenger boards, so that every `trip_completed` or `trip_failed` has its start.

`nudge_converted` records that a nudged driver went on to offer the stretch that day. It is
not evidence that the nudge caused it, and no report uses it as a result: CLAUDE.md makes
driver response a swept assumption, because simulation cannot identify it.

Contract the analysis relies on: `booking_cancelled` carries `departure_time`, so the
late-cancellation share can be computed.

## What the simulator assumes

All in `experiments/configs/world.yaml`, and **all assumptions chosen by the authors**,
not measured. The team has not yet confirmed them.

| Assumption | Value |
|---|---|
| Corridor | Kolhapur to Pune, about 230 km (fixed by the project) |
| Span | 60 days (fixed) |
| Booking horizon | 7 days (fixed) |
| Rides published per day | 12, at fixed weights across 06:00 to 18:00 |
| Seats per ride | 1 to 4, mostly 2 or 3 |
| Searches per day | 60, from a population of 800 people |
| Party size | 1 (70%), 2 (20%), 3 (10%) |
| A ride matches a search if it leaves within | 2 hours of the time asked for |
| A driver accepts a request | with probability 0.85 |
| A driver withdraws a published ride | with probability 0.04 |
| Passenger types | four latent types with different cancellation behaviour (below) |
| Cancellation timing | a Weibull hazard per type, with unobservable shocks |
| Response to a reconfirmation request | swept, never estimated |

### Latent passenger types

Each simulated person is one of `committed`, `flexible`, `opportunistic` or
`deadline_bound`, and keeps that type for the whole run. The type drives when and
whether they cancel. **It is never written to an event and never used as a feature.**
Tests fail if a type name, or a `kind` key, appears in any payload or training row.

### Deliberate misspecification

Cancellations are generated from a Weibull hazard with random shocks the models cannot
see. The models fitted are Cox proportional hazards and gradient-boosted survival,
neither of which can represent that generator. This is on purpose: a model that matched
its generator exactly would say nothing about how it would fare on real data.

## The training set

Built by `experiments/hazard/dataset.py`. One row per accepted booking.

| Field | Meaning |
|---|---|
| The nine features of version `f1` | Listed in AI_ML_METHODOLOGY.md section 3. All known when the booking was requested |
| `duration_hours` | From acceptance to the passenger's cancellation, or to censoring |
| `cancelled` | True if the passenger cancelled |
| `horizon_hours` | From acceptance to the scheduled departure |
| `reached_departure` | True if the booking was still live at departure |

The clock starts at acceptance. A booking is censored, not counted as a cancellation,
when it reaches departure, or earlier if the driver withdraws the ride: all that is known
then is that the passenger had not cancelled. Only cancellations by the passenger count.

| Set | Seeds | Accepted bookings | Passenger cancellations |
|---|---|---|---|
| Training | 1001-1020 | 25,272 | 3,196 |
| Validation | 2001-2010 | 12,595 | 1,611 |
| Experiments | 1-30 | never used for fitting or model choice | |

The three seed ranges are disjoint, set in `experiments/configs/hazard.yaml`, and the
training code refuses to run if they overlap. All training data comes from variant B0,
so the model learns from a world no intervention has yet changed.

## Reproducing the data

The simulator is deterministic given a seed: the same configuration and seed produce the
same events, row for row. A test checks this. So every dataset here can be regenerated
from the configuration files and the seed numbers, and nothing needs to be shipped
except the code and `experiments/configs/`.

The model's data needs no database: training simulates its seeds itself.

```sh
docker compose --profile research run --rm research python -m experiments.hazard.train
```

Experiment runs write to PostgreSQL; EXPERIMENT_PROTOCOL.md section 4 shows how.

## Personal data

The simulator invents no names, contacts or positions beyond stop coordinates; a
simulated person is a number. Platform events written by core-api carry user ids, and
search positions rounded to about 100 metres. There is no export of real user data and no
dataset of it.

## Real data: the public hotel bookings

Nuno Antonio, Ana de Almeida and Luis Nunes (2019), Hotel booking demand datasets,
*Data in Brief* 22, 41–49, doi:10.1016/j.dib.2018.11.126, licence CC BY 4.0: 119,390
bookings at a city hotel and a resort hotel in Portugal, arriving from July 2015 to
August 2017. It is read from the TidyTuesday copy (2020-02-11), which joins the article's
two files into one; the address and SHA-256 are in `experiments/configs/hotel.yaml`, and
a file with any other hash is refused. The file is not committed.

Used: the hotel, the lead time in days, the arrival date, and the reservation's status
(`Check-Out`, `Canceled` or `No-Show`) with the date it took that status, which for a
cancellation is the day it was cancelled. Nothing about the guests is used. Only bookings
made at most 7 days before arrival are loaded (19,746): the platform's horizon.

Each becomes the events a seat booking makes (`booking_requested` and `booking_accepted`
when made, `booking_cancelled` by the passenger when cancelled, `trip_failed` with
`passenger_no_show` at arrival), with variant `HOTEL-CITY` or `HOTEL-RESORT` and the year
of arrival as the seed, so the metric queries count it exactly as they count the
simulator. Dates have no time, so each is read as noon UTC; a real time zone would move
some intervals by an hour where summer time starts or ends.

It supports a time-to-event check to the day, of cancellation timing only: nothing says
whether a cancelled room was let again, and none of the durability model's features
exist in it. The comparison is EXPERIMENT_PROTOCOL.md section 18.
