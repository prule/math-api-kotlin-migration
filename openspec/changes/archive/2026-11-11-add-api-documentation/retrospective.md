# Retrospective: add-api-documentation

## Caught by review

**Plan review (ponytail):**
- The docs' server URL would have echoed the `Host` port raw. Java drops port 80 there too. → One shared
  `baseUrl(call)` for the actuator links, `servers` and `oauth2RedirectUrl`. `… Host port 80` golden cases pin it.

**Code review (ponytail, mine):**
- **Uncapped multi-range (security).** `Range: bytes=0-,0-,…` made Kotlin build a body of many copies of the
  file in memory. Java refuses more than 100 ranges, and several ranges adding up to the file size. → Both guards
  were ported, with a 101-range golden case and an in-process test. The spec review flagged it independently,
  because it probed the build from before the fix.
- `Range` on the first draft: a huge start crashed into a 500, and malformed or suffix ranges fell back to 200.
  → Full parsing as Spring does: suffix, multiple (`multipart/byteranges`), and 416 with Spring Boot's error JSON.
- The multipart boundary differed in length from Spring's (32 hex characters, where Spring uses 30 to 40 from
  `[-_0-9a-zA-Z]`). → Copied.

**Code review (`/code-review`, Standards):**
- Design Decision 5 still described Ktor's ConditionalHeaders and PartialContent, which were replaced during apply,
  and Decision 1 named a nonexistent `ApiDocs`. → Rewritten.
- Two MIGRATION-LOG rows filed known Kotlin behaviour under Unknowns. → Probing showed one isn't a difference
  (Java also ignores `If-None-Match` and `If-Range`: it sends no ETag). The other was fixed (`.txt` →
  `text/plain`, and every webjar file type is now covered). Both rows were removed, so no new accepted difference
  was needed.
- The 416 error JSON and multi-range had no spec requirement, and STANDARDS §3 didn't list the exceptions to
  Problem Details. → Two requirements were added, and §3 now lists every copied exception.
- STANDARDS §1 lacked the YAML and webjar dependencies. → Added.
- The webjar version was written in two places. → Read from the jar's `pom.properties`.
- Three copies of the OPTIONS block. → `optionsAllowingGetHead`.
- `description()` was a mysterious name, and the `toBody` KDoc named a missing parameter. → `openApiDocument()`,
  and the KDoc was fixed.
- Range parsing had no in-process test (§5). → `SwaggerUiRangesTest`.
- A loose citation (`OpenApiConfig.java:24-33` for the operand types). → `27-34`.
- **Not changed, with reasons:**
  - `readOnlyResource`'s `Any?` body (null = bare 404) stays as is: three body kinds and one sentinel don't earn a
    sealed type.
  - `baseUrl` stays next to the helper that all its callers use.
  - The two hand-written `index.html` `Accept` cases stay, because a loop over two values reads no better.

**Code review (`/code-review`, Spec):** paired probes. Every `Accept`, `Host` and method variant on the documents
matched.
- **Fixed:**
  - OPTIONS, POST and DELETE on an unknown Swagger UI file now return 404, as Java does: it looks up the file before
    the method;
  - RFC 850 and asctime dates in `If-Modified-Since` (Ktor parses only IMF-fixdate);
  - `If-Unmodified-Since` → 412;
  - empty range entries (`bytes=`, `bytes=0-1,`);
  - Spring's path normalisation: a trailing slash, `./` and `//` are dropped, and the transformed copy is served only
    at the exact path, otherwise the raw file;
  - the `/swagger-ui//` 404 detail.

## Hidden behaviour found

- `servers[0].url` and `oauth2RedirectUrl` are built from the `Host` header, with the port parsed as Tomcat does.
- Each document has exactly one media type. 406s follow the actuator rules.
- **Spring's reflected-file-download protection:** error bodies on paths with an unsafe extension get
  `Content-Disposition: inline;filename=f.txt`. It applies to every endpoint, so it is in `Problems.kt`.
  springdoc's own handler for its transformed files skips it on 405.
- **Springdoc rewrites two webjar files:** `index.html` (served unchanged, but `no-store`) and the initializer
  (two config lines added). Any other path to those files gets the raw webjar copy.
- Spring's resource handler: `Last-Modified`, 304, 412, single, suffix and multi ranges, 416 with Boot's error
  JSON and its two denial-of-service guards, and no ETag.

## Surprises

- **The YAML matched byte for byte on the first run** with swagger-core's three YAML flags. The template-plus-
  runtime-parts design made it possible: key order is kept by Jackson's ordered nodes.
- **`RFC_1123_DATE_TIME` writes `Sat, 7 Nov`**, while HTTP (and Java) write `Sat, 07 Nov`. It was caught before
  the golden run, from a format check.
- **A random multipart boundary** broke replays of Java against itself. Captures now normalise it to
  `{boundary}`, as they do `{base}` and `{timestamp}`.
- **The usage limit cut this item** between implementation and review. The work was checkpointed as a WIP
  commit and resumed the next day without loss.

## Shortcuts taken

None. The draft's single-range `shortcut:` was removed when full range support replaced it. `grep shortcut: src`
is empty.

## Lessons

- **Security review is part of equivalence.** "Copy the framework" includes copying its guards. Look for
  framework limits (range counts, body sizes) as hidden behaviour in every item, not only response shapes.
- **Static file serving is a large surface** (dates, ranges, normalisation, download protection). Budget for it
  as its own capability rather than as part of "docs".
- **Check Unknowns by probing before filing them.** Two "known differences" turned out to be no difference, or
  a one-line fix.
- **Checkpoint long items:** commit WIP on the branch at the end of apply, so an interruption costs nothing.
