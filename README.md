# Promotion Studio

A transparent promotion sandbox: Spring Boot / Java 21, DynamoDB, two React/TypeScript frontends, and OIDC. Orders are simulated; there is no payment, inventory, fulfillment, or tax integration.

## Overview

Promotion Studio lets customers build reusable item catalogs, try combinations of promotions, compare Suggest and Swindle results, and submit simulated purchases with detailed price explanations. Administrators manage typed promotions, run saved pricing scenarios, and review actual promotion usage and costs. Customer and administrator applications share one Java pricing engine and run entirely locally with DynamoDB Local and Keycloak.

## Development and tests

Java follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) with the project's explicit override of tabs (two-column tab width). Every Java source, including tests, uses this convention. Set `JAVA_HOME` to Java 21 and `GOOGLE_JAVA_FORMAT_JAR` to the `google-java-format` 1.28.0 all-dependencies JAR, then run `node scripts/format-java.mjs` or `node scripts/format-java.mjs --check` from the repository root. CI checks the same formatting. Record accessors and framework override names retain their standard names; application methods use descriptive verbs. Java enum constants use uppercase names; Jackson annotations preserve the existing lowercase JSON values.

Container-only backend tests:

```sh
docker compose run --rm backend-tests
```

Optional host development requires Java 21 and Node 22.12+; Maven is provided by the wrapper:

```sh
docker compose up -d dynamodb keycloak
cd backend
./mvnw verify
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd`. In a second terminal:

```sh
cd frontend
npm ci
npm run generate
npm run build
cd ..
node scripts/serve.mjs customer 3000
# In another terminal:
node scripts/serve.mjs admin 3001
```

`npm run generate` derives shared TypeScript contracts from `backend/target/openapi.json`, which the backend component tests export. Commit `frontend/src/generated/schema.ts` with contract changes. The frontends share components, authentication and API transport; their separate runtime configurations select customer/admin entry experiences. `build:customer` and `build:admin` produce independently deployable outputs.

For real DynamoDB adapter tests, set `DYNAMO_TEST_ENDPOINT=http://localhost:8000` before `mvnw verify`; otherwise those tests are explicitly skipped. Component tests use an in-memory transactional test double behind the production repository boundary, inject failures at each resource-access prefix, and require no infrastructure.

```sh
cd frontend
npx playwright install chromium
npm run test:e2e
npm audit --audit-level=moderate
```

See [architecture](docs/architecture.md), [capacity and retention](docs/capacity-and-retention.md), [test evidence](docs/verification.md), and [AWS deployment](infra/aws/README.md).

The backend provides correlated JSON request, database, pricing and exception logs plus administrator-only performance metrics. Every response includes `X-Request-ID`. See [service observability](docs/observability.md) for event fields, timing definitions, privacy behavior and ready-to-use investigation queries.

## Run locally — no external accounts

Install a container runtime with Docker Compose support and start its engine. No Docker Hub login, AWS account, cloud credentials, external identity account, or SaaS subscription is required. First setup downloads public images and packages; running the built system needs no internet access.

```sh
docker compose up --build -d --wait
```

| Application | URL | Local credentials |
|---|---|---|
| Customer | http://localhost:3000 | `customer` / `customer-password` |
| Administrator | http://localhost:3001 | `admin` / `admin-password` |
| Keycloak account administration | http://localhost:8080/admin | `local-admin` / `local-admin-password` |
| Backend health | http://localhost:8081/actuator/health | Public |
| OpenAPI | http://localhost:8081/v3/api-docs | Public |

The startup imports a local Keycloak realm, users, roles and PKCE client, creates the DynamoDB table, and seeds two sample items, three promotions, and the **$54 sketch** test set. Initialization preserves existing data. Seed passwords are exclusively for the localhost development profile. Create more local accounts and assign realm roles through Keycloak; neither email delivery nor an external identity provider is needed.

Use `localhost`, rather than `127.0.0.1`, in browser URLs: OIDC redirect URIs are exact. Sessions keep bearer tokens in browser memory; a refresh may show the sign-in screen again, and Keycloak's local session resumes authentication without reentering credentials. User work stays in DynamoDB.

```sh
docker compose logs -f backend
docker compose stop          # keep containers and data
docker compose start        # resume
docker compose down         # remove containers; keep data volumes
```

**Reset local data:** `docker compose down --volumes` permanently deletes this project's local carts, orders, test sets, and Keycloak accounts. The next startup recreates the seed data.

## Verify offline operation

Build application and test images while connected:

```sh
docker compose --profile test build
docker compose pull dynamodb keycloak
docker compose up -d --wait
docker compose run --rm browser-tests
docker compose down
docker compose -f compose.yaml -f compose.offline.yaml up -d --no-build --pull never --wait
docker compose -f compose.yaml -f compose.offline.yaml run --rm -e EXPECT_PERSISTED=1 browser-tests
```

The offline override places the services on an **internal Docker network**, disables service image pulls, and retains the data volumes. Browser tests also reject every non-local browser request. The browser-test container proxies its own loopback ports to the service containers, so no host-networking feature or Docker Desktop login is needed. All fonts and browser assets are bundled. DynamoDB telemetry is disabled; local AWS SDK calls use explicit dummy credentials and a local endpoint. Initial builds and optional dependency/advisory checks are not offline operations.

Some container runtimes restrict host access to ports on internal networks. The container-based browser suite remains the portable offline verification path. Return to the normal network with `docker compose -f compose.yaml -f compose.offline.yaml down` followed by `docker compose up -d --wait`.

## Purchase records and promotion dashboard

Each purchase permanently retains its items, quantities, promotion versions, discount allocations, shipping, total, and purchase time. Checkout atomically records it and deletes the source cart. A cart can be purchased only once, including concurrent submissions with different keys. Retrying the same completed submission returns its existing purchase. “Clone this cart” on the completion page creates a fresh cart from the purchase snapshot.

Administrators can open **Promotion dashboard** to see all-time purchase counts, total promotion cost, order value, and per-promotion usage, discounted units, discount cost, shipping cost, and last-use time. Costs are actual recorded discounts in USD, including shipping savings. Editing a promotion cannot change past costs. Customer accounts cannot access the dashboard API.

Existing orders are migrated into the purchase ledger once on startup, and their purchased carts are removed. Stop any older backend instance before starting this version; migration retains purchase history and does not require resetting local data.

## Rule semantics

- All money is USD cents. Percentage arithmetic uses decimal HALF_UP rounding. Aggregate savings use proportional largest-remainder allocation, with stable unit-ID ties. Percentages round once per selected scope, except `item`, which rounds per unit.
- `oneitem` means once per selected line total. `item` means separately for each unit in that selected line. `items` aggregates eligible merchandise. `order` aggregates eligible merchandise and shipping.
- Item-ID and category filters intersect when both are supplied. Narrow scopes target one eligible line; omitted targets select the first line by ID. Broad scopes cannot be narrowed by a selection's line ID.
- Non-goal rules sort by declared scope, eligible distinct-item count, discount style, and ID. Styles are flat/BOGO/BOGO triple, percentage, fixed price. Fixed prices never increase a balance.
- BOGO pairs as many units as possible. Each pair grants the cheaper remaining unit free; equal-price ties use unit ID. Manual selections default to descending-price pairing. Optimizer results carry explicit pairs so their result can be reproduced exactly.
- BOGO triple (`bogotriple`) gives two eligible merchandise units free only when their combined remaining balance is strictly less than a third qualifying unit. Units participate in at most one triple per application, including the paid qualifying unit; shipping never participates. All groups use balances after earlier promotions. Default applications repeatedly take the largest qualifying unit and its highest-value qualifying add-on pair. Explicit groupings must be disjoint and exhaustive: no additional qualifying triple may remain among unused units. Suggest and Swindle search alternative exhaustive groupings, which can have different numbers of groups. The `pairs` selection field holds three unit IDs per group for this strategy; any order is accepted.
- Goal rules run after all non-goal rules. All goal eligibility uses one frozen, post-non-goal merchandise subtotal, excluding shipping. Thresholds are strict `>`. Goal discounts still reduce remaining balances, so shipping cannot be discounted twice below zero.
- Rainbow percentages count distinct eligible input categories, even if earlier discounts have reduced some eligible balances to zero.
- Unit-exclusive promotions reserve all their targets, including BOGO and BOGO triple qualifying and free units. Order-exclusive promotions cannot coexist with another applied promotion. Zero-saving rules do not count as applied or reserve targets.
- Expiration is inclusive: a promotion is unavailable at its expiration instant. Missing, disabled, expired, ineligible, conflicting, and zero-saving selections receive explanations.
- The display sorts each line's savings descending. The calculation trace retains execution order.

## Limits

- USD cents; 100 cart lines / 1,000 units; 50 promotion definitions.
- Amount inputs are at most 100,000,000 cents; quantities are at most 1,000 per line.
- Imports and HTTP write bodies are at most 256 KiB; stored documents are at most 350,000 bytes.
- Test sets contain at most 100 cases; a run includes at most 10 optimization cases.
- Optimization has a two-second search budget and four concurrent calculation slots per backend instance. Unproven results are labeled explicitly.
- Confirmation quotes expire after five minutes. Expiration or changed inputs requires refreshed confirmation.
