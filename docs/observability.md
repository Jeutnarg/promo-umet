# Service logging and performance diagnostics

The backend emits one JSON object per log event using Spring Boot's ECS console format. It works with local console output and the existing AWS CloudWatch log group; no additional account, agent, or service is required for local operation.

## Follow a request

Every backend response receives a server-generated `X-Request-ID`, including 400, 401, 403, 404, 413, and 500 responses. The header is exposed to the two frontend origins. Client-provided request IDs are ignored. Search `request_id` to follow the request through its API, SDK, pricing, and exception events.

Request context includes a stable `actor_ref` derived from the verified issuer and subject, an `actor_type` (`anonymous`, `user`, or `admin`), and the registered `api_route` and `api_operation`. The initial event precedes authentication; later events identify the verified actor. The actor reference is pseudonymous, not a username, email address, or token. Background/startup events have no request context.

Routes are registered templates such as `/api/v1/carts/{id}`. Raw paths, query strings, bodies, headers, item names, categories, promotion descriptions, access tokens, passwords, and idempotency keys are never passed to these loggers. Cart references are hashed. Metrics never use request IDs, actor references, or cart identifiers as labels.

## Events and timing

| Event | Meaning and useful fields |
|---|---|
| `request_received` | Arrival at the backend filter, with method, registered operation and resource family. |
| `request_completed` | Final status, total `duration_ms`, `db_calls`, `db_attempts`, `db_duration_ms`, `db_failures`, `pricing_evaluations`, `pricing_duration_ms`, `optimization_calls`, `optimization_duration_ms`, `optimization_candidates`, and `error_code`. |
| `database_call_completed` | One actual SDK operation, including each query/scan page: `db_operation`, `db_resource`, `outcome`, `duration_ms`, HTTP `attempts`, AWS request ID, returned/evaluated item counts, transaction size, and consumed capacity when supplied. |
| `pricing_evaluation_completed` | Cart line count, unique item count, total units, available/selected/applied/rejected promotion counts, discounted units, and duration. |
| `optimization_completed` | The same input/result counts plus mode, search budget, candidates examined, duration, and optimality/coverage proof flags. |
| `pricing_calculation_failed` | Failed validation or calculation, with calculation type, exception type and elapsed time. |
| `api_error` | Structured status, stable application error code and exception type. |
| `request_exception` | An exception escaping the application filter, followed by a failed completion event. |
| `purchase_recorded` / `purchase_retry_reused` | A newly committed purchase versus reuse of an existing idempotent result, with a hashed cart reference. |

All durations use monotonic clocks. Database duration is client-observed SDK time including serialization, network time, response decoding, retries and backoff; it is not a database-server CPU measurement. `db_calls` counts logical SDK executions, while `db_attempts` counts HTTP transmissions including retries. Each paginator page is a separate SDK execution. Repository JSON decoding outside the SDK remains part of the overall request duration.

Pricing summaries exclude optimizer candidate evaluations. A search logs one summary regardless of candidate count, and its timer includes the candidate calculations. Request pricing time covers separate evaluations such as checkout revalidation; optimization time is recorded separately to avoid counting candidate work twice. Total request duration also includes security, validation, other application work and response serialization. The counters describe synchronous work on the request thread; future asynchronous work will need explicit context propagation.

Normal operations use INFO. Expected HTTP errors and version conflicts use WARN. Unexpected calculation, database, and server failures use ERROR. Stack traces retain exception classes, causes and code locations, but discard exception messages and suppressed exceptions because those can contain credentials or request bodies. A server-error stack is reported once per request; other events retain its correlation and type. Health-probe arrival/completion events use DEBUG to avoid filling production logs.

## Metrics

Actuator exposes `/actuator/metrics` and `/actuator/metrics/{name}` to administrator bearer tokens only. The public health endpoint stays unchanged. Standard JVM, CPU, thread and `http.server.requests` measurements are available alongside:

- `sandbox.database.duration`: timer labeled by SDK operation, bounded resource family and outcome.
- `sandbox.database.attempts`: HTTP transmission count by SDK operation and resource family.
- `sandbox.pricing.duration`: ordinary evaluation timer by outcome.
- `sandbox.optimization.duration`: optimizer timer by mode and outcome.
- `sandbox.optimization.candidates`: candidates examined by mode.
- `sandbox.optimization.budget_exhausted`: searches that returned without proving optimality.

Timers provide counts, total elapsed time and maximum measurements through Actuator. Database consumed-capacity values are logged only when the provider supplies them; DynamoDB Local measurements are not AWS billing estimates. No metrics endpoint exposes customer records or credentials.

## Read and investigate logs

```sh
docker compose logs -f --no-log-prefix backend
```

In CloudWatch Logs Insights, find expensive API operations:

```text
filter message = "request_completed"
| stats count(*) as requests, avg(duration_ms) as average_ms,
    pct(duration_ms, 95) as p95_ms, avg(db_duration_ms) as average_db_ms,
    avg(optimization_duration_ms) as average_optimization_ms by api_operation
| sort p95_ms desc
```

Follow one reported request:

```text
filter request_id = "REPLACE_WITH_X_REQUEST_ID"
| sort @timestamp asc
```

Compare database work by user action:

```text
filter message = "database_call_completed"
| stats count(*) as calls, sum(attempts) as transmissions,
    avg(duration_ms) as average_ms, pct(duration_ms, 95) as p95_ms
    by api_operation, db_operation, db_resource, outcome
| sort p95_ms desc
```

Measure optimizer workloads:

```text
filter message = "optimization_completed"
| stats count(*) as searches, avg(duration_ms) as average_ms,
    avg(candidates) as average_candidates by mode, optimality_proven
```

Runtime verbosity can be changed with ordinary Spring logging configuration. Keep HTTP wire/header/body logging disabled when handling real credentials; the application's diagnostic fields already provide correlation and workload information.
