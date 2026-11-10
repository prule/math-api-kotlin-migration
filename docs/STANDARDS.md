# Engineering standards

These standards are derived only from the Java code in `../mathi-api-one-agent-2` (paths below are relative to its
`src/`). Each rule cites its evidence. The Java comments point to section numbers in a standards document
(`STANDARDS.md §2–§4`). This file keeps those numbers, so the hints still line up, but its content comes from the
code, not from that document.

## 1. Stack

- Kotlin (JVM toolchain 25), Ktor (Netty), Jackson for JSON (from item 3), Gradle Kotlin DSL with the wrapper.
- `./gradlew build` is the gate: compile, tests, ArchUnit, and `spotlessCheck` (ktlint).
- CI runs `./gradlew build` on every pull request and every push to `main`.

Evidence: `../build.gradle.kts:4-44`, `../.github/workflows/build.yml:1-22`.

## 2. Architecture

Clean architecture in one Gradle module, with layers as packages under `com.paulrule.mathapi`:

| Package | Holds | Must not depend on |
|---|---|---|
| `domain` | Entities, value objects, operations, domain exceptions. Plain Kotlin. | Ktor, Jackson 2 (`com.fasterxml`) and 3 (`tools.jackson`), any other layer |
| `application` | Use cases and ports | Ktor (any part), `adapter..` |
| `adapter.inbound.web` | Routes, request/response DTOs, Problem Details mapping. No business rules. | — (no one may depend on it) |
| `config` | Wiring. The only place use cases are created. | — (no one may depend on it) |

- Dependencies point inwards: `application` may be used only by `adapter.inbound.web` and `config`, and
  `domain` only by `application`, `adapter.inbound.web` and `config`.
- Each rule is enforced by ArchUnit in `architecture/ArchitectureTest`. Each rule also has a fixture that breaks
  it on purpose, and `ArchitectureRulesAreLiveTest` proves the rule rejects that fixture. A new rule comes with a
  new fixture.
- Never weaken an ArchUnit rule to get green.
- Kotlin adaptation (a design decision, not Java evidence): the package is `adapter.inbound.web`, not
  `adapter.in.web`, because `in` is a Kotlin keyword. See `openspec/changes/archive/2026-11-10-add-kotlin-skeleton/design.md` Decision 7.

Evidence: `test/java/com/paulrule/mathapi/architecture/ArchitectureTest.java:11-55`,
`ArchitectureRulesAreLiveTest.java:1-38`, `main/java/com/paulrule/mathapi/domain/package-info.java:1`,
`application/package-info.java:1`, `adapter/in/web/package-info.java:1`, `config/package-info.java:1`.

## 3. Domain and errors

- The domain owns its rules and messages. Domain exceptions carry the client-facing message.
- An exception about a bad input never echoes the input value when that value could be large.
- Look up enum names with an explicit, exact, case-sensitive match. Never use `valueOf`, which leaks a platform
  message.
- Every HTTP error is RFC 9457 Problem Details (`application/problem+json`) with `type`, `title`, `status`,
  `detail` and `instance`. Domain errors use `type = /problems/<slug>`. Framework errors and the catch-all use
  `about:blank`.
- No error `detail` contains a class or package name, a parser message, or the word `Exception`. An unexpected
  failure is logged and answered with a fixed message.

Evidence: `main/java/com/paulrule/mathapi/domain/OperandOutOfRangeException.java:3`, `Operation.java:38-46`,
`adapter/in/web/ProblemDetailsAdvice.java:20-92`,
`test/java/.../adapter/in/web/CalculationControllerTest.java` (`assertProblem`).

## 4. Docs come from the code

- Values that come from the domain (the list of operations) must never be typed twice. The API docs either take
  them from the domain, or a test checks the docs against the domain.
- Each documented error example must equal the real response, and a test checks that.

Evidence: `main/java/com/paulrule/mathapi/config/OpenApiConfig.java:12`, `adapter/in/web/ProblemExamples.java:14-19`,
`test/java/com/paulrule/mathapi/ApiDocumentationTest.java` (`documentedExampleIsTheRealResponse`).

## 5. Testing

- Domain classes have plain unit tests (no framework).
- The web layer is tested in process with Ktor's `testApplication` (the counterpart of MockMvc). End-to-end
  behaviour is tested over real HTTP by the contract suite.
- Prefer parameterised tests, one row per case, named after the behaviour they prove.
- Test names are sentences that describe behaviour. Java writes them in camelCase
  (`rejectsAnyOtherNameListingTheSupportedOnes`); Kotlin writes them as backtick names (Kotlin adaptation).
- A test must not list the closed set of operations. It derives the set from the domain, so adding an operation
  edits no test.
- A test that cannot reach a branch through the API (for example, the catch-all 500) calls the handler directly,
  with a comment explaining why.

Evidence: `test/java/com/paulrule/mathapi/domain/OperationTest.java:33-44`, `CalculationTest.java:18-30`,
`OperationTest.java:27`,
`adapter/in/web/CalculationControllerTest.java:24, 219-229`, `ServiceHealthSmokeTest.java:18`.

## 6. Comments and formatting

- Comment only the *why* the code cannot show, in one line where possible. Examples: why two comparisons
  instead of `abs`, or why a field is a String rather than an enum.
- A known limit is marked `shortcut: <the limit>, <when to upgrade>`.
- Formatting is ktlint via Spotless. Fix it with `./gradlew spotlessApply`. Never hand-format to fight the tool.

Evidence: `main/java/com/paulrule/mathapi/domain/Calculation.java:24-28`,
`adapter/in/web/CalculationRequest.java:6-9`, `../build.gradle.kts:37-44`.

## 7. Workflow

- Every change follows the change workflow in `CLAUDE.md`: OpenSpec propose → plan review → apply → two code
  reviews → archive → retrospective → PR → merge on green.
- Every review finding is fixed together with a test that fails without the fix.
- A pull request merges only after its CI `build` check passes.

Evidence: this repo's `CLAUDE.md` (Change workflow), Java `../.github/workflows/build.yml:3-6` (build on every PR
and every push to `main`). These are process rules, so the evidence is the workflow, not Java code.
