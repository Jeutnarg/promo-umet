reqs not read
# Promotion Studio

A transparent promotion sandbox: Spring Boot / Java 21, DynamoDB, two React/TypeScript frontends, and OIDC. Orders are simulated; there is no payment, inventory, fulfillment, or tax integration.

## Overview

Promotion Studio lets customers build reusable item catalogs, try combinations of promotions, compare Suggest and Swindle results, and submit simulated purchases with detailed price explanations. Administrators manage typed promotions, run saved pricing scenarios, and review actual promotion usage and costs. Customer and administrator applications share one Java pricing engine and run entirely locally with DynamoDB Local and Keycloak.

**Quick start without Docker:** with JDK 21, Node.js 22.12+ (including npm), and `tar` installed, run these commands from the repository root:

```sh
node scripts/local.mjs setup
node scripts/local.mjs start
```

Open http://localhost:3000 and sign in as `customer` / `customer-password`. Keep the terminal open; Ctrl+C stops the services. [Complete setup, administrator credentials, restart, logs and troubleshooting](#run-locally--no-external-accounts) are below.

## Development and tests

For the quickest start, follow [Run locally — no external accounts](#run-locally--no-external-accounts) below. Docker is optional. All commands assume the repository root unless a block changes directories.

Java code follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html). Set `JAVA_HOME` to JDK 21 and `GOOGLE_JAVA_FORMAT_JAR` to the `google-java-format` 1.28.0 all-dependencies JAR, then run `node scripts/format-java.mjs --check`. CI checks the same formatting.

Backend checks use the committed Maven Wrapper; installing Maven is unnecessary. With the local stack running, include real DynamoDB tests:

PowerShell:

```powershell
$env:DYNAMO_TEST_ENDPOINT = 'http://localhost:8000'
cd backend
.\mvnw.cmd -B verify
cd ..
```

macOS/Linux:

```sh
cd backend
DYNAMO_TEST_ENDPOINT=http://localhost:8000 sh ./mvnw -B verify
cd ..
```

Leave `DYNAMO_TEST_ENDPOINT` unset to run infrastructure-independent backend tests; database integration tests are then explicitly skipped. If Windows reports a locked application JAR during packaging, stop the native stack with Ctrl+C before building.

Frontend checks (with the local stack running for browser tests):

```sh
cd frontend
npm run generate
npm run build
npx playwright install chromium
npm run test:e2e
npm audit --audit-level=moderate
cd ..
```

The browser installation and advisory check need internet access. Linux browser tests may also require Playwright's documented OS packages. `npm run generate` derives contracts from `backend/target/openapi.json`, exported by the backend component tests; commit generated changes. Normal startup uses the committed contracts and does not require browser test tooling. The two frontends share components, authentication and API transport; `build:customer` and `build:admin` produce independent deployment outputs.

See [architectural decisions](DECISIONS.md), [architecture](docs/architecture.md), [capacity and retention](docs/capacity-and-retention.md), [test evidence](docs/verification.md), and [AWS deployment](infra/aws/README.md). The backend provides correlated JSON request/database/pricing logs, `X-Request-ID`, and administrator-only metrics; [observability documentation](docs/observability.md) explains them.

## Run locally — no external accounts

### Recommended: without Docker

Install these prerequisites once, then open a **new terminal in this repository's root directory**:

- **JDK 21**, including development tools: [Temurin 21 downloads](https://adoptium.net/temurin/releases/?version=21). Set `JAVA_HOME` to its installation directory, or make Java 21 available on `PATH`.
- **Node.js 22.12 or newer**, including npm: [Node.js downloads](https://nodejs.org/en/download).
- **tar**, included with current Windows and macOS installations; install your distribution's `tar` package on Linux if missing.

Check the installed tools:

```sh
java -version
node --version
npm --version
tar --version
```

`java -version` must report **21**. If several JDKs are installed, set `JAVA_HOME` to JDK 21; the launcher uses that installation even when another Java is on `PATH`. Keycloak supports [OpenJDK 21](https://www.keycloak.org/server/supported-configurations). No host Maven installation, AWS CLI, Docker, or external account is needed.

**First setup** (the same commands work in PowerShell, macOS and Linux terminals):

```sh
node scripts/local.mjs setup
node scripts/local.mjs start
```

`setup` downloads pinned **Keycloak 26.8.1** and **DynamoDB Local 2.6.1** archives from their public publishers, verifies their SHA-256 checksums, downloads Maven through the wrapper, installs npm packages and builds both applications. It does not run the test suite. First setup requires internet access and can take several minutes. It is repeatable and preserves saved data.

`start` launches **DynamoDB Local, Keycloak, Spring Boot, the customer frontend and the administrator frontend** as supervised native processes. It checks ports and waits for database, realm and application readiness before printing the URLs. Keep this one terminal open; separate service terminals are unnecessary. Startup uses existing artifacts and local endpoints, so after successful setup it needs no internet connection.

| Application | URL | Local credentials |
|---|---|---|
| Customer | http://localhost:3000 | `customer` / `customer-password` |
| Administrator | http://localhost:3001 | `admin` / `admin-password` |
| Keycloak account administration | http://localhost:8080/admin | `local-admin` / `local-admin-password` |
| Backend health | http://localhost:8081/actuator/health | Public |
| OpenAPI | http://localhost:8081/v3/api-docs | Public |

Open the customer/admin URL and click **Sign in to your workspace**, then use the corresponding credentials. Use `localhost` in browser URLs because OIDC redirect addresses must match. Tokens stay in memory; after a page refresh, click sign-in again to resume the local Keycloak session. Create extra accounts and assign realm roles through the local Keycloak administration page; email delivery is unnecessary.

The first startup imports the realm, customer/admin accounts and PKCE client, creates the application table, and seeds sample items, promotions and the **$54 sketch** test set. Later startups preserve edits and purchases. Development credentials stay confined to local setup.

**Stop and restart:** press **Ctrl+C** in the launcher terminal to stop all five child services. To resume, run only:

```sh
node scripts/local.mjs start
```

**After changing code:** stop the stack, rerun `node scripts/local.mjs setup` to rebuild, and run `node scripts/local.mjs start` again. Existing downloaded runtimes are reused. Builds may need internet for any newly introduced dependencies.

**Storage and logs:** native databases live in `.local/data/0/`; cached runtimes/downloads live in `.local/tools/` and `.local/downloads/`. Service logs are `.local/logs/0/{dynamodb,keycloak,backend,customer,admin}.log`. The entire `.local/` directory is ignored by Git. Read a log with `Get-Content .local/logs/0/backend.log -Tail 30 -Wait` in PowerShell or `tail -f .local/logs/0/backend.log` on macOS/Linux.

**Troubleshooting:** run `node scripts/local.mjs check` to check Java/Node and the six required ports (3000, 3001, 8000, 8080, 8081, and Keycloak's management port 9000). Stop older native processes or the Compose stack if a port is occupied; the launcher never kills an unrelated listener. If a service fails, inspect its named log. Frontends, Spring Boot and Keycloak bind to loopback; DynamoDB Local has no bind-host option, so keep its development port private.

For an isolated second dataset, set `LOCAL_PORT_OFFSET` before starting; for example, PowerShell `$env:LOCAL_PORT_OFFSET='100'` or macOS/Linux `LOCAL_PORT_OFFSET=100 node scripts/local.mjs start`. All six ports shift by that amount, OIDC/frontend URLs follow it, and data/logs use `.local/data/100/` and `.local/logs/100/`. Start only one launcher at a time because the downloaded Keycloak runtime is shared.

**Reset native data:** stop the launcher first. Delete only `.local/data/0/` to remove native customer/admin data and locally created Keycloak accounts while keeping downloaded tools. PowerShell: `Remove-Item -LiteralPath '.local/data/0' -Recurse -Confirm`; macOS/Linux: `rm -ri .local/data/0`. The next start imports the development accounts and seed data again. Native data and Compose volumes are independent; resetting one does not reset the other.

### Optional: Docker Compose

If you prefer containers, install a container runtime with Compose support, start its engine, and run:

```sh
docker compose up --build -d --wait
```

Use the same URLs and credentials above. No registry login or external account is required. Public images/packages need internet for the initial build; fonts and other runtime assets are bundled. Do not run native and Compose services on the same ports simultaneously.

```sh
docker compose logs -f backend
docker compose stop          # stop; retain containers and saved data
docker compose start        # resume
docker compose down         # remove containers; retain data volumes
```

For container-only backend tests: `docker compose run --rm backend-tests`. To permanently reset **Compose** data, run `docker compose down --volumes`; the next startup recreates seed data and accounts.

### Configure calculation concurrency

The default is **10 concurrent calculation requests per backend instance**. Suggest, Swindle and administrator test-set runs share this pool; one test-set run holds one slot for its entire run. When all slots are occupied, another calculation request receives HTTP **429**. Ordinary cart evaluation and checkout pricing do not use this pool.

Set the **positive integer** environment variable `CALCULATION_CONCURRENCY` before starting the backend, then restart to apply changes. The Spring property is `sandbox.calculation-concurrency` (also available as `--sandbox.calculation-concurrency=20` for direct Spring Boot startup). Zero, negative and non-integer values fail startup.

PowerShell, using the native launcher:

```powershell
$env:CALCULATION_CONCURRENCY = '20'
node scripts/local.mjs start
```

macOS/Linux:

```sh
CALCULATION_CONCURRENCY=20 node scripts/local.mjs start
```

Compose forwards the same variable; set it in your shell or `.env` before `docker compose up --build -d`. Optional AWS deployment exposes Terraform input `calculation_concurrency`, also defaulting to 10. Each backend instance has its own pool, so multiple replicas multiply total capacity; this setting is not a per-user throttle.

## Verify offline operation

For the native path, finish `node scripts/local.mjs setup` while connected, stop the launcher, disconnect from the internet, and run `node scripts/local.mjs start`. Check customer/admin login, checkout and administrator tests using the local URLs. `node scripts/local.mjs start --smoke` is a shorter readiness check: it starts all five services, waits for readiness, and stops them while preserving data. It does not exercise browser login.

For stronger automated network isolation using the optional Compose path, build application and test images while connected:

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
- Optimization has a two-second search budget and a configurable limit of 10 concurrent calculation slots per backend instance by default. Unproven results are labeled explicitly.
- Confirmation quotes expire after five minutes. Expiration or changed inputs requires refreshed confirmation.
