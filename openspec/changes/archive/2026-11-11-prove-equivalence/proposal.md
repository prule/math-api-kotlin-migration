# Proposal

## Why

Items 3–5 ported every capability, and the contract suite (537 tests: ported Java assertions, probed framework
defaults, and 375 golden responses recorded from Java) passes against both services. Two things stop that
from being a proof that holds:
- CI never runs the suite, so the next change could break equivalence unseen;
- the Unknowns in MIGRATION-LOG still have no Kotlin answer, and some of them hide real differences. Probing
  showed Kotlin never closes an idle connection, while Tomcat closes it after 60 s.

## What Changes

- **CI** runs the contract suite against a freshly started Kotlin service on every PR and every push to `main`, as
  part of the `build` check. Java isn't needed there: the golden recordings plus the overlay carry the Java truth,
  and `isJavaReference` is false.
- **Connection behaviour** is set to Tomcat's, as Spring Boot configures it:
  - a 60 s read timeout, which closes idle keep-alive connections and stalled requests;
  - a 60 s write timeout;
  - a 30 s shutdown timeout, configured to mirror Spring Boot's graceful shutdown but not verified (see MIGRATION-LOG).
- **Golden file made install-independent:** both services send the webjar *file's* modification time as
  `Last-Modified`, which is different on every machine (CI downloads a fresh jar). Captures record
  `{last-modified}`, and conditional cases fill their dates from the service's own `Last-Modified` at send time.
  Found in plan review.
- **MIGRATION-LOG:**
  - every Unknown gets its Kotlin value;
  - Accepted difference 5 (no reason phrase in Tomcat's status line) is recorded by user decision;
  - a closing "Equivalence" section gives the evidence and its limits.
- **BACKLOG:** v1 is complete.

## Capabilities

### New Capabilities

None. CI, connection settings and docs only, so `skip_specs: true` is set.

### Modified Capabilities

None.

## Impact

- `.github/workflows/build.yml`, `Application.kt` (engine configuration), `docs/MIGRATION-LOG.md`,
  `docs/BACKLOG.md`, and the CLAUDE.md reference-service notes.
- CI takes longer, by about the time it takes to start the service plus the suite (around 30 s).
