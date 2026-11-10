# Proposal

## Why

The Kotlin service can only be called equal to the Java one if the same tests pass against both. Those tests
must exist, and be green against **Java**, before any behaviour is ported. Otherwise the tests would be written
to fit the new code, not the old behaviour. Probing the Java service while proposing turned up behaviour that
no Java test asserts (RB-22 to RB-26: YAML support, health groups, protocol headers). A black-box suite plus
recorded real responses is how that kind of behaviour gets pinned down.

## What Changes

- Add a `contractTest` test suite: black-box HTTP tests that read `BASE_URL`, run with
  `./gradlew contractTest`, and are not part of `./gradlew build`.
- Port every observable assertion from the Java `CalculationControllerTest`, `ServiceHealthSmokeTest` and
  `ApiDocumentationTest`.
- Add tests for each RB row that no Java test asserts (RB-8a, RB-8b, the exact RB-11 titles and `instance`,
  RB-12 titles, RB-22 to RB-26).
- Add golden responses: a request corpus (each test body plus generated edge cases) whose real Java responses
  (status, selected headers, body) are recorded with `./gradlew contractTest -Precord`. Every later run
  compares against them and reports each mismatch as its own test.
- Add `docs/MIGRATION-LOG.md` (Unknowns, Accepted differences, Source defects). Move the three audit findings
  into Source defects, and retire `docs/JAVA-AUDIT.md`.

## Capabilities

### New Capabilities

None. This is a test harness with no service behaviour, so `skip_specs: true` is set. The RB rows it pins
become specs in items 3–5.

### Modified Capabilities

None.

## Impact

- New: `src/contractTest/`, a `contractTest` suite in `build.gradle.kts`, `docs/MIGRATION-LOG.md`.
- Removed: `docs/JAVA-AUDIT.md`. Its content moves to the log and to `MIGRATION-PLAN.md` RB-8a/8b.
- Updated: `MIGRATION-PLAN.md` (RB-22 to RB-26), and each reference to `JAVA-AUDIT.md`.
- Running the suite needs the Java service on port 8080 (`./gradlew bootRun` in the Java repo).
- The suite fails against the Kotlin service until items 3–5 are done. That is expected.
