# Retrospective: prove-equivalence

## Caught by review

**Plan review (ponytail):**
- The design assumed the only risk to the golden file on CI was a time zone. Checking showed both services send
  the webjar **file's** modification time (when Gradle downloaded it) as `Last-Modified`. On a fresh CI runner,
  about 20 golden cases would have failed for reasons that have nothing to do with equivalence. → Captures record
  `{last-modified}`, and conditional cases fill their dates from the service's own `Last-Modified` at send time,
  in all three HTTP formats. Item 5's log row about the time zone was wrong about the cause and is corrected.

**Code review (ponytail):** no findings. Concurrent Gradle runs, the `Host` permission in CI, and the log's
arithmetic were all checked.

**Code review (`/code-review`, Standards):**
- The log said `api-documentation` has 15 requirements; it has 16. → Corrected. Item 5's commit message and PR
  said 15 too: that miscount came from before the review added a requirement.
- design.md still described `requestReadTimeoutSeconds`, a `TZ` workaround, and "Netty never" closes idle
  connections. → Rewritten to match the code.
- Task 3.2 was ticked "mark v1 complete" before archive. → Done at archive.
- CI: `-q` hid the startup log that is dumped on a health timeout, and the upload also fired when the build failed.
  → Full output, and the upload now runs only when the contract step fails.
- `withDates`: placeholder formats were rebuilt on every call, there was a bare `!!`, and the name was unclear.
  → One companion `DATE_FORMATS` map, a clear `checkNotNull` message, and the name `withLastModifiedFilledIn`.

**Code review (`/code-review`, Spec):** confirmed the idle timeout by probing: idle, keep-alive and never-requested
connections close at 60 s on both services, and a 10 KB/s download outlives 60 s on both.
- Task 2.1 was ticked before any CI run. → Unticked until this PR's run executed `contractTest` (3 tasks
  executed, not from cache), then ticked.
- The proposal overstated graceful shutdown ("finishes in-flight requests for up to 30 s"). Only the timeout is
  set, and Ktor's grace period stays at 1 s. → Reworded as configured but not verified, which the log already said.
- The golden placeholder change wasn't in the proposal or design. → Recorded as design Decision 4.

## Hidden behaviour found

- Tomcat closes a connection after 60 s with no traffic. Ktor's Netty engine, out of the box, never does, and
  `requestReadTimeoutSeconds` only covers reading a request.
- `Last-Modified` on Swagger UI files is the jar **file's** mtime on both services. It is not the entry time.
- Tomcat writes status lines without a reason phrase (accepted difference 5).
- Neither service offers HTTP/2, compression or a `Server` header.

## Surprises

- The first timeout attempt (`requestReadTimeoutSeconds = 60`) changed nothing for idle connections. A 70-second
  probe showed it. Netty's `IdleStateHandler` with all-idle semantics was the right tool, and unlike a read
  timeout it doesn't cut a slow download.
- The CI run was the first time the suite ran on a machine other than this one. It passed first time, which
  confirms the placeholder change.

## Shortcuts taken

None.

## Lessons

- **An install-time value is not a constant.** Any recorded value that comes from the environment (file times,
  host names, ports) must be a placeholder before the suite leaves the machine it was recorded on. Check the
  golden file for these before the first CI run, not after.
- **Probe connection-level behaviour, not just request/response:** idle timeouts, keep-alive, the status line.
  The golden file can't see them, and that is where the last real difference (idle connections) was hiding.
- **Don't tick a task whose check is a future event** (a CI run). Leave it open and tick it with the evidence.
