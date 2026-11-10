# Design

## Context

See proposal.md (Why). The Java build is defined by `build.gradle.kts`: Spring Boot 4.1.2, a Java 25
toolchain, Spotless with Palantir and ktlint for build scripts, and `useJUnitPlatform`. Its layer rules are in
`src/test/java/com/paulrule/mathapi/architecture/ArchitectureTest.java:11-55`. The "rules are live" proof is
`ArchitectureRulesAreLiveTest.java:1-38`, with fixtures `domain/fixture/UsesSpring.java`,
`application/fixture/UsesWeb.java` and `adapter/in/web/fixture/WebType.java`.

## Goals / Non-Goals

**Goals:**
- A green `./gradlew build` with Kotlin, ArchUnit and ktlint all checked.
- The same three layer rules as the Java code, each proven able to fail.

**Non-Goals:**
- Any endpoint, JSON setup or error handling. Those come in items 3–5.
- Jackson or Exposed dependencies, until an item needs them.

## Decisions

1. **JDK 25 toolchain, Kotlin 2.4.21.** This matches the Java toolchain (`build.gradle.kts:12-16`). Kotlin 2.4
   can target JVM 25. If the build shows otherwise, fall back to 21 and record it here.
   Alternative: 21 everywhere. Rejected, because it would differ from the source for no reason.
2. **A single Gradle module, with layers as packages, enforced by ArchUnit.** This keeps the Java structure one
   to one. Alternative: a Gradle module per layer (the compiler enforces it). Rejected for now, because it
   changes the shape the exercise wants to keep, and ArchUnit already gives the guarantee.
3. **The rules are translated, not copied word for word:**

   | Java rule | Kotlin rule |
   |---|---|
   | Layers depend inwards (Web, Config not accessed; Application ← Web, Config; Domain ← Application, Web, Config) | Identical |
   | Domain must not use `org.springframework..`, `jakarta..`, `com.fasterxml..`, `tools.jackson..` | Domain must not use `io.ktor..`, `com.fasterxml..` or `tools.jackson..` (Jackson 2 and 3) |
   | Application must not use `org.springframework.web..` or `..adapter..` | Application must not use `io.ktor..` or `..adapter..` |

   The application rule bans **all** of Ktor, not just its web part. There is no DI framework here, so
   application code has no reason to touch Ktor at all.
4. **Empty layers.** ArchUnit fails a rule that matches no classes by default, and the layers are empty until
   item 3. Allow empty matches only where needed (`allowEmptyShould(true)` on the rule, and `withOptionalLayers`
   for the layered rule), each marked `shortcut: allows empty layer until item 3 fills it; remove in
   add-calculation`, so `/ponytail:ponytail-debt` lists it until it is gone. The live-rule tests keep each rule
   proven to fire.
5. **No main-code package docs.** Kotlin has no `package-info`. The role of each package goes into
   `docs/STANDARDS.md` §2 instead. In Java it lived in the `package-info.java` javadoc.
6. **Port: `PORT` env var, default 8080.** The default is the Java service's port (Spring's default), so the
   wire is the same after cutover. Local contract runs use `PORT=8081` so the Java service (8080) can run
   alongside. One env var is the closest equivalent of Spring's `SERVER_PORT`.
7. **`adapter.in.web` becomes `adapter.inbound.web`.** `in` is a hard keyword in Kotlin, so the Java package
   name would need backticks in every import, and ktlint's `package-name` rule rejects it. Alternatives: suppress
   the rule in every web file, or turn it off for the whole project. Both rejected: they are noise in each file,
   or they hide real naming mistakes. The future persistence adapter becomes `adapter.outbound.persistence` for
   symmetry. Only the name changes; the layers are the same. (Found during apply.)
8. **The Gradle wrapper is copied from the Java repo** (9.8.2). No local Gradle is installed, and both builds
   then use the same version.

9. **Logging: logback at INFO** (found in code review). Ktor logs through SLF4J. Without a binding there are
   no logs at all, and logback with no config logs at DEBUG (51 Netty lines at startup). `logback.xml` sets INFO,
   which is Spring Boot's default, and `LoggingTest` pins it.
10. **Live fixtures prove each half of a rule** (found in code review). The application rule bans Ktor and
    adapters, so it gets two fixtures: `UsesKtorCall` (Ktor only) and `UsesWeb` (adapter only). That way,
    removing either ban fails a test. The Java original shared one fixture, so its Spring ban was never proven.
    The domain rule's Jackson bans can't be proven yet, because Jackson isn't on the classpath. Item 3 adds a
    Jackson fixture when it adds Jackson.

## Risks / Trade-offs

- [Kotlin 2.4 rejects JVM target 25] → Fall back to toolchain 21 and record it under Decisions.
- [`allowEmptyShould` hides a broken rule while layers are empty] → The live-rule tests check each rule
  directly against its fixtures.
- [ktlint rule churn between versions] → Pin the version the Spotless plugin ships with. Do not configure custom rules.
