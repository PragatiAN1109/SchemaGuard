# Demo Runbook — End to End

Walks through create → index → search, PATCH propagation, cascaded delete, and stale-event rejection, using the running Docker Compose stack.

Prerequisites: stack is up (`docker compose up --build`, see the [Quick Start](../README.md#quick-start) in the README) and you have a Google ID token (see [Getting a token](local-development.md#getting-a-google-id-token)).

```bash
TOKEN="<your Google ID token>"
```

## 1 — Create a plan

```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @samples/plan.json
```

## 2 — Confirm the event reached RabbitMQ

Open the RabbitMQ Management UI at `http://localhost:15672` (guest/guest) → **Queues** → `schemaguard.index.queue`. You can inspect message contents via **Get Message(s)**.

## 3 — Confirm the consumer processed it

In the same queue view, check **Ready** and **Unacked** counts — both should be `0` once `RabbitMQIndexListener` has processed the message (the message is acknowledged after successful processing, or after a stale-event skip).

## 4 — Confirm the parent is indexed

```bash
curl "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508"
```
Expected: `"found": true` with the plan document.

## 5 — Confirm the children are indexed

```bash
curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"parent_id":{"type":"child","id":"12xvxc345ssdsds-508"}}}'
```
Expected: 2 hits (the two `linkedPlanServices` entries from `samples/plan.json`).

Or via the API's own search endpoint:
```bash
curl -s "http://localhost:8080/api/v1/search/parent/12xvxc345ssdsds-508/children" \
  -H "Authorization: Bearer $TOKEN"
```

## 6 — PATCH propagation (Redis → RabbitMQ → Elasticsearch)

> Index: `plans-index` · Queue: `schemaguard.index.queue` · Join field: `my_join_field`

**6a — baseline, before the patch:**
```bash
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" | python3 -m json.tool | grep planType
# Expected: "planType": "inNetwork"
```

**6b — send the PATCH (JSON Merge Patch, RFC 7396):**
```bash
curl -X PATCH "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Content-Type: application/merge-patch+json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"planType": "outOfNetwork"}'
# Expected: 200 OK with updated body and a new ETag header
```

App logs (`docker logs schemaguard-app`) will show the KV update and the queued event; once the consumer processes it, a re-index log line follows.

**6c — confirm the PATCH event reached the queue:** RabbitMQ UI → `schemaguard.index.queue` → Get Message(s) → look for `operation=PATCH`, `documentId=12xvxc345ssdsds-508`.

**6d — confirm Elasticsearch reflects it (allow a brief moment for the consumer):**
```bash
sleep 1
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" | python3 -m json.tool | grep planType
# Expected: "planType": "outOfNetwork"
```

**6e — confirm it's queryable:**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"term":{"planType":"outOfNetwork"}}}' | python3 -m json.tool | grep -E '"planType"|"objectId"'
```

## 7 — Cascaded delete (Redis + Elasticsearch parent + children)

> The consumer deletes children first (`delete_by_query` with `routing=parentId`), then the parent — see [architecture.md](architecture.md) for why order matters here.

**7a/7b/7c — verify the parent, its children, and the Redis record all exist before deleting** (same queries as steps 4/5).

**7d — delete:**
```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X DELETE "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN"
# Expected: 204
```

**7e — confirm the DELETE event reached the queue** (same as 6c, `operation=DELETE`).

**7f — confirm the Redis record is gone:**
```bash
sleep 1
curl -s "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | grep -E '"status"|"error"'
# Expected: "status": 404, "error": "Not Found"
```

**7g — confirm the parent is gone from Elasticsearch:**
```bash
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" | python3 -m json.tool | grep '"found"'
# Expected: "found": false
```

**7h — confirm all children are gone:**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"parent_id":{"type":"child","id":"12xvxc345ssdsds-508"}}}' \
  | python3 -m json.tool | grep -E '"total"|"value"'
# Expected: "value": 0
```

**7i — confirm nothing is stuck unacknowledged:** RabbitMQ UI → `schemaguard.index.queue` → Ready/Unacked should both read `0`.

## 8 — Stale-event rejection demo

Re-create the plan (step 1) before running this.

**8a — get the current ETag:**
```bash
ETAG=$(curl -si "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN" | grep -i ^etag | awk '{print $2}' | tr -d '"\r')
```

**8b — apply the first PATCH (captures the new ETag from the response header):**
```bash
ETAG_V1=$(curl -si -X PATCH "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Content-Type: application/merge-patch+json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "If-Match: \"$ETAG\"" \
  -d '{"planType": "outOfNetwork"}' | grep -i ^etag | awk '{print $2}' | tr -d '"\r')
```

**8c — apply a second PATCH immediately after:**
```bash
curl -s -X PATCH "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Content-Type: application/merge-patch+json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "If-Match: \"$ETAG_V1\"" \
  -d '{"planType": "inNetwork"}' | python3 -m json.tool | grep planType
# Expected: planType = inNetwork (the latest write)
```

**8d — check the consumer logs for the stale skip:**
```bash
docker logs schemaguard-app 2>&1 | grep -E "skipping stale|processing UPSERT|processing PATCH" | tail -10
```
You should see the fresher event indexed and the older (now-stale) event logged and skipped — order depends on consumer timing, but both log lines should appear.

**8e — confirm Elasticsearch holds only the latest value:**
```bash
sleep 1
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" | python3 -m json.tool | grep planType
# Expected: "planType": "inNetwork"
```

**8f — confirm a stale If-Match is rejected outright:**
```bash
curl -s -X PATCH "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Content-Type: application/merge-patch+json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "If-Match: \"wrong-etag-value\"" \
  -d '{"planType": "outOfNetwork"}' | python3 -m json.tool | grep -E '"status"|"error"'
# Expected: "status": 412, "error": "Precondition Failed"
```

## Pre-flight check script

[`verify-demo.sh`](../verify-demo.sh) builds the app, runs the test suite, brings up the full Docker Compose stack, and polls Elasticsearch/RabbitMQ/the app until healthy — run it before a live demo to catch problems early.
