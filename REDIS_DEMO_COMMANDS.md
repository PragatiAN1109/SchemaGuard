# Quick Redis Demo Commands

## Before Starting Demo

### 1. Start Redis
```bash
redis-server
```

### 2. Open Redis CLI in separate terminal
```bash
redis-cli
```

### 3. Enable real-time monitoring (optional)
```bash
redis-cli MONITOR
```

### 4. Start Application with Redis
Edit `src/main/resources/application.properties`:
```properties
spring.profiles.active=redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

Then run:
```bash
./mvnw spring-boot:run
```

## During Demo: View Redis Data

### Check if Redis is empty (before creating plans)
```bash
redis-cli KEYS "*"
# Should return (empty array) or empty string
```

### After creating a plan via Postman, view it immediately
```bash
# List all plan keys
redis-cli KEYS "plan:*"

# Get specific plan
redis-cli GET "plan:12xvxc345ssdsds-508"
```

### View data in readable format
```bash
# Pretty print JSON (requires jq)
redis-cli --raw GET "plan:12xvxc345ssdsds-508" | jq .

# Or view in Redis CLI
redis-cli
> GET plan:12xvxc345ssdsds-508
```

### Count total plans
```bash
redis-cli KEYS "plan:*" | wc -l
```

### Check plan existence
```bash
redis-cli EXISTS "plan:12xvxc345ssdsds-508"
# Returns: 1 (exists) or 0 (doesn't exist)
```

### View TTL (time to live)
```bash
redis-cli TTL "plan:12xvxc345ssdsds-508"
# Returns: seconds remaining or -1 (no expiry) or -2 (doesn't exist)
```

## Postman Requests for Demo

### 1. Create Plan
```
POST http://localhost:8080/api/v1/plan
Content-Type: application/json

[Paste content from samples/plan.json]
```

**Immediately show in Redis CLI:**
```bash
redis-cli GET "plan:12xvxc345ssdsds-508"
```

### 2. Get Plan (first time)
```
GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

**Copy the ETag from response header**

### 3. Conditional GET (should return 304)
```
GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
Headers:
  If-None-Match: "paste-etag-here"
```

**Show that Redis was accessed but no data transferred (304 Not Modified)**

### 4. Delete Plan
```
DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

**Verify deletion in Redis:**
```bash
redis-cli GET "plan:12xvxc345ssdsds-508"
# Should return: (nil)

redis-cli KEYS "plan:*"
# Should not show the deleted plan
```

## Demo Flow Summary

```bash
# Terminal 1: Redis Server
redis-server

# Terminal 2: Application
./mvnw spring-boot:run

# Terminal 3: Redis CLI (for viewing data)
redis-cli

# Terminal 4: (Optional) Monitor all Redis operations
redis-cli MONITOR
```

## Clean Up After Demo

### Remove all plans
```bash
redis-cli KEYS "plan:*" | xargs redis-cli DEL
```

### Flush entire database
```bash
redis-cli FLUSHDB
```

### Stop Redis
```bash
redis-cli SHUTDOWN
# Or just Ctrl+C in the redis-server terminal
```

## Impressive Demo Points

1. **Real-time visibility**: Show data appearing in Redis CLI immediately after Postman POST
2. **Persistence**: Restart application, data still there
3. **ETag caching**: Show 304 response with If-None-Match header
4. **Key structure**: Show organized key naming with "plan:" prefix
5. **TTL**: Show automatic expiration after 24 hours (if enabled)
6. **Monitoring**: Use MONITOR to show all operations in real-time

## Troubleshooting

### Redis not connecting
```bash
# Check if Redis is running
redis-cli ping
# Should return: PONG

# Check port
lsof -i :6379
```

### Data not appearing
```bash
# Check active profile
# Look for "Active Profile: redis" in application startup logs

# Verify in application.properties
cat src/main/resources/application.properties | grep spring.profiles.active
```

### Clear stuck data
```bash
redis-cli FLUSHDB
```
