"""The features of the booking-durability model, and the one place they are computed.

`FeatureTracker` is fed events in time order and answers "what did the platform know
about this passenger at this moment?". The training set is built by replaying an event
log through it and the simulator feeds it the events it emits, so training and serving
cannot drift apart, and a feature can only ever be something that was in an event.
Latent passenger type is never in an event (research integrity rule 1).
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping

FEATURE_VERSION = "f2"

# All known when the booking is requested. Order is the model's column order.
FEATURES = (
    "lead_hours",            # request to the ride's departure
    "seats",                 # seats in the booking
    "departure_hour",        # local hour of day the ride leaves
    "desired_gap_hours",     # how far the ride is from the time the passenger asked for
    "prior_accepted",        # this passenger's earlier bookings that a driver accepted
    "prior_cancellations",   # of those, cancelled by the passenger
    "prior_completed",       # of those, travelled
    "prior_no_shows",        # of those, never boarded
    "prior_cancel_share",    # prior_cancellations / prior_accepted, 0 with no history
    "last_mile_distance_m",       # gap from the passenger's start to the boarding stop,
                                   # as shown with the candidate they booked (feature 91)
    "last_mile_mode_available",   # whether a non-walk access option (rickshaw or bus)
                                   # was shown alongside that stop
)


@dataclass
class _History:
    accepted: int = 0
    cancellations: int = 0
    completed: int = 0
    no_shows: int = 0


class FeatureTracker:

    def __init__(self) -> None:
        self._history: dict[int, _History] = defaultdict(_History)
        self._passenger_of: dict[int, int] = {}  # by booking id

    def observe(self, event_type: str, payload: Mapping[str, Any]) -> None:
        """Updates what is known. Call once per event, in time order."""
        booking = payload.get("booking_id")
        if event_type == "booking_requested":
            self._passenger_of[booking] = payload["passenger_id"]
            return
        passenger = self._passenger_of.get(booking)
        if passenger is None:
            return
        history = self._history[passenger]
        if event_type == "booking_accepted":
            history.accepted += 1
        elif event_type == "booking_cancelled" and payload.get("cancelled_by") == "passenger":
            history.cancellations += 1
        elif event_type == "trip_completed":
            history.completed += 1
        elif event_type == "trip_failed" and payload.get("reason") == "passenger_no_show":
            history.no_shows += 1

    def features(self, *, passenger_id: int, seats: int, now: datetime, departure: datetime,
                 desired_departure: datetime, last_mile_distance_m: float = 0.0,
                 last_mile_mode_available: bool = False) -> dict[str, float]:
        """Features of a booking this passenger would request right now.

        ``last_mile_distance_m`` and ``last_mile_mode_available`` describe the gap and
        access option feature 91 showed for the candidate being booked; they default to
        "nothing shown", not to history, since they are not a property of the passenger.
        """
        history = self._history[passenger_id]
        return {
            "lead_hours": (departure - now).total_seconds() / 3600.0,
            "seats": float(seats),
            "departure_hour": float(departure.hour),
            "desired_gap_hours": abs((departure - desired_departure).total_seconds()) / 3600.0,
            "prior_accepted": float(history.accepted),
            "prior_cancellations": float(history.cancellations),
            "prior_completed": float(history.completed),
            "prior_no_shows": float(history.no_shows),
            "prior_cancel_share": (
                history.cancellations / history.accepted if history.accepted else 0.0),
            "last_mile_distance_m": float(last_mile_distance_m),
            "last_mile_mode_available": 1.0 if last_mile_mode_available else 0.0,
        }
