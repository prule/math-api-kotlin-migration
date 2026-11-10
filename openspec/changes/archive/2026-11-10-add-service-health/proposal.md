# Proposal

## Why

Load balancers and orchestrators probe the Java service at `/actuator/health`, `/actuator/health/liveness` and
`/readiness`. The Kotlin service answers all of them with 404, so it can't be deployed. In Java this capability
is nothing but Spring Boot Actuator defaults (one dependency, no code). So, as with item 3, every rule has to be
read off the wire.

## What Changes

- Serve the actuator surface the Java service exposes, and nothing more:
  - `GET /actuator` (links);
  - `GET /actuator/health` (UP plus groups);
  - `GET /actuator/health/liveness` and `/readiness`;
  - an empty 404 for any other group path.
- Copy the actuator's own content negotiation: v3 (default), v2 and plain JSON types; a 406 problem listing them;
  and a bodiless 406 that keeps its `Accept` header for an unparseable `Accept`.
- Methods: `GET`, `HEAD` (no body), `OPTIONS` (`Allow: GET,HEAD,OPTIONS`), and 405 with `Allow: GET` for anything else.
- Order equally specific `Accept` types by quality in the shared `MediaType.select`. Probing showed Spring does
  this, and item 3 missed it.
- Add golden cases and contract tests for each probed rule, recorded from Java.
- Record the actuator Unknowns (health when degraded, readiness during shutdown) and the container cases that
  join Accepted differences 3 and 4 (a missing `Host`, `/actuator//health`, TRACE).

## Capabilities

### New Capabilities

- `service-health`: the operational endpoints: health status and groups, the actuator index, and their media
  types and methods. Nothing else is exposed.

### Modified Capabilities

- `calculation`: `Response content type` now orders equally specific `Accept` types by quality. The behaviour
  doesn't change for any recorded calculation case. This tightens the requirement to what probing showed.

## Impact

- New `adapter/inbound/web/HealthRoutes.kt`, wired in `Application.kt`. `MediaType.select` takes the producible
  list as a parameter.
- Contract suite: health negotiation tests, and about 30 new golden cases. The Java recordings are re-recorded.
- No domain or application code: health has no business rules. It reports the process as up.
