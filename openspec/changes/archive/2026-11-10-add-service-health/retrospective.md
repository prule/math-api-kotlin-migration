# Retrospective: add-service-health

## Caught by review

**Plan review (ponytail):**
- The JDK `HttpClient` silently drops a custom `Host` header, so the "links follow Host" golden case would have
  recorded `localhost:8080` and proved nothing. → The `contractTest` task sets
  `jdk.httpclient.allowRestrictedHeaders=host`, and the recorded link was checked to show `example.org:9999`.

**Code review (ponytail, my own paired probes):**
- An HTTP/1.0 request with no `Host` produced links to `http://null/actuator`. → It falls back to the local
  name and port, as Tomcat does.
- Duplicated 405 handlers. → `methodNotAllowed`.

**Code review (`/code-review`, Standards):**
- The new no-`Host` text was filed as an accepted difference without the user's agreement. → It was put to the
  user with the other new cases, and they agreed.
- Two spec requirements cited "the cases recorded in this change" instead of naming them. → They now name the
  golden cases.
- The `SpringFormattedBytes` KDoc claimed HEAD handling it doesn't do (§6). → Moved to its own file with a
  correct KDoc. `bodyUnlessHead` now holds the HEAD rule, shared by problems and the actuator, which also removes
  the duplicated `if HEAD`.
- `HealthRoutesTest` used a `Triple` and two cases in one test (§5). → A small `Result` class and parameterised tests.
- The group names were written twice. → `GROUPS`. The inline call chain became a named `segments` value.

**Code review (`/code-review`, Spec):** about 120 paired probes.
- **Quality before specificity:** Java orders `Accept` types by quality first, and the shared `MediaType.select`
  (from item 3) used specificity first. Five probed rows differed, two of them on the calculations endpoint,
  where the bug was already on `main`. → Fixed. Both specs now say quality first, and 5 golden cases pin it.
- **Host ports:** Java drops port 80 (and `:0080`) and reads an empty port as 0. → Fixed, with 4 golden cases.
- **Accepted differences extended**, agreed with the user in one batched question:
  - Java's 500 for empty `Accept` entries on the actuator (source defect 5);
  - actuator path parameters, encoded slashes and invalid `Host`;
  - TRACE on other actuator paths.
- Design Decision 5 claimed the empty 404 needs no marker. → Corrected (see Surprises).

## Hidden behaviour found

All of it is Spring Boot Actuator defaults, with no Java code behind it:
- three actuator media types, with v3 as the default and no `*+json` echo;
- a bodiless 406 that, unlike the calculations endpoint, keeps its `Accept` header;
- negotiation happens before the unknown-group 404;
- trailing slashes are accepted on health paths but not on `/actuator`;
- `OPTIONS` answers `GET,HEAD,OPTIONS`;
- the index links come from the raw `Host` header, as Tomcat parses it, and `X-Forwarded-*` is ignored;
- `Accept` is ordered by quality first, which also corrected item 3.

## Surprises

- **StatusPages intercepts every 404 response**, not only unrouted requests, so it replaced the deliberate
  empty 404 with a problem. Cause: Ktor `status()` handlers run on any response with that status. Fixed with a
  `BARE_RESPONSE` call attribute. `HealthRoutesTest.an unknown group is an empty 404` caught it before the
  contract suite did.
- **All 49 new health golden cases matched on the first run against Kotlin.** That's the payoff of item 3's
  `MediaType`, `respondProblem` and the probe-first habit.
- **The no-`Host` fallback name depends on reverse DNS**, so it can't be pinned (`localhost` against
  `ip6-localhost`).
- Edits to Spotless-formatted code kept failing to match their original text. Cause: Spotless rewraps long
  expressions after the first write. Read the file before editing it a second time.

## Shortcuts taken

None. `grep shortcut: src` is empty.

## Lessons

- **Paired probes, again:** they found the quality-order bug, which item 3's single-service probes and three
  reviews all missed. Next time, probe each rule with values that pull two ordering keys against each other
  (quality against specificity), not only one at a time.
- **Shared code needs its old callers re-checked.** Changing `MediaType.select` for the actuator exposed a latent
  calculation bug. The calculation golden cases didn't catch it, because none of them pitted quality against
  specificity. When shared logic changes, add golden cases for the old caller too.
- **Ktor plugins act on responses, not just on routing.** Check StatusPages and similar plugins against every
  deliberate non-standard response.
- **Accepted differences:** a new case in an agreed category still needs the user's yes. Batch them into one
  question at review time, as here.
