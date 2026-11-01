# Figures

Seven figures for the report, drawn by `python -m experiments.analysis.figures` from files
the repository already commits: the ladder and sweep reports, whose numbers come from
`experiments/analysis/metrics.sql`, and the durability model. `scripts/reproduce.sh`
redraws them and fails if any comes out different.

**All of it is synthetic: simulator output under assumptions in
`experiments/configs/world.yaml`, not evidence about real passengers.** Each figure says
so in its footnote.

The figures and the numbers below are those of world h3 (the last-mile effect) and model
h3, rebuilt on 2026-10-28. The h2 versions are in the repository's history; switching the
last-mile effect off reproduces the h2 numbers exactly (EXPERIMENT_PROTOCOL.md section 20).

## Figure 1. The variant ladder (`fig1-ladder.png`)

Completed rides per 100 searches, each variant against B0, with the 95% interval of the
seed-paired difference (run `7a1c0de0-…-003`, 30 seeds per variant). B3 gains +0.32. B4
and B5, which ask passengers to reconfirm, gain +0.35 and +0.32: nothing measurable beyond
B3. ORACLE, which asks exactly the bookings that will cancel late, gains +0.44, so even
perfect targeting adds only about +0.13 to B3. EXPERIMENT_PROTOCOL.md sections 5, 8, 12
and 20.

## Figure 2. Early and late releases (`fig2-refill-timing.png`)

The share of released seats that were refilled, on rides whose passenger releases were
all at least 24 hours before departure ("early") or all later ("late"); rides with both
are left out. Under B0, 92% of early releases and 62% of late ones are refilled. The
premise holds: an early release is worth more. But late releases are refilled three times
in five anyway, which bounds what moving them earlier can gain. Section 11.

## Figure 3. The sweeps (`fig3-sweeps.png`)

The change in completed rides per 100 searches from asking passengers, across three
settings: how far from their chosen time passengers will travel (B4 against B3), how early
drivers publish (B4 against B3), and how many requests go out per round (B5 against B4).
No setting tried shows a gain; an 8-hour window (−0.06, −0.12 to −0.01) and a 7-day
publishing lead (−0.05, −0.10 to −0.003) show a loss. Sections 8, 9 and 10.

## Figure 4. Booking durability (`fig4-survival.png`)

The Kaplan–Meier curve of model h3: of bookings still waiting to depart, the share not yet
cancelled by their passenger, by days since booking (training seeds only). 93% last a day,
73% three days, 56% six. A booking leaves the curve when it departs, so the later part
describes only bookings made well ahead, and few of them: by the last day only a few dozen
remain, and the final steps rest on a handful. In this simulator a booking made a week
ahead has close to an even chance of being released before it travels. That is the seat
the project's idea is about. AI_ML_METHODOLOGY.md.

## Figure 5. Calibration (`fig5-calibration.png`)

Predicted against observed survival to departure, validation seeds, in ten equal groups by
prediction, for the Cox and gradient-boosted models. Both sit close to the diagonal, so a
predicted 80% means about 80%. Neither ranks bookings well (concordance 0.64 and 0.62), and
the simulator generates cancellations from a Weibull hazard with shocks that neither model
can represent, on purpose. AI_ML_METHODOLOGY.md.

## Figure 6. When cancellations come (`fig6-cancellation-timing.png`)

Cancellations per seed by how long before departure they came, for B3, B4 and ORACLE (run
`7a1c0de0-…-003`, 30 seeds, from the `cancellation_timing` query; every cancellation is
counted, as in the late share). Asking passengers (B4) does cut late cancellations, by
about 16 per seed, but it also cuts about 12 from 24–48 hours and adds about 36 two or
more days out: about 8 more cancellations in all, so some bookings that would have
travelled unasked are released instead. ORACLE, which asks only the bookings that will
cancel late, cuts late cancellations by about 28 per seed and moves them mostly into the
one to four days before departure. Even that buys only about +0.13 completed rides per 100
searches (Figure 1), because late releases are mostly refilled anyway (Figure 2).

Mean cancellations per seed, and the seed-paired difference from B3:

| Before departure | B3 | B4 | B4 − B3 (95%) | ORACLE | ORACLE − B3 (95%) |
|---|---|---|---|---|---|
| 96 h or more | 26.4 | 50.3 | +23.9 (+21.9 to +25.9) | 29.9 | +3.5 (+2.8 to +4.2) |
| 48-96 h | 74.1 | 85.7 | +11.6 (+9.2 to +14.0) | 87.2 | +13.1 (+11.6 to +14.6) |
| 24-48 h | 67.6 | 55.2 | -12.4 (-14.3 to -10.6) | 80.2 | +12.6 (+11.6 to +13.6) |
| 12-24 h | 44.8 | 36.6 | -8.2 (-9.6 to -6.8) | 31.0 | -13.8 (-15.2 to -12.4) |
| 6-12 h | 26.2 | 22.6 | -3.6 (-4.5 to -2.7) | 19.3 | -6.9 (-7.9 to -5.9) |
| 2-6 h | 17.8 | 15.4 | -2.4 (-3.2 to -1.6) | 13.2 | -4.6 (-5.5 to -3.6) |
| under 2 h | 9.0 | 7.7 | -1.3 (-2.0 to -0.7) | 6.3 | -2.7 (-3.5 to -2.0) |

## Figure 7. Robustness (`fig7-robustness.png`)

Completed rides per 100 searches, each variant against B3 (run `7a1c0de0-…-004`, 30 seeds,
paired by seed). Replacing the Cox model with one that uses no booking feature (B3-KM,
B4-KM) changes nothing; asking without B3's ranking (ASK-ONLY) is no better than B0; and
the contact scheduler (B5) does no better than sending the same requests at random
(B5-RANDOM). The one gain, B3's ranking, comes from timing, not from predicting the
passenger: offering the soonest-leaving ride first, with no model at all (EARLIEST), does
exactly as well (−0.001, −0.021 to +0.018; EXPERIMENT_PROTOCOL.md section 21). Section 14.
