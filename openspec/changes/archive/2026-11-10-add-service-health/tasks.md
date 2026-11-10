# Tasks

## 1. Contract suite first

- [x] 1.1 Add golden cases for every probed actuator request: paths and trailing slashes, a query string, case,
  nested paths, `Accept` values (including quality and garbage), methods (HEAD, POST, PUT, DELETE, OPTIONS), the
  `Host` header (the suite runs with `jdk.httpclient.allowRestrictedHeaders=host`, because the JDK client
  otherwise drops a custom `Host`), `X-Forwarded-*`, and `/actuator//health`. Re-record from Java. Verify that
  the Host case records `example.org:9999`. Verify that the golden diff only
  adds cases.
- [x] 1.2 Add `HealthContractTest` tests for the media types, the 406s, the methods, links from `Host`, and the
  empty 404s. Verify that they are green against Java.

## 2. Implementation

- [x] 2.1 Make `MediaType.select` take the producible list and sort by specificity, then quality, then order. Add
  `MediaTypeTest` rows for the quality order. Verify with `./gradlew test`, and that the calculation golden cases
  are still green against Kotlin.
- [x] 2.2 Add `HealthRoutes.kt` (design Decisions 1, 2, 4 and 5) and wire it in `Application.kt`. Add in-process
  `testApplication` tests for health, a group, the empty 404 and the index. Verify with `./gradlew build`.

## 3. Equivalence

- [x] 3.1 Run the whole contract suite against both services. Verify that Java is green, and that Kotlin fails
  only the docs cases (item 5) and any agreed accepted differences.
- [x] 3.2 Record the new container cases under Accepted differences 3 and 4 in MIGRATION-LOG, and add the
  actuator Unknowns. Add any overlay entries, and verify that the counts in the log match the overlay.

## Workflow follow-up

- Review the code (ponytail and `/code-review`), archive (syncs `service-health`, modifies `calculation`), write
  the retrospective, commit, open the PR, and merge on green.
