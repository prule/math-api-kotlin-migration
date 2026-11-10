# Tasks

## 1. Suite setup

- [x] 1.1 Register the `contractTest` JVM test suite (JUnit 5, AssertJ, jackson-databind 2.22.2, the version Ktor uses), and add the shared
  HTTP helper (HTTP/1.1, `BASE_URL` required). Verify that `./gradlew contractTest` with no `BASE_URL` fails
  with the message, and that `./gradlew build` does not run the suite.

## 2. Ported Java assertions

- [x] 2.1 Port `CalculationControllerTest`'s observable assertions to `CalculationContractTest` (every
  parameterised row, the `assertProblem` checks, ordering, limits, and the 404/405/406/415 cases). Tag each test
  `// RB-n`. Verify that it is green against Java.
- [x] 2.2 Port `ServiceHealthSmokeTest` to `HealthContractTest`. Verify that it is green against Java.
- [x] 2.3 Port `ApiDocumentationTest` to `ApiDocumentationContractTest`. The operation list comes from the
  unknown-operation detail, not from domain code, because the suite is black-box. Verify that it is green
  against Java.

## 3. Rows no Java test asserts

- [x] 3.1 Add tests for RB-8a, RB-8b, the exact RB-11 titles, details and `instance`, RB-12 titles and headers,
  and RB-22 to RB-26. Verify that they are green against Java.

## 4. Golden responses

- [x] 4.1 Add `GoldenResponsesContractTest` with the corpus, record mode, and comparison (design Decisions 5–6).
  Record mode refuses any service that is not the Java one. Record against Java. Verify that recording against
  the Kotlin app on 8081 is refused, that a second run with no `-Precord` is green, and that editing one golden value
  makes exactly that case fail.

## 5. Docs

- [x] 5.1 Create `docs/MIGRATION-LOG.md`: Unknowns (auth, rate limits, logging format, TLS, timeouts, HTTP/2),
  Accepted differences (none yet), and Source defects (the three audit findings). Delete `docs/JAVA-AUDIT.md`
  and fix every reference to it. Verify with `grep -r JAVA-AUDIT`, which finds no live reference.
- [x] 5.2 Verify that every RB id in `MIGRATION-PLAN.md` §2 except RB-13 appears in a `// RB-` tag under
  `src/contractTest`, and add the run commands to CLAUDE.md's reference-service section if they changed.

## Workflow follow-up

- Review the code (ponytail and `/code-review`), archive, write the retrospective, commit, open the PR, and merge on green.
