"""Live what-if runs over HTTP: administrators only, bounded, exploratory."""

from datetime import datetime, timedelta, timezone

import jwt
import pytest
from fastapi.testclient import TestClient

from intel.app.main import app

SECRET = "test-secret-test-secret-test-secret!"
client = TestClient(app)


def token(role="ADMIN", secret=SECRET, issuer="vayora-core-api", expires_in=timedelta(hours=1)):
    now = datetime.now(timezone.utc)
    return jwt.encode({"iss": issuer, "sub": "1", "role": role, "iat": now, "exp": now + expires_in},
                      secret, algorithm="HS256")


def as_(bearer):
    return {"Authorization": f"Bearer {bearer}"}


@pytest.fixture(autouse=True)
def secret(monkeypatch):
    monkeypatch.setenv("JWT_SECRET", SECRET)


def test_only_an_administrator_with_a_token_core_api_signed_may_ask(monkeypatch):
    assert client.get("/v1/whatif/sweeps").status_code == 401
    assert client.get("/v1/whatif/sweeps", headers=as_(token(role="DRIVER"))).status_code == 403
    for forged in (token(secret="another-secret-another-secret-123"), token(issuer="someone-else"),
                   token(expires_in=timedelta(seconds=-1))):
        assert client.get("/v1/whatif/sweeps", headers=as_(forged)).status_code == 401
    assert client.get("/v1/whatif/sweeps", headers=as_(token())).status_code == 200

    monkeypatch.delenv("JWT_SECRET")
    assert client.get("/v1/whatif/sweeps", headers=as_(token())).status_code == 503


def test_the_sweeps_offered_say_how_far_their_setting_may_go():
    offered = {s["name"]: s for s in client.get("/v1/whatif/sweeps", headers=as_(token())).json()}

    assert set(offered) == {"contact-budget", "departure-tolerance", "publish-lead", "survival-threshold",
                            "oracle-response", "flexible-booking"}
    assert offered["contact-budget"]["whole"] is True
    assert offered["flexible-booking"] == {
        "name": "flexible-booking", "parameter": "demand.flexible_booking.share", "min": 0.0, "max": 1.0,
        "whole": False, "variant": "variants/B3.yaml", "baseline": "variants/B3.yaml", "baseline_per_cell": False}


@pytest.mark.parametrize(("body", "status"), [
    ({"sweep": "flexible-booking", "value": 1.5, "seeds": 2}, 400),   # beyond its bounds
    ({"sweep": "contact-budget", "value": 2.5, "seeds": 2}, 400),     # a whole number only
    ({"sweep": "supply-density", "value": 10, "seeds": 2}, 404),      # a sweep not offered live
    ({"sweep": "flexible-booking", "value": 0.3, "seeds": 1}, 422),   # too few seeds to pair
    ({"sweep": "flexible-booking", "value": 0.3, "seeds": 31}, 422),  # more than the frozen 30
])
def test_a_run_outside_what_is_offered_is_refused(body, status):
    assert client.post("/v1/whatif/run", json=body, headers=as_(token())).status_code == status


def test_a_run_answers_with_means_and_no_interval_and_says_what_it_is():
    response = client.post("/v1/whatif/run", json={"sweep": "flexible-booking", "value": 0.3, "seeds": 2},
                           headers=as_(token()))

    assert response.status_code == 200
    result = response.json()
    assert (result["variant"], result["baseline"], result["seeds"], result["value"]) == ("B3", "B3", 2, 0.3)
    assert "not a report figure" in result["data"]
    per_100 = result["measures"]["per_100"]
    assert set(per_100) == {"baseline_mean", "mean", "difference", "seeds", "seeds_up", "seeds_down"}
    assert per_100["difference"] == pytest.approx(per_100["mean"] - per_100["baseline_mean"])
