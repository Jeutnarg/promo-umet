# Architecture and domain decisions

## Boundaries

`backend/domain` contains immutable records, validation, pricing, and optimization with no Spring or database dependencies. `api/SandboxService` coordinates ownership, versions, quotes, and transactional writes. `store/Store` is the persistence port; `DynamoStore` is its AWS/local adapter. Controllers map authenticated identities to application operations. Frontends consume generated OpenAPI types and share transport, OIDC, styles, and pricing explanations.

Each DynamoDB document has `pk`, `sk`, numeric `v`, and a JSON `payload`. Partition names separate user items, carts, quotes and orders. Promotion definitions, the catalog revision, test sets and audit events have dedicated partitions. All reads used for pricing are strongly consistent. Catalog reads bracket the promotion query with revision reads; writes condition-check that revision. Every promotion change increments the revision atomically with its audit event.

Cart ownership is determined exclusively by the validated JWT subject. Every cart read, update, deletion, clone, evaluation, optimization, and quote operation looks up the supplied identifier inside that subject's partition. Administrator status does not grant access to other users' carts. Cart updates reject a body ID that disagrees with the route ID. Quote lookup and idempotency records are also owner-scoped, so a foreign quote or another user's order key cannot submit or expose an order. Missing and foreign resources return the same 404 response.

Cart writes condition-check their expected version. Optimization searches a saved snapshot and only commits its result if the cart and catalog versions still match. Quotes expire after five minutes. Submission reloads and recalculates against current server time, checks the quote's inputs, and atomically writes an immutable owner-scoped order, creates a permanent purchase-ledger entry keyed by cart ID, and deletes the cart. The conditional ledger insertion and cart deletion allow only one purchase even when different quotes or submission keys race. A per-owner idempotency key is also the order ID; retries return the existing order, while reuse with another quote is rejected. Cloning a purchase reads the owner's order snapshot and generates a fresh cart ID.

The administrator promotion dashboard queries the immutable purchase ledger. Usage counts purchases with an applied promotion; discounted units count positively discounted merchandise units. Cost is the recorded saving, including shipping discounts. Totals group historical promotion versions by stable promotion ID and never reprice old purchases using current definitions. Disabled, expired, and unused promotions remain visible, with zero counts where applicable. Cart evaluations, optimization previews, and administrator test-set runs do not write ledger entries.

On the first startup after upgrading, a versioned migration scans legacy owner-scoped order documents, indexes their snapshots in the ledger, and removes their purchased carts. It records a completion marker after all orders are processed, retries concurrent conditional-write conflicts, and resumes safely after interruption. Later startups check the marker and skip the scan. This upgrade must run after stopping older backend versions so no old writer can create an unindexed purchase during migration. Normal dashboard reads use a partition query rather than a table scan.

Rule semantics are documented in the [README](../README.md#rule-semantics).

## Search

Optimization and administrator test-set execution share an instance-local semaphore. Its default capacity is 10; configure the positive integer `sandbox.calculation-concurrency` through `CALCULATION_CONCURRENCY` and restart the backend to change it. Admission fails immediately with HTTP 429 when the pool is full. Permits are released in `finally`, including failures. Ordinary cart evaluation and checkout pricing remain outside this pool, and the setting does not impose per-user fairness or a cluster-wide limit.

The optimizer enumerates subsets, narrow-scope line targets, and maximum-cardinality BOGO matchings and exhaustive BOGO triple groupings in deterministic order. It prunes invalid partial selections. The empty selection is always a feasible initial incumbent. Every candidate passes through the same evaluator as manual checkout.

Suggest minimizes total. Swindle lexicographically maximizes units with positive allocated savings, then minimizes total savings. Zero-priced units cannot contribute positive coverage; shipping never counts as a merchandise unit. Exact ties use stable serialized selection identifiers. Search completion proves optimality; a two-second deadline returns the best feasible incumbent and explicitly marks unproven objectives. Complete unit coverage independently proves maximum coverage. The budget bounds search work, not network/persistence latency.

## Security and local independence

Browsers use authorization code + PKCE; tokens remain in memory. Session storage contains transient OIDC state/verifiers, not bearer tokens. The backend verifies signing keys, issuer, audience/client, lifetime and subject, rejects Cognito ID tokens, checks ownership, and protects administrator routes by role. Anonymous users can access only health and OpenAPI.

Keycloak stores all local credentials and signs tokens locally. Local DynamoDB configuration uses explicit `local`/`local` credentials and never invokes the AWS default credentials chain. No runtime fonts, analytics, CDN assets, external authorization services, or API secrets are required. The optional AWS profile uses task-role credentials and Cognito; there are no application client secrets to store because both clients are public PKCE clients.

The local Keycloak development database is appropriate for local use; it is not the production identity deployment. AWS uses managed Cognito. Cloud administrator provisioning is explicit and local seed users are never provisioned there.

## Test-set format

Exports are versioned JSON objects: `id`, `version`, `schemaVersion: 1`, `name`, and `cases`. Each case has `name`, `cart`, `promotions`, `mode` (`manual`, `suggest`, `swindle`), fixed ISO-8601 `now`, `expectedTotal`, and optional `expectedCoveredUnits`. Fixture promotions are snapshots, independent of live definitions. Import-as-new generates a new ID/version; append adds cases to the current set. Backend validation is authoritative.

The administrator manual builder creates a simple single-line case; the advanced JSON editor supports arbitrary multi-line fixtures and target/pair assignments. Exported JSON can be pasted or loaded from a file without a separate storage service.
