# SchemaGuard Demo 3 — Quick Reference Cheat Sheet

## Startup
```bash
# Terminal 1: Start all services (Redis + RabbitMQ + Elasticsearch + App)
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com
docker compose up --build

# Wait for logs:
#   schemaguard-redis          | Ready to accept connections
#   schemaguard-rabbitmq       | Server startup complete
#   schemaguard-elasticsearch  | Cluster health status changed to [GREEN]
#   schemaguard-app            | Elasticsearch index 'plans-index' initialized
```

## Get Token
Open `docs/get-token.html` in browser → Sign in with Google → Copy `id_token`
Paste into Postman collection variable `token`

## Demo Flow (Postman requests 1–14)

### Phase 1: CREATE + VERIFY INDEX
| # | Request | Expected | What It Proves |
|---|---------|----------|----------------|
| 1 | POST /api/v1/plan | 201 + etag | KV store write + RabbitMQ event publish |
| 2 | GET /api/v1/search/all | totalDocuments: **3** | ES indexed 1 parent + 2 children via RabbitMQ consumer |
| 3 | GET /api/v1/search/parent/{id}/children | count: **2** | **has_parent** query works |
| 4 | GET /api/v1/search?childField=planserviceCostShares.copay&childValue=175 | count: **1** | **has_child** term query |
| 5 | GET /api/v1/search?childField=planserviceCostShares.copay&childOp=gt&childValue=100 | count: **1** | **has_child** range query |

### Phase 2: CONDITIONAL READ
| # | Request | Expected | What It Proves |
|---|---------|----------|----------------|
| 6 | GET /api/v1/plan/{id} | 200 + ETag header | Normal read |
| 7 | GET /api/v1/plan/{id} + If-None-Match: {etag} | **304** Not Modified | Conditional read (ETag) |

### Phase 3: PATCH → INDEX (Key Demo Moment)
| # | Request | Expected | What It Proves |
|---|---------|----------|----------------|
| 8 | PATCH /api/v1/plan/{id} (copay 0→50) | 200 + new etag | Merge patch + KV update + RabbitMQ publish |
| 9 | GET /api/v1/search/all | totalDocuments: still **3** | PATCH updates in-place, no new docs |
| 10 | GET /search/parent/{id}/children | child shows copay=**50** | **Patch reflected in ES index** |

### Phase 4: CASCADED DELETE
| # | Request | Expected | What It Proves |
|---|---------|----------|----------------|
| 11 | GET /api/v1/plan | count: **1** | KV has the plan |
| 12 | DELETE /api/v1/plan/{id} | **204** | KV delete + RabbitMQ publish |
| 13 | GET /api/v1/search/all | totalDocuments: **0** | Cascaded delete: children first, then parent |
| 14 | GET /api/v1/plan | count: **0** | KV also empty |

## Verify Queue Events (RabbitMQ Management UI)
Open **http://localhost:15672** in browser (login: **guest / guest**)

1. Click **Queues and Streams** tab
2. Click **schemaguard.index.queue**
3. You'll see:
   - **Messages published** count increases after POST/PATCH/DELETE
   - **Messages acknowledged** count matches (consumer processed them)
   - **Ready = 0** means all messages were consumed successfully
4. To inspect a message: scroll to **Get Message(s)** → click **Get Message(s)**
   - You'll see the JSON body with operation, documentId, etag

## Show Logs (Event-Driven Architecture Proof)
```bash
# Filter [DEMO] logs to show the full event pipeline:
docker logs schemaguard-app 2>&1 | grep "\[DEMO\]"

# You'll see the chain:
#   [DEMO] POST created plan → published UPSERT event to queue
#   [DEMO] RABBITMQ published UPSERT event → exchange=schemaguard.index.exchange
#   [DEMO] RABBITMQ consumed UPSERT event from queue=schemaguard.index.queue
#   [DEMO] RABBITMQ processing UPSERT → indexed 1 parent + 2 children
#   [DEMO] ES indexed parent id=...
#   [DEMO] ES indexed child id=... routing=...
```

## Architecture (if professor asks)
```
Client → PlanController → Redis KV Store (write)
                        → RabbitMQ Exchange (publish event)
                                ↓
                        RabbitMQ Queue (schemaguard.index.queue)
                                ↓
                        RabbitMQIndexListener (consumer)
                                ↓
                        Elasticsearch (parent-child index)
                                ↓
                        SearchController ← Client (query)
```

## Key Talking Points
- **Why RabbitMQ?** Dedicated message broker with durable queues, ack/nack semantics, and a Management UI for observability.
- **Why async queue?** Decouples API latency from indexing. API returns fast, indexing happens in background.
- **Why parent-child join?** Plans have nested services. Join field keeps parent+children on same shard for efficient queries.
- **Why refresh=true?** Forces ES to make writes searchable immediately (demo convenience; production uses 1s interval).
- **PATCH doesn't increase count** because ES upsert semantics replace the doc with the same ID.
- **Cascaded delete** removes children FIRST (via delete_by_query), then parent. Order matters because children reference parent.
- **Stale event guard**: if two writes happen fast, the consumer skips the older event by comparing etags.

## Troubleshooting
```bash
# ES not responding?
curl http://localhost:9200/_cluster/health

# RabbitMQ not responding?
curl -sf http://localhost:15672/api/overview -u guest:guest | head

# Redis not responding?
docker exec schemaguard-redis redis-cli ping

# Queue has unprocessed messages?
# Check RabbitMQ UI → Queues → schemaguard.index.queue → Messages Ready

# Reset everything (nuclear option):
docker compose down -v && docker compose up --build
```
