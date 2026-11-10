# Tasks

## 1. Contract suite first

- [x] 1.1 Add golden cases for the descriptions, swagger-config, the redirect and the Swagger UI files, including
  methods, `Accept` values, `Host`, caching headers, 304 and 206. Record from Java. Verified: only additions,
  plus the new caching headers on Swagger UI cases.

## 2. Implementation

- [x] 2.1 Extract `ReadOnlyResource.kt` from the actuator helper (design Decision 4), and `baseUrl(call)` from
  `links()` (Decision 7), and move health onto them.
  Verify that `./gradlew build` and the health golden cases still pass against Kotlin.
- [x] 2.2 Add `openapi/openapi.json` (the Java output without `servers`, the enum and the examples) and
  `ApiDocsRoutes.kt` (Decisions 1–3). Add a unit test that the enum equals `Operation.entries` and that each
  example equals the real problem for its exception. Verify that the description golden cases pass against Kotlin,
  the YAML byte for byte.
- [x] 2.3 Add `SwaggerUiRoutes.kt` with the webjar dependency (Decisions 5–6). Verify that the Swagger UI golden
  cases pass against Kotlin.

## 3. Equivalence

- [x] 3.1 Run the whole contract suite against both services. Verify that Java is green, and that Kotlin is green
  except for agreed accepted differences (record any new one only with the user's agreement).
- [x] 3.2 Update MIGRATION-LOG Unknowns (`Last-Modified` time zone, Spring-only range and conditional cases, `.map`
  content types). Verify that the overlay counts still match the log.

## Workflow follow-up

- Review the code (ponytail and `/code-review`), archive, write the retrospective, commit, open the PR, and merge on green.
