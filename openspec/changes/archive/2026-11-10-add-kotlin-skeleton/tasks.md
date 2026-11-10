# Tasks

## 1. Build setup

- [x] 1.1 Copy `gradlew`, `gradlew.bat` and `gradle/wrapper/` from the Java repo, and add `settings.gradle.kts`
  (`rootProject.name = "math-api"`) and `.gitignore` (`.gradle/`, `build/`, `.kotlin/`). Verify with `./gradlew --version`, which reports Gradle 9.8.2.
- [x] 1.2 Add `build.gradle.kts`: Kotlin JVM 2.4.21 with toolchain 25, the `application` plugin, Ktor 3.6.1
  server-core and Netty, JUnit 5 with `testRuntimeOnly` `junit-platform-launcher` (Gradle 9 needs it), AssertJ, ArchUnit 1.5.1, and Spotless (ktlint for `kotlin` and
  `kotlinGradle`). Verify that `./gradlew build` compiles.
- [x] 1.3 Add `Application.kt` (`main` starts Netty on `PORT`, default 8080, and calls an empty
  `Application.module()`). Verify that `PORT=8081 ./gradlew run` logs that it is listening on 8081.

## 2. Architecture rules

- [x] 2.1 Port `ArchitectureTest` to Kotlin with the three translated rules from design.md Decision 3. Mark
  each empty-layer allowance with a `shortcut:` comment. Verify that it passes on the empty layers.
- [x] 2.2 Port the fixtures (`domain.fixture.UsesKtor`, `application.fixture.UsesWeb`,
  `adapter.inbound.web.fixture.WebType`) and `ArchitectureRulesAreLiveTest`. Verify that each of the three tests
  passes, which proves each rule rejects its fixture.

## 3. Standards and CI

- [x] 3.1 Write `docs/STANDARDS.md`: stack, architecture (layers, rules, package roles), errors, testing,
  formatting and workflow, each rule citing its Java evidence. Verify that every section has at least one
  `Evidence:` line.
- [x] 3.2 Add `.github/workflows/build.yml` (checkout, Temurin 25, setup-gradle, `./gradlew build`). Verify that
  the YAML matches the Java workflow apart from comments.
- [x] 3.3 Run `./gradlew clean build`. Verify that it is green, with tests, ArchUnit and spotlessCheck all run.

## Workflow follow-up

- Review the code (ponytail and `/code-review`), archive, write the retrospective, commit, open the PR, and merge on green.
