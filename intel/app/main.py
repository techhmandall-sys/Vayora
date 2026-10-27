"""VAYORA intel service: inference, and simulation on request, with no state."""

import os

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .hazard.api import router as hazard_router
from .scheduler.api import router as scheduler_router
from .whatif.api import router as whatif_router

app = FastAPI(title="journex-intel")
app.include_router(hazard_router)
app.include_router(scheduler_router)
app.include_router(whatif_router)
# The browser calls intel for one thing only: an administrator's live what-if run.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[o for o in os.environ.get("CORS_ALLOWED_ORIGINS", "").split(",") if o],
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type"],
)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "service": "intel"}
