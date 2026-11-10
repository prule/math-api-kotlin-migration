# Tasks

## 1. Contract suite first (red against Kotlin, green against Java)

- [x] 1.1 Add `isJavaReference` to `Http.kt`, reuse it in the golden record guard, and add the
  `golden/accepted-differences.json` overlay to `GoldenResponsesContractTest`. Building the corpus checks that
  every overlay key names a corpus case. Verify that the suite is still green against Java.
- [x] 1.2 Make the YAML tests in `FrameworkDefaultsContractTest` assert Java's behaviour against Java and the
  Accepted difference 1 behaviour otherwise. Do the same for each `Accept` header assertion that lists YAML.
  Verify that they are green against Java.
- [x] 1.3 Add contract tests and golden cases for RB-27 to RB-30 (the probed content types, `Accept` values,
  strict-JSON bodies, the nesting boundary, the operand string forms, and a 1 MiB body). Re-record from Java. Verify that the
  golden diff only adds cases, and that the suite is green against Java.

## 2. Domain and application

- [x] 2.1 Port `Operation`, `Calculation` and the three exceptions, with `OperationTest` and `CalculationTest`
  ported row by row. Verify with `./gradlew test`.
- [x] 2.2 Port `PerformCalculation`. Verify with `./gradlew test`.

## 3. Web adapter and wiring

- [x] 3.1 Add the `ObjectMapper` (design Decision 3), `MediaTypes` (Decision 4), `Problem` and its StatusPages
  mapping (Decision 5), the route (Decision 6), and the DTOs. Wire them in the root `Application.kt` (Decision 7). Verify with
  `./gradlew build`.
- [x] 3.2 Port `CalculationControllerTest` to `testApplication`, including the direct 500-handler test (RB-13).
  Verify with `./gradlew test`.
- [x] 3.3 Remove the three empty-layer `shortcut:` allowances, and add the `UsesJackson` domain fixture and its
  live test. Verify that `./gradlew test` passes, and that removing `com.fasterxml..` from the domain rule fails
  the new live test.

## 4. Equivalence for this capability

- [x] 4.1 Run `CalculationContractTest` and `FrameworkDefaultsContractTest` against Kotlin (8081) and Java
  (8080). Verify that both are green.
- [x] 4.2 Run the golden calculation cases (every case whose path is `/api/v1/calculations`, or that is the
  generic 404) against Kotlin with the overlay applied. Verify that they are green. Health and docs cases may
  still fail (items 4–5); list them in the retrospective.
- [x] 4.3 If the 1 MiB body gets a connection reset from Kotlin instead of the 400, drain the request body (up to
  2 MiB, like Tomcat) before replying, and verify that the case is green.
- [x] 4.4 Add the overlay entries to `docs/MIGRATION-LOG.md` Accepted difference 1, so the log names each golden
  case it changes. Verify that the counts match.

## Workflow follow-up

- Review the code (ponytail and `/code-review`), archive (which syncs `specs/calculation`), write the
  retrospective, commit, open the PR, and merge on green.
