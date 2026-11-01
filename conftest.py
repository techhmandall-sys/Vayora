"""Shared fixtures for the research-side tests.

Tests run against a real PostgreSQL, inside a throwaway schema built from the same
Flyway migration core-api applies, so they never touch real event rows.
"""

import os
import uuid
from pathlib import Path

import psycopg
import pytest
from sqlalchemy import create_engine, make_url

REPO_ROOT = Path(__file__).parent
EVENTS_MIGRATION = (
    REPO_ROOT / "core-api/src/main/resources/db/migration/V2__create_events.sql"
)


@pytest.fixture(scope="session")
def db_url():
    url = os.environ.get("VAYORA_DB_URL")
    if not url:
        pytest.fail("VAYORA_DB_URL is not set; these tests need a real PostgreSQL")
    return make_url(url)


@pytest.fixture
def events_schema(db_url):
    """A fresh schema holding an empty `events` table; dropped afterwards."""
    schema = f"test_{uuid.uuid4().hex}"
    dsn = db_url.set(drivername="postgresql").render_as_string(hide_password=False)
    with psycopg.connect(dsn, autocommit=True) as admin:
        admin.execute(f"CREATE SCHEMA {schema}")
        admin.execute(f"SET search_path TO {schema}")
        admin.execute(EVENTS_MIGRATION.read_text(encoding="utf-8"))
        yield schema
        admin.execute(f"DROP SCHEMA {schema} CASCADE")


@pytest.fixture
def pg_conn(db_url, events_schema):
    """psycopg connection scoped to the test schema, for the event writer."""
    dsn = db_url.set(drivername="postgresql").render_as_string(hide_password=False)
    with psycopg.connect(dsn, options=f"-csearch_path={events_schema}") as conn:
        yield conn


@pytest.fixture
def sa_conn(db_url, events_schema):
    """SQLAlchemy connection scoped to the test schema, for the metric queries."""
    engine = create_engine(
        db_url.set(drivername="postgresql+psycopg"),
        connect_args={"options": f"-csearch_path={events_schema}"},
    )
    with engine.connect() as conn:
        yield conn
    engine.dispose()
