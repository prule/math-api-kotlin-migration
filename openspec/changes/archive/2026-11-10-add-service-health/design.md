# Design

## Context

See proposal.md (Why) and the spec deltas. In Java the whole capability is Spring Boot Actuator's defaults: one
dependency (`build.gradle.kts:25`) and no configuration, so only `health` is exposed over HTTP, without details.
Item 3 already built the parts this needs: Spring-compatible media types (`MediaType`), Problem Details with the
bodiless-for-unparseable-`Accept` rule (`respondProblem`), and the generic 404.

## Goals / Non-Goals

**Goals:**
- Every probed actuator response is matched, and pinned by a golden case.

**Non-Goals:**
- Real health checks or readiness state. The Java service has no health contributors, so it always reports UP.
  What it reports when degraded or shutting down is an Unknown (MIGRATION-LOG).
- A domain or application layer for health: it has no business rules.

## Decisions

1. **One file, `adapter/inbound/web/HealthRoutes.kt`**, with three routes:
   - `/actuator` (index);
   - `/actuator/health`;
   - `/actuator/health/{path...}` (groups, a trailing slash, and the empty 404).

   Each route has `get`, `head`, `options` and a `handle` that answers 405 with `Allow: GET`. Alternative:
   Ktor's `AutoHeadResponse` plugin. Rejected: it would also change `HEAD` on the calculations endpoint, which
   must stay 405.
2. **Negotiate before routing to the group.** Each GET or HEAD first selects the response type from `Accept`
   (406 otherwise), then looks up the group, so `/actuator/health/nope` with a bad `Accept` gives 406, as in Java.
3. **`MediaType.select(accepted, producible)`.** The producible list becomes a parameter. Calculations pass
   `[application/json, application/*+json]`; the actuator passes `[v3, v2, application/json]`. The sort becomes
   quality (higher first), then specificity, then `Accept` order. The first version had specificity first, and
   code review's paired probes showed Java puts quality first (`*/*;q=0.9, application/vnd.a+json;q=0.1` gives
   JSON). That bug was already on `main` for calculations.
4. **Links from the `Host` header.** The contract suite must be able to send one: `Host` is restricted in the JDK
   client, so the `contractTest` task sets `jdk.httpclient.allowRestrictedHeaders=host` (found in plan review).
   Links are `http://` and the Host's name and port, parsed as Tomcat does (port 80 dropped), then the path. The Java service ignores `X-Forwarded-*`
   (Spring Boot's default forward-headers strategy is none), so Kotlin reads only `Host`.
5. **The empty 404** for an unknown group is a direct `respondBytes(empty, status = 404)`, not a problem.
   StatusPages' `status(NotFound)` handler intercepts every 404 response, so the route marks it with a
   `BARE_RESPONSE` call attribute, which the handler respects (found in apply).
6. **Path normalisation.** `/actuator//health` is 404 in Java (Tomcat). If Ktor collapses it, it joins Accepted
   difference 3, like the calculations double slash.

7. **Found in code review:**
    - **Fixed:** quality-first ordering (Decision 3); Tomcat's Host port parsing in the links; a fallback name for
      a request with no `Host`.
    - **Accepted differences 2 to 4 extended, with the user's agreement:** Java's 500 for empty `Accept` entries;
      actuator path parameters, encoded slashes and invalid `Host`; TRACE on other actuator paths.
    - **Refactors:** `SpringFormattedBytes` got its own file, with `bodyUnlessHead` shared by problems and the
      actuator; `methodNotAllowed` is shared; the group names are declared once.

## Risks / Trade-offs

- [`{path...}` matches `/actuator/health` itself] → The tailcard route only serves non-empty group paths. An
  empty path (`/actuator/health/`) gives the health body. Golden cases pin `/actuator/health`, `/actuator/health/`
  and `/actuator/health/liveness/`.
- [The quality sort changes calculation answers] → Re-run the calculation golden cases: none may change.
