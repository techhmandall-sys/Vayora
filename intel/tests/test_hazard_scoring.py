import math
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from intel.app.hazard.features import FEATURE_VERSION, FEATURES, FeatureTracker
from intel.app.hazard.model import MODEL_DIR, HazardModel
from intel.app.main import app

NOW = datetime(2026, 1, 5, 9, 0, tzinfo=timezone(timedelta(hours=5, minutes=30)))
ZERO = dict.fromkeys(FEATURES, 0.0)


def _features(tracker, passenger=7, lead_hours=30):
    return tracker.features(
        passenger_id=passenger, seats=2, now=NOW,
        departure=NOW + timedelta(hours=lead_hours),
        desired_departure=NOW + timedelta(hours=lead_hours - 1.5))


def _toy_model(**changes):
    base = dict(
        version="t1", feature_version=FEATURE_VERSION, kind="cox_ph", features=FEATURES,
        coef=(0.0,) * len(FEATURES), mean=(0.0,) * len(FEATURES),
        times=(10.0, 20.0, 40.0), cumulative_hazard=(0.1, 0.3, 0.7))
    return HazardModel(**{**base, **changes})


# ---- features ---------------------------------------------------------------

def test_a_new_passenger_has_trip_features_and_an_empty_history():
    features = _features(FeatureTracker())

    assert tuple(features) == FEATURES
    assert features["lead_hours"] == pytest.approx(30.0)
    assert features["seats"] == 2.0
    assert features["departure_hour"] == 15.0  # 09:00 + 30 h, local time
    assert features["desired_gap_hours"] == pytest.approx(1.5)
    assert all(features[name] == 0.0 for name in FEATURES if name.startswith("prior_"))
    assert features["last_mile_distance_m"] == 0.0
    assert features["last_mile_mode_available"] == 0.0


def test_the_last_mile_gap_and_mode_shown_are_read_from_the_current_candidate():
    tracker = FeatureTracker()

    shown = tracker.features(
        passenger_id=7, seats=1, now=NOW, departure=NOW + timedelta(hours=30),
        desired_departure=NOW + timedelta(hours=28.5),
        last_mile_distance_m=2300, last_mile_mode_available=True)

    assert shown["last_mile_distance_m"] == 2300.0
    assert shown["last_mile_mode_available"] == 1.0


def test_history_counts_only_what_has_been_observed_for_that_passenger():
    tracker = FeatureTracker()
    for booking, passenger in [(1, 7), (2, 7), (3, 7), (4, 8)]:
        tracker.observe("booking_requested", {"booking_id": booking, "passenger_id": passenger})
        tracker.observe("booking_accepted", {"booking_id": booking})
    tracker.observe("booking_cancelled", {"booking_id": 1, "cancelled_by": "passenger"})
    tracker.observe("booking_cancelled", {"booking_id": 2, "cancelled_by": "driver"})
    tracker.observe("trip_completed", {"booking_id": 3})
    tracker.observe("trip_failed", {"booking_id": 4, "reason": "passenger_no_show"})

    seven, eight = _features(tracker, 7), _features(tracker, 8)

    assert (seven["prior_accepted"], seven["prior_cancellations"], seven["prior_completed"],
            seven["prior_no_shows"]) == (3.0, 1.0, 1.0, 0.0)  # the driver's cancel is not theirs
    assert seven["prior_cancel_share"] == pytest.approx(1 / 3)
    assert (eight["prior_accepted"], eight["prior_no_shows"]) == (1.0, 1.0)


def test_a_request_that_was_never_accepted_leaves_no_history():
    tracker = FeatureTracker()
    tracker.observe("booking_requested", {"booking_id": 1, "passenger_id": 7})
    tracker.observe("booking_rejected", {"booking_id": 1})
    tracker.observe("search_issued", {"passenger_id": 7, "search_id": 1})

    assert _features(tracker)["prior_accepted"] == 0.0


# ---- model ------------------------------------------------------------------

def test_survival_is_a_step_function_of_the_baseline_hazard():
    model = _toy_model()

    assert model.survival(ZERO, 5.0) == 1.0
    assert model.survival(ZERO, 10.0) == pytest.approx(math.exp(-0.1))
    assert model.survival(ZERO, 39.9) == pytest.approx(math.exp(-0.3))
    assert model.survival(ZERO, 1000.0) == pytest.approx(math.exp(-0.7))


def test_features_scale_the_hazard_relative_to_the_training_mean():
    index = FEATURES.index("prior_cancellations")
    coef = tuple(0.5 if i == index else 0.0 for i in range(len(FEATURES)))
    mean = tuple(1.0 if i == index else 0.0 for i in range(len(FEATURES)))
    model = _toy_model(coef=coef, mean=mean)

    at_mean = {**ZERO, "prior_cancellations": 1.0}
    riskier = {**ZERO, "prior_cancellations": 3.0}

    assert model.survival(at_mean, 20.0) == pytest.approx(math.exp(-0.3))
    assert model.survival(riskier, 20.0) == pytest.approx(math.exp(-0.3 * math.exp(1.0)))


def test_conditional_survival_is_the_ratio_of_two_survivals():
    model = _toy_model()

    conditional = model.conditional_survival(ZERO, elapsed_hours=15.0, horizon_hours=45.0)

    assert conditional == pytest.approx(model.survival(ZERO, 45.0) / model.survival(ZERO, 15.0))
    assert model.conditional_survival(ZERO, elapsed_hours=0.0, horizon_hours=45.0) == \
        pytest.approx(model.survival(ZERO, 45.0))
    assert model.conditional_survival(ZERO, elapsed_hours=30.0, horizon_hours=30.0) == 1.0
    with pytest.raises(ValueError):
        model.conditional_survival(ZERO, elapsed_hours=30.0, horizon_hours=20.0)


def test_a_model_for_other_features_or_with_a_missing_input_is_refused():
    with pytest.raises(ValueError, match="trained on features"):
        _toy_model(feature_version="f0")
    with pytest.raises(ValueError):
        _toy_model(features=FEATURES[:-1], coef=(0.0,) * (len(FEATURES) - 1),
                   mean=(0.0,) * (len(FEATURES) - 1))
    with pytest.raises(ValueError, match="time-ordered"):
        _toy_model(times=(20.0, 10.0, 40.0))
    incomplete = {k: v for k, v in ZERO.items() if k != "seats"}
    with pytest.raises(KeyError, match="seats"):
        _toy_model().survival(incomplete, 10.0)


def test_a_model_survives_a_round_trip_through_its_file(tmp_path):
    model = _toy_model(coef=tuple(0.1 * i for i in range(len(FEATURES))))
    path = tmp_path / "t1.json"
    path.write_text(model.to_json(), encoding="utf-8")

    assert HazardModel.load(path) == model


@pytest.mark.parametrize("name", ["h3-km", "h3-cox"])
def test_the_shipped_models_load_and_give_sane_decreasing_probabilities(name):
    model = HazardModel.load(MODEL_DIR / f"{name}.json")
    typical = {**ZERO, "lead_hours": 48.0, "seats": 1.0, "departure_hour": 8.0}

    curve = [model.survival(typical, hours) for hours in (0, 6, 24, 72, 168)]

    assert curve[0] == 1.0
    assert curve == sorted(curve, reverse=True)
    assert 0.5 < curve[-1] < 1.0


def test_the_shipped_cox_model_treats_past_cancellations_as_a_risk():
    model = HazardModel.load(MODEL_DIR / "h3-cox.json")
    clean = {**ZERO, "lead_hours": 48.0, "seats": 1.0, "departure_hour": 8.0,
             "prior_accepted": 3.0, "prior_completed": 3.0}
    flaky = {**clean, "prior_completed": 0.0, "prior_cancellations": 3.0,
             "prior_cancel_share": 1.0}

    assert model.survival(flaky, 48.0) < model.survival(clean, 48.0)


# ---- HTTP -------------------------------------------------------------------

client = TestClient(app)


def _body(**changes):
    typical = {**ZERO, "lead_hours": 48.0, "seats": 1.0, "departure_hour": 8.0}
    return {"features": typical, "elapsed_hours": 0, "horizon_hours": 48, **changes}


def test_scoring_returns_an_estimate_with_its_provenance():
    response = client.post("/v1/hazard/score", json=_body())

    assert response.status_code == 200
    body = response.json()
    assert 0.0 < body["survival_probability"] < 1.0
    assert body["model_version"] == "h3-cox"
    assert body["feature_version"] == FEATURE_VERSION
    assert body["trained_on"] == "synthetic"
    assert datetime.fromisoformat(body["scored_at"]).utcoffset() is not None


def test_a_booking_that_has_already_lasted_is_scored_on_what_remains():
    fresh = client.post("/v1/hazard/score", json=_body()).json()
    lasted = client.post("/v1/hazard/score", json=_body(elapsed_hours=40)).json()

    assert lasted["survival_probability"] > fresh["survival_probability"]


@pytest.mark.parametrize("body", [
    _body(horizon_hours=10, elapsed_hours=20),
    _body(elapsed_hours=-1),
    _body(features={k: v for k, v in ZERO.items() if k != "seats"}),
    _body(features={**ZERO, "latent_type": 1.0}),
    {"elapsed_hours": 0, "horizon_hours": 10},
])
def test_bad_requests_are_rejected(body):
    assert client.post("/v1/hazard/score", json=body).status_code == 422


def test_health_still_answers():
    assert client.get("/health").json() == {"status": "UP", "service": "intel"}


# ---- explanations -----------------------------------------------------------------

def test_contributions_are_exact_and_add_up_to_the_log_relative_risk():
    model = _toy_model(coef=(0.5,) + (0.0,) * (len(FEATURES) - 1), mean=(10.0,) + (0.0,) * (len(FEATURES) - 1))
    x = {**ZERO, FEATURES[0]: 14.0}

    shares = model.contributions(x)

    assert shares[FEATURES[0]] == pytest.approx(2.0)  # 0.5 * (14 - 10)
    assert all(shares[name] == 0.0 for name in FEATURES[1:])
    assert sum(shares.values()) == pytest.approx(math.log(model._relative_risk(x)))
    # And therefore reproduce the survival estimate exactly.
    hazard_at_20 = 0.3
    assert model.survival(x, 20.0) == pytest.approx(math.exp(-hazard_at_20 * math.exp(sum(shares.values()))))


def test_the_served_model_explains_every_score_it_gives():
    model = HazardModel.load(MODEL_DIR / "h2-cox.json")
    x = {name: 1.0 for name in FEATURES}

    body = TestClient(app).post("/v1/hazard/score", json={
        "features": x, "elapsed_hours": 0, "horizon_hours": 48}).json()

    assert set(body["contributions"]) == set(FEATURES)
    assert body["contributions"] == pytest.approx(model.contributions(x))


def test_a_model_with_no_covariates_has_nothing_to_explain():
    km = HazardModel.load(MODEL_DIR / "h2-km.json")
    assert set(km.contributions({name: 3.0 for name in FEATURES}).values()) == {0.0}
