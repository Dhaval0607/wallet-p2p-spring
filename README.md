# wallet-p2p

[![ci](https://github.com/Dhaval0607/wallet-p2p-spring/actions/workflows/ci.yml/badge.svg)](https://github.com/Dhaval0607/wallet-p2p-spring/actions/workflows/ci.yml)

Java 21 · Spring Boot 3.5 · Postgres 16

A small wallet service with peer-to-peer transfers, built so that the interesting
properties hold **under concurrency and failure**, not just on the happy path.

Money is integer paise everywhere. There is no float in this program.

## Live

| | |
|---|---|
| **API** | https://wallet-p2p-spring.onrender.com |
| **Live logs** (public, no login) | https://wallet-p2p-spring.onrender.com/logs |
| **Dashboard** | https://wallet-p2p-spring.onrender.com/dashboard |
| **Metrics** | https://wallet-p2p-spring.onrender.com/metrics |
| **Invariant audit** | https://wallet-p2p-spring.onrender.com/invariants |
| **Design write-up** | [WRITEUP.md](WRITEUP.md) |

Reproduce every invariant against the live service in one command:

```bash
ADMIN_TOKEN=<token supplied with the submission> \
  ./scripts/burst.sh https://wallet-p2p-spring.onrender.com
```

That token gates `POST /admin/mint`, which is test funding only: it mints play
money into a single wallet and cannot move money between wallets, so it cannot
affect any invariant this service claims. It is kept out of the repo rather than
published, since it is a live credential on a public instance.

Everything else is open without it — `/invariants`, `/metrics`, `/logs` and the
dashboard need no auth, and `make up && make burst` reproduces all three gates
locally with no token at all.

> The free instance sleeps after ~15 minutes idle and takes ~40-60s to wake (a
> JVM cold start is slower than a native binary's). The burst script polls
> `/healthz` for up to 180s (`WAKE_TIMEOUT`) and then `/readyz` for up to 60s
> before it starts timing anything, reporting the wake as `cold start: took Ns`.
> So a sleeping instance shows up as a slow start, not a failure.

**Design write-up:** [WRITEUP.md](WRITEUP.md) — data model, the deadlock that
sorted lock ordering does *not* fix, where idempotency lives,
consistency-vs-availability, and where AI helped or misled me.

---

## The four invariants

| # | invariant | enforced by |
|---|---|---|
| 1 | **Conservation** — the sum of balances never changes across a transfer | debit + credit in one transaction; double-entry ledger that must sum to 0 |
| 2 | **No overdraft** — a balance never goes negative | row lock → check → conditional `UPDATE … WHERE balance >= amount` → `CHECK (balance_paise >= 0)` |
| 3 | **Exactly-once** — a repeated `idempotency_key` applies once | `UNIQUE (requester_user_id, idempotency_key)` committed **in the same transaction** as the money |
| 4 | **Race-free get-or-create** — two concurrent creates yield one wallet | `UNIQUE (user_id)` + `INSERT … ON CONFLICT DO UPDATE … RETURNING` |

Don't take this table's word for it — `GET /invariants` recomputes all of it from
the base tables on every call, and answers **HTTP 500** if any of it is false.

---

## Run it

```bash
docker compose up --build -d --wait     # app + postgres, one command
./scripts/burst.sh http://localhost:8080
```

`make up` and `make burst` do the same. Nothing else is required — no `.env`, no
manual migration step, no seed script.

---

## The burst script

`scripts/burst.sh` is the one-command adversarial probe. Bash + curl + awk only —
no jq, no python, no node. It exits non-zero if any check fails, which is why CI
runs it too.

```
GATE 1  race-free get-or-create   50 simultaneous POST /wallets for a brand-new
                                  user  →  expect exactly ONE wallet id

GATE 2  idempotent exactly-once   30 simultaneous identical transfers, same key
                                  →  expect ONE debit, ONE credit, 30 identical
                                     responses, 29 flagged as replays
                                  →  same key + different body  →  409, no money moved

GATE 3  conservation + overdraft  400 concurrent transfers among a small wallet
                                  set, A→B and B→A deliberately overlapping,
                                  every 7th one overdrawing
                                  →  total unchanged, nothing negative, every
                                     overdraft declined cleanly, zero 500s
```

Then it asks the server to audit itself, so the result does not depend on the
script's own arithmetic.

Turn it up:

```bash
N_TRANSFERS=1500 N_PARALLEL=100 N_WALLETS=6 K_IDEMPOTENT=60 \
  ./scripts/burst.sh http://localhost:8080
```

Every request carries `X-Correlation-Id: <run-id>-…`, so you can paste the run id
into the filter box at `/logs` and watch only your own burst stream past.

---

## API

Auth is a bearer token per user: `Authorization: Bearer <token>`. The token **is**
the identity — first use provisions the user. Auth sophistication is explicitly
not what this exercise is about.

**One token owns exactly one wallet.** `POST /wallets` takes no body: it returns
the caller's wallet, creating it on first call, and `wallets.user_id` is `UNIQUE`,
so the same token can never produce a second one — that is what makes the
concurrent get-or-create gate hold. To have two parties to a transfer, use two
tokens:

```bash
A=$(curl -sX POST "$URL/wallets" -H 'Authorization: Bearer alice' | jq -r .id)
B=$(curl -sX POST "$URL/wallets" -H 'Authorization: Bearer bob'   | jq -r .id)
```

Only the owner of the **source** wallet may move money out of it, so a transfer
from `$A` is sent with alice's token. Anything else is `403`.

| method | path | notes |
|---|---|---|
| `POST` | `/wallets` | get-or-create the caller's wallet. `201` created, `200` already existed |
| `GET` | `/wallets/{id}` | current balance |
| `POST` | `/transfers` | `{from, to, amount_paise, idempotency_key}` |
| `GET` | `/transfers/{id}` | transfer status |
| `POST` | `/admin/mint` | test funding, admin token. **Not** a transfer — see the write-up |
| `GET` | `/invariants` | live audit, recomputed from base tables. `500` if broken |
| `GET` | `/metrics` | Prometheus |
| `GET` | `/dashboard` | live metrics dashboard |
| `GET` | `/logs` | public live log stream |
| `GET` | `/healthz` `/readyz` | liveness (no DB) / readiness (checks DB) |

### A transfer

```bash
curl -X POST "$URL/transfers" \
  -H "Authorization: Bearer alice" \
  -H 'Content-Type: application/json' \
  -d '{"from":"<alice-wallet>","to":"<bob-wallet>","amount_paise":25000,
       "idempotency_key":"order-8123"}'
```

Send it again with the same key and you get the identical document plus
`Idempotent-Replay: true`. Send it with the same key and a different amount and
you get `409`.

### Status codes worth knowing

| code | when |
|---|---|
| `201` | transfer recorded — **including `status:"declined"`** |
| `409` | idempotency key reused with a different body |
| `422` | validation failed; the key was **not** consumed, so it is safe to reuse |
| `403` | you don't own the source wallet |

A decline is a successfully recorded business outcome, not an HTTP failure. It
returns `201` with `"status":"declined"` so that retrying it returns the identical
document — which a 4xx body could not do.

---

## Observability

**Logs.** A custom logback appender writes every record as one line of JSON with a
correlation id from MDC, echoed back as `X-Correlation-Id`. The same appender
tees into a bounded in-memory ring buffer served over SSE at `/logs` — publicly
viewable, no login, no host-dashboard credentials to share. Domain events:
`wallet_get_or_create`, `transfer_created`, `transfer_succeeded`,
`transfer_declined`, `idempotent_replay`, `idempotency_conflict`,
`transfer_rejected`, `mint`, `db_tx_retry`, `invariant_violation`.

**Metrics.** `/metrics` (Micrometer → Prometheus), plus a dashboard at
`/dashboard` that renders them with no Prometheus to run.

- rate / latency / errors: `http_server_requests_seconds` with percentile
  histograms, so p50/p95/p99 by route are real rather than estimated
- domain: `wallet_transfers_total{outcome=succeeded|declined_insufficient_funds|idempotent_replay|idempotency_key_conflict|rejected_invalid}`,
  `wallet_transferred_paise_total`, `wallet_wallets_provisioned_total`,
  `wallet_wallets_reused_total`
- invariants as gauges: `wallet_total_balance_paise`, `wallet_ledger_sum_paise`
  (pinned at 0), sampled every 15s
- `wallet_db_retries_total` — deadlock/serialization retries. **This staying at 0
  is the evidence the locking discipline works.** CI fails if it is not.

---

## Tests

```bash
make test    # invariant tests against a real postgres
```

Integration tests on purpose: the invariants live in Postgres, in unique indexes
and row locks and `CHECK` constraints, so a test with a mocked database would
verify nothing that matters. `drainRaceNeverOverdraws` is the sharpest — 40
concurrent claimants against a balance that can fund 10, and the naive
read-modify-write implementation passes every other test and fails this one.

CI additionally boots the stack from a clean checkout with one command, asserts
the container is non-root and healthy, runs both burst profiles, and asserts zero
deadlock retries and zero 5xx.

---

## Layout

```
src/main/java/com/dhaval/wallet/
  store/TransferService.java     the transaction that is the heart of the service
  store/WalletRepository.java    get-or-create, mint, the invariant audit
  web/                           controllers, correlation-id filter, error envelope
  obs/                           JSON log appender + ring buffer, domain metrics
  config/DatabaseUrl.java        libpq URL → JDBC, which every managed host needs
src/main/resources/schema.sql    the schema; the constraints are the real enforcement
src/main/resources/static/       the two operator pages
scripts/burst.sh                 the one-command invariant probe
render.yaml                      Render blueprint: app + free Postgres together
```

## Deploy

`render.yaml` is a Render Blueprint — **New → Blueprint → pick this repo** brings
up the web service and its free Postgres together, on the free tier, no card.
`ADMIN_TOKEN` is generated by Render rather than committed; read it from the
service's Environment tab.

One account gets exactly one free Postgres, so that database is shared with
whatever else is deployed to it. This service therefore keeps its tables in a
dedicated `wallet` schema rather than `public` — see the write-up for how that
isolation silently failed the first time, and what makes it real.

## Container

Multi-stage. Maven builds the jar, then `jarmode=tools extract --layers` splits it
so a code change rebuilds one small layer instead of re-pushing every Spring
dependency. Runtime is `eclipse-temurin:21-jre-alpine` running as a real
unprivileged user (uid 10001), with `MaxRAMPercentage` so the JVM sizes its heap
from the container's cgroup limit rather than the host's memory — without that it
gets OOM-killed on a 512MB free-tier instance.
