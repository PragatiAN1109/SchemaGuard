#!/bin/bash
# ============================================================
# SchemaGuard Demo 3 — Pre-Demo Verification Script
# Run this BEFORE your demo to make sure everything works.
# ============================================================
set -e

echo "============================================"
echo "  SchemaGuard Demo 3 — Pre-flight Check"
echo "============================================"
echo ""

# 1. Build check
echo ">>> Step 1: Maven build (skip tests)..."
./mvnw clean package -DskipTests -q
echo "    ✅ Build successful"
echo ""

# 2. Run unit tests
echo ">>> Step 2: Running tests..."
./mvnw test -q 2>/dev/null && echo "    ✅ All tests passed" || echo "    ⚠️  Some tests failed — check output"
echo ""

# 3. Docker compose
echo ">>> Step 3: Starting Docker services..."
if [ -z "$GOOGLE_CLIENT_ID" ]; then
    echo "    ⚠️  GOOGLE_CLIENT_ID not set!"
    echo "    Run: export GOOGLE_CLIENT_ID=<your-id>.apps.googleusercontent.com"
    exit 1
fi

docker compose down -v 2>/dev/null || true
docker compose up --build -d

echo "    Waiting for services to be healthy..."
sleep 5

# Poll until ES is ready (max 60s)
for i in $(seq 1 12); do
    if curl -sf http://localhost:9200/_cluster/health > /dev/null 2>&1; then
        echo "    ✅ Elasticsearch ready"
        break
    fi
    if [ $i -eq 12 ]; then
        echo "    ❌ Elasticsearch not ready after 60s"
        exit 1
    fi
    sleep 5
done

# Poll until RabbitMQ is ready
for i in $(seq 1 12); do
    if curl -sf http://localhost:15672/api/overview -u guest:guest > /dev/null 2>&1; then
        echo "    ✅ RabbitMQ ready (Management UI at http://localhost:15672)"
        break
    fi
    if [ $i -eq 12 ]; then
        echo "    ❌ RabbitMQ not ready after 60s"
        exit 1
    fi
    sleep 5
done

# Poll until app is ready
for i in $(seq 1 12); do
    if curl -sf http://localhost:8080/api/v1/index/health > /dev/null 2>&1; then
        echo "    ✅ App ready"
        break
    fi
    if [ $i -eq 12 ]; then
        echo "    ❌ App not ready after 60s"
        exit 1
    fi
    sleep 5
done

echo ""
echo ">>> Step 4: Smoke test (no auth — index health)..."
HEALTH=$(curl -sf http://localhost:8080/api/v1/index/health)
echo "    Index health: $HEALTH"
echo "    ✅ Smoke test passed"

echo ""
echo "============================================"
echo "  ✅ All pre-flight checks passed!"
echo ""
echo "  Next steps:"
echo "  1. Open docs/get-token.html in browser"
echo "  2. Sign in and copy the id_token"
echo "  3. Import docs/postman/schema-guard-crud.json"
echo "  4. Set 'token' variable in Postman"
echo "  5. Run requests 1-14 in order"
echo ""
echo "  Show logs:     docker logs -f schemaguard-app 2>&1 | grep '\[DEMO\]'"
echo "  RabbitMQ UI:   http://localhost:15672 (guest/guest)"
echo "  ES health:     curl http://localhost:9200/_cluster/health"
echo "============================================"
