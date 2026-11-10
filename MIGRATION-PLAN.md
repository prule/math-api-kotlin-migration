# math-api: Java/Spring → Kotlin/Ktor migration plan

**Source:** `../mathi-api-one-agent-2` (Spring Boot 4, Java 25).
**Rule of the exercise:** the code (main, tests, build, config) is the only spec. `docs/` and `openspec/` are
off-limits. Comments that cite `BR-n` / `PRD §x` are kept as hints that a rule exists. They are not the spec.

**Goal:** a Kotlin + Ktor service that behaves the same on the wire, keeps the clean-architecture layers, and is
proven equal by tests that ran green against the Java service first.

---

## 1. What the code says the system is (inventory)

| Area | Java source | Notes |
|---|---|---|
| Entry point | `MathApiApplication` | Spring Boot app |
| Domain | `domain/Operation`, `Calculation`, 3 exceptions | Pure Java, `BigDecimal` |
| Application | `application/PerformCalculation` | One use case, no ports |
| Web adapter | `adapter/in/web/CalculationController`, DTOs, `ProblemDetailsAdvice`, `ProblemExamples` | REST + RFC 9457 errors |
| Wiring | `config/UseCaseConfig`, `OpenApiConfig` | DI + OpenAPI customisation |
| Ops | actuator (health only) | Exposure set by Spring defaults |
| Config | `application.yaml` | Jackson stream limits |
| Persistence | **none** | Stateless |
| Arch rules | `architecture/ArchitectureTest` (+ "rules are live" test) | ArchUnit |
| CI | `.github/workflows/build.yml` | `./gradlew build` |

### Architecture to keep

```
adapter.inbound.web ─┐
config ─────────┼──► application ──► domain
                └───────────────────► domain
```
- `domain`: no framework, no JSON library.
- `application`: no web types, no adapter types.
- Nothing depends on `web` or `config`.

---

## 2. Recovered behaviour catalogue (the "spec" we derive)

This list is the seed for the recovered requirements. Backlog items 3–5 turn its rows into OpenSpec specs
(`openspec/specs/<capability>/spec.md`), where each requirement cites its evidence (file:line in the Java repo,
or a Java test). Those specs are the main output of a "code is the spec" migration. Unknowns and accepted
differences go in `docs/MIGRATION-LOG.md`. Starting set, from reading the code:

**Endpoint: `POST /api/v1/calculations`**
- RB-1 Request `{operation, a, b}`. `a`/`b` accept a JSON number **or** a numeric string. All three are required.
- RB-2 Operations: `ADD, SUBTRACT, MULTIPLY, DIVIDE`, in that order. Names are case-sensitive and must match exactly (`"add"`, `" ADD"` are unknown).
- RB-3 ADD/SUBTRACT/MULTIPLY are exact. DIVIDE uses `MathContext.DECIMAL128` (34 significant digits, HALF_EVEN).
- RB-4 The result has trailing zeros stripped and is rendered in plain notation (`1.50+1.50 → "3"`, `1e3*1 → "1000"`).
- RB-5 Response: `{operation, a, b, result}`, all **strings**. Operands are echoed as received, in plain notation, scale kept (`1e2 → "100"`, `"1.50" → "1.50"`).
- RB-6 Operand limits: precision ≤ 100, −1000 ≤ scale ≤ 1000, checked on the operand as written. The check uses two comparisons, so `Integer.MIN_VALUE` scale is rejected. The error names the operand (`"Operand a …"`) and never echoes its value. If both are bad, `a` is reported.
- RB-7 Division by any zero (`0`, `"0.00"`, `"-0"`, `"0E+3"`) → 422 `division-by-zero`.
- RB-8 Parse limits (Jackson): body ≤ 16,384 chars, number ≤ 1,000 chars, string ≤ 1,000 chars. A breach → `invalid-request`.
- RB-9 **Error precedence:** invalid-request → unknown-operation → operand-out-of-range → division-by-zero.
  (`{"operation":"NOPE","a":1}` is invalid-request, not unknown-operation.)
- RB-8a The body limit is counted in **bytes**, although the error says "characters" (`docs/MIGRATION-LOG.md` Source defect 1; port as is).
- RB-8b Lenient parsing, from Jackson defaults (confirmed by curl):
  - unknown extra fields are ignored;
  - for duplicate keys, the last value wins;
  - operand strings are trimmed, and `+` and exponents are accepted;
  - a number or boolean `operation` is turned into a string, so it returns unknown-operation.
- RB-10 A huge exponent (`1e999999999`) is rejected in < 1 s with no arithmetic done.

**Errors (RFC 9457, `application/problem+json`)**
- RB-11 Domain errors: `type=/problems/<slug>`, fixed `title`, `status`, `detail`, `instance`=request path.

  | slug | status | title | detail |
  |---|---|---|---|
  | invalid-request | 400 | Invalid request | fixed text with the 16,384 / 1,000 limits |
  | unknown-operation | 400 | Unknown operation | `Unknown operation '<name>'. Supported operations: ADD, SUBTRACT, MULTIPLY, DIVIDE.` |
  | operand-out-of-range | 400 | Operand out of range | `Operand <a\|b> must have at most 100 significant digits and a scale between -1000 and 1000.` |
  | division-by-zero | 422 | Division by zero | `Division by zero is undefined.` |
- RB-12 Framework errors 404/405/406/415 → Problem Details with `type=about:blank`, title = the HTTP reason phrase.
- RB-13 An unexpected failure → 500, `about:blank`, detail `The calculation could not be completed.`, logged. No internals in the body.
- RB-14 No `detail` ever contains a class/package name or the word `Exception`.

**Ops**
- RB-15 `GET /actuator/health` → 200 `{"status":"UP"}`, with no `components` and no `details`.
- RB-16 `GET /actuator` → `_links` with exactly `self`, `health`, `health-path`.
- RB-17 `/actuator/env|beans|info` → 404.

**API docs**
- RB-18 `/v3/api-docs` serves OpenAPI **3.1**. `/swagger-ui.html` serves the UI (a redirect is allowed).
- RB-19 The `operation` enum in the docs = the domain's Operation list, in order. `a`/`b` are typed `[number, string]`. All three fields are required.
- RB-20 The documented responses are exactly 200, 400, 415, 422. The 200 schema has 4 string fields. The ProblemDetail schema has only `type,title,status,detail,instance`.
- RB-21 There is one example per problem slug, and each example equals the **real** response body.

**Found by probing the running Java service (framework defaults, item 2)**
- RB-22 Content negotiation:
  - request bodies are accepted as `application/json`, any `application/*+json`, or `application/yaml`;
  - any other type, or no `Content-Type` at all (read as `application/octet-stream`), gets 415, with an `Accept` header listing the three types;
  - a success response is JSON by default, or YAML for `Accept: application/yaml`;
  - errors are always `application/problem+json`.
- RB-23 Protocol details:
  - 405 carries `Allow: POST`, and `HEAD` on the endpoint is also 405;
  - `OPTIONS` gets 200 with `Allow: POST,OPTIONS`;
  - 406 carries an `Accept` header;
  - a trailing slash is a different path, so it gets 404;
  - framework error `detail` texts are Spring's own (e.g. `No static resource api/v1/nope.`).
- RB-24 Health also returns `"groups":["liveness","readiness"]`. `/actuator/health/liveness` and `/readiness` return `{"status":"UP"}`. An unknown group returns 404 with an **empty body** (the only error that is not Problem Details). Unlike the calculations endpoint, `/actuator/health/` with a trailing slash answers 200. The content type is `application/vnd.spring-boot.actuator.v3+json`.
- RB-25 `/v3/api-docs.yaml` serves the same docs as YAML (`application/vnd.oai.openapi`).
- RB-26 No CORS headers and no response compression, even when the client asks for them.

**Found by probing for item 3 (Spring content negotiation and Jackson 3 parsing defaults)**
- RB-27 Choosing the success response type:
  - candidates are the `Accept` types compatible with `application/json` or `application/*+json`, in `Accept` order, most specific first;
  - the first concrete type wins and is echoed back, parameters included (`application/vnd.a+json`, `application/problem+json`, `application/json;charset=utf-8`);
  - `*/*`, `application/*` or no `Accept` gives `application/json`;
  - `Accept` types are ordered by specificity before matching;
  - `q` values only matter for validity: `q=0` is not excluded, and `q>1` or an unparseable `Accept` gives 406 with an **empty body**;
  - with an unparseable `Accept`, **every** response, errors included, keeps its status and headers but has no body (item 3 code review);
  - parameter names are lowercased, a parameter without a value is ignored, separators inside quotes are kept, and an unknown `charset` makes the type invalid;
  - no compatible type gives a 406 problem with detail `Acceptable representations: [...]`;
  - domain errors ignore `Accept`.
- RB-28 Request content types:
  - accepted when compatible with `application/json` or `application/*+json` (any case, any parameters, surrounding spaces);
  - otherwise 415 with detail `Content-Type '<type>' is not supported.`;
  - in that detail, `<type>` is lowercased, its parameters are joined with `;`, and `;charset=UTF-8` is appended when no charset is given;
  - a missing type is reported as `application/octet-stream` (no charset), and an unparseable one as `Could not parse Content-Type.`;
  - with no body and no type, the response is invalid-request;
  - a declared non-Unicode charset is decoded;
  - a wildcard type gets 500 from Java (source defect 4) and 415 from Kotlin (Accepted difference 2).
- RB-29 Strict JSON:
  - trailing tokens, a second document, comments, single quotes, `NaN`, leading zeros and a top-level `null` are all invalid-request;
  - nesting deeper than 500 levels (the root counts) is invalid-request;
  - an `operation` of `1.5` is coerced to the string `"1.5"`; `null` or `{}` is invalid-request;
  - operand strings like `"1."` and `".5"` are accepted, but `"NaN"`, `"0x10"` and `"1_000"` are not.
- RB-30 Framework error wording (the user chose to copy Spring's):
  - 404 `No static resource <path without leading or trailing slash>.`;
  - 405 `Method '<METHOD>' is not supported.`, and `HEAD` gets the headers but no body;
  - 415 and 406 as in RB-27/28;
  - titles are the reason phrases, and `instance` is the request path.

Gaps to record as *unknown, not assumed*: no auth, no rate limiting, no CORS, no logging format, no port config.
Each takes the platform default in the Java app. Record the Ktor default you get, and list each as a known difference.

---

## 3. Decisions

| Concern | Java | Kotlin choice | Why |
|---|---|---|---|
| HTTP | Spring MVC | **Ktor 3** (Netty) | Requested |
| JSON | Jackson 3 | **Jackson** via `ktor-serialization-jackson` + `jackson-module-kotlin` | `BigDecimal` from number-or-string and `StreamReadConstraints` (RB-8) come for free. kotlinx.serialization would need a custom serializer and has no stream limits. |
| Validation | Bean Validation `@NotNull` | Nullable DTO fields + one explicit check → invalid-request | One rule, no extra library |
| Errors | `ResponseEntityExceptionHandler` | **StatusPages** plugin | Map domain exceptions *and* framework statuses (404/405/406/415) to Problem Details. A small `Problem` data class replaces `ProblemDetail`. |
| DI | Spring beans | Hand wiring in `Application.module()` | One use case. Koin would be ceremony. |
| Health | Actuator | Two plain routes | RB-15..17 is small and fixed |
| OpenAPI | springdoc (code-first) | Hand-written `openapi.yaml` (3.1) + `ktor-server-swagger` | Ported docs tests (RB-19..21) stop drift, which is the job `OpenApiConfig`/`ProblemExamples` did |
| Arch rules | ArchUnit | **ArchUnit** (it reads Kotlin bytecode). Swap `org.springframework.web..` for `io.ktor..`. | Same rules, same "rules are live" fixtures |
| Persistence | none | None during the port. **Exposed in Phase 6.** | See §6 |
| Format | Spotless (Palantir) | Spotless + ktlint | |
| Tests | JUnit 5 + MockMvc + AssertJ | JUnit 5 + Ktor `testApplication` + AssertJ | `testApplication` plays the MockMvc role |

Package layout stays the same: `com.paulrule.mathapi.{domain, application, adapter.inbound.web, config}`.

---

## 4. The method (phases and exit criteria)

The core idea: **build the safety net against the old system before writing the new one.** A test suite that
passes against Java and then passes against Kotlin is the proof of equivalence. This matters most when no spec exists.

### Phase 0: Bootstrap (½ h)
- `git init`, Gradle wrapper, Kotlin JVM, Ktor, Spotless, CI workflow (a copy of the Java one).
- Check the Kotlin version can target the JDK you pick. If Kotlin can't target 25 yet, use 21 and record the change.
- **Exit:** `./gradlew build` is green on an empty app.

### Phase 1: Recover behaviour (1–2 h)
- Read every main and test class. Extend the §2 list with evidence links. Backlog items 3–5 turn it into specs.
- Mark each row as **asserted by an existing Java test** or **only in code**. "Only in code" rows need a new test in Phase 2.
  Today: RB-12 titles, RB-16, and response headers are only partly asserted.
- **Exit:** every public path, status, header and message in the code maps to an RB row.

### Phase 2: Black-box contract suite, green against **Java** (2–3 h)
- In the new repo, add a `contractTest` Gradle source set. It holds plain HTTP tests (JDK `HttpClient` + AssertJ)
  that read `BASE_URL` from the environment.
- Port every **observable** assertion from `CalculationControllerTest`, `ServiceHealthSmokeTest` and
  `ApiDocumentationTest` into it, with exact titles and `instance`. Add tests for the "only in code" rows.
- Run it against the Java app (`./gradlew bootRun` in the old repo, then `BASE_URL=http://localhost:8080 ./gradlew contractTest`).
- **Differential safety net:** a script sends a request corpus (every test body plus generated edge cases:
  exponents, signs, zeros, limits ±1) to the service and saves `status + content-type + body` as golden files.
  Later, the same corpus runs against Kotlin and a JSON diff shows any change. This catches what nobody thought to assert.
- **Exit:** the contract suite is 100% green against Java, and the golden files are committed.

### Phase 3: Build Kotlin, inside out (3–4 h)
Port each layer with its unit tests, then move up a layer:
1. **Domain:** `enum class Operation { ADD { override fun apply… } … }` with `companion fun named(name: String?)`;
   `data class Calculation` with an `init {}` range check; 3 exceptions. Port `OperationTest` and `CalculationTest` one to one.
   Watch for: `stripTrailingZeros`, `toPlainString`, the two-comparison scale check.
2. **Application:** `class PerformCalculation { fun perform(op: String, a: BigDecimal, b: BigDecimal) }`.
3. **Web adapter:** the route, DTOs (`CalculationRequest(val operation: String?, val a: BigDecimal?, val b: BigDecimal?)`),
   StatusPages mapping, and the Jackson `JsonFactory` with `StreamReadConstraints` (16384/1000/1000). Port `CalculationControllerTest`
   to `testApplication`.
4. **Ops + docs:** health routes, `openapi.yaml`, Swagger UI. Port `ApiDocumentationTest`.
5. **ArchUnit:** port `ArchitectureTest` and `ArchitectureRulesAreLiveTest` with their fixtures.
- **Exit:** `./gradlew build` is green: unit, web and arch tests, plus ktlint.

### Phase 4: Prove equivalence (1 h)
- `BASE_URL=<kotlin> ./gradlew contractTest` must be green. Then run the differential corpus and diff it against the golden files.
- For each diff, either fix Kotlin or record the difference in `docs/MIGRATION-LOG.md` under **Accepted differences**, with a reason.
- **Exit:** zero unexplained diffs.

### Phase 5: Cutover
- Run the contract suite against the deployed Kotlin service as a smoke test. Then retire the Java repo.

---

## 5. Known hard spots (where Spring did work for free)

| Spot | Risk | What to do |
|---|---|---|
| 405 vs 404 | Ktor routing may answer 404 for a wrong method | Assert `GET /api/v1/calculations` → 405 Problem early |
| 406 | Ktor ContentNegotiation may ignore `Accept: text/plain` | Check `Accept` explicitly, or handle it in StatusPages |
| 415 | `UnsupportedMediaTypeException` must become Problem Details | StatusPages `exception<…>` |
| Jackson coercion | `""`, `true`, `{}`, `[1]` must all be invalid-request | Disable scalar coercion for numbers. Contract tests pin all four. |
| Jackson errors | Any `JsonProcessingException`/`BadRequestException` must become the **fixed** invalid-request detail | Catch broadly in the route, never pass the message through |
| Problem content type | Must be `application/problem+json`, not `application/json` | `call.respondText(json, ContentType("application","problem+json"), status)` |
| Field order / nulls | Spring omits null fields | `JsonInclude.NON_NULL` on `Problem` |
| Swagger path | `/swagger-ui.html` must resolve | Add a redirect route |

---

## 6. Exposed: post-migration feature (decided)

The Java service stores nothing, so Phases 0–5 leave Exposed out. Adding it during the port would add behaviour
and break the equivalence proof. Exposed arrives in **Phase 6**, after the contract suite proves the two services match.

### Phase 6: Calculation history with Exposed
New behaviour, so this phase has a real spec: a new `calculation-history` capability whose requirements are
stated directly, not recovered (backlog item 7).

- **Feature:** a successful `POST /api/v1/calculations` is stored and returns `id` and a `Location` header.
  `GET /api/v1/calculations/{id}` returns the stored calculation, or a 404 `calculation-not-found` Problem.
  Failed calculations are not stored.
- **Layers:**
  - `domain`: `Calculation` gets an id type (`CalculationId`). It still has no framework code.
  - `application`: a `CalculationRepository` port (an interface). `PerformCalculation` saves through it, and a new
    `FindCalculation` use case reads through it.
  - `adapter.outbound.persistence` (new): an Exposed `Table` + `ExposedCalculationRepository`. Operands and the result are
    stored as **strings** (`varchar`/`text`), not `decimal` columns, so scale and precision (precision ≤ 100,
    scale ±1000) round-trip exactly (RB-5).
  - `config`: creates the `Database` (H2 for tests, Postgres for running), the schema, and the wiring.
- **Arch rules:** add a `Persistence` layer that nothing may access except `config`. Ban `org.jetbrains.exposed..`
  from `domain` and `application`. Add a "rule is live" fixture for each new rule.
- **Tests:**
  - an in-memory fake repository for the use-case tests;
  - an Exposed repository test against H2 (or Testcontainers Postgres, if you want it to match production);
  - contract tests for the new endpoints. **Run the old contract suite again**: the only change it should allow is the
    new `id` field and `Location` header, recorded as an intended difference.
- **Docs:** update `openapi.yaml` (the 201-or-200 decision, the GET path, the 404 example) and the docs tests.
- **Decide before building:** keep 200 for the POST response, or switch to 201 Created? 201 is the correct REST status,
  but it breaks the old contract. Make the choice on purpose and record it.
- **Exit:** `./gradlew build` is green, every Phase 2 contract test still passes (except the intended changes), and
  the arch rules prove persistence stays outside the core.

---

## 7. Lessons the exercise should show
1. The old **tests** are the best spec you have. Port their assertions before you port the code.
2. Write the recovered behaviour down with evidence. Untested behaviour that exists only in code is the main risk.
3. Prove equality from outside (HTTP), against both systems, with the same suite.
4. Framework defaults (Spring's 404/405/406/415, actuator exposure, Jackson coercion) are hidden requirements.
   Hunt for them on purpose.
5. Don't add features during a migration. Add them after equivalence is proven.
6. Clean architecture pays off when a new outbound adapter (Exposed) arrives: domain and application gain a port,
   not a dependency.
