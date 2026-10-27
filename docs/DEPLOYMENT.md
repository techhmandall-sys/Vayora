# Running and deploying

How to run VAYORA on one machine, which is the only way it has ever been run. Nothing
here describes a production deployment, because there has not been one; the last
section lists what one would need.

## What you need

Docker Desktop (or Docker Engine with Compose v2). Nothing else: Java, Python and Node
all run inside containers. Node on the host is needed only to run the web tests or the
development server.

## Start everything

```sh
docker compose up -d --wait
```

The first start downloads an OpenStreetMap extract for western India (about 220 MB) and builds a
road graph for the corridor, which takes several minutes. Later starts reuse it.

| Service | Address | What it is |
|---|---|---|
| web | http://localhost:5173 | The app |
| core-api | http://localhost:8080 | The API; health at `/actuator/health` |
| gateway | http://localhost:3000 | Chat and live position; health at `/health` |
| intel | http://localhost:8000 | The model, the scheduler and live what-if runs; health at `/health` |
| osrm | http://localhost:5000 | Road routing |
| postgres | localhost:5432 | Database `journex`, user `journex` |
| mongo | localhost:27017 | |
| redis | localhost:6379 | |

To start without road routing (routes become straight lines, labelled as such):

```sh
docker compose up -d --wait postgres mongo redis core-api intel gateway web
```

## Configuration

Every setting has a working default in `docker-compose.yml`. To change one, copy
`.env.example` to `.env` and edit it. `.env` is ignored by git; do not commit one.

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | a public development value | At least 32 bytes. core-api signs with it; the gateway, and intel for live what-if runs, verify with it, so all three must have the same one. **Change it** |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | empty | Set both to have an administrator created when core-api starts. The password needs 12 characters or more. There is no other way to make one |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `journex` | |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Where the browser app is served from, for core-api, the gateway and intel. Change it together with `WEB_PORT` |
| `*_PORT` | see the table above | Change one if the port is taken |
| `OSRM_PBF_URL`, `OSRM_BBOX` | western India, clipped to the corridor | |
| `JOURNEX_PUSH_PUBLIC_KEY`, `JOURNEX_PUSH_PRIVATE_KEY` | empty | Push notifications are off unless both are set. `node scripts/vapid-keys.mjs` prints a new pair. Keep the private key secret; a new pair makes every browser subscribe again |
| `GOOGLE_CLIENT_ID` | empty | "Sign in with Google" is off unless set: a Web application OAuth client ID from the Google Cloud console, with the web app's address as an authorised JavaScript origin. Add it to the core-api service's environment |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | unset | An SMTP server for real email. Unset: nothing is emailed. Add them to the core-api service's environment |
| `JOURNEX_MAIL_FROM` | empty | The sender address. Required once an SMTP server is set; core-api will not start without it |
| `JOURNEX_PUSH_SUBJECT` | `mailto:journex@example.invalid` | A contact push services can use about this server's messages. Set a real one before turning push on |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | empty | Payments through Razorpay. Both or neither; neither is the simulated provider. A test key (`rzp_test_...`) is Razorpay's test mode, where no real money moves; get one from the Razorpay dashboard in Test Mode, under API Keys. Keep the secret out of git |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` | empty | Text messages and masked calls through Twilio. All three or none; none sends texts to the simulated outbox and simulates calls. `TWILIO_FROM` must be able to text and call. Twilio's test credentials with `TWILIO_FROM=+15005550006` are answered as if sent and send nothing. Real texts to Indian numbers need DLT registration |

The web app's addresses for core-api, the gateway and intel are fixed when its image is
built (`VITE_API_URL`, `VITE_GATEWAY_URL`, `VITE_INTEL_URL`). Changing `CORE_API_PORT`,
`GATEWAY_PORT` or `INTEL_PORT` means rebuilding it: `docker compose up -d --build web`.

intel's image is built from the repository root, as it carries the simulator and its
configurations for live what-if runs: `docker compose up -d --build intel` after changing
`sim/` or `experiments/configs/`.

Application settings that are not secrets (refund tiers, service fee, hold length, code
expiry, search radius) are in `core-api/src/main/resources/application.yml`. Research
settings are in `experiments/configs/`.

## The database

Flyway applies the migrations in `core-api/src/main/resources/db/migration/` when
core-api starts. There is nothing to run by hand. A migration that has been applied is
never edited; a change is a new numbered file.

Data lives in Docker volumes (`pgdata`, `mongodata`, `osrmdata`) and survives
`docker compose down`. To start from nothing:

```sh
docker compose down -v
```

This deletes every account, ride, booking and experiment run, and the road graph.

## Developing the web app

```sh
docker compose up -d --wait postgres mongo redis core-api gateway
cd web
npm ci
npm run dev
```

## Tests

See TESTING.md. In short:

```sh
docker compose --profile test run --rm core-api-tests
docker compose up -d --wait postgres core-api
docker compose --profile research run --rm research
cd web && npm ci && npm test
cd gateway && npm ci && npm test
```

## Experiments

See EXPERIMENT_PROTOCOL.md section 4. Runs write to the same PostgreSQL as the
platform, in the `events` table, kept apart by `run_id`.

## When something does not start

| Symptom | Likely cause |
|---|---|
| `failed to connect to the docker API` | Docker Desktop is not running. Start it and wait for the engine |
| core-api exits at once | `JWT_SECRET` is shorter than 32 bytes, or `ADMIN_PASSWORD` is shorter than 12 characters |
| A port is already allocated | Something else is using it; change the matching `*_PORT` in `.env` |
| The app loads but every request fails | `CORS_ALLOWED_ORIGINS` does not match the address the app is opened at |
| Sign-in works but chat does not connect | The gateway has a different `JWT_SECRET` from core-api |
| Routes are straight lines | OSRM is not running, or still building its graph. Check `docker compose logs osrm-build` |
| Walks say "in a straight line" | The walking graph (`osrm-foot`) is not running or still building. Check `docker compose logs osrm-build-foot` |
| No administrator | `ADMIN_EMAIL` and `ADMIN_PASSWORD` were not set when core-api started. Set them and restart it |

## What a real deployment would need

None of this exists.

- HTTPS, and a reverse proxy in front of web, core-api and the gateway.
- Secrets from a secret store, not from a file or a default.
- Databases on a private network with their own credentials, backups, and a tested
  restore.
- intel reachable only from core-api, except `/v1/whatif/*`, which an administrator's
  browser calls and which checks the token itself. The model and scheduler endpoints have
  no authentication and must not be exposed.
- More than one core-api instance would need the simulated outbox gone (it is in
  memory) and has not been tried.
- More than one gateway instance would need a Socket.IO adapter so that a message
  reaches clients connected to another instance.
- Logs and metrics collected somewhere. Today they are container output.
- A build pipeline. Images are built on the machine that runs them.
- The work listed at the end of SECURITY.md.
