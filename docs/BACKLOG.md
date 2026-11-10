# Backlog

Ordered OpenSpec changes. Work them top to bottom with the change workflow in `CLAUDE.md`. Set **Status** to
In progress when you start an item and Done when you archive it.

**Milestones:**
- **v1 (migration):** items 1–6. Done when the Kotlin service is proven equal to the Java one.
- **v2 (new behaviour):** item 7 onwards.

Gate before item 1: audit the Java repo (see `CLAUDE.md` → Milestone gates).

| # | Change | Capability | Status |
|---|---|---|---|
| 1 | `add-kotlin-skeleton` | (none: tooling and standards) | Done |
| 2 | `add-contract-suite` | (none: test harness) | Done |
| 3 | `add-calculation` | `calculation` | Done |
| 4 | `add-service-health` | `service-health` | Not started |
| 5 | `add-api-documentation` | `api-documentation` | Not started |
| 6 | `prove-equivalence` | (none: verification) | Not started |
| 7 | `add-calculation-history` | `calculation-history` | Not started |
| 8 | `compare-recovered-specs` | (none: exercise write-up) | Not started |

---

## 1. `add-kotlin-skeleton`

**Prompt:**
> Bootstrap the Kotlin service in this folder per `MIGRATION-PLAN.md` Phase 0 and §3. Run `git init` and
> `openspec init` (for the Claude Code tool). Ask the user before creating the GitHub repository. Add a Gradle
> wrapper, Kotlin JVM, Ktor 3 (Netty) serving an empty app on port 8081 (`./gradlew run`), Spotless with ktlint,
> JUnit 5 + AssertJ, and a CI workflow that runs `./gradlew build` on PRs and pushes to `main`. Create the
> packages `com.paulrule.mathapi.{domain, application, adapter.inbound.web, config}`. Port the Java
> `ArchitectureTest` and `ArchitectureRulesAreLiveTest`, with their fixtures, to Kotlin, replacing Spring web
> packages with `io.ktor..`. Write `docs/STANDARDS.md`, derived only from the Java code's ArchUnit rules, build
> config and test style, with each rule citing its Java evidence. Pick the newest JDK that Kotlin can target
> (prefer 25); if it is not 25, record why in the design.

**Done when:** `./gradlew build` is green on an empty app, the ArchUnit rules are proven live, and
`docs/STANDARDS.md` exists.

## 2. `add-contract-suite`

**Prompt:**
> Add a `contractTest` Gradle source set with black-box HTTP tests (JDK `HttpClient` + AssertJ) that read
> `BASE_URL`. Port every observable assertion from the Java `CalculationControllerTest`,
> `ServiceHealthSmokeTest` and `ApiDocumentationTest`, using exact titles, details, `instance` and content types.
> Add tests for each RB row in `MIGRATION-PLAN.md` §2 that no Java test asserts. Run the suite against the Java
> service; it must be fully green there. Add a differential script that sends a request corpus (every test body
> plus generated edge cases: exponents, signs, zeros, each limit ±1) and saves status, content type and body as
> golden files under `src/contractTest/resources/golden/`. Create `docs/MIGRATION-LOG.md` with its three
> sections, and move the Java audit's findings into **Source defects**.

**Done when:** the contract suite is 100% green against Java, the golden files are committed, and every RB row
maps to at least one contract test. The suite is expected to fail against Kotlin at this point. Exclude it from
`./gradlew build`.

## 3. `add-calculation`

**Prompt:**
> Recover and implement the `calculation` capability: `POST /api/v1/calculations` and its Problem Details
> errors, RB-1 to RB-14 in `MIGRATION-PLAN.md`. Write the spec delta from the Java code under the evidence rule.
> Port the domain (`Operation`, `Calculation`, its exceptions), the application layer (`PerformCalculation`) and
> the web adapter (route, DTOs, StatusPages, Jackson with `StreamReadConstraints`), inside out, each with its
> ported unit tests (`OperationTest`, `CalculationTest`, and `CalculationControllerTest` on `testApplication`).
> Cover every hard spot in `MIGRATION-PLAN.md` §5. Add an ArchUnit live fixture for the domain rule's Jackson ban
> (now provable, because Jackson is on the classpath), and remove the three empty-layer `shortcut:` allowances.

**Done when:** the calculation contract tests pass against both services.

## 4. `add-service-health`

**Prompt:**
> Recover and implement the `service-health` capability: RB-15 to RB-17 (`/actuator/health` returns UP with no
> details, the `/actuator` index links only `self`, `health` and `health-path`, and other actuator paths return
> 404). Write the spec delta from the Java code and its Spring Boot defaults under the evidence rule. Use plain
> Ktor routes.

**Done when:** the health contract tests pass against both services.

## 5. `add-api-documentation`

**Prompt:**
> Recover and implement the `api-documentation` capability: RB-18 to RB-21. Serve a hand-written OpenAPI 3.1
> document at `/v3/api-docs`, and Swagger UI reachable from `/swagger-ui.html`. Port `ApiDocumentationTest`. Its
> checks that the operation enum matches the domain and that each example equals the real response are what
> stop the hand-written document from drifting.

**Done when:** the docs contract tests pass against both services.

## 6. `prove-equivalence`

**Prompt:**
> Run the full contract suite and the differential corpus against the Kotlin service, and diff the results
> against the golden files. For each difference, either fix Kotlin (with a test) or, with the user's agreement,
> record it under **Accepted differences** in `docs/MIGRATION-LOG.md`. Record every **Unknown** with the Ktor
> default the Kotlin service now has. Add the contract suite against the Kotlin service to CI.

**Done when:** there are zero unexplained differences. v1 is complete.

## 7. `add-calculation-history`

Gate first: audit this repo (see `CLAUDE.md` → Milestone gates).

**Prompt:**
> Add calculation history with Exposed, per `MIGRATION-PLAN.md` §6 Phase 6. This is **new behaviour**, so the
> evidence rule does not apply: the spec delta states the new requirements directly. Before proposing, ask the
> user whether the POST keeps 200 or switches to 201 Created. Add a `CalculationRepository` port in
> `application`, an Exposed adapter in `adapter.outbound.persistence` that stores operands and the result as text, and
> new ArchUnit rules (persistence is reachable only from `config`; Exposed is banned from `domain` and
> `application`) with live fixtures. Update `openapi.yaml`, the docs tests and the contract suite. List each
> intentional contract change in `docs/MIGRATION-LOG.md`.

**Done when:** `./gradlew build` is green and the v1 contract suite still passes, except for the listed
intentional changes.

## 8. `compare-recovered-specs`

**Prompt:**
> Now, and only now, read `../mathi-api-one-agent-2/openspec/specs/` and `docs/`. Compare them with this repo's
> `openspec/specs/` (v1 capabilities only). Write `docs/SPEC-COMPARISON.md`, listing each original requirement
> under one of four labels:
> - recovered exactly;
> - recovered with different wording;
> - missed (say why the code alone didn't reveal it);
> - recovered but not in the original (behaviour the code has that the docs never stated).
>
> End with the lessons for migrating an undocumented codebase. Change no code.

**Done when:** `docs/SPEC-COMPARISON.md` exists. This item does not need the code reviews; run the plan review
and the retrospective only.
