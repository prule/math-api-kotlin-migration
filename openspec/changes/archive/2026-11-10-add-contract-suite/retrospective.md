# Retrospective: add-contract-suite

## Caught by review

**Plan review (ponytail):**
- `-Precord` against Kotlin would silently replace the golden truth. → Added a guard that refuses non-Java services.
- Pin jackson-databind to Ktor's 2.22.2. → Done.

**Code review (ponytail):**
- The calculation tests built the unknown-operation detail from `/v3/api-docs`, so item 3 could never pass its
  own contract tests before item 5. → They now check the detail's exact shape with a regex
  (`assertUnknownOperation`). Only `ApiDocumentationContractTest` compares the list with the docs (design
  Decision 8).
- Duplicate corpus names would overwrite each other. → The uniqueness check runs when the corpus is built.

**Code review (`/code-review`, Standards):**
- **Hard violation:** the golden corpus listed the operations by hand (STANDARDS.md §5). → It now uses
  `operationsFromService()`, read from the unknown-operation detail.
- Duplicated code: the 12 invalid bodies were copied into the corpus, and the valid body appeared three
  times. → `CalculationContractTest.invalidBodies()` is shared, and `VALID_ADD` and `validBodyPaddedTo` moved to
  `Http.kt`.
- Misplaced helper: `assertFrameworkProblem` lived at the bottom of `CalculationContractTest.kt`. → Moved to
  `Problems.kt`.
- The JUnit and AssertJ versions were written twice in `build.gradle.kts`. → They are now `val`s.
- Three path loops at the end of the corpus. → Merged into one list.
- The Jackson version wasn't stated. → STANDARDS.md §1 and design Decision 9 now say Jackson 2 (Ktor's) throughout.
- Source defect 2 had no line number. → Now cites `CalculationRequest.java:11`.

**Code review (`/code-review`, Spec):**
- **The record guard would stop working after item 4:** it keyed on the health content type, which RB-24 makes
  Kotlin copy. → The guard now checks Spring Boot's `/error` body (`"status":999`), which no RB row asks Kotlin
  to copy. `/error` must stay out of the corpus.
- RB-24's exact content type was pinned only by the golden file. → Added `health uses the actuator media type`.
- `{base}` was replaced in bodies but not in the recorded `Location` header. → Now replaced in headers too.
- Task 4.1's checks weren't visible in the diff. → They were run. See Surprises for the evidence and the
  problem rerunning them exposed.

## Hidden behaviour found

All of this was found by probing the running Java service while proposing. No Java test asserts any of it. It is
pinned in `FrameworkDefaultsContractTest`, `HealthContractTest` and the golden file.
- **YAML in and out (RB-22):** `application/yaml` request bodies are accepted, and `Accept: application/yaml` gets
  YAML. Errors stay `problem+json`. Probably an accident: springdoc puts the YAML Jackson module on the
  classpath, and Spring Boot 4 registers it.
- **Media types (RB-22):** any `application/*+json` is accepted, and no `Content-Type` gets 415.
- **Protocol (RB-23):**
  - 405 sends `Allow: POST`, and `HEAD` gets 405;
  - `OPTIONS` gets 200 with `Allow: POST,OPTIONS`;
  - 406 and 415 send an `Accept` header;
  - the calculations path with a trailing slash gets 404.
- **Health (RB-24):**
  - the body includes `groups: [liveness, readiness]`, and both groups exist as endpoints;
  - an unknown group returns 404 with an **empty body**, the only error that is not Problem Details;
  - `/actuator/health/` with a trailing slash answers 200;
  - the content type is Spring's vendor type.
- **Docs (RB-25):** `/v3/api-docs.yaml` exists.
- **Absent (RB-26):** no CORS headers and no compression.
- **Framework error wording:** Spring's `detail` texts (`No static resource …`) are recorded only in the golden
  file. Whether Kotlin copies them is item 6's decision (MIGRATION-LOG Unknowns).

## Surprises

- **The guard looked like it worked but never ran.** Against the empty Kotlin app, record mode failed with
  `ExceptionInInitializerError`. Building the corpus at class load called `operationsFromService()` before the guard
  ran, and the golden file was "untouched" for the wrong reason. Cause: eager companion initialisation. Fixed by
  building the corpus lazily and running the guard first. Now the failure reads
  `Refusing to record: http://localhost:8081 is not the Java reference service (GET /error was '')`.
- The first record run "failed" even though it wrote the file. Cause: the factory returned no tests in record
  mode, and Gradle fails a `--tests` filter that matches nothing. Fixed by making recording a dynamic test of its own.
- The audit's Java line citations (`application.yaml:58`, `ProblemDetailsAdvice.java:218-220`) came from the
  concatenated dump and were wrong. The real lines are 10 and 33-35. This is the same cause as in item 1's
  retrospective, carried over from before that lesson.
- A 5,000-character `€` string was rejected by the 1,000-character **string** limit, not the byte limit. The RB-8a
  test uses several 900-character strings to isolate the byte limit.
- `pkill -f com.paulrule.mathapi.ApplicationKt` also matched the shell running it, ending each verification
  command early (exit 144). Cause: the pattern appears in the command line itself. Steps after it were rerun
  separately.

Task 4.1 evidence:
- Re-recording against Java is byte-identical.
- Recording against Kotlin on 8081 is refused, and the golden file is unchanged.
- Changing `ADD a=0.1 b="0.2"` to `0.4` fails exactly that one of 130 cases.
- With no `BASE_URL`, the run fails with "Set BASE_URL …".
- `./gradlew build` does not run the suite.

## Shortcuts taken

None added. The three empty-layer shortcuts from item 1 (`ArchitectureTest.kt:24, 52, 65`) are still open, for item 3.

## Lessons

- **Propose step:** probe the running reference service for framework defaults (media types, methods,
  headers, actuator sub-paths) before writing the design. It found six RB rows that reading the code missed.
  Do this for every remaining item.
- **Guards:** a guard must key on something the new service will never be asked to copy. Check every guard
  against the RB rows. Prove it fires by reading its own failure message, not by the absence of damage.
- **Contract tests:** each item's contract tests must depend only on behaviour that item delivers. Check
  cross-item dependencies (like docs → calculation) during plan review.
- **Shell habits:** stop the Kotlin app by its port (`fuser -k 8081/tcp`), not with `pkill -f` on a string
  that appears in the command.
