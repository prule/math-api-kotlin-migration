# Retrospective: add-calculation

## Caught by review

**Plan review (ponytail):**
- A stale overlay entry would be silently ignored. → Building the corpus now fails if an overlay key names no case.
- A large body might end in a reset instead of a 400. → A 1 MiB golden case and a contract test were added. It
  really did hang, see Surprises.

**Code review (ponytail):**
- **An unparseable `Accept` should strip every response body.** Java answers 400/404/405/415/422 with no body
  and no content type when `Accept` is `garbage`. → `respondProblem` now does the same. Seven golden cases and a
  contract test cover it. This also removed `UnreadableAcceptException`.
- **Quoted parameters were split wrongly** (`boundary="a;b"`, `x="1,2"`). → Splitting now respects quotes. Covered by
  golden cases, contract tests and `MediaTypeTest`.

**Code review (`/code-review`, Standards):**
- STANDARDS.md §1 said Jackson is "the version Ktor brings", but `ktor-server-core` brings none. → Now states the
  pinned 2.22.2.
- Spec citations: `Error precedence` cited `Calculation.java:21-30`, which leaves out the constructor's range
  checks. → Now `11-22`. `ProblemDetailsAdvice` → `82-91`.
- Duplicated domain handlers in `Problems.kt`. → A `domainProblem` helper, as Java's `problem(...)`.
- The sort in `MediaType.select` changed nothing. → Replaced by the specificity order Spring really uses.
- `readRequest` returned nullable fields, so the route needed `!!`. → It now returns a non-null `Triple`.
- The strict-JSON bodies were copied between the contract test and the corpus. → Shared through
  `FrameworkDefaultsContractTest.strictInvalidBodies()`.
- An unclear comment on the catch-all test. → Rewritten.
- Design Decision 4's file name and task 3.1's `config/Module.kt` were stale. → Fixed.
- `OperationTest`'s four single-operation tests could be one parameterised test. → **Kept on purpose:** they port
  the Java tests one to one, and a one-to-one port is the point of the domain tests.

**Code review (`/code-review`, Spec):** about 150 paired curl probes against both services.
- **Fixed, with golden cases:**
  - no body and no Content-Type is invalid-request, not 415;
  - `Accept` specificity order (`application/*;q=1, application/vnd.c+json` gives the vendor type);
  - an empty `Accept` entry (`,`);
  - a valueless parameter (`application/json;foo`);
  - parameter names lowercased (`Charset`);
  - an unknown `charset` is invalid in both headers;
  - a declared Latin-1 body is decoded as Latin-1.
- **Accepted differences, agreed with the user:**
  - 2: a wildcard Content-Type gets 415 (Java answers 500, now source defect 4);
  - 3: Tomcat path normalisation (`//` and `;jsessionid`) is not copied;
  - 4: the OPTIONS/TRACE extras (CORS-preflight `Allow` list, TRACE error JSON, empty `Accept-Patch`) are not copied.
- Two spec lines were wrong about Java: "Errors SHALL ignore `Accept`" and the missing-content-type scenario.
  → Corrected, and 4 requirements added (28 in total).

## Hidden behaviour found

- **Found while probing for the proposal (RB-27 to RB-30):**
  - Spring echoes `application/vnd.a+json` and `application/problem+json` as the success content type;
  - `q=0` doesn't exclude a type;
  - the 415 detail adds `;charset=UTF-8`;
  - Jackson 3 rejects trailing tokens and caps nesting at 500 levels.
- **Found by code review:**
  - the bodiless responses for an unparseable `Accept`;
  - the specificity sort;
  - charset validation and decoding;
  - the empty-body rule;
  - and the Java 500 for wildcard content types (a defect).
- **Found while applying:**
  - Tomcat drains up to 2 MiB of a rejected body before replying, which Java clients rely on without knowing it.

## Surprises

- **ArchUnit rejected the first wiring** (`config/Module.kt` calling the web routes). Cause: Spring's container,
  outside every layer, found the controller by scanning, so the Java rule never had to allow config → web. Fixed
  without weakening the rule: the root `Application.kt` is now the composition root (STANDARDS.md §2).
- **The contract suite hung on the 1 MiB body.** Kotlin replied after 16 KiB, and the JDK client won't read a
  response until it has sent its whole body. Cause: no drain (Tomcat has one built in). Fixed by draining up to
  2 MiB on every POST error. A second bug then hid the fix: Jackson's `AUTO_CLOSE_SOURCE` closed the stream on the
  parse failure, so the drain read nothing. Both are fixed (design Decision 10).
- Ktor writes `application/json; charset=utf-8` (with a space). Fixed with a small `OutgoingContent` carrying the
  exact header.
- Kotlin allows nested block comments, so `application/*` inside a KDoc opened a second comment. The compiler
  reported "Unclosed comment" 20 lines later.
- Java's TRACE response carries a `timestamp`, so its golden case would never replay equal. Captures now
  normalise `timestamp`.
- The Java `rejectsMissingComponents` test has no Kotlin port. Non-null types make that case a compile error.

## Shortcuts taken

None added. Item 1's three empty-layer shortcuts were removed (task 3.3). `grep shortcut: src` is empty.

## Lessons

- **Propose step:** probing found 4 RB rows, but code review's paired probes found about 12 more differences.
  Probe **pairs** (the same request to both services, diffed) during apply, not just Java alone. Each probe
  becomes a golden case before the fix. Do this for items 4 and 5.
- **Framework defaults carry hidden infrastructure behaviour** (drain size, header formatting, path
  normalisation), not just API behaviour. For each remaining item, ask which container behaviour the Java
  service gets for free.
- **Accepted differences need the user early.** Batch them into one question with a recommendation, as in this
  item, instead of one question each.
- **ArchUnit:** when a rule fails during a port, find the Java mechanism that made the rule hold (component scan)
  and port that mechanism, not a looser rule.
