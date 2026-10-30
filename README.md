# Promotion Studio

A transparent promotion sandbox: Spring Boot / Java 21, DynamoDB, two React/TypeScript frontends, and OIDC. Orders are simulated; there is no payment, inventory, fulfillment, or tax integration.

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

See [architecture and rule semantics](docs/architecture.md), [test evidence](docs/verification.md), and [AWS deployment](infra/aws/README.md).

The backend provides correlated JSON request, database, pricing and exception logs plus administrator-only performance metrics. Every response includes `X-Request-ID`. See [service observability](docs/observability.md) for event fields, timing definitions, privacy behavior and ready-to-use investigation queries.

## Purchase records and promotion dashboard

Each purchase permanently retains its items, quantities, promotion versions, discount allocations, shipping, total, and purchase time. Checkout atomically records it and deletes the source cart. A cart can be purchased only once, including concurrent submissions with different keys. Retrying the same completed submission returns its existing purchase. “Clone this cart” on the completion page creates a fresh cart from the purchase snapshot.

Administrators can open **Promotion dashboard** to see all-time purchase counts, total promotion cost, order value, and per-promotion usage, discounted units, discount cost, shipping cost, and last-use time. Costs are actual recorded discounts in USD, including shipping savings. Editing a promotion cannot change past costs. Customer accounts cannot access the dashboard API.

Existing orders are migrated into the purchase ledger once on startup, and their purchased carts are removed. Stop any older backend instance before starting this version; migration retains purchase history and does not require resetting local data.

## Limits

Customer carts show item names and unit numbers rather than internal identifiers. Saved-cart labels show the server-calculated price followed by total quantity, newest save first. The save timestamp appears on hover, with `---` for older carts without timestamps. “Surprise me!” replaces the current contents with a random selection of 50–100% of catalog items and clears promotion selections. It uses 6–20 total units for catalogs smaller than eight items, or 2–3 times the catalog's unique-item count otherwise. Cart deletion requires confirmation and atomically creates an empty replacement.

Promotion descriptions are editable and persisted, with suggested plain-language text for each strategy and scope. Existing descriptions default to generated customer wording. New administrator promotions default to one calendar month ahead, clamping to the last valid day of the month. Past expirations are allowed and immediately make the promotion unavailable; the editor locks other fields until expiration is renewed or cleared. The promotion filter is a mutually exclusive radio group.

- USD cents; 100 cart lines / 1,000 units; 50 promotion definitions.
- Amount inputs are at most 100,000,000 cents; quantities are at most 1,000 per line.
- Imports and HTTP write bodies are at most 256 KiB; stored documents are at most 350,000 bytes.
- Test sets contain at most 100 cases; a run includes at most 10 optimization cases.
- Optimization has a two-second search budget and four concurrent calculation slots per backend instance. Unproven results are labeled explicitly.
- Confirmation quotes expire after five minutes. Expiration or changed inputs requires refreshed confirmation.

AWS deployment is optional and entirely separate. No local command loads Terraform credentials or contacts Cognito.
