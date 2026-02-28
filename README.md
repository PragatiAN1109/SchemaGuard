# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag support for efficient caching, and Redis integration for persistent storage.

## Overview

SchemaGuard is a RESTful web service that provides CRUD operations for healthcare insurance plan data. It enforces strict JSON Schema validation to ensure data integrity and supports conditional GET requests using ETags for optimized caching. The application supports both in-memory and Redis-based storage.

## Features

- JSON Schema Validation for all incoming plan data
- RESTful API with standard HTTP methods (POST, GET, DELETE)
- ETag support for conditional GET requests
- Dual storage options: In-memory or Redis
- Comprehensive error handling with detailed validation messages
- Maven-based build system
- Spring Boot 4.0.2 framework
- Profile-based configuration for different storage backends

## Technology Stack

- Java 17
- Spring Boot 4.0.2
- Spring Web MVC
- Spring Data Redis
- Jackson for JSON processing
- NetworkNT JSON Schema Validator (v1.0.87)
- Redis (optional, for persistent storage)
- Maven 3.x

## Prerequisites

- Java Development Kit (JDK) 17 or higher
- Maven 3.6 or higher (or use the included Maven wrapper)
- Redis Server (optional, for Redis storage mode)

## Getting Started

### Clone the Repository

```bash
git clone https://github.com/PragatiAN1109/SchemaGuard.git
cd SchemaGuard
```

### Build the Project

Using Maven wrapper:

```bash
./mvnw clean install
```

Or with system Maven:

```bash
mvn clean install
```

### Run with In-Memory Storage (Default)

```bash
./mvnw spring-boot:run
```

### Run with Redis Storage

First, start Redis:
```bash
redis-server
```

Then uncomment Redis configuration in `src/main/resources/application.properties`:
```properties
spring.profiles.active=redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

Run the application:
```bash
./mvnw spring-boot:run
```

For detailed Redis setup instructions, see [REDIS_SETUP.md](REDIS_SETUP.md).

For quick Redis demo commands, see [REDIS_DEMO_COMMANDS.md](REDIS_DEMO_COMMANDS.md).

The application will start on the default port 8080.

## Storage Options

### In-Memory Storage (Default)
- Fast performance
- No external dependencies
- Data lost on application restart
- Best for development and testing

### Redis Storage
- Persistent storage (data survives application restarts)
- Real-time data visibility via redis-cli
- Configurable TTL (Time To Live)
- Production-ready
- Best for demos and production deployments

Switch between modes by setting `spring.profiles.active` in `application.properties`.

## API Endpoints

### Create a Plan

**Endpoint:** `POST /api/v1/plan`

**Description:** Creates a new insurance plan. The request body must conform to the JSON schema defined in `src/main/resources/schema/schema.json`.

**Request Headers:**
- `Content-Type: application/json`

**Request Body Example:**

```json
{
  "planCostShares": {
    "deductible": 2000,
    "_org": "example.com",
    "copay": 23,
    "objectId": "1234vxc2324sdf-501",
    "objectType": "membercostshare"
  },
  "linkedPlanServices": [
    {
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
    }
  ],
  "_org": "example.com",
  "objectId": "12xvxc345ssdsds-508",
  "objectType": "plan",
  "planType": "inNetwork",
  "creationDate": "12-12-2017"
}
```

**Success Response:**
- Status Code: `201 Created`
- Headers: 
  - `Location: /api/v1/plan/{objectId}`
  - `ETag: {etag-value}`
- Body:
```json
{
  "objectId": "12xvxc345ssdsds-508",
  "etag": "generated-etag-value"
}
```

**Error Responses:**
- `400 Bad Request` - Invalid JSON or schema validation failure
- `409 Conflict` - Plan with the same objectId already exists

### Retrieve a Plan

**Endpoint:** `GET /api/v1/plan/{objectId}`

**Description:** Retrieves an insurance plan by its objectId. Supports conditional GET using ETag.

**Path Parameters:**
- `objectId` - The unique identifier of the plan

**Request Headers (Optional):**
- `If-None-Match: {etag-value}` - For conditional GET request

**Success Response:**
- Status Code: `200 OK`
- Headers: 
  - `ETag: {etag-value}`
  - `Content-Type: application/json`
- Body: The complete plan JSON object

**Conditional Success Response:**
- Status Code: `304 Not Modified` - When If-None-Match matches current ETag
- Headers: 
  - `ETag: {etag-value}`

**Error Response:**
- `404 Not Found` - Plan with specified objectId does not exist

### Delete a Plan

**Endpoint:** `DELETE /api/v1/plan/{objectId}`

**Description:** Deletes an insurance plan by its objectId.

**Path Parameters:**
- `objectId` - The unique identifier of the plan to delete

**Success Response:**
- Status Code: `204 No Content`

**Error Response:**
- `404 Not Found` - Plan with specified objectId does not exist

## Viewing Data in Redis

When using Redis storage, you can view stored data in real-time:

```bash
# List all plan keys
redis-cli KEYS "plan:*"

# View a specific plan
redis-cli GET "plan:12xvxc345ssdsds-508"

# Monitor all Redis operations in real-time
redis-cli MONITOR
```

See [REDIS_DEMO_COMMANDS.md](REDIS_DEMO_COMMANDS.md) for comprehensive Redis commands.

## JSON Schema

The application validates all plan data against a JSON Schema located at `src/main/resources/schema/schema.json`. The schema defines the required structure for insurance plans, including:

- Required fields: `_org`, `objectId`, `objectType`, `planType`, `creationDate`, `planCostShares`, `linkedPlanServices`
- Data types and constraints for all fields
- Nested object validation for cost shares and services
- Date format validation (DD-MM-YYYY)

## Data Model

### Plan Object

The top-level plan object contains:
- Organization identifier
- Unique object ID
- Object type (must be "plan")
- Plan type (e.g., "inNetwork")
- Creation date
- Plan cost shares (deductible and copay information)
- Linked plan services (array of covered services)

### Member Cost Share

Represents cost-sharing information:
- Deductible amount
- Copay amount
- Organization identifier
- Unique object ID

### Plan Service

Represents a covered service:
- Linked service details (name, identifiers)
- Service-specific cost shares
- Organization identifier
- Unique object ID

## Architecture

### Project Structure

```
SchemaGuard/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── schemaguard/
│   │   │           ├── SchemaGuardApplication.java
│   │   │           ├── config/           # Configuration classes
│   │   │           ├── controller/       # REST controllers
│   │   │           ├── exception/        # Custom exceptions
│   │   │           ├── model/            # Data models
│   │   │           ├── service/          # Business logic
│   │   │           ├── store/            # Data storage interfaces
│   │   │           ├── util/             # Utility classes
│   │   │           └── validation/       # Schema validation
│   │   └── resources/
│   │       ├── schema/                   # JSON schemas
│   │       └── application.properties    # Application configuration
│   └── test/                             # Test classes
├── samples/                              # Sample JSON files
├── pom.xml                               # Maven configuration
├── README.md                             # This file
├── REDIS_SETUP.md                        # Redis setup guide
└── REDIS_DEMO_COMMANDS.md                # Quick Redis commands reference
```

### Key Components

**Controllers:**
- `PlanController` - Handles HTTP requests for plan management

**Validation:**
- `SchemaValidator` - Validates JSON against the defined schema
- `SchemaValidationException` - Custom exception for validation errors

**Storage:**
- `KeyValueStore` - Interface for data storage operations
- `InMemoryKeyValueStore` - In-memory implementation (default)
- `RedisKeyValueStore` - Redis implementation (persistent storage)

**Configuration:**
- `RedisConfig` - Redis connection and serialization configuration

**Models:**
- `StoredDocument` - Internal representation of stored plans with metadata

**Utilities:**
- `JsonUtil` - Helper methods for JSON processing
- `EtagUtil` - ETag generation utilities

## Testing

Sample files are provided in the `samples/` directory:
- `plan.json` - Valid plan example
- `invalid.json` - Invalid plan example for testing validation

To test the API:

1. Start the application
2. Use curl, Postman, or any HTTP client

### Example curl Commands

Create a plan:
```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -d @samples/plan.json
```

Get a plan:
```bash
curl -X GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

Get a plan with ETag:
```bash
curl -X GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "If-None-Match: {etag-value}"
```

Delete a plan:
```bash
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

## Error Handling

The application provides detailed error messages for various scenarios:

**Validation Errors (400):**
```json
{
  "error": "VALIDATION_ERROR",
  "message": "JSON validation failed",
  "details": [
    "$.objectType: must be equal to constant value 'plan'",
    "$.creationDate: does not match the regex pattern"
  ]
}
```

**Conflict Errors (409):**
```json
{
  "error": "CONFLICT",
  "message": "Plan with objectId already exists: 12xvxc345ssdsds-508"
}
```

**Not Found Errors (404):**
```json
{
  "error": "NOT_FOUND",
  "message": "Plan not found: 12xvxc345ssdsds-508"
}
```

## Configuration

Application configuration can be modified in `src/main/resources/application.properties`.

Default settings:
```properties
# Server Configuration
server.port=8080

# Redis Configuration (uncomment to enable Redis)
#spring.profiles.active=redis
#spring.data.redis.host=localhost
#spring.data.redis.port=6379
#spring.data.redis.timeout=60000
```

## Important Notes

### Data Persistence

**In-Memory Mode (Default):**
- Data is lost when the application restarts
- No external dependencies required
- Best for development and testing

**Redis Mode:**
- Data persists between application restarts
- Configurable TTL (currently set to 24 hours)
- Data visible in real-time via redis-cli
- Best for demos and production

### ETag Implementation

ETags are automatically generated for each plan using SHA-256 hashing of the JSON content. The application supports conditional GET requests to optimize bandwidth usage and reduce server load.

### Concurrency

Both storage implementations include thread-safe operations to handle concurrent requests safely.

## Future Enhancements

Potential improvements for production deployment:
- Database integration (PostgreSQL, MongoDB, etc.)
- Redis clustering for high availability
- Authentication and authorization
- Rate limiting
- Comprehensive unit and integration tests
- API documentation with Swagger/OpenAPI
- Docker containerization
- Kubernetes deployment manifests
- Monitoring and observability (Prometheus, Grafana)
- PUT/PATCH endpoints for plan updates

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/improvement`)
3. Commit your changes (`git commit -am 'Add new feature'`)
4. Push to the branch (`git push origin feature/improvement`)
5. Create a Pull Request

## License

This project is currently unlicensed. Please contact the repository owner for licensing information.

## Contact

For questions or issues, please open an issue in the GitHub repository.

## Acknowledgments

- Spring Boot team for the excellent framework
- NetworkNT for the JSON Schema validator library
- Redis Labs for Redis
- JSON Schema specification contributors
