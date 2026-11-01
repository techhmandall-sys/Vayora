"""HTTP face of the booking-durability model."""

from __future__ import annotations

import os
from datetime import datetime, timezone
from functools import lru_cache

from fastapi import APIRouter
from pydantic import BaseModel, Field, model_validator

from .features import FEATURES
from .model import MODEL_DIR, HazardModel

router = APIRouter(prefix="/v1/hazard", tags=["hazard"])

DEFAULT_MODEL = "h3-cox"


@lru_cache
def served_model() -> HazardModel:
    return HazardModel.load(MODEL_DIR / f"{os.environ.get('HAZARD_MODEL', DEFAULT_MODEL)}.json")


class ScoreRequest(BaseModel):
    features: dict[str, float]
    # Both measured from the moment the booking was accepted.
    elapsed_hours: float = Field(ge=0)
    horizon_hours: float = Field(ge=0)

    @model_validator(mode="after")
    def _check(self) -> ScoreRequest:
        if set(self.features) != set(FEATURES):
            missing = sorted(set(FEATURES) - set(self.features))
            unknown = sorted(set(self.features) - set(FEATURES))
            raise ValueError(f"features must be exactly the model's: missing {missing}, "
                             f"unknown {unknown}")
        if self.horizon_hours < self.elapsed_hours:
            raise ValueError("horizon_hours must not be before elapsed_hours")
        return self


class ScoreResponse(BaseModel):
    # An estimate that the passenger will not cancel before `horizon_hours`, given no
    # cancellation by `elapsed_hours`. Not a prediction of boarding or completion.
    survival_probability: float
    elapsed_hours: float
    horizon_hours: float
    model_version: str
    model_kind: str
    feature_version: str
    # Each feature's share of the log relative risk; they sum to it. Positive raises the
    # estimated chance of a cancellation, negative lowers it. All zero for a model with no
    # covariates.
    contributions: dict[str, float]
    scored_at: datetime
    # The model is trained on simulator output only; see docs/AI_ML_METHODOLOGY.md.
    trained_on: str = "synthetic"


@router.post("/score")
def score(request: ScoreRequest) -> ScoreResponse:
    model = served_model()
    return ScoreResponse(
        survival_probability=model.conditional_survival(
            request.features, elapsed_hours=request.elapsed_hours,
            horizon_hours=request.horizon_hours),
        elapsed_hours=request.elapsed_hours,
        horizon_hours=request.horizon_hours,
        model_version=model.version,
        model_kind=model.kind,
        contributions=model.contributions(request.features),
        feature_version=model.feature_version,
        scored_at=datetime.now(timezone.utc),
    )
