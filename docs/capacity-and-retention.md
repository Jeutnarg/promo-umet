# Capacity, retention, and abuse review

These findings describe the current implementation. Recommendations below are future work, not protections already deployed. Capacity depends on workload and machine size; no multi-user saturation load test has established a requests-per-second limit.

## Data retention

Purchasing deletes the source cart transactionally; explicit cart deletion removes that cart and creates an empty replacement. Other data has no automatic cleanup policy. Expired quotes remain stored even though checkout rejects them. Unpurchased carts, personal items, promotion definitions, test sets, purchases and audit events remain indefinitely. There is no DynamoDB TTL attribute, TTL table configuration, or scheduled cleanup job. Local volumes survive service restarts and normal Compose shutdown. Resetting volumes deletes local data explicitly.

Keep purchase history and the one-purchase-per-cart guarantee when introducing retention. The purchase ledger currently duplicates the full order snapshot; archiving it should preserve a compact permanent cart-purchase marker. Consider short quote retention, configurable inactive-cart retention, and separate audit/archive retention. A local cleanup implementation is needed alongside any AWS TTL configuration. [DynamoDB TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html) is asynchronous and must never replace server-time validity checks.

## Likely bottlenecks

Under optimization-heavy traffic, backend CPU and calculation slots are the likely first bottleneck. Suggest and Swindle search combinatorial selections/groupings. A configurable semaphore admits 10 concurrent optimization/test runs per backend instance by default; additional runs return 429. Set `CALCULATION_CONCURRENCY` (Spring property `sandbox.calculation-concurrency`) to a positive integer and restart to change the limit. The two-second search budget excludes database/network time and may finish an in-progress evaluation before returning.

Under history-heavy traffic, whole-collection reads and heap usage are the larger risk. Saved-cart summaries reprice every saved cart on each request. The administrator dashboard materializes every purchase and traverses its snapshot to recompute all-time totals. Growing purchase history is concentrated in the `purchases` partition; the catalog and revision are also shared. These access patterns need pagination, aggregate counters and, at higher volume, partition redesign. DynamoDB's managed scaling does not fix expensive application queries or hot access patterns. Local DynamoDB/Keycloak are development services rather than production capacity targets. Terraform currently provisions a fixed ECS task count and alarms, without autoscaling policies.

## Malicious resource consumption

Authentication, ownership checks, bounded bodies/documents, cart/unit limits, a 50-definition promotion limit, bounded test sets, optimization deadlines and the configurable calculation concurrency limit provide useful boundaries. They do not prevent an authenticated user from continuously occupying all calculation slots, making repeated ordinary evaluations, triggering large collection reads, or creating unlimited records over time. There are no per-user rate limits, daily budgets, stored-byte quotas, or per-user counts for items, carts, quotes and orders. The administrator-only test runner can hold a calculation slot across up to ten two-second optimizer cases. Administrator audit events and test-set counts can also grow without a collection quota.

Prioritize per-user plus global rate/concurrency budgets, write/storage quotas enforced atomically, request/read deadlines, response pagination and resource alarms. UI controls are not security boundaries. These measures follow the resource limits and rate-limiting guidance in [OWASP API4](https://api-security.owasp.org/editions/2023/en/0xa4-unrestricted-resource-consumption/).

## Promotion validity

The shared evaluator checks `enabled` and expiration against server time for every selected promotion. At the expiration instant the offer is unavailable. Supplying old promotion definitions in a customer request cannot override the live catalog. Administrator fixtures intentionally use their own snapshots and fixed time; those results cannot be submitted as purchases.

Quotes snapshot current pricing and expire in five minutes. Checkout reloads the owner's cart and current catalog, reevaluates, compares versions/revision, steps and total, and rejects invalid or changed selections. Its transaction checks the catalog revision again, so a concurrent disable/edit causes a conflict. Browser focus/expiration refresh and gray offer cards help customers understand eligibility; they do not enforce checkout security. Historical purchases intentionally retain old promotion snapshots.

## Calculation complexity

Let `P` be promotion definitions, `L` cart lines, `U` merchandise units, `T` the maximum of one and the target-list lengths, `C` saved carts and `H` purchase history size. Bounds omit string lengths, JSON serialization and database/network latency.

| Calculation | Current upper bound / growth |
|---|---|
| Promotion ordering | `O(P log P × L × T)`; comparator eligibility counts are currently recomputed. |
| Evaluation with ordinary strategies/BOGO | `O(P log P × L × T + P × (L log L + L × T + U log U))`; scope/strategy choices often do less work. |
| Default BOGO triple grouping | `O(U²)` per application after sorting: repeatedly find a qualifying add-on pair with two pointers and remove the group. Explicit group validation is `O(U log U)` including the exhaustion check. |
| Evaluation memory | Up to `O(P × U + P × T + L)` for allocations, input targets and unit balances. |
| Suggest / Swindle | Product of selection, target and grouping choices, multiplied by evaluation work; exponential in `P` and factorial/combinatorial in eligible units for BOGO/grouping searches. |
| Saved-cart summaries | Sum of evaluation costs across all `C` carts, plus `O(C log C)` sorting. |
| Promotion dashboard | Linear in the total purchase snapshot/allocated-unit data read, plus `O(P log P)` result sorting; all snapshots currently occupy memory together. |

For example, pairing `2k` units has `(2k)! / (2^k k!)` distinct perfect matchings. Partitioning `3g` units into triples has `(3g)! / (6^g g!)` groupings before price qualification; optional unused units add further choices. Multiple promotions multiply these branches. The search deadline limits practical execution rather than changing this complexity. Unfinished searches return verified incumbents and do not claim proven optimality.

## Recommended order of work

1. Add authenticated-user rate limits, fair calculation admission, storage/record quotas and bounded request lifetimes; test sustained abuse and concurrency.
2. Add retention/cleanup for quotes and abandoned carts, purchase/audit archival, and local log rotation; preserve permanent purchase markers.
3. Paginate collection APIs and saved-cart summaries. Maintain idempotent promotion-usage aggregates when purchases commit, with a reconciliation path from immutable orders.
4. Precompute eligibility/order keys, reuse validated unit structures during search, prune with objective bounds, and isolate optimization workers from checkout capacity. Measure before introducing more infrastructure.
5. Reduce duplicated/unneeded snapshot data while retaining reproducible purchases; add saturation tests and use existing timing metrics to decide autoscaling and partition changes.

Keep the typed pricing engine, server-side repricing, transactional purchase guards and local authentication. They remain useful correctness boundaries as the system grows.
