# Redis Integration Setup Guide

This guide explains how to set up and use Redis with SchemaGuard for persistent storage.

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Redis Server

## Step 1: Install Redis

### macOS (using Homebrew)
```bash
brew install redis
```

### Ubuntu/Debian
```bash
sudo apt update
sudo apt install redis-server
```

### Windows
Download and install Redis from:
- Official Windows port: https://github.com/microsoftarchive/redis/releases
- Or use WSL (Windows Subsystem for Linux) and follow Linux instructions

### Docker (Cross-platform)
```bash
docker run --name redis -p 6379:6379 -d redis:latest
```

## Step 2: Start Redis Server

### macOS/Linux
```bash
redis-server
```

Or start as a service:
```bash
# macOS
brew services start redis

# Ubuntu/Debian
sudo systemctl start redis-server
```

### Docker
```bash
docker start redis
```

### Verify Redis is Running
```bash
redis-cli ping
```
Expected output: `PONG`

## Step 3: Configure SchemaGuard for Redis

Edit `src/main/resources/application.properties` and uncomment the Redis configuration:

```properties
# Server Configuration
server.port=8080

# Redis Configuration (uncomment to enable Redis)
spring.profiles.active=redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=60000
```

## Step 4: Build and Run the Application

```bash
# Clean and build
./mvnw clean install

# Run with Redis profile
./mvnw spring-boot:run
```

The application will now use Redis for storage instead of in-memory storage.

## Viewing Redis Data Before Demo

### Method 1: Using Redis CLI

Connect to Redis:
```bash
redis-cli
```

View all keys:
```bash
KEYS *
```

View all plan keys:
```bash
KEYS plan:*
```

Get a specific plan:
```bash
GET plan:12xvxc345ssdsds-508
```

View all data in a formatted way:
```bash
# List all keys with plan prefix
KEYS plan:*

# For each key, view its content
GET plan:12xvxc345ssdsds-508
```

### Method 2: Using Redis Desktop Manager (GUI)

Download and install Redis Desktop Manager:
- Official website: https://resp.app/
- Or use RedisInsight: https://redis.io/insight/

Connect to:
- Host: localhost
- Port: 6379

Browse the data visually in the GUI.

### Method 3: Using redis-cli with JSON formatting

```bash
# View a specific plan with pretty JSON
redis-cli --raw GET "plan:12xvxc345ssdsds-508" | jq .
```

Note: Requires `jq` to be installed (`brew install jq` on macOS)

## Demo Flow: Viewing Data in Redis

### Step 1: Start Redis and Application
```bash
# Terminal 1: Start Redis
redis-server

# Terminal 2: Start SchemaGuard
./mvnw spring-boot:run
```

### Step 2: Open Redis CLI (Terminal 3)
```bash
redis-cli
```

In redis-cli, monitor all commands:
```bash
MONITOR
```

This will show all Redis operations in real-time.

### Step 3: Create Plans via Postman

**Create First Plan:**
```
POST http://localhost:8080/api/v1/plan
Content-Type: application/json

{
  "planCostShares": {
    "deductible": 2000,
    "_org": "example.com",
    "copay": 23,
    "objectId": "1234vxc2324sdf-501",
    "objectType": "membercostshare"
  },
  "linkedPlanServices": [{
    "linkedService": {
      "_org": "example.com",
      "objectId": "1234520xvc30asdf-502",
      "objectType": "service",
      "name": "Yearly physical"
    },
    "planserviceCostShares": {
      "deductible": 10,
      "_org": "example.com",
      "copay": 0,
      "objectId": "1234512xvc1314asdfs-503",
      "objectType": "membercostshare"
    },
    "_org": "example.com",
    "objectId": "27283xvx9asdff-504",
    "objectType": "planservice"
  }],
  "_org": "example.com",
  "objectId": "12xvxc345ssdsds-508",
  "objectType": "plan",
  "planType": "inNetwork",
  "creationDate": "12-12-2017"
}
```

### Step 4: View Data in Redis

In a new terminal or stop MONITOR and run:
```bash
# List all plans
redis-cli KEYS "plan:*"

# View specific plan
redis-cli GET "plan:12xvxc345ssdsds-508"

# Count total plans
redis-cli KEYS "plan:*" | wc -l
```

### Step 5: Verify Data Structure

```bash
# View the complete stored document structure
redis-cli --raw GET "plan:12xvxc345ssdsds-508"
```

The stored data includes:
- `objectId`: The plan identifier
- `json`: The complete plan JSON
- `etag`: SHA-256 hash for caching
- `lastModified`: Timestamp

## Redis Commands Reference

### Useful Commands for Demo

```bash
# View all keys
KEYS *

# View all plan keys
KEYS plan:*

# Get a plan
GET plan:12xvxc345ssdsds-508

# Check if a key exists
EXISTS plan:12xvxc345ssdsds-508

# Get TTL (time to live) of a key
TTL plan:12xvxc345ssdsds-508

# Delete a plan
DEL plan:12xvxc345ssdsds-508

# Delete all plans
redis-cli KEYS "plan:*" | xargs redis-cli DEL

# Flush entire database (use with caution!)
FLUSHDB
```

### Monitor Real-time Operations

```bash
redis-cli MONITOR
```

This shows all Redis commands as they happen, great for demo purposes.

## Postman Testing Workflow

### 1. Create a Plan
```
POST http://localhost:8080/api/v1/plan
Content-Type: application/json
Body: [Use sample from samples/plan.json]
```

**Immediately check Redis:**
```bash
redis-cli GET "plan:12xvxc345ssdsds-508"
```

### 2. Get the Plan
```
GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

**Note the ETag in response header**

### 3. Conditional GET (should return 304)
```
GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
If-None-Match: "etag-value-from-step-2"
```

### 4. Delete the Plan
```
DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

**Verify deletion in Redis:**
```bash
redis-cli GET "plan:12xvxc345ssdsds-508"
# Should return (nil)
```

## Data Persistence

With Redis:
- Data persists between application restarts
- Data does NOT persist between Redis server restarts (by default)
- Current configuration includes 24-hour TTL on plans
- To make data permanent, either:
  - Remove TTL in `RedisKeyValueStore.java`
  - Configure Redis persistence (RDB or AOF)

### Remove TTL for Permanent Storage

Edit `src/main/java/com/schemaguard/store/RedisKeyValueStore.java`:

```java
// Instead of:
redisTemplate.opsForValue().set(key, doc, 24, TimeUnit.HOURS);

// Use:
redisTemplate.opsForValue().set(key, doc);
```

## Troubleshooting

### Redis Connection Refused
```
Error: Could not connect to Redis at 127.0.0.1:6379: Connection refused
```

**Solution:** Start Redis server:
```bash
redis-server
```

### Spring Profile Not Active
If the app still uses in-memory storage, verify:
```bash
# Check application.properties
cat src/main/resources/application.properties | grep spring.profiles.active

# Should show:
spring.profiles.active=redis
```

### Port Already in Use
```
Error: Address already in use
```

**Solution:** 
```bash
# Find process using port 6379
lsof -i :6379

# Kill the process
kill -9 <PID>

# Or use different port in application.properties
spring.data.redis.port=6380
```

## Comparison: In-Memory vs Redis

| Feature | In-Memory | Redis |
|---------|-----------|-------|
| Persistence | No (lost on restart) | Yes (configurable) |
| Data visible externally | No | Yes (via redis-cli) |
| Performance | Fastest | Very fast |
| Scalability | Single instance only | Can cluster |
| Demo-friendly | No visibility | High visibility |
| Production-ready | No | Yes |

## Next Steps

After setting up Redis:
1. Create sample plans via Postman
2. View data in redis-cli in real-time
3. Demonstrate persistence by restarting the application
4. Show ETag-based caching with conditional GET
5. Monitor Redis operations during demo with `MONITOR` command

## Additional Resources

- Redis Documentation: https://redis.io/docs/
- Spring Data Redis: https://spring.io/projects/spring-data-redis
- Redis Commands Reference: https://redis.io/commands/
- RedisInsight (GUI): https://redis.io/insight/
