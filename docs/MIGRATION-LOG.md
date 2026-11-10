# Migration log

Everything about the migration that is not a requirement. Requirements live in `openspec/specs/`. The RB rows
that seed them are in `MIGRATION-PLAN.md` §2.

## Unknowns

Behaviour the Java code doesn't settle. Each takes a platform default in the Java service. Item 6 records the
Kotlin default next to each one.

| Topic | Java service (observed or default) | Kotlin service |
|---|---|---|
| Authentication / authorisation | None: every endpoint is open | (item 6) |
| Rate limiting | None | (item 6) |
| CORS | No CORS headers sent (pinned as RB-26) | (item 6) |
| Response compression | None, even with `Accept-Encoding: gzip` (pinned as RB-26) | (item 6) |
| Logging format | Spring Boot console default, INFO level | logback pattern in `logback.xml`, INFO (item 1) |
| TLS | None. Plain HTTP; TLS is expected to end in front of the service | (item 6) |
| Timeouts (request, idle) | Tomcat defaults | (item 6) |
| HTTP/2 | Not enabled (Tomcat default) | (item 6) |
| Graceful shutdown | Spring Boot default (graceful) | (item 6) |
| Framework error `detail` wording (404/405/406/415) | Spring's own, e.g. `No static resource api/v1/nope.`; recorded in golden files only | (item 6: copy it, or accept a difference) |
| The 500 handler (RB-13) | Not reachable over HTTP; the Java test calls it directly | Unit test in item 3 |

## Accepted differences

Each place the Kotlin service behaves differently from Java, agreed with the user, with the reason.

None yet.

## Source defects

Bugs in the Java code, found by `/ponytail:ponytail-audit` (2026-11-10, gate before item 1) and confirmed with curl
against the running service. **They are ported faithfully** and pinned in
`src/contractTest/.../FrameworkDefaultsContractTest.kt`. Fix them only after the migration, each as a new backlog item.

1. **The size limit counts bytes, but the error says characters** (Java `src/main/resources/application.yaml:10`,
   `ProblemDetailsAdvice.java:33-35`; RB-8a)
   - Spring hands Jackson raw bytes, so the 16,384 limit is in UTF-8 bytes. About 6,400 characters of `€`
     (about 19,000 bytes) are rejected with "at most 16,384 characters".
   - Fix later: change the wording to "16,384 bytes" (one string).
2. **`operation` accepts numbers and booleans** (Java `CalculationRequest.java:11`; RB-8b)
   - Jackson coerces `5` to `"5"`, so the response is `unknown-operation` ("Unknown operation '5'"). The same
     wrong type in `a` gives `invalid-request`.
   - Fix later: turn off scalar-to-string coercion, so a non-string `operation` becomes `invalid-request`.
3. **Duplicate keys are accepted silently** (Jackson default; RB-8b)
   - `{"operation":"ADD","a":1,"a":9,"b":2}` returns `a = 9, result 11`.
   - Fix later: turn on strict duplicate detection, so duplicates become `invalid-request`.

The audit's verdict was healthy: nothing breaks at the expected load. `PerformCalculation` only passes the call
through, but it is kept on purpose, because the clean architecture is a goal of the migration.
