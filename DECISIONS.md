# Architectural decisions

Promotion Studio prioritizes reproducible pricing, explainable results, and complete local operation. It is a working sandbox with optional AWS infrastructure, not a production-hardened commerce service. The choices below describe the implementation; the improvement list distinguishes missing operational capabilities from existing foundations.

## Key choices and accepted trade-offs

**Overall structure** 
Almost any website works with the frontend-backend-resource architecture paradigm. No compelling reason was found to break out of that mold or attempt anything particularly fancy.

**One modular backend and one authoritative pricing engine, no pre-baked pricing engine library/framework**
No framework seemed to provide the necessary transparency or flexibility: it was easier to self-supply. 
Manual selection, optimization, confirmation, checkout and administrator fixtures use that engine.
Java 21/Spring Boot handles HTTP, authentication and orchestration.
Limiting the concurrent heavy calculations is a bit of a temporary hack and should be chucked once we have a proper rate limiter.

**Separate FE applications for admin and customer** 
At a slight overhead cost, we have two React/TypeScript applications which share components and generated OpenAPI contracts. 
The needs of the two are so different, that long-term it will be inevitable to be mostly separate.
Also, if we get particularly fancy with dashboards and charting, we may want to switch the dashboard up in order to use a lot of python.
Pricing implementations in the browser, administrator scripting and microservices were rejected for this delivery: they would increase inconsistency, security exposure and operational work. 
*Never assume the frontend is secure.*
Customer eligibility hints remain advisory. 
The trade-off is shared backend capacity and a service layer that will need further decomposition as features grow.

**Typed, deterministic promotion rules.**
Versioned strategy data replaces executable administrator code.
Integer cents, decimal percentage arithmetic, explicit rounding, stable ordering and unit allocations make results reproducible.
BOGO and BOGO triple use remaining balances; triple groups cannot reuse units or leave an additional qualifying group unused.
This is more restrictive than a general rules language and more expensive than subtotal-only arithmetic, but it supports trustworthy explanations, historical snapshots and regression fixtures.

**Bounded search with honest proof status.**
Suggest and Swindle enumerate compatible selections, targets and groupings through the same evaluator, using deterministic ties and a default two-second search budget.
Aside from any other concerns, the search budget absolutely cannot be unbounded: that's a disaster waiting to happen.
Always claiming an optimum from a greedy heuristic was rejected; unlimited exhaustive search was also rejected because grouping choices grow combinatorially.
The accepted compromise is a verified best-so-far candidate with explicit proof status.
A configurable calculation limit (10 slots by default) protects each backend instance, but it does not provide fairness or a complete request-time limit.
With more time, reuse validated structures, precompute eligibility, add stronger pruning and isolate search from checkout capacity.

**DynamoDB documents with conditional transactions.** The same storage adapter serves DynamoDB Local and AWS.
Owner-keyed access, optimistic versions and catalog revision checks protect edits and prevent foreign-cart access.
Checkout revalidates current pricing, writes immutable purchase snapshots and a unique cart-purchase ledger entry, and deletes the cart atomically.
Retries are idempotent; cloning creates a new cart.
Eventual consistency for pricing and a nontransactional “save order, then delete cart” sequence were rejected.
Accepted costs include duplicated snapshots, shared access partitions and whole-history dashboard reads that will not scale indefinitely at this time.

Relational vs NoSQL was a serious consideration at the beginning. 
A relational store would simplify reporting and retention, but was not chosen because our use case is so tightly associated with individual users or genuine bulk queries.
If we decide later to perform multi-user queries with highly customized selectors, then we will suffer or migrate to something relational.

**Local OIDC and cloud isolation.** Keycloak supplies local accounts, signing keys and PKCE login; browser tokens stay in memory.
Local DynamoDB uses explicit dummy credentials, assets are bundled, and startup initializes persistent storage and development users.
Cloud-only Cognito, runtime CDNs and required hosted developer accounts were rejected because every feature must work locally.
AWS/Cognito/Terraform remain an explicit optional configuration. The native launcher requires JDK 21 and Node; optional containers avoid host Java/Maven installations but require a container runtime.
Local Keycloak development mode is not a production identity deployment.

## What needs more time

- **CI/CD:** A verification workflow exists for tests, builds, generated contracts, offline acceptance and Terraform validation. A proven release pipeline is still missing: immutable artifact promotion, deployment approvals, cloud smoke checks and rehearsed rollback. Container-engine limitations also left fresh Compose/image acceptance unverified on this workstation.
- **Monitoring and unusual client behavior alarms:** Correlated JSON logs, request/database/pricing timers, administrator metrics and basic Terraform CPU/5xx alarms exist. Add dashboards, service objectives, latency/saturation alerts, delivery to an accountable operator, and privacy-conscious signals for excessive requests, repeated authorization failures, write bursts and calculation-slot monopolization. Logging alone is not detection or response.
- **Autoscaling and AWS costs:** Add bounded autoscaling for the Spring Boot ECS service using measured CPU, latency and calculation saturation. Static frontends do not need application-worker autoscaling. Load-test first; increasing replicas also increases aggregate search concurrency. Add AWS budgets, cost-anomaly alerts, tested notification routing, capacity ceilings and an explicit runaway-cost response. Current resource alarms are not spend controls, and a budget alert is not a hard spending cap.
- **Deletion and overall data limits:** Expired quotes are rejected but remain stored; there is no automatic retention or user-requested personal-data deletion (PDD) workflow. Add local cleanup plus optional AWS TTL, inactive-cart retention, and a documented purchase/audit retention policy. PDD must address identity records, snapshots, logs and backups, with an explicit decision about anonymized purchase markers. Add atomically enforced per-user record/byte quotas; per-document limits do not bound total storage.
- **Fair calculation admission:** Add per-user token buckets, weighted by calculation cost, alongside per-user concurrency limits and the global guard. Multi-instance enforcement needs shared coordination. Cover ordinary request floods and expensive collection reads as well as optimization; authentication is not resource isolation.
- **Administrator clarity and simpler development:** Replace identifier-heavy targeting and advanced JSON dependence with item pickers, strategy-specific examples, guided multi-line test creation and live explanations. Keep JSON import/export for reproducibility. Native setup/start/check commands now provide one-terminal startup and readiness diagnostics; add watch-mode development next while preserving the account-free container option. Paginate collections and maintain idempotent dashboard aggregates instead of rereading all history.

## Deliberately outside this delivery

Checkout records simulated purchases. Payment, inventory, fulfillment and tax are excluded. Arbitrary promotion code, guaranteed optimal answers for every large cart, multi-region operation and live AWS deployment are also excluded. The operational gaps above are acknowledged unfinished work, not claims of production readiness. More time would first go to resource fairness, quotas, deletion and an operated delivery/monitoring loop before adding more infrastructure.
