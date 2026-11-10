# Spec Delta

## Purpose

Publishes a machine-readable OpenAPI 3.1 description of the calculation API (JSON and YAML) and an interactive
Swagger UI, both kept equal to the running code, matching the Java service on the wire.

## ADDED Requirements

### Requirement: OpenAPI description
`GET /v3/api-docs` SHALL answer the OpenAPI 3.1 description as `application/json`, titled "OpenAPI definition", version v0, documenting the one `POST /api/v1/calculations` operation (`calculate`) with a required JSON body and responses 200, 400, 415 and 422.

Evidence: Java `adapter/in/web/CalculationController.java:25-53`; tests `ApiDocumentationTest.servesAnOpenApi31Description`, `documentsExactlyTheStatusesTheEndpointReturns`; golden `GET /v3/api-docs`.

#### Scenario: Version
- **WHEN** `GET /v3/api-docs` is called
- **THEN** `openapi` is `3.1.0` and the operation documents exactly 200, 400, 415 and 422
### Requirement: Schemas
The request schema SHALL require `operation`, `a` and `b`, with `a` and `b` typed `[number, string]`. The response schema SHALL have exactly four string properties. `ProblemDetail` SHALL have only `type`, `title`, `status`, `detail` and `instance`.

Evidence: Java `config/OpenApiConfig.java:27-34`, `adapter/in/web/ProblemExamples.java:31-37`; tests `ApiDocumentationTest.acceptsOperandsAsNumberOrString`, `documentsTheSuccessResponseAsJsonStrings`, `problemSchemaHasOnlyWireFields`.

#### Scenario: Operand types
- **WHEN** the description is read
- **THEN** `CalculationRequest.a.type` is `["number", "string"]`

### Requirement: Domain values come from the domain
The `operation` enum SHALL list the domain's operations in declaration order, taken from the domain at runtime, so the description can't drift from the code.

Evidence: Java `config/OpenApiConfig.java:14-26`; test `ApiDocumentationTest.enumeratesExactlyTheSupportedOperationsInOrder`.

#### Scenario: Enum
- **WHEN** the description is read
- **THEN** the `operation` enum equals the operation list in the unknown-operation detail

### Requirement: Examples are real responses
The 400 and 422 responses SHALL carry one example per problem slug (invalid-request, unknown-operation, operand-out-of-range; division-by-zero). Each example SHALL be built by the code that builds the real response, with `instance` `/api/v1/calculations`.

Evidence: Java `adapter/in/web/ProblemExamples.java:41-58`; tests `ApiDocumentationTest.documentsEveryProblemDetailsResponse`, `documentedExampleIsTheRealResponse`.

#### Scenario: Division by zero example
- **WHEN** `DIVIDE` by zero is requested
- **THEN** the response equals the description's `division-by-zero` example

### Requirement: Server URL from the Host header
`servers` SHALL hold one entry: `url` `http://` plus the `Host` header, and `description` "Generated server url". `X-Forwarded-*` headers SHALL be ignored.

Evidence: golden `GET /v3/api-docs, Host header`, `GET /v3/api-docs, X-Forwarded headers`.

#### Scenario: Host
- **WHEN** `GET /v3/api-docs` is called with `Host: example.org:9999`
- **THEN** `servers[0].url` is `http://example.org:9999`

### Requirement: YAML description
`GET /v3/api-docs.yaml` SHALL answer the same description as YAML, typed `application/vnd.oai.openapi`, in the same text form as the Java service (no document marker, minimal quoting).

Evidence: tests `ApiDocumentationTest` (`/v3/api-docs.yaml` via contract test `serves the same description as YAML`); golden `GET /v3/api-docs.yaml`.

#### Scenario: Text
- **WHEN** `GET /v3/api-docs.yaml` is called
- **THEN** the body starts with `openapi: 3.1.0` and equals the Java service's text for the same `Host`

### Requirement: Description media types
`/v3/api-docs` and `/v3/api-docs/swagger-config` SHALL offer only `application/json`, and the YAML only `application/vnd.oai.openapi`. Anything else SHALL get a 406 problem listing that type, or a bodiless 406 with that `Accept` header when `Accept` can't be parsed.

Evidence: golden `GET /v3/api-docs, accept 'text/plain'`, `GET /v3/api-docs.yaml, accept 'application/json'`, `GET /v3/api-docs, accept 'garbage'`.

#### Scenario: YAML asked of the JSON description
- **WHEN** `GET /v3/api-docs` is called with `Accept: application/yaml`
- **THEN** the response is 406 with detail `Acceptable representations: [application/json].`

### Requirement: Description methods
The three description paths SHALL serve `GET` and `HEAD`, answer `OPTIONS` with `Allow: GET,HEAD,OPTIONS`, and give 405 with `Allow: GET` for other methods.

Evidence: golden `POST /v3/api-docs`, `PUT /v3/api-docs.yaml`, `HEAD /v3/api-docs/swagger-config`, `OPTIONS /v3/api-docs.yaml`.

#### Scenario: POST
- **WHEN** `POST /v3/api-docs` is called
- **THEN** the response is 405 with `Allow: GET`
### Requirement: Swagger UI configuration
`GET /v3/api-docs/swagger-config` SHALL answer `{"configUrl": "/v3/api-docs/swagger-config", "oauth2RedirectUrl": "http://<Host>/swagger-ui/oauth2-redirect.html", "url": "/v3/api-docs", "validatorUrl": ""}`.

Evidence: golden `GET /v3/api-docs/swagger-config`, `GET /v3/api-docs/swagger-config, Host header`.

#### Scenario: Config
- **WHEN** `GET /v3/api-docs/swagger-config` is called
- **THEN** `url` is `/v3/api-docs`

### Requirement: Swagger UI entry point
`GET` or `HEAD /swagger-ui.html` SHALL redirect with 302 to `/swagger-ui/index.html`, keeping the query string. `OPTIONS` SHALL answer `Allow: GET,HEAD,OPTIONS`, and other methods 405 with `Allow: GET`.

Evidence: test `ApiDocumentationTest.servesTheInteractiveUi`; golden `GET /swagger-ui.html`, `GET /swagger-ui.html?x=1`, `POST /swagger-ui.html`.

#### Scenario: Redirect
- **WHEN** `GET /swagger-ui.html?x=1` is called
- **THEN** the response is 302 with `Location: /swagger-ui/index.html?x=1`

### Requirement: Swagger UI files
Files under `/swagger-ui/` SHALL be the Swagger UI 5.34.0 distribution, served whatever the `Accept` header says. The initializer SHALL point at `/v3/api-docs/swagger-config`. `index.html` and the initializer SHALL be `Cache-Control: no-store`, and other files SHALL carry `Last-Modified`.

Evidence: golden `GET /swagger-ui/index.html`, `GET /swagger-ui/swagger-initializer.js`, `HEAD /swagger-ui/swagger-ui-bundle.js`, `GET /swagger-ui/index.html, accept text/plain`.

#### Scenario: Initializer
- **WHEN** `GET /swagger-ui/swagger-initializer.js` is called
- **THEN** it holds `"configUrl" : "/v3/api-docs/swagger-config"` and `Cache-Control: no-store`

### Requirement: Swagger UI caching
Swagger UI files SHALL answer `If-Modified-Since` with 304 when unchanged, and `Range` with 206 and `Accept-Ranges: bytes`.

Evidence: golden `GET /swagger-ui/swagger-ui.css, If-Modified-Since`, `GET /swagger-ui/index.css, Range`.

#### Scenario: Not modified
- **WHEN** `swagger-ui.css` is requested with `If-Modified-Since` equal to its `Last-Modified`
- **THEN** the response is 304
### Requirement: Swagger UI errors
An unknown file under `/swagger-ui/` (or `/swagger-ui` itself) SHALL be a 404 problem whose detail names the path relative to `/swagger-ui/`, whatever the method. Files SHALL answer `OPTIONS` with `Allow: GET,HEAD,OPTIONS`, and other methods with 405 `Allow: GET, HEAD`.

Evidence: golden `GET /swagger-ui/nope.js`, `GET /swagger-ui/`, `GET /swagger-ui`, `POST /swagger-ui/index.html`, `OPTIONS /swagger-ui/index.html`.

#### Scenario: Unknown file
- **WHEN** `GET /swagger-ui/nope.js` is called
- **THEN** the response is 404 with detail `No static resource nope.js.`

### Requirement: Swagger UI ranges
Swagger UI files SHALL answer `Range: bytes=…` with 206: one range with `Content-Range`, several (or none, after skipping empty entries) as one `multipart/byteranges` body.

Evidence: golden `GET /swagger-ui/index.css, Range 'bytes=0-9,20-29'`, `… Range 'bytes=-5'`, `… Range 'bytes='`.

#### Scenario: Suffix range
- **WHEN** `Range` is `bytes=-5`
- **THEN** the response is 206 with the last 5 bytes

### Requirement: Unsatisfiable ranges
A malformed or unsatisfiable range, more than 100 ranges, or several adding up to the file size SHALL be 416 with `Content-Range: bytes */<size>` and Spring Boot's error JSON, as in Java.

Evidence: golden `GET /swagger-ui/index.css, Range 'bytes=500-'`, `… Range 'bytes=0-150,0-150'`, `… Range 'items=0-9'`.

#### Scenario: Too many ranges
- **WHEN** 101 ranges are requested
- **THEN** the response is 416

### Requirement: Swagger UI preconditions and paths
`If-Modified-Since` and `If-Unmodified-Since` SHALL be read in all three HTTP date formats; an unmet `If-Unmodified-Since` SHALL be 412 with no body. Paths SHALL be resolved as Spring does: empty and `.` segments and a trailing slash dropped, the transformed copy only at its exact path.

Evidence: golden `GET /swagger-ui/swagger-ui.css, If-Modified-Since RFC 850`, `… If-Unmodified-Since older`, `GET /swagger-ui/index.html/`, `GET /swagger-ui//index.html`.

#### Scenario: Unmet precondition
- **WHEN** `swagger-ui.css` is requested with `If-Unmodified-Since` before its `Last-Modified`
- **THEN** the response is 412 with no body

