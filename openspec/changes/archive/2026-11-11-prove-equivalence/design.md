# Design

## Context

See proposal.md. The contract suite needs `BASE_URL`. Its golden test compares the service with
`responses.json`, which was recorded from Java, after applying `accepted-differences.json` whenever the service
is not Java. Probed on 2026-11-11, both services answer HTTP/1.1 only (no h2c, no prior-knowledge HTTP/2), never
compress, and add no `Server` header. Tomcat closes an idle connection after 60 s; Ktor's Netty engine, out of the box, never does.

## Goals / Non-Goals

**Goals:**
- Equivalence is checked on every change, automatically.
- Every Unknown has a stated answer for both services.

**Non-Goals:**
- Running the Java service in CI. The Java repo is separate and frozen, and its recordings are the reference.
- Load or soak testing. That is out of the migration's scope; it is recorded as a limit of the proof.

## Decisions

1. **One CI job, extended:** after `./gradlew build`, start `PORT=8081 ./gradlew run` in the background, wait
   for `/actuator/health`, run `BASE_URL=http://localhost:8081 ./gradlew contractTest`, and upload the test
   report and `build/golden-actual.json` if it fails. The check is still called `build`, so CLAUDE.md's
   merge-on-green rule needs no change. Alternative: a separate job. Rejected: it would need a second check name
   and would compile everything twice.
2. **Engine settings in `Application.kt`:**
   - a Netty `IdleStateHandler(0, 0, 60)` with a small handler that closes the channel on the idle event: a
     connection with no traffic either way for 60 s closes, as Tomcat's does. A slow download that keeps moving is
     not cut. Changed during apply: `requestReadTimeoutSeconds` only covers reading a request, so a connection that
     never sent one stayed open;
   - `responseWriteTimeoutSeconds = 60`;
   - `shutdownTimeout = 30 s`, mirroring Spring Boot's graceful-shutdown phase timeout. Ktor's grace period stays
     at 1 s, so whether a long request in flight survives shutdown is not verified (MIGRATION-LOG).

   This is checked by probing, and recorded in the log: idle, keep-alive and never-requested connections close at
   60 s on both services, and a 10 KB/s download outlives 60 s on both.
   No automated test: a 60-second test isn't worth its cost in CI.
3. **The log's Unknowns table** keeps its three columns. A row whose answers now match moves to "Matched".
   The "Equivalence" section states:
   - what was compared: 537 tests, 375 golden cases, both services;
   - what wasn't: load, HTTP/2, TLS, and behaviour nobody probed;
   - the 5 accepted differences.

4. **Install-independent golden file** (found in plan review). `Last-Modified` is the webjar file's mtime on both
   services, so captures record `{last-modified}` when the value is a valid IMF-fixdate. Cases that need the
   date get it at send time from a HEAD of the same path, in the format they test (`{last-modified}`,
   `{last-modified:rfc850}`, `{last-modified:asctime}`). The "equal date" cases still assert 304 and 200.

## Risks / Trade-offs

- [The service takes longer to start on CI runners] → Wait up to 60 s for health before failing.
- [A golden case depends on this machine] → `Last-Modified` was the only one, handled by Decision 4. CI is the check.
