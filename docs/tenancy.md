# Tenancy, auditing, paging, money and plan limits

Shared foundations every tenant module builds on (blueprint section 3.3, prompt B3). Read this before
writing a module that touches community data.

## Checklist for a new `/community` module

1. **Entity** extends `TenantEntity` (gives `community_id` and the Hibernate `tenantFilter`).
2. **Repository** extends `TenantRepository`. Every read takes the community id in its name
   (`findByCommunityIdAndStatus`). There is no `findById`/`findAll`/`delete*`. A lookup that genuinely
   cannot be scoped (token to community, super-admin report) is marked `@CrossTenantLookup("reason")`.
3. **Controller** gets the community with `@CurrentCommunity UUID communityId` (or `CurrentTenant`).
   It never reads one from the path, query, header or body, never touches a repository, and never
   returns or accepts an entity (DTOs only). Mutating methods carry `@Audited` or
   `@AuditHandledBy("who writes the audit row")`.
4. **Service** looks things up with `findByIdAndCommunityId(id, communityId)` and unwraps with
   `tenantGuard.found(optional)`, so "missing" and "belongs to another community" are the same 404.
5. **Lists** take `@Valid PageQuery`, convert with a `SortWhitelist`, and return `PageResponse<Dto>`.
6. **Money** is `BigDecimal` in entities, `Money` where arithmetic matters, validated with
   `@MoneyAmount` on input. JSON carries amounts as strings.
7. **Limits and features**: call `PlanLimitService.checkMemberLimit / checkStorage / checkEmailQuota /
   requireFeature` before the action.
8. **Tests** extend `AbstractTenantIT` and call `assertCrossTenantRead / Update / Delete`,
   `assertListHides` and `assertCreateCannotTargetOtherTenant` for every endpoint.
   `TenantCoverageIT` fails the build for a `/community` endpoint with no such test.

All of this is checked by architecture tests (`ArchitectureRules`), which are themselves tested against
deliberately bad fixtures (`ArchitectureRulesSelfTest`).

## How the tenant is found

`TenantContextFilter` runs on `/api/v1/community/**` after bearer authentication. For a COMMUNITY_ADMIN it
resolves the community through `community_users` (`CommunityTenantResolver`), binds a `TenantContext`
for the request and clears it afterwards. A SUSPENDED or ARCHIVED community gets `403
COMMUNITY_SUSPENDED` on every write (reads stay allowed so data can still be exported). The status is read
on every request, so suspending takes effect at once. For work outside a request (jobs, public flows
resolved from a token) use `TenantContext.callAs(tenant, ...)`.

## The Hibernate filter is a second net, not the first

`TenantAwareJpaTransactionManager` switches `tenantFilter` (and `communityIdFilter` on the community
table) on as each transaction starts whenever a tenant is bound. It narrows HQL/JPQL, criteria and derived
queries. It does **not** apply to `EntityManager.find`, to loading a lazy association by id, or to native
SQL, which is why repositories still take the community id explicitly and native queries must include
`community_id` themselves.

## Auditing

* `AuditService.record(action, entityType, entityId, before, after)` takes the actor from the security
  context, the community from the tenant context, and the address, user agent and request id from the
  request. Use it directly when you need a before/after diff.
* `@Audited(action = "...")` records the returned DTO as `after`. The aspect owns the transaction, so the
  change and its audit row commit or roll back together; a failing audit write undoes the change.
* Everything passes through `AuditRedactor`: keys containing password, secret, token, hash, recovery,
  authorization, cookie, apikey, privatekey or credential (and a few exact names such as totp, otp, pin)
  are replaced, as are values that look like JWTs, bearer headers, `otpauth://` URIs, 256-bit tokens or
  SHA-256 digests. Over-redaction is intentional. A test fails if any secret-looking JPA field name is not
  covered, so a new column cannot start leaking.

## Paging

`?page=0&size=20&sort=name,desc` (page is zero-based; size defaults to 20, max 100 and is rejected, not
clamped; sort accepts only whitelisted names, with `id` added as a tiebreaker so pages never repeat rows).
Response: `{"items": [...], "total": 42, "page": 0, "size": 20}`.

## Money

`NUMERIC(14,2)` in the database, `BigDecimal` in Java, strings in JSON (never numbers). `Money` rounds
HALF_UP to two decimals; `Money.parse` and `@MoneyAmount` reject more than two decimals instead of rounding
what a person typed.

## Plan limits

Limits live in `plans.limits` (a null or missing value means unlimited) and features in
`plans.features`. Violations are `402 PLAN_LIMIT_EXCEEDED` or `402 PLAN_FEATURE_UNAVAILABLE`, with the limit
name, the numbers and the plan in the body. Storage usage is not tracked yet: `StorageUsageProvider`
reports 0 until the file module supplies a real one, so the storage limit cannot trip today.
