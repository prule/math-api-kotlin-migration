# Proposal

## Why

The Java service publishes an OpenAPI 3.1 description (JSON and YAML) and a Swagger UI. Clients and people use
them to learn the API, and they are the last 14 contract tests still failing against Kotlin. In Java, springdoc
generates the description at runtime from the controller annotations, plus two customisers that put domain
values (the operation enum) and real error responses (the examples) into it. So the description can never drift
from the code. The port must keep that property.

## What Changes

- Serve `/v3/api-docs` (JSON) and `/v3/api-docs.yaml` (`application/vnd.oai.openapi`). They are built from one
  JSON template recovered from the Java service's output, with three runtime parts filled in as Java's
  customisers do:
  - `servers[0].url` from the `Host` header;
  - the operation enum from `Operation.entries`;
  - one example per error, built by the same code that builds the real responses.
- Serve `/v3/api-docs/swagger-config`.
- Serve Swagger UI from the same webjar (`org.webjars:swagger-ui:5.34.0`) at `/swagger-ui/…`:
  - `/swagger-ui.html` redirects with a 302, keeping the query string;
  - the initializer gets springdoc's config lines added;
  - `index.html` and the initializer are `no-store`;
  - other files keep `Last-Modified`, with `If-Modified-Since` → 304 and `Range` → 206;
  - 404 details are relative to `/swagger-ui/`.
- Copy each endpoint's negotiation and methods: one type per document, the 406s, `OPTIONS`, and 405 with
  `Allow: GET` (or `GET, HEAD` for static files).
- Add golden cases for all of it, recorded from Java, and the YAML compared as raw text.

## Capabilities

### New Capabilities

- `api-documentation`: the OpenAPI description (JSON and YAML), its runtime parts, the Swagger UI and its config.

### Modified Capabilities

None.

## Impact

- New: `adapter/inbound/web/ApiDocsRoutes.kt`, `SwaggerUiRoutes.kt`, `src/main/resources/openapi/openapi.json`.
- The actuator's GET/HEAD/OPTIONS/405 helper becomes shared (`ReadOnlyResource.kt`).
- New dependencies: `jackson-dataformat-yaml` 2.22.2 (the YAML writer swagger-core uses), `org.webjars:swagger-ui`
  5.34.0 (runtime), and Ktor `conditional-headers` and `partial-content` (installed on the Swagger UI route only).
- After this item every contract test should pass against Kotlin, except the accepted differences.
