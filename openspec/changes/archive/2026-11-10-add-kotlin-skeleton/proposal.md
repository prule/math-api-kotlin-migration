# Proposal

## Why

Every later migration item needs a Kotlin build that is already green, the Java service's layer rules already
enforced, and written standards for `/code-review` to check against. Without that base, items 3–5 would mix
build setup with behaviour, and nothing would stop a layering mistake from creeping in early.

## What Changes

- Add a Gradle build (wrapper 9.8.2, same as the Java repo), Kotlin JVM 2.4.21, Ktor 3.6.1 (Netty), JUnit 5 +
  AssertJ, and Spotless with ktlint for Kotlin sources and build scripts.
- Add an empty Ktor app that starts with `./gradlew run` on port 8080 (the Java service's port), or on `PORT` if that is set.
- Fix the package names `com.paulrule.mathapi.{domain, application, adapter.inbound.web, config}` in the ArchUnit rules.
  Git does not track empty folders, so each package appears with its first class in item 3. Until then, only test
  fixtures live there.
- Port the Java `ArchitectureTest` (three rules) and `ArchitectureRulesAreLiveTest`, with their fixtures,
  replacing Spring and Jakarta packages with their Ktor and Jackson equivalents.
- Add a CI workflow that runs `./gradlew build` on pull requests and on pushes to `main`. It is a copy of the
  Java one.
- Add `docs/STANDARDS.md`, derived only from the Java code, with each rule citing its evidence.
- Add `.gitignore`, and `.gitattributes` (copied with the wrapper; it keeps `gradlew` LF and `gradlew.bat` CRLF).
- Add logback with an INFO config, so the app logs the way the Java service does (Spring Boot's default level).

## Capabilities

### New Capabilities

None. This change is tooling, architecture rules and standards. It has no externally visible behaviour, so
`skip_specs: true` is set.

### Modified Capabilities

None.

## Impact

- New files only: build scripts, the Gradle wrapper, `src/`, `.github/workflows/build.yml`, `docs/STANDARDS.md`,
  `.gitignore` and `.gitattributes`.
- The Java repo is read only. The Gradle wrapper files are copied from it.
- A GitHub repository is needed for the PR and merge-on-green steps. The user is asked before one is created.
