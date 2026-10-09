# Performance

## Targets

| What | Target |
|---|---|
| List and search endpoints (members, invoices, payments, ledger, complaints, audit) | **p95 < 300 ms** at 20,000 members and 200,000 invoices |
| Dashboard (computed, not cached) | p95 < 500 ms; cached answers cost no queries |
| Login | p95 < 1.5 s (BCrypt cost 12 is slow on purpose) |
| Any main query plan | no sequential scan of a big table; every community-scoped query uses an index starting with `community_id` |
| Queries per dashboard / list request | constant: independent of the amount of data (`DashboardIT` counts statements) |

## How it is checked

* `PerformanceIT` (opt-in, about two minutes) seeds **50 communities, 20,000 members, 200,000 invoices, about 140,000 payments with receipts and 1% reversals, 50,000 ledger
  entries, 20,000 complaints and 200,000 audit rows**, runs `ANALYZE`, then for each main endpoint (1) turns on PostgreSQL `auto_explain` and reads back the **real plan of every
  statement the endpoint ran, with its real parameters**, failing on a sequential scan of a big table, and (2) times 25 requests and fails when p95 is above 300 ms.
  The report is written to `backend/target/perf/report.md`.

  ```
  cd backend
  ./mvnw verify -Dperf=true -Dit.test=PerformanceIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -DargLine="-Dperf=true"
  ```

  It is not part of the normal build because it takes minutes and fills the test database. Run it before a release that changes queries or indexes, and when data volumes have grown 3x.
* `backend/perf/smoke.k6.js` is a k6 smoke test for a deployed environment (login, dashboard, invoice list, member list; thresholds are the targets above).

  ```
  k6 run -e BASE_URL=https://staging.example.com -e EMAIL=... -e PASSWORD=... backend/perf/smoke.k6.js
  ```

## What the check found and fixed

* The payments list (and the receipts list) answered "is this payment already reversed?" with `EXISTS (SELECT 1 FROM payment_records rv WHERE rv.community_id = p.community_id AND rv.reversed_of = p.id)`.
  Because the community was a *correlated column* rather than the bound value, PostgreSQL hashed **every community's payment rows** (140,000 at this size, growing with the whole platform) on each request.
  Using the bound community (`rv.community_id = :c`) limits it to the caller's rows and the plan uses the community index. Slowest statement went from 62 ms to 11 ms, and
  more importantly it no longer grows with other communities.

## Results (reference run)

Single machine (PostgreSQL 16 in a container, the API in the same JVM as the test client, BCrypt strength 4 in tests). The numbers show the shape, not production capacity;
a production Neon instance adds network latency to every statement (budget about 1-3 ms per round trip, so the dashboard's four queries and a list's two stay far under target).
 (auto_explain, real parameters, 20,000 members / 200,000 invoices)

| Endpoint | Statements | Big-table seq scans | Slowest statement (ms) |
|---|---|---|---|
| dashboard (cold) | 6 | none | 30.8 |
| members list | 4 | none | 1.1 |
| members search | 3 | none | 1.1 |
| members counts | 7 | none | 0.8 |
| invoices list | 4 | none | 13.6 |
| invoices overdue | 4 | none | 1.5 |
| invoices outstanding | 4 | none | 3.2 |
| payments list | 4 | none | 11.1 |
| ledger entries | 4 | none | 2.0 |
| audit trail | 3 | none | 0.6 |
| audit trail, filtered | 3 | none | 2.9 |
| complaints list | 4 | none | 1.1 |

## Latency (25 sequential requests each, after warm-up)

Target: p95 < 300 ms for list endpoints.

| Endpoint | p50 ms | p95 ms | max ms |
|---|---|---|---|
| dashboard (cold) | 26 | 31 | 36 |
| members list | 15 | 20 | 20 |
| members search | 13 | 16 | 20 |
| members counts | 14 | 19 | 26 |
| invoices list | 20 | 33 | 42 |
| invoices overdue | 10 | 14 | 14 |
| invoices outstanding | 12 | 18 | 50 |
| payments list | 25 | 41 | 46 |
| ledger entries | 9 | 12 | 12 |
| audit trail | 8 | 12 | 12 |
| audit trail, filtered | 7 | 12 | 13 |
| complaints list | 9 | 12 | 13 |

Login (BCrypt strength 4 in tests, so production is slower by design: about 250 ms at strength 12): median 13 ms.

## Reading a slow-query report in production

`org.hibernate.SQL_SLOW` logs any JPA statement slower than 250 ms (`hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS`), with the SQL and without bound values. For statements run through
`JdbcTemplate` (dashboard, lists, audit, exports) use the `http_server_requests_seconds` histogram to find the slow endpoint and `EXPLAIN (ANALYZE, BUFFERS)` on a copy of the data to see why.
Neon exposes `pg_stat_statements`; sort it by `total_exec_time`.

## Indexing conventions

Every tenant table has an index that starts with `community_id`. Lists that sort by a column get a composite `(community_id, column)` index; "open" subsets (unpaid invoices, pending outbox rows, running exports)
use partial indexes. The audit trail pages by keyset on `(created_at DESC, id DESC)` with matching indexes per filter. New list endpoints must be added to `PerformanceIT`'s endpoint list.
