# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag caching, Redis persistent storage, and Google OAuth2 JWT security.

## Overview

SchemaGuard is a RESTful web service that provides full CRUD operations for healthcare insurance plan data. It enforces strict JSON Schema validation, supports conditional GET/PUT/PATCH/DELETE using ETags, implements JSON Merge Patch (RFC 7396), and secures all plan endpoints using Google RS256 Bearer tokens.

## Features

- JSON Schema Validation for all incoming plan data (POST, PUT, PATCH)
- Full CRUD: POST, GET, PUT (replace), PATCH (JSON Merge Patch), DELETE
- ETag support for conditional requests (SHA-256 based)
- **Google OAuth2 RS256 JWT security — all `/api/v1/plan/**` endpoints require a valid Bearer token**
- **Public endpoint: `GET /api/v1/schema/plan` — no auth required**
- Redis as primary store — data persists across app restarts
- Docker Compose for one-command demo startup

## Spring Profile → Storage + Security Mapping

| Profile | Storage Backend | Security |
|---------|----------------|----------|
| `redis` (default) | `RedisKeyValueStore` | Google JWT enforced |
| `test` | `InMemoryKeyValueStore` | Security auto-config excluded |

---

## Task 5 — Google IDP Security

### How It Works

SchemaGuard is configured as an **OAuth2 Resource Server**. Every request to `/api/v1/plan/**` must include a valid `Authorization: Bearer <token>` header. The token is:

1. A Google ID token (RS256-signed JWT)
2. Validated against Google's public JWK Set: `https://www.googleapis.com/oauth2/v3/certs`
3. Issuer checked: must be `https://accounts.google.com`
4. Audience checked: must contain your Google Client ID

On success, the server logs `sub` and `email` from the token and adds `X-User-Sub` / `X-User-Email` response headers.

---

## Step 1 — Google Cloud Console Setup

### 1.1 Create a Google Cloud Project

1. Go to [https://console.cloud.google.com](https://console.cloud.google.com)
2. Click the project dropdown → **New Project**
3. Name it `SchemaGuard-Demo` and click **Create**

### 1.2 Configure OAuth Consent Screen

1. Navigate to **APIs & Services → OAuth consent screen**
2. Choose **External** → **Create**
3. Fill in:
   - App name: `SchemaGuard`
   - User support email: your email
   - Developer contact email: your email
4. Click **Save and Continue** through the remaining steps
5. Under **Test users**, add your own Google email address
6. Click **Back to Dashboard**

### 1.3 Create OAuth 2.0 Client ID

1. Go to **APIs & Services → Credentials**
2. Click **Create Credentials → OAuth client ID**
3. Application type: **Web application**
4. Name: `SchemaGuard Web Client`
5. Under **Authorized JavaScript origins**, add:
   ```
   http://localhost:5500
   http://localhost:8080
   ```
   *(Add whatever host you'll serve `docs/get-token.html` from)*
6. Click **Create**
7. **Copy the Client ID** — it looks like: `123456789-abc123.apps.googleusercontent.com`

---

## Step 2 — Obtain a Google ID Token (Demo)

### Option A — HTML Token Page (Recommended)

A ready-made token page is included at `docs/get-token.html`.

**Setup:**
1. Open `docs/get-token.html` in a text editor
2. Replace `YOUR_GOOGLE_CLIENT_ID` with your actual Client ID
3. Serve it locally (any static server works):

```bash
# Option 1: Python
cd docs && python3 -m http.server 5500

# Option 2: Node npx
cd docs && npx serve -p 5500

# Option 3: VS Code Live Server extension — just open get-token.html
```

4. Open `http://localhost:5500/get-token.html` in a browser
5. Click **Sign in with Google**
6. **Copy the token** from the text box — this is your Bearer token

The page also displays the decoded `sub` and `email` from the token so you can verify it worked.

### Option B — curl with OAuth Playground

1. Go to [https://developers.google.com/oauthplayground](https://developers.google.com/oauthplayground)
2. Click the settings gear (⚙) → check **Use your own OAuth credentials**
3. Enter your Client ID and Client Secret
4. In Step 1, find **Google OAuth2 API v2** → select `openid`, `email`
5. Click **Authorize APIs** → sign in
6. In Step 2, click **Exchange authorization code for tokens**
7. Copy the `id_token` value from the response

---

## Step 3 — Start the Application

### Local run (Redis must be running):

```bash
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com
./mvnw spring-boot:run
```

### Docker Compose:

```bash
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com
docker compose up --build
```

---

## Step 4 — Demo the Security (Copy-Paste Commands)

> Replace `<TOKEN>` with the token you obtained in Step 2.
> Replace `<YOUR_CLIENT_ID>` with your actual Google Client ID.

### 4.1 — Unauthenticated request → 401

```bash
curl http://localhost:8080/api/v1/plan/some-id -v
```

**Expected response:**
```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
```

### 4.2 — Invalid/fake token → 401

```bash
curl http://localhost:8080/api/v1/plan/some-id \
  -H "Authorization: Bearer this.is.not.a.valid.token" -v
```

**Expected:** `401 Unauthorized` — signature validation fails against Google's public keys.

### 4.3 — Schema endpoint is public — no token needed

```bash
curl http://localhost:8080/api/v1/schema/plan
```

**Expected:** `200 OK` with full JSON Schema — no `Authorization` header required.

### 4.4 — Valid Google token → 200 (POST a plan)

```bash
TOKEN="<paste your Google ID token here>"

curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @samples/plan.json -v
```

**Expected:** `201 Created` with `ETag` + `Location` headers, and:
- App logs show: `Authenticated request: method=POST path=/api/v1/plan sub=<sub> email=<email>`
- Response includes `X-User-Sub` and `X-User-Email` headers

### 4.5 — GET plan with valid token

```bash
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN" -v
```

**Expected:** `200 OK` with plan body.

### 4.6 — DELETE plan with valid token + If-Match

```bash
# First get the ETag from the GET response above, then:
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'If-Match: "<etag-value>"' -v
```

**Expected:** `204 No Content`

---

## Step 5 — Verify RS256 Using jwt.io

1. Copy your Google ID token
2. Go to [https://jwt.io](https://jwt.io)
3. Paste the token into the **Encoded** box on the left
4. In the **Header** section (top right), you'll see:
   ```json
   {
     "alg": "RS256",
     "kid": "...",
     "typ": "JWT"
   }
   ```
5. The **Payload** section shows your claims:
   ```json
   {
     "iss": "https://accounts.google.com",
     "aud": "<your-client-id>.apps.googleusercontent.com",
     "sub": "1234567890",
     "email": "you@gmail.com",
     ...
   }
   ```

The API validates the signature using Google's public keys fetched from:
`https://www.googleapis.com/oauth2/v3/certs`

---

## Security Architecture

```
Request → Spring Security filter chain
           ↓
       Authorization: Bearer <token> present?
           ↓ yes
       Fetch Google public keys (JWK Set URI)
           ↓
       Verify RS256 signature
           ↓
       Validate issuer = https://accounts.google.com
           ↓
       Validate audience contains GOOGLE_CLIENT_ID
           ↓
       JwtClaimsLogger: log sub + email, set X-User-Sub/X-User-Email headers
           ↓
       Route to controller
```

**Public routes (no auth):**
- `GET /api/v1/schema/**`

**Protected routes (Bearer token required):**
- `GET /api/v1/plan/**`
- `POST /api/v1/plan`
- `PUT /api/v1/plan/**`
- `PATCH /api/v1/plan/**`
- `DELETE /api/v1/plan/**`

---

## Demo Runbook — Docker Compose

### Prerequisites
- Docker + Docker Compose installed
- Google Client ID from Step 1

```bash
# Set your Google Client ID
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com

# Start everything
docker compose up --build
```

Wait for `Started SchemaGuardApplication` in the logs.

### Verify Redis + Security together

```bash
TOKEN="<your Google ID token>"

# 1. Create a plan (authenticated)
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @samples/plan.json

# 2. Verify it's in Redis
docker exec -it schemaguard-redis redis-cli KEYS "plan:*"

# 3. Restart app — data persists, auth still works
docker compose restart app
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN"
# Expected: 200 OK
```

---

## Running Tests

```bash
./mvnw test
```

Tests use the `test` profile which uses `InMemoryKeyValueStore` and disables Spring Security auto-configuration. No Redis and no Google token required.

---

## JSON Schema

Schema file location:
```
src/main/resources/schemas/plan-schema.json
```

Retrieve at runtime (public endpoint — no auth needed):
```bash
curl http://localhost:8080/api/v1/schema/plan
```

---

## Error Response Format

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable description",
  "details": ["optional field-level errors"]
}
```

| Code | HTTP Status | Meaning |
|------|-------------|---------|
| `VALIDATION_ERROR` | 400 | JSON Schema validation failed |
| `INVALID_JSON` | 400 | Malformed JSON body |
| `NOT_FOUND` | 404 | Resource does not exist |
| `CONFLICT` | 409 | Duplicate objectId on POST |
| `PRECONDITION_FAILED` | 412 | If-Match header didn't match stored ETag |
| `INTERNAL_SERVER_ERROR` | 500 | Unexpected error |
| *(Spring Security)* | 401 | Missing or invalid Bearer token |

---

## Architecture

```
SchemaGuard/
├── compose.yaml                                    ← Docker Compose (app + Redis)
├── Dockerfile                                      ← Multi-stage ARM64/AMD64 build
├── docs/
│   └── get-token.html                              ← Google sign-in token page for demo
├── src/main/java/com/schemaguard/
│   ├── config/
│   │   ├── AppConfig.java                          ← ObjectMapper bean
│   │   ├── RedisConfig.java                        ← Redis serialization (redis profile)
│   │   └── SecurityConfig.java                     ← OAuth2 Resource Server, route rules
│   ├── security/
│   │   └── JwtClaimsLogger.java                    ← Extracts sub/email, logs + headers
│   ├── controller/      # PlanController, SchemaController
│   ├── exception/       # GlobalExceptionHandler, custom exceptions
│   ├── model/           # StoredDocument, ErrorResponse
│   ├── store/           # KeyValueStore, InMemoryKeyValueStore, RedisKeyValueStore
│   ├── util/            # EtagUtil (SHA-256), JsonUtil
│   └── validation/      # SchemaValidator, SchemaValidationException
├── src/main/resources/
│   ├── schemas/plan-schema.json                    ← JSON Schema (single source of truth)
│   ├── application.properties                      ← default profile = redis
│   └── application-redis.properties                ← Redis + Google Client ID config
└── src/test/resources/
    └── application-test.properties                 ← test: InMemory, security disabled
```
