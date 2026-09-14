# Wallet & P2P Transfer — design write-up

Java 21 · Spring Boot 3.5 · Postgres 16.

**Live:** https://wallet-p2p-spring.onrender.com | **Logs:** https://wallet-p2p-spring.onrender.com/logs | **Invariants:** https://wallet-p2p-spring.onrender.com/invariants
**Repo:** https://github.com/Dhaval0607/wallet-p2p-spring

---

# The one page

**Data model.** Five tables. Money is `bigint` paise in Postgres and `long` paise
in Java — no float, no `BigDecimal` on the money path, and `"amount_paise": 12.5`
is rejected by the deserializer, not rounded. `users` (SHA-256 of the token),
`wallets` (`UNIQUE (user_id)` makes get-or-create race-free, `CHECK (balance_paise
>= 0)` backstops overdraft), `transfers` — which **is** the idempotency table, not
a second one — `ledger_entries` (double-entry, `SUM` always 0), and `mints` for
money entering from outside. Mints are deliberately not transfers: routed through
the transfer path, conservation would be unfalsifiable, since the total could
change and I could always call it a deposit. Apart, it is a checkable equation —
`SUM(balances) == SUM(mints)` — which `GET /invariants` recomputes from the base
tables on every call, answering **500** if it fails. Funding is reachable without
a shared secret: `POST /wallets/{id}/fund` is a bounded public faucet for a wallet
the caller owns, so anyone can reproduce the gates against the live URL.

**The simplest-correct mechanism.** One `READ COMMITTED` transaction covers the
key, the debit, the credit and the ledger rows. Claim the idempotency key first
with `INSERT … ON CONFLICT DO NOTHING` (the unique index is the lock), then lock
both wallets `FOR NO KEY UPDATE` in **ascending wallet-id order**, then decide
while holding both. A decline happens before any money write, so nothing partial
exists to undo.

Sorted ordering alone is **not** sufficient. I built this design in Go first with
`FOR UPDATE` and correct ordering; a 400-transfer burst produced **428 deadlocks
and 133 HTTP 500s**. `INSERT INTO transfers` takes `FOR KEY SHARE` on both wallets
for its foreign keys — before the sorted section, in an order the constraint
checker picks — and `FOR UPDATE` conflicts with it, so the cycle forms before my
ordering begins and no application ordering can break it. The fix is the *correct*
lock strength, not the strongest: `balance_paise` is not a key column, so
`FOR NO KEY UPDATE` excludes every writer without touching the FK locks. Zero
deadlocks, zero 500s, at 1500 transfers with 100 in flight.
**Rejected:** `SERIALIZABLE` (trades a deadlock storm for a retry storm, against
anomalies this transaction cannot have); conditional `UPDATE` with no locks (safe,
but makes "no partial apply" a property of my rollback code rather than of never
having written); JPA `@Lock(PESSIMISTIC_WRITE)` (silently maps to the `FOR UPDATE`
that deadlocks here); application mutexes (wrong layer — breaks on replica two).

**Where idempotency lives.** On `UNIQUE (requester_user_id, idempotency_key)` in
`transfers`, inserted **in the same transaction as the debit and credit**. Check
the key in a separate transaction and a window opens between "no row with this key"
and "money moved" where a concurrent retry sees no row and moves money too —
exactly what a 30-way storm finds. Committed together, a duplicate either blocks on
the index and reads the committed result, or loses the race and reads the same.
Same key, different body is a **409**, compared on a fingerprint of the request's
*meaning* (`SHA-256("v1|from|to|amount")`), so reformatted JSON is a retry rather
than a spurious conflict. Validation runs before the key is claimed, so a typo
never burns a key the client cannot then reuse.

**Consistency vs availability.** Consistency, deliberately. Single Postgres
primary, synchronous linearizable writes; if the database is unreachable
`POST /transfers` fails rather than queuing or optimistically accepting. I gave up
availability — single point of failure, writes do not scale past one primary. Right
trade for money: "your transfer failed, retry" is recoverable and the idempotency
key makes that retry exactly-once, while "both succeeded and the money existed
once" is not.

**AI: directed vs decided.** I directed the correctness design — one transaction
spanning key and money, `transfers` as the idempotency table, lock ordering,
redundant debit layers, mints kept out of the transfer path, and the rejections
above. AI decided, and I accepted: `ON CONFLICT DO NOTHING` over catching `23505`,
the `xmax = 0` trick, the Micrometer boundaries, the libpq→JDBC translation.
Accepting cost me twice — `FOR UPDATE` with correct ordering, argued convincingly
and deadlocking 428 times, and `SpringApplicationBuilder.properties()`, which
registers *default* properties below `application.yml` and failed silently at
boot. Both were caught by running it, not reading it. That is the honest reason
`scripts/burst.sh` exists and why CI asserts `wallet_db_retries_total == 0`.

**Cost: ₹0.** Render free web + free Postgres, no card; GitHub Actions free for
public repos. No other services.

---

*Everything below is the detail behind that page — the full data model, the
deadlock in depth, the rejected-alternatives table, and a schema-isolation bug
that passed every health check while reading another service's rows. The page
above is the submission; the rest is the reasoning, which is what the exercise
says it is grading.*

---

## 1. Data model

Five tables. Money is `bigint` paise in the database and `long` paise in Java —
there is no `double`, no `float` and no `BigDecimal` anywhere on the money path,
so there is no place for a rounding rule to hide. A body carrying
`"amount_paise": 12.5` is rejected by the deserializer with a 400, not rounded.

That last sentence was false for most of this project's life, and I only found out
by testing it. Jackson's default is `ACCEPT_FLOAT_AS_INT`, which silently
**truncates** a decimal into a `long` — `12.5` became a 12-paise transfer that
returned `201 succeeded`. A `long` field looks like it rejects decimals; it does
not. The fix is one line of config (`accept-float-as-int: false`), and the burst
script now asserts it over HTTP against the deployed URL, because a property of
the deserializer is only real at the edge where the float actually arrives.

| table | purpose | the constraint that does the work |
|---|---|---|
| `users` | a bearer token is the identity; only its SHA-256 is stored | `UNIQUE (token_hash)` |
| `wallets` | one balance per user | `UNIQUE (user_id)` · `CHECK (balance_paise >= 0)` |
| `transfers` | one attempted movement **and** the idempotency record | `UNIQUE (requester_user_id, idempotency_key)` |
| `ledger_entries` | double-entry audit trail, two rows per success | `SUM(delta_paise)` over the table is always 0 |
| `mints` | money entering from outside (test funding, admin or faucet) | `UNIQUE (idempotency_key)` |

Two choices worth calling out.

**`transfers` *is* the idempotency table.** No separate `idempotency_keys` table,
because a second table means a second write that could commit apart from the
money. One row, one unique index, one transaction.

**`mints` is separate from `transfers`.** Test funding has to come from
somewhere, and if it went through the transfer path then "conservation" would be
unfalsifiable — the total could change and I could always call it a deposit.
Keeping mints in their own table makes conservation a checkable equation:

```
SUM(wallets.balance_paise) == SUM(mints.amount_paise)     at all times
SUM(ledger_entries.delta_paise) == 0                      at all times
```

`GET /invariants` recomputes exactly that from the base tables on every call and
returns **HTTP 500** if it does not hold. A scheduled sampler runs it every 15s
so a violation reaches the logs and the dashboard even when nobody is looking.

### Why JDBC and not JPA

Every invariant here is a specific piece of SQL: `ON CONFLICT ... DO NOTHING`,
`FOR NO KEY UPDATE`, a conditional `UPDATE` carrying a balance predicate,
`RETURNING`. Hibernate would sit between me and exactly the statements that have
to be right, and its first-level cache and flush ordering would make the lock
sequence something I infer rather than something I wrote. `JdbcTemplate` is the
whole persistence layer: the SQL in `TransferService` is the SQL that executes.

### A dedicated schema, and the way it silently wasn't one

The free tier gives one account exactly one database, so this service shares a
Postgres instance. Its tables therefore live in a dedicated `wallet` schema, and
Hikari pins `search_path` to `wallet, public` on every pooled connection.

That was true of the DDL too — `schema.sql` opened with `SET search_path TO
wallet, public` — and it made the isolation a no-op. `CREATE TABLE IF NOT EXISTS`
skips creation when a table of that name is **visible on the search_path**, not
when it is absent from the target schema. Another service already had a `wallets`
in `public`; with `public` on the path, every `CREATE` below matched it and did
nothing, `wallet` stayed empty, and every query afterwards fell through to
`public` and read and wrote the *other* service's rows.

Nothing looked wrong. The app booted, `/healthz` was green, `/invariants`
returned `all_invariants_hold: true` — because the invariants genuinely did hold,
just over somebody else's table. What exposed it was a differential test rather
than an inspection: create one wallet against this service, then read the other
service's wallet count and watch it move too.

The fix is one line — narrow the DDL to `SET search_path TO wallet` alone, so the
`IF NOT EXISTS` checks mean what they appear to mean — and the script restores the
runtime path at the end, since `connection-init-sql` runs once per physical
connection rather than per borrow. Two tests hold it down: one asserts all five
tables resolve inside `wallet`, and one plants a decoy `public.wallets` and
asserts it captures nothing.

The general lesson is the one this exercise is about. A green health check is
evidence that a service is running, not that it is operating on the data it
thinks it is. Isolation you have configured but never observed failing is a
claim, not a property.

---

## 2. The simplest-correct mechanism

One `READ COMMITTED` transaction covers the idempotency key, the debit, the
credit and the ledger rows. Inside it:

1. **Claim the key.**
   `INSERT INTO transfers (… status='pending' …) ON CONFLICT (requester_user_id,
   idempotency_key) DO NOTHING RETURNING id`. The unique index is the lock.
2. **Lock both wallets** with `SELECT … FOR NO KEY UPDATE`, issued in **ascending
   wallet-id order**.
3. **Decide while holding both locks.** If the source balance is short, mark the
   transfer `declined` and commit. *No money write has happened yet*, so there is
   nothing partial to undo.
4. **Apply**: conditional debit, credit, two ledger rows, status to `succeeded`.
5. **Commit.** Key and money land together or not at all.

The debit is still written as `UPDATE wallets SET balance_paise = balance_paise -
? WHERE id = ? AND balance_paise >= ?`. That predicate is redundant while the row
lock stands — it is kept so the debit remains atomically safe if someone later
removes the lock, with `CHECK (balance_paise >= 0)` as a third layer under both.
No single one of the three is load-bearing alone.

### `ON CONFLICT DO NOTHING` rather than catching the duplicate-key exception

Worth a line because it is a JDBC-specific choice. Catching `23505` would work,
but in Postgres *any* error aborts the whole transaction, so the catch block
could not then read the winning row — it would need a savepoint before every
insert purely to survive the exception. `DO NOTHING` reports the duplicate
through the affected-row count instead, the transaction stays alive, and the
committed row is readable immediately (each statement in READ COMMITTED takes a
fresh snapshot). Same guarantee, no savepoint machinery, no exception on a
perfectly ordinary path.

It also preserves the blocking behaviour that makes the storm correct: a
concurrent duplicate still waits on the index until the first transaction
finishes, rather than guessing.

### Deadlock: what sorted ordering does *not* fix

Sorted lock ordering alone **is not enough**, and I have numbers because I built
this design in Go first, shipped it with `FOR UPDATE`, and the burst script
produced **428 deadlocks and 133 HTTP 500s** in a single 400-transfer run.

The reason is a lock nobody writes down. `INSERT INTO transfers` has foreign keys
to both wallets, so Postgres takes `FOR KEY SHARE` on both rows to check them —
*before* the sorted section, and in an order chosen by the constraint checker,
not by me. `FOR KEY SHARE` is shared, so two transfers happily both hold it on
both wallets. Then both try to upgrade to `FOR UPDATE`, which **conflicts** with
the other's `FOR KEY SHARE`. Each waits for a lock the other already holds. No
amount of ordering in application code can break that cycle, because the cycle is
created before the ordering begins.

The fix is to take the *correct strength* rather than the strongest one:
`balance_paise` is not a key column, so `FOR NO KEY UPDATE` is the right lock. It
still excludes every other writer — all the mutual exclusion a debit needs — but
it does not conflict with `FOR KEY SHARE`. With that change: **zero deadlocks and
zero 500s**, verified here at 1500 transfers with 100 in flight and A→B / B→A
pairs deliberately overlapping.

So the answer to "what happens when A→B and B→A hit at the same instant" is two
things, and both are required:

- **ascending wallet-id order** gives a total order on the blocking locks, so the
  waits-for graph between two transfers cannot contain a cycle; and
- **`FOR NO KEY UPDATE` rather than `FOR UPDATE`** keeps the foreign-key locks
  out of that graph entirely.

A bounded retry on Spring's `ConcurrencyFailureException` (which is where
SQLSTATE class 40 — `40P01` deadlock, `40001` serialization failure — lands) sits
underneath as a safety net, counted by `wallet_db_retries_total`. That counter
staying at **0** through every run is the evidence that the prevention is doing
the work, not the retry. CI fails the build if it is ever non-zero.

### Heavier alternatives I rejected

| alternative | why not |
|---|---|
| `SERIALIZABLE` isolation | Correct, but it converts contention into `40001` aborts I would have to retry in a loop — replacing a deadlock storm with a retry storm. It buys protection against anomalies this workload cannot have: the transaction reads exactly the two rows it writes and holds locks on both while doing so. Paying global serialization cost for a guarantee two row locks already give is the definition of cargo-culting. |
| Conditional `UPDATE` alone, no row locks | Genuinely tempting, and safe for conservation and overdraft on its own. I still take the locks because they let me decide the decline **before** writing anything. Without them, a credit-then-failed-debit ordering needs a savepoint to unwind, and "no partial apply" becomes a property of my rollback code instead of a property of never having written. |
| JPA / Hibernate with `@Lock(PESSIMISTIC_WRITE)` | Maps to `FOR UPDATE`, which is exactly the lock that deadlocks here — and the mapping is not obvious from the annotation. The failure mode is a framework detail deciding a correctness property. |
| `synchronized` / `ReentrantLock` in the application | Wrong layer. It breaks the moment there is a second replica, and it puts the correctness of money in a process that can restart independently of the database. |
| Optimistic locking (`@Version`, retry) | More round trips and more moving parts than a row lock, for a workload where contention on a hot wallet is the expected case rather than the rare one. |

One honest cost of the Java build: pgx let me send both `FOR NO KEY UPDATE`
statements in a single pipelined batch, so deterministic ordering cost one
network round trip. JDBC has no pipelining for reads, so the two locks are two
round trips. On a same-region database that is roughly a millisecond, and I took
the clarity over the millisecond — but it is a real difference, not a wash.

---

## 3. Where idempotency lives

**In the `transfers` table, on `UNIQUE (requester_user_id, idempotency_key)`,
inserted inside the same transaction as the debit and credit.**

That co-location is the whole point. If the key were checked in a separate
transaction — or before the money transaction started — there is a window between
"no row with this key" and "money moved" in which a concurrent retry also sees no
row and also moves money. That TOCTOU gap is exactly what a 30-way retry storm
finds. Because the key and the balances commit together, the gap does not exist:
a duplicate either **blocks** on the unique index and then reads the committed
result, or **loses** the insert race and reads the same.

Keys are scoped per requester, so one user cannot burn or probe another's keys.

**Replay:** the duplicate finds zero rows inserted, reads the committed row,
compares fingerprints, and returns the stored outcome with
`Idempotent-Replay: true`. This includes declines — retrying a declined transfer
returns the original decline, it does not re-evaluate against a balance that may
since have been topped up.

**Same key, different body → `409`.** The fingerprint is
`SHA-256("v1|from|to|amount_paise")` — the request's *meaning*, not its bytes, so
reformatted JSON or reordered keys is a retry rather than a spurious conflict. A
409 moves no money; the burst asserts the balance is unchanged after one.

**Validation happens before the key is claimed.** A malformed body, a wallet that
does not exist, or a caller who does not own the source wallet is rejected
without consuming the key — otherwise a client who typo'd a wallet id could never
retry that key after fixing it.

---

## 4. Consistency vs availability

**I chose consistency, deliberately, and gave up availability.**

This is a single Postgres primary. Every transfer is a synchronous, linearizable
write against it. If the database is unreachable, `POST /transfers` fails — it
does not queue, buffer, or optimistically accept. `/readyz` reports the failure
honestly so a load balancer can take the instance out.

What that costs: the database is a single point of failure, writes cannot scale
past one primary, and a regional outage is a full outage.

Why it is right anyway: the alternative for money is accepting a write you cannot
yet prove is safe. An available-but-inconsistent wallet means either double-spend
under partition, or a reconciliation process that produces a negative balance a
customer has already spent. "Your transfer failed, retry" is a recoverable,
honest outcome — and the idempotency key makes that retry free and exactly-once.
"Your transfer succeeded, and so did the other one, and the money existed once"
is not recoverable.

One deliberate softening: `/healthz` (liveness) does **not** touch the database,
while `/readyz` does. A liveness probe that fails on a database blip would have
the host restart a perfectly healthy container and turn a ten-second hiccup into
a multi-minute outage.

Where I would go next, in order: read replicas for `GET` endpoints (balances
tolerate staleness; debits do not), then partitioning by wallet id if one primary
ever became the ceiling. Neither is warranted at this size, and both would be
complexity bought with no evidence.

---

## 5. AI: directed vs decided

Used throughout, transparently. The split:

**I directed (I made the call, AI wrote the code):**
- Java 21 + Spring Boot + **JdbcTemplate rather than JPA**, for the reason in §1.
- The whole correctness design: one transaction spanning key + debit + credit;
  `transfers` doubling as the idempotency table rather than a second table;
  ascending-wallet-id lock ordering; conditional debit and `CHECK` as redundant
  layers; the decline decided before any write so there is nothing to unwind.
- Keeping `mints` out of the transfer path specifically so conservation stays
  falsifiable, and exposing `/invariants` so a reviewer audits the running system
  instead of trusting this document.
- Streaming the JSON logs to a public `/logs` endpoint rather than handing out
  host-dashboard credentials.
- Rejecting `SERIALIZABLE`, JPA pessimistic locks, and app-level mutexes, for the
  reasons in §2.

**AI decided (I reviewed and accepted):**
- `ON CONFLICT DO NOTHING` over catching `23505`, and the savepoint argument for
  why — a genuinely better call than the exception-catching version I had in the
  Go build.
- The `xmax = 0` trick for reporting whether `ON CONFLICT` inserted or matched.
- Micrometer histogram/SLO boundaries, and the custom logback appender that
  serializes SLF4J key-value pairs into the same JSON shape the log viewer reads.
- The `DatabaseUrl` libpq→JDBC translation, and moving it out of an
  `EnvironmentPostProcessor` once the repackaged jar turned out to hoist
  `META-INF/` off the application classpath.
- Most of the burst script's portable shell.

**Where accepting AI output cost me, twice:**

1. **`FOR UPDATE` with correct sorted ordering.** I accepted it as correct; the
   reasoning looked sound. The burst produced 428 deadlocks. The foreign-key
   `FOR KEY SHARE` interaction in §2 is something neither of us considered until
   the numbers forced it.
2. **`SpringApplicationBuilder.properties()`** for the datasource override, which
   registers *default* properties — the lowest precedence tier, below
   `application.yml`. It failed silently at startup with `'url' must start with
   "jdbc"`. System properties were the fix.

Both were caught by running the thing rather than reading it, which is the honest
reason the burst script exists and why CI asserts `wallet_db_retries_total == 0`.
A design argument that sounds right is not evidence.

---

## 6. Cost

**₹0.** Render free web service + Render free Postgres, no card required. GitHub
Actions is free for public repositories. There are no other services.

The free web instance sleeps after ~15 minutes idle and takes ~40–60s to wake (a
JVM cold start is slower than a Go binary's), so the first request after a quiet
period is slow — `scripts/burst.sh` waits on `/healthz` before it starts timing
anything. Render's free Postgres expires 30 days after creation; swapping
`DATABASE_URL` to a Neon free database (also ₹0, no card, no expiry) is the only
change needed if that matters.
