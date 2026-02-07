# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation and ETag support for efficient caching.

## Overview

SchemaGuard is a RESTful web service that provides CRUD operations for healthcare insurance plan data. It enforces strict JSON Schema validation to ensure data integrity and supports conditional GET requests using ETags for optimized caching.

## Features

- JSON Schema Validation for all incoming plan data
- RESTful API with standard HTTP methods (POST, GET, DELETE)
- ETag support for conditional GET requests
- In-memory data storage with conflict detection
- Comprehensive error handling with detailed validation messages
- Maven-based build system
- Spring Boot 4.0.2 framework

## Technology Stack

- Java 17
- Spring Boot 4.0.2
- Spring Web MVC
- Jackson for JSON processing
- NetworkNT JSON Schema Validator (v1.0.87)
- Maven 3.x

## Prerequisites

- Java Development Kit (JDK) 17 or higher
- Maven 3.6 or higher (or use the included Maven wrapper)

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

### Run the Application

```bash
./mvnw spring-boot:run
```

Or:

```bash
mvn spring-boot:run
```

The application will start on the default port 8080.

## API Endpoints

### Create a Plan

**Endpoint:** `POST /api/v1/plans`

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
  - `Location: /api/v1/plans/{objectId}`
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

**Endpoint:** `GET /api/v1/plans/{objectId}`

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

**Endpoint:** `DELETE /api/v1/plans/{objectId}`

**Description:** Deletes an insurance plan by its objectId.

**Path Parameters:**
- `objectId` - The unique identifier of the plan to delete

**Success Response:**
- Status Code: `204 No Content`

**Error Response:**
- `404 Not Found` - Plan with specified objectId does not exist

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
└── README.md                             # This file
```

### Key Components

**Controllers:**
- `PlanController` - Handles HTTP requests for plan management

**Validation:**
- `SchemaValidator` - Validates JSON against the defined schema
- `SchemaValidationException` - Custom exception for validation errors

**Storage:**
- `KeyValueStore` - Interface for data storage operations
- `InMemoryKeyValueStore` - In-memory implementation (data is not persistent)

**Models:**
- `StoredDocument` - Internal representation of stored plans with metadata

**Utilities:**
- `JsonUtil` - Helper methods for JSON processing

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
curl -X POST http://localhost:8080/api/v1/plans \
  -H "Content-Type: application/json" \
  -d @samples/plan.json
```

Get a plan:
```bash
curl -X GET http://localhost:8080/api/v1/plans/12xvxc345ssdsds-508
```

Get a plan with ETag:
```bash
curl -X GET http://localhost:8080/api/v1/plans/12xvxc345ssdsds-508 \
  -H "If-None-Match: {etag-value}"
```

Delete a plan:
```bash
curl -X DELETE http://localhost:8080/api/v1/plans/12xvxc345ssdsds-508
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
- Server port: 8080 (Spring Boot default)
- Context path: /

## Important Notes

### Data Persistence

The current implementation uses in-memory storage. All data will be lost when the application restarts. For production use, consider implementing a persistent storage solution (database, Redis, etc.) by creating a new implementation of the `KeyValueStore` interface.

### ETag Implementation

ETags are automatically generated for each plan based on the JSON content and last modification time. The application supports conditional GET requests to optimize bandwidth usage and reduce server load.

### Concurrency

The in-memory store implementation includes thread-safe operations to handle concurrent requests safely.

## Future Enhancements

Potential improvements for production deployment:
- Persistent database integration (PostgreSQL, MongoDB, etc.)
- Redis integration for distributed caching
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
- JSON Schema specification contributors