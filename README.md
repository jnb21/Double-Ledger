# Double-Ledger

[![CI](https://github.com/jnb21/Double-Ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/jnb21/Double-Ledger/actions/workflows/ci.yml)

A double-entry ledger API that records every movement of money as a balanced,
append-only journal entry, so every balance is traceable, retries never
double-post, and concurrent transfers never overdraw an account.

**Stack:** Java 21 · Spring Boot 4 · PostgreSQL 16 · Flyway · JUnit 5 + Testcontainers · GitHub Actions

## Guarantees

Each guarantee holds in the database itself, not only in Java, and has a test that proves it.

| Guarantee | Enforced by | Proven by |
|---|---|---|
| Debits always equal credits; one currency per entry | Java check (422) + deferred Postgres trigger at COMMIT | `SchemaTest`, `JournalEntryControllerTest` |
| History is never edited; mistakes are fixed with reversals | Triggers block `UPDATE` / `DELETE` / `TRUNCATE`; `UNIQUE` reversal link | `SchemaTest`, 8 simultaneous reversals → exactly 1 |
| A retried request never posts twice | `Idempotency-Key` claimed in the same transaction as the entry | 8 simultaneous requests with one key → 1 entry |
| Concurrent transfers never overdraw an account | `SELECT … FOR UPDATE` + `CHECK (allow_negative OR balance >= 0)` | 50 withdrawals of 100 from 1,000 → exactly 10 succeed |
| Opposite transfers never deadlock | Accounts always locked in id order | 25 A→B and 25 B→A at once → all succeed |
| Cached balances never drift from history | Atomic `balance = balance + Δ` in the posting transaction | Balance recomputed from postings in SQL and compared |

## How a write flows

```
POST /journal-entries   (Idempotency-Key: …)
  │
  ▼
JournalEntryController ── fingerprint = SHA-256(endpoint + parsed body)
  │
  ▼  ONE TRANSACTION ──────────────────────────────────────────────────────────┐
IdempotencyService   INSERT key ON CONFLICT DO NOTHING                          │
  │                    seen before? same fingerprint → replay stored 201        │
  │                                 different       → 409                       │
  ▼                                                                             │
PostingService       debits = credits?            → 422                         │
                     lock accounts ORDER BY id FOR UPDATE                       │
                     accounts exist? one currency? → 404 / 422                  │
                     enough funds?                 → 422                        │
                     insert entry + postings, balance = balance + Δ             │
                     store the response with the key                            │
COMMIT ── deferred trigger re-checks debits = credits ──────────────────────────┘
```

**Lock order** is the same on every write path: the idempotency key, then accounts by id.

## Run it locally

Requires Java 21 and Docker.

```bash
cp .env.example .env            # then fill in DB_USER and DB_PASSWORD
docker compose up -d            # Postgres 16 as ledger_db on localhost:5433
./mvnw spring-boot:run          # API on localhost:8080; Flyway migrates on startup
curl localhost:8080/actuator/health
```

Without compose: `./mvnw spring-boot:test-run` starts the app with a throwaway
Postgres from Testcontainers.

### Try it: the PayFriend demo

`scripts/payfriend-demo.sh` plays a small wallet app, "PayFriend", calling the ledger:
user wallets, a deposit, a payment with a fee, a network retry that doesn't double-charge,
an "insufficient funds" rejection, a fraud reversal (and a refused second one), a withdrawal,
the user's history, and a final check that the books balance. Run it while the app is up:

```bash
./scripts/payfriend-demo.sh            # PAUSE=1 to step through one request at a time
```

Run the tests (Docker must be running):

```bash
./mvnw test
./mvnw test -Dtest=ConcurrencyTest
```

## API

Amounts are integers in **minor units** (cents): `100000` = 1,000.00 CAD.
Every error is [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/accounts` | `name`, `type` (`ASSET` `LIABILITY` `EQUITY` `REVENUE` `EXPENSE`), `currency`, optional `allowNegative` |
| `GET` | `/accounts`, `/accounts/{id}` | Balance on the account's normal side |
| `GET` | `/accounts/{id}/postings` | The account's history, newest first |
| `POST` | `/journal-entries` | **Requires `Idempotency-Key`** |
| `GET` | `/journal-entries/{id}` | Entry with its postings |
| `POST` | `/journal-entries/{id}/reversal` | **Requires `Idempotency-Key`**; once per entry |

### Example session

These responses come from a real run against a fresh database.

```bash
curl -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Cash","type":"ASSET","currency":"CAD"}'
# 201  Location: /accounts/1
# {"id":1,"name":"Cash","type":"ASSET","currency":"CAD","balance":0,"allowNegative":false,...}
# (also created: 2 "Owner equity" EQUITY, 3 "Rent" EXPENSE)

curl -X POST localhost:8080/journal-entries -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c2a9e-invest' \
  -d '{"description":"Owner invests","postings":[
        {"accountId":1,"direction":"DEBIT","amount":100000},
        {"accountId":2,"direction":"CREDIT","amount":100000}]}'
# 201  Location: /journal-entries/1   Idempotent-Replayed: false

# The same request again (a retry): nothing new is posted
# 201  Idempotent-Replayed: true

# The same key with a different body
# 409  {"title":"Conflict","detail":"Idempotency-Key was already used for a different request"}

# Rent of 1,200.00 when Cash holds 1,000.00
# 422  {"title":"Ledger rule violated","detail":"Insufficient funds in account 1: balance 100000, change -120000"}

# Debits 100, credits 90
# 422  {"title":"Ledger rule violated","detail":"Entry is unbalanced: debits 100 != credits 90"}

curl -X POST localhost:8080/journal-entries/1/reversal -H 'Idempotency-Key: reverse-1'
# 201  {"id":2,"description":"Reversal of entry 1","reversesEntryId":1,"postings":[
#        {"accountId":1,"direction":"CREDIT","amount":100000},
#        {"accountId":2,"direction":"DEBIT","amount":100000}]}

# Reversing entry 1 again, with a new key
# 409  {"title":"Conflict","detail":"Journal entry 1 has already been reversed"}
```

### Errors

| Status | When |
|---|---|
| `400` | Malformed JSON, invalid fields (with an `errors` list), missing or invalid `Idempotency-Key` |
| `404` | Account or entry doesn't exist |
| `409` | Duplicate account name, entry already reversed, idempotency key reused with a different request |
| `422` | Breaks a money rule: unbalanced, mixed currencies, insufficient funds, reversing a reversal |

## Design decisions

**Money as `BIGINT` cents.** Floating point can't represent 0.10 exactly; integers can't round wrong.
`Math.addExact` turns overflow into a 422 instead of a silent wrap to a negative number.

**Rules in two layers.** Java gives the clear answer (a precise 422); Postgres gives the
guarantee (constraints and triggers that hold even if the Java code has a bug, or someone uses
`psql`). With the Java lock deliberately removed, the 50-thread test still never overdrew: the
database `CHECK` stopped what Java let through.

**Insert, then check.** Duplicate names, double reversals and reused idempotency keys are all
decided by a `UNIQUE` constraint, never by a "does it exist?" query first. Check-then-insert lets
two concurrent requests both pass the check.

**Append-only history with reversals.** A wrong entry is cancelled by its mirror image, linked
through `reverses_entry_id`. Both stay visible, which is what an audit needs. A reversal can't be
reversed: the fix for a wrong entry is reverse + repost.

**Pessimistic locking in id order.** The overdraft check is read-then-decide, so the account rows
are locked with `FOR UPDATE` before reading. Many transfers hit the same accounts (Cash), so
blocking is a better fit than optimistic retry. A single global lock order makes deadlocks
impossible rather than merely rare.

**Idempotency in the same transaction.** The key, the entry and the stored response commit
together or not at all. A request that fails rolls its key back too, so the client can fix the
request and retry with the same key. Only successful responses are stored.

**Fingerprint the parsed request.** The SHA-256 covers the endpoint plus the re-serialized
request, so a retry that differs only in whitespace or field order is still a replay, while the
same key on another endpoint is a conflict.

**Schema owned by Flyway.** Hibernate runs with `ddl-auto: validate`: it may only check that the
entities match the tables, never change them. Migrations are never edited after they run.

## Testing

52 integration tests run against a real PostgreSQL 16 started by Testcontainers, because the rules
under test (triggers, constraints, row locks) only exist in a real Postgres. An in-memory imitation
database couldn't prove them.

| Test class | Tests | Covers |
|---|---|---|
| `SchemaTest` | 9 | Database rules with plain SQL, checked by SQLSTATE code |
| `AccountControllerTest` | 9 | Account API: 201/Location, 400 field errors, 404, 409 |
| `JournalEntryControllerTest` | 22 | Posting, balances on each type's normal side, history, reversals, overdraft |
| `IdempotencyTest` | 9 | Replays, mismatches, failed requests, 8 simultaneous requests with one key |
| `ConcurrencyTest` | 2 | 50 parallel withdrawals; opposite-direction transfers |
| `LedgerApplicationTests` | 1 | The app starts |

CI runs `./mvnw -B verify` on every push and pull request.

## Limitations and next steps

- No authentication; idempotency keys are global rather than scoped per client, and never expire.
- No paging on lists and account history.
- `GET /journal-entries/{id}` doesn't show which entry reversed it (only the reversal points back).
- Account names are case-sensitive.
- OpenAPI/Swagger documentation.
