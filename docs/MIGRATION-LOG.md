# Migration log

Everything about the migration that is not a requirement. Requirements live in `openspec/specs/`. The RB rows
that seed them are in `MIGRATION-PLAN.md` §2.

## Unknowns

Behaviour the Java code doesn't settle. Each takes a platform default in the Java service. Item 6 (2026-11-11)
probed both services for each row, and "Matched" means they behave the same.

| Topic | Java service (observed or default) | Kotlin service | |
|---|---|---|---|
| Authentication / authorisation | None: every endpoint is open | None | Matched |
| Rate limiting | None | None | Matched |
| CORS | No CORS headers sent (pinned as RB-26) | None sent (pinned) | Matched |
| Response compression | None, even with `Accept-Encoding: gzip` (RB-26) | None (pinned) | Matched |
| Logging format | Spring Boot console default, INFO | logback pattern in `logback.xml`, INFO (item 1) | Level matched; line format differs (operational, not on the wire) |
| TLS | None. Plain HTTP; TLS is expected to end in front of the service | None | Matched |
| Idle and keep-alive connections | Closed after 60 s of no traffic (Tomcat connection timeout) | Closed after 60 s (Netty `IdleStateHandler`, item 6); writes time out after 60 s | Matched (probed) |
| HTTP/2 | Not offered (no h2c, no prior knowledge) | Not offered | Matched (probed) |
| `Server` header | None | None | Matched (probed) |
| Graceful shutdown | Spring Boot default: graceful, up to 30 s per phase (not probed) | Ktor stop with a 30 s timeout (item 6; not probed) | Configured to match; not verified under load |
| Health when degraded | Never observed: no health contributors, so always `UP` | Always `UP` (item 4) | Matched |
| Readiness during shutdown | Spring Boot reports `OUT_OF_SERVICE` while shutting down gracefully (framework default, not probed) | Always `UP` until the process stops (item 4) | Unknown: not probed in Java |
| Swagger UI `Last-Modified` | The webjar **file's** modification time (when it was downloaded or deployed) | The same | Matched; the value differs per install, so the golden file records `{last-modified}` (item 6) |
| The 500 handler (RB-13) | Not reachable over HTTP; the Java test calls it directly | Unit test in item 3 | Matched by test |

## Accepted differences

Each place the Kotlin service behaves differently from Java, agreed with the user, with the reason.

1. **No YAML** (RB-22; user decision, item 3, 2026-11-10). The Java service accepts `application/yaml` request
   bodies and answers in YAML for `Accept: application/yaml`. That is probably an accident: springdoc puts the YAML
   Jackson module on the classpath, and Spring Boot registers it. The Kotlin service supports JSON only:
   - a YAML request body gets 415;
   - `Accept: application/yaml` on a success gets 406;
   - the `Accept` header on 406/415 responses, and the 406 detail list, drop `application/yaml`.

   The contract tests branch on the service (`isJavaReference`). The golden diff applies
   `golden/accepted-differences.json` on top of the Java recordings when it runs against Kotlin. The overlay
   changes these 15 golden cases, and nothing else:
   - the two YAML cases: `yaml request` (now 415) and `accept yaml` (now 406);
   - 10 cases where only the 415 `Accept` header list changes: `no content type`, `text/plain`, and the 8
     `content type '…'` cases for `TEXT/PLAIN`, `text/plain; charset=ISO-8859-1`, `application/octet-stream`,
     `image/png`, `application/x-www-form-urlencoded`, `multipart/form-data; boundary=x`, `garbage` and
     `application/xml`;
   - 3 cases where the 406 `Accept` header and detail list change: `accept text/plain`, `accept 'text/*'` and
     `accept 'application/xml'`;
   - 4 more 415 `Accept`-header-only cases added in item 3's code review: `accept garbage, text/plain`,
     `content type with quoted ';'`, `no body, text/plain` and `content type 'application/json;charset=bogus'`.

2. **A wildcard request Content-Type is 415, not 500** (RB-28; user decision, item 3 code review, 2026-11-10).
   Java answers `*/*`, `application/*` and `application/*+json` with a 500 "could not be completed" (source defect
   4). Kotlin answers 415 with the usual detail, for example `Content-Type '*/*;charset=UTF-8' is not supported.`
   Overlay cases: `content type '*/*'`, `content type 'application/*'`, `content type 'application/*+json'`.
   Extended in item 4 (user decision): an `Accept` with empty entries (`,,,`, `application/json,`,
   `, application/json`) gets 500 from Java on actuator paths (source defect 5). Kotlin skips the empty entries,
   as Java itself does on the calculations endpoint. Overlay cases: `actuator accept ',,,'`,
   `actuator accept 'application/json,'`, `actuator accept ', application/json'`.
3. **Tomcat path normalisation is not copied** (user decision, item 3 code review). Java (Tomcat) treats
   `//api/v1/calculations` as unknown (404), and strips path parameters, so `/api/v1/calculations;jsessionid=1`
   is the endpoint (200). Ktor collapses the double slash (200) and keeps the path parameter (404). Overlay cases:
   `double slash`, `path parameter`, and `GET /actuator//health` (item 4).

   Extended in item 4 (user decision):
   - actuator path parameters (`/actuator/health;x=1`: Java 200, Kotlin 404);
   - an encoded slash (`/actuator/health/liveness%2F`: Tomcat's HTML 400, Kotlin 404);
   - an invalid `Host` (`a/b`: Tomcat's HTML 400; Kotlin answers and puts it in the links).

   Overlay cases: `GET /actuator/health;x=1`, `GET /actuator/health/;x=1`, `GET /actuator/health/liveness%2F`,
   `actuator index Host 'a/b'`.

   Not in the golden file, because the JDK client always sends `Host`:
   - Tomcat answers an HTTP/1.1 request with no `Host` with its HTML 400 page;
   - for HTTP/1.0 without `Host`, both services name themselves by reverse DNS of the local address. That gave
     `localhost` in Java's probe and `ip6-localhost` over IPv6 loopback in Kotlin's, so it depends on the
     environment, not on the code.
4. **OPTIONS and TRACE extras are not copied** (user decision, item 3 code review):
   - Java's `OPTIONS` also sends an empty `Accept-Patch:` header (not recorded in the golden file);
   - a CORS preflight (`Origin` plus `Access-Control-Request-Method`) gets `Allow: GET, HEAD, POST, PUT, DELETE, OPTIONS, PATCH`;
   - `TRACE` gets Spring Boot's own error JSON with a six-method `Allow`.

   Kotlin answers these like any other `OPTIONS`, or like any other unsupported method (405 problem, with the
   path's own `Allow`), or as the generic 404 where the path is unknown. Overlay cases: `CORS preflight`,
   `TRACE calculations`, `TRACE /actuator/health`, `TRACE /actuator/env` and `TRACE /actuator/` (item 4).

5. **The status line has a reason phrase** (user decision, item 6, 2026-11-11). Tomcat writes `HTTP/1.1 200 ` with
   no reason phrase; Ktor writes `HTTP/1.1 200 OK`. HTTP/1.1 tells clients to ignore the reason phrase, and the
   golden file can't see it (the JDK client doesn't expose it). No overlay case.

The overlay holds 37 cases in all: 19 for difference 1, 6 for difference 2, 7 for difference 3, and 5 for difference 4.

**Decided to copy (not a difference):** Spring's framework error wording for 404/405/406/415 (RB-30). This was a
user decision in item 3.

## Source defects

Bugs in the Java code, found by `/ponytail:ponytail-audit` (2026-11-10, gate before item 1) and confirmed with curl
against the running service. Defects 1-3 **are ported faithfully** and pinned in
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

4. **A wildcard Content-Type gets 500** (found in item 3's code review, not by the audit; RB-28)
   - A request with `Content-Type: */*`, `application/*` or `application/*+json` reaches the catch-all and gets
     500 "The calculation could not be completed." Spring tries to read the body as a wildcard type, which fails
     inside the converter rather than as 415.
   - Kotlin does **not** port this. It answers 415 (Accepted difference 2).
   - Fix later on the Java side only, if Java lives on.

5. **An `Accept` with empty entries gets 500 on actuator paths** (found in item 4's code review)
   - `Accept: ,,,` or `application/json,` reaches the catch-all on `/actuator/health` and its groups. The
     calculations endpoint skips empty entries instead.
   - Kotlin does not port this. It skips the empty entries everywhere (Accepted difference 2).

The audit's verdict was healthy: nothing breaks at the expected load. `PerformCalculation` only passes the call
through, but it is kept on purpose, because the clean architecture is a goal of the migration.

## Equivalence (item 6, 2026-11-11)

**Claim:** apart from the five accepted differences above, the Kotlin service answers every request the contract
suite knows exactly as the Java service does, and CI checks this on every change.

**Evidence:**
- **537 contract tests pass against both services:**
  - 52 calculation tests ported from the Java `CalculationControllerTest`;
  - 29 health tests (ported from `ServiceHealthSmokeTest`, plus probed behaviour);
  - 13 docs tests ported from `ApiDocumentationTest`;
  - 68 tests of framework defaults found by probing;
  - **375 golden cases** whose status, key headers and body were recorded from the Java service.
- The golden recordings are pure Java. The 37 overlay cases name exactly what Kotlin does instead, each one tied
  to an accepted difference.
- Every RB row (RB-1 to RB-30) is pinned by a tagged contract test or a golden case, except RB-13, which is
  covered by a unit test.
- Three recovered specs: `calculation` (28 requirements), `service-health` (9) and `api-documentation` (16).
  Every requirement cites Java code, a Java test, or a recorded Java response.
- Connection behaviour (idle timeout, HTTP/2, `Server` header) was probed on both services. See Unknowns.
- CI (`build` check) starts the Kotlin service and runs the whole suite on every PR and every push to `main`.

**Limits of the proof:**
- **Only what was asked is compared.** Behaviour no test or probe reached is unknown, not proven.
- **No load, soak or concurrency testing:** both services are stateless, but throughput and latency were never
  compared.
- **Graceful shutdown and readiness during shutdown** are configured, not observed.
- **HTTP/2 and TLS** are absent on both sides, so they were not compared further.
- The proof covers the Java service as recorded on 2026-11-10/11. If the Java service changes, the golden file must
  be re-recorded from it (`-Precord`, which refuses any other service).

