# Retrospective: add-kotlin-skeleton

## Caught by review

**Plan review (ponytail):**
- The default port of 8081 differed from Java's 8080, a wire difference that would only show at cutover. → The
  default is now 8080. Local runs use `PORT=8081`. CLAUDE.md is updated to match.
- `junit-platform-launcher` was missing, and Gradle 9 doesn't load JUnit without it. → Added to task 1.2 and
  the build.
- Nothing forced the "empty layers allowed" escapes to be removed later. → Each one is now a `shortcut:` comment,
  and backlog item 3 removes them.
- `.kotlin/` was not ignored. → Added to `.gitignore`.

**Code review (ponytail):**
- Logback with no config logged at DEBUG (51 Netty lines at startup), while Java logs at INFO. → Added
  `logback.xml` (INFO). `LoggingTest` fails without it.

**Code review (`/code-review`, Standards):**
- The domain rule didn't ban `tools.jackson..` (Jackson 3), which the Java rule bans. → Added.
- STANDARDS.md cited `build.gradle.kts` lines past the end of the file, cited an archive path before it existed,
  and had weak §5 citations. → Fixed the line numbers, cited the real path, cited `@WebMvcTest` and
  `@SpringBootTest` lines, and marked the backtick test names as a Kotlin adaptation.
- `group = "com.paulrule"` was never used, because nothing is published. → Deleted.

**Code review (`/code-review`, Spec):**
- The application rule's Ktor ban was unproven: its only fixture also broke the adapter ban. → Split it into
  `UsesKtorCall` (Ktor only) and `UsesWeb` (adapter only), with a new test for each half. The Ktor test fails
  when `io.ktor..` is removed from the rule.
- STANDARDS.md had no workflow section. → Added §7, and moved the "test per review fix" rule there.
- The proposal didn't list `.gitattributes` or logback, and said the packages would be "added" although git
  can't track empty folders. → Updated the proposal and recorded design Decisions 9 and 10.
- The design cited Java line ranges taken from a concatenated dump, not from the real files. → Corrected them.

## Hidden behaviour found

- **The Java service's port (8080)** comes from Spring Boot's default, not the code. No test pins it. Evidence: no
  `server.port` in `src/main/resources/application.yaml`. Now pinned by the default in `Application.kt`.
- **Log level INFO** is also a Spring Boot default that the code never states. Now pinned by `LoggingTest`.
- **The Java ArchUnit live test never proved the Spring-web half of its application rule**, because its fixture
  `UsesWeb` broke both halves at once (`ArchitectureRulesAreLiveTest.java:22-25`). This is a test gap, not a
  service defect.

## Surprises

- ktlint rejects the package name `adapter.in.web`, because `in` is a hard Kotlin keyword. Renamed to
  `adapter.inbound.web` (design Decision 7). Cause: the Java package name doesn't carry over to Kotlin.
- `git mv -k` on untracked files did nothing and still exited 0, so the first rename left the fixture behind. Cause:
  `-k` skips errors silently. Use plain `mv` for untracked files.
- `/code-review` diffs against `main`, which didn't exist in a fresh repo. Cause: the workflow assumes an existing
  history. Fixed with a baseline docs-only commit on `main`.
- Removing the empty-layer allowances fails all three ArchUnit rules, as expected. That confirms the shortcuts
  are needed, not speculative.

## Shortcuts taken

- `src/test/kotlin/com/paulrule/mathapi/architecture/ArchitectureTest.kt:24`: `withOptionalLayers(true)`. Limit:
  an empty layer passes the layered rule. Upgrade: remove it in `add-calculation` (item 3).
- `ArchitectureTest.kt:52`: `allowEmptyShould(true)` on the domain rule. Limit: a renamed or empty domain package
  passes silently. Upgrade: item 3.
- `ArchitectureTest.kt:65`: `allowEmptyShould(true)` on the application rule. Same limit. Upgrade: item 3.

## Lessons

- **Propose step:** cite Java lines from the real files (`grep -n` / `wc -l`), never from a concatenated dump.
  Two of the review findings came from this.
- **Live fixtures:** give each half of a rule its own fixture. A fixture that breaks two clauses proves neither.
  Apply this to the Jackson fixture in item 3.
- **Hidden defaults:** check for framework defaults (port, log level) as part of "propose", not in review. Add
  "which defaults does the Java framework apply here?" to each item's proposal.
- **Fresh repo:** create the baseline `main` commit before branching, so `/code-review` has a fixed point.
