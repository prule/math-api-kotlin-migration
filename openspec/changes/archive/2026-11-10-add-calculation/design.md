# Design

## Context

See proposal.md (Why) and `specs/calculation/spec.md` (what). The Java layers are:
- `domain` (`Operation`, `Calculation`, three exceptions);
- `application` (`PerformCalculation`);
- `adapter.in.web` (controller, DTOs, `ProblemDetailsAdvice`);
- `config` (`UseCaseConfig`).

Spring MVC, Jackson 3 and Spring Boot defaults supply most of the wire behaviour the spec now pins (content
negotiation, framework errors, parsing strictness). Ktor supplies none of it, so the adapter has to state it
explicitly.

## Goals / Non-Goals

**Goals:**
- A one-to-one port of domain and application. The Java unit tests port row by row.
- The calculation contract tests and the calculation golden cases are green against Kotlin, except the accepted
  differences.

**Non-Goals:**
- Health (item 4) and API docs (item 5). Until then, their paths get the generic 404 problem.
- Fixing source defects (post-migration).

## Decisions

1. **Domain:**
   - `enum class Operation` with an abstract `apply`, and a companion `named(name: String?)` that does an exact
     match (no `valueOf`, Java `Operation.java:38`).
   - `data class Calculation` validates in `init`, using two comparisons for scale, not `abs`
     (Java `Calculation.java:26`). `result()` computes on demand, so division by zero surfaces after the range
     check, which keeps the RB-9 order.
   - Exceptions extend `RuntimeException` with the Java messages.
2. **No `ContentNegotiation` plugin.** The adapter reads the body with a configured `ObjectMapper` from
   `call.receiveStream()` and writes bytes with an explicit `ContentType`. Reasons:
   - the stream makes Jackson count **bytes** (RB-8a) and stop at 16,384 without buffering the body;
   - Ktor's negotiation can't echo `application/vnd.a+json`, and it can't produce an empty-body 406;
   - `respondBytes` adds no `charset`, and Java sends exactly `application/json`.

   Alternative: `ContentNegotiation` with a custom converter. Rejected: it fights the plugin on each of these.
3. **One `ObjectMapper` with Jackson 3's strictness on Jackson 2:**
   - `StreamReadConstraints`: document length 16,384, number length 1,000, string length 1,000, nesting depth 500;
   - `FAIL_ON_TRAILING_TOKENS` on;
   - `FAIL_ON_UNKNOWN_PROPERTIES` off (Spring Boot's default);
   - Kotlin module on.

   The DTO has nullable fields, and one check turns null into invalid-request, as `@NotNull` did. Each Jackson or
   I/O failure while reading becomes invalid-request with the fixed detail. The parser message never reaches the
   client.
4. **Spring-compatible media types in one small file** (`adapter.inbound.web.MediaType`):
   - `parse` (type, subtype, parameters; lowercase type and subtype; rejects invalid `q`);
   - `isCompatible` (with wildcards and `*+json` suffixes);
   - Spring's `getMostSpecificMediaType` and "first concrete wins" selection;
   - Spring's 415 detail formatting.

   The producible types are `application/json` and `application/*+json`. Alternative: Ktor's `ContentType` plus
   `parseHeaderValue`. Rejected for selection, because it lacks the suffix and specificity rules. It is still used
   for header parsing where it matches Spring.
5. **Errors:**
   - Domain and framework errors are a `Problem` data class (`type`, `title`, `status`, `detail`, `instance`),
     written as `application/problem+json`.
   - Ktor `StatusPages` maps the domain exceptions, an internal `ProblemException` for adapter errors (415/406),
     the 404 status, and `Throwable` (logged, 500). The handler functions are plain functions, so the 500 path is
     unit tested directly, as in Java (RB-13).
   - The empty-body 406 is a separate exception that StatusPages answers with no body.
6. **Methods:** `route(PATH) { post { … }; options { … }; handle { 405 } }`, so any other method on the path gets
   405 with `Allow: POST`. `HEAD` gets the 405 headers and an empty body, because there is no
   `AutoHeadResponse` plugin.
7. **Wiring:**
   - `config/UseCaseConfig` creates `PerformCalculation` once, as the Java `UseCaseConfig` bean did. It is the only
     place a use case is created (STANDARDS.md §2).
   - The root `Application.kt` (`main` and `Application.module()`) is the composition root. It installs
     `StatusPages` and hands the use case to the routes.
   - Found in apply: the first version wired the routes from `config/Module.kt`, and ArchUnit rejected it,
     because no layer may access `web`. In Java, the Spring container, outside every layer, found the controller
     by scanning. The root package is the faithful counterpart, so the rule stays unchanged.
   - The three empty-layer ArchUnit shortcuts are removed now that every layer has classes. `UsesJackson` (domain
     fixture) proves the Jackson ban.
8. **Accepted differences in the contract suite:**
   - `isJavaReference` (shared with the record guard: `GET /error` has `"status":999`) lets a test assert the
     Java or the Kotlin side of a listed difference, naming the MIGRATION-LOG entry.
   - The golden test applies `golden/accepted-differences.json` (case name → expected Kotlin entry) only when
     the service is not Java. Recordings stay pure Java. Every overlay key must name a corpus case, so a renamed
     case can't leave a stale entry behind (found in plan review).
9. **Tests:**
   - Port `OperationTest` and `CalculationTest` exactly.
   - Port `CalculationControllerTest` to `testApplication`, so CI covers the web layer before item 6 adds the
     contract suite to CI.
   - The probed RB-27 to RB-30 behaviour goes into the contract suite and the golden corpus. The ported unit
     tests stay one to one.

10. **Found in apply:**
    - **Draining.** Every error on the POST first reads up to 2 MiB of the rest of the body, like Tomcat's
      `maxSwallowSize`. Without this, a client that writes its whole body before reading (the JDK `HttpClient`)
      deadlocks on a body rejected after 16 KiB. The 1 MiB contract test hung until this was added. Jackson's
      `AUTO_CLOSE_SOURCE` is off, so the stream is still open to drain after a parse failure.
    - **Exact Content-Type.** Ktor renders `ContentType` parameters with a space (`application/json; charset=utf-8`),
      and Spring doesn't. A small `OutgoingContent` sends the header string exactly as Spring would.

11. **Found in code review**, by about 150 paired probes of both services:
    - **Fixed:**
      - an unparseable `Accept` strips the body from every response;
      - quoted `;` and `,` stay inside parameters;
      - parameter names are lowercased, and valueless parameters are ignored;
      - `charset` is validated;
      - `Accept` types are ordered by specificity;
      - no body and no type is invalid-request;
      - a declared non-Unicode charset is decoded through a `Reader`, as Spring's converter does.
    - **Agreed with the user as accepted differences 2 to 4:** wildcard content types, Tomcat path normalisation,
      and the OPTIONS/TRACE extras.
    - The golden test also writes `build/golden-actual.json`, which makes a failing run diffable (item 6 needs it).
    - `UnreadableAcceptException` was removed, because the general "unparseable Accept means no body" rule covers it.

## Risks / Trade-offs

- [Spring's negotiation has more rules than the probes covered] → Each probed rule is a golden case. Rules
  nobody probed stay unknown. Item 6's golden diff is the safety net, not this design.
- [Jackson 2's coercion differs from Jackson 3 in a case nobody tested] → The golden corpus includes every
  probed coercion. If a difference appears, it shows up as a failing golden case.
- [Netty resets the connection when the handler answers before a large body is read; seen as a client hang] → A 1 MiB golden case
  checks it. If needed, drain up to 2 MiB first, as Tomcat does (found in plan review).
- [Netty sends a body for `HEAD`] → Respond with an empty body explicitly. The golden case `HEAD calculations` pins it.
