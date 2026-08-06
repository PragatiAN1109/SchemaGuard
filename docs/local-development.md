# Local Development

## Running the full stack (recommended)

```bash
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com
docker compose up --build
```

This builds the app image and starts all four services defined in [`compose.yaml`](../compose.yaml):

| Service | Image | Port(s) | Purpose |
|---------|-------|---------|---------|
| `redis` | `redis:7-alpine` | 6379 | Authoritative KV store for plan documents |
| `rabbitmq` | `rabbitmq:3-management-alpine` | 5672 (AMQP), 15672 (management UI) | Event queue between the API and the indexer |
| `elasticsearch` | `elasticsearch:8.13.4` | 9200 | Searchable parent-child index (single-node, security disabled) |
| `app` | built from [`Dockerfile`](../Dockerfile) | 8080 | The Spring Boot API |

`app` waits for all three infrastructure services to report healthy (`depends_on: condition: service_healthy`) before starting.

Startup logs to expect:
```
Elasticsearch index 'plans-index' initialized with parent-child mapping
Started SchemaGuardApplication
```

## Environment variables

| Variable | Default | Used for |
|----------|---------|----------|
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis connection |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ connection |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | `guest` / `guest` | RabbitMQ credentials |
| `ELASTIC_HOST` / `ELASTIC_PORT` | `localhost` / `9200` | Elasticsearch connection |
| `GOOGLE_CLIENT_ID` | *(required, no default)* | OAuth2 audience validation |
| `SPRING_PROFILES_ACTIVE` | `redis` (set in `application.properties`) | Selects `RedisKeyValueStore` + real security; `test` profile switches to in-memory store |

## Running the app without Docker

You still need Redis, RabbitMQ, and Elasticsearch reachable at the hosts/ports above (e.g. run each individually with `docker run`, or use Homebrew/apt for Redis and RabbitMQ locally). Then:

```bash
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com
./mvnw spring-boot:run
```

## Getting a Google ID token

Protected endpoints require a Google RS256 Bearer token. [`docs/get-token.html`](get-token.html) is a static page using Google Identity Services — open it in a browser (e.g. `python3 -m http.server` from `docs/`, or any static file server), sign in, and copy the ID token it displays. It contains a demo Google OAuth Client ID for convenience; for your own deployment, add your own `data-client_id` and register your serving origin in the Google Cloud Console under **APIs & Services → Credentials → Authorized JavaScript origins**.

```bash
TOKEN="<paste the id_token here>"
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @samples/plan.json
```

## Postman

Import [`docs/postman/schema-guard-crud.json`](postman/schema-guard-crud.json) and set its `token` collection variable to your ID token. Requests are numbered to run top-to-bottom through the full CRUD + search flow.

## Inspecting Redis directly

```bash
redis-cli KEYS "plan:*"                       # all stored plan keys
redis-cli GET "plan:12xvxc345ssdsds-508"       # a specific plan (raw JSON blob)
redis-cli MONITOR                              # watch commands in real time
```

Plans have **no TTL** — `RedisKeyValueStore` writes with `opsForValue().set(key, doc)` and no expiration, so data persists until explicitly deleted or Redis is flushed.

## Inspecting RabbitMQ directly

Open `http://localhost:15672` (guest/guest) → **Queues** → `schemaguard.index.queue` to view message contents, and the **Ready**/**Unacked** counters to confirm the consumer has caught up. See [demo-runbook.md](demo-runbook.md) for a full walkthrough.

## Running tests

```bash
./mvnw test
```

Tests run under the `test` Spring profile: `InMemoryKeyValueStore` replaces Redis, `NoOpIndexEventPublisher` replaces RabbitMQ (RabbitMQ autoconfiguration is excluded outright), and `@WithMockUser` supplies a mock authenticated principal so no real Google token is required. No Docker services need to be running. The Elasticsearch client still attempts a connectivity check on startup and logs a "connection refused" warning if nothing is listening on `localhost:9200` — this is non-fatal and does not fail the build.

## Pre-flight check before a demo

[`verify-demo.sh`](../verify-demo.sh) builds the app, runs the test suite, brings the Docker Compose stack up, and polls each service until healthy.
