# Design

## Context

See proposal.md (Why) and the spec. Java's springdoc builds the description at runtime:
- from `@ApiResponse` annotations (`CalculationController.java:25-53`);
- plus `OpenApiConfig` (operation enum, `[number, string]` operand types);
- plus `ProblemExamples` (it removes ProblemDetail's `properties`, and adds one example per problem, built by
  the advice itself).

springdoc also serves the Swagger UI webjar, rewriting `swagger-initializer.js`. The probed responses are in the
golden file (about 80 cases).

## Goals / Non-Goals

**Goals:**
- Byte-identical YAML and the same JSON for the same `Host`.
- The same webjar files, headers and caching.
- No drift: values that come from code stay generated from code.

**Non-Goals:**
- A code-first OpenAPI generator. Nothing in Kotlin has annotations to read, and the description has one
  operation.

## Decisions

1. **A JSON template plus runtime parts, mirroring Java's customisers.** `openapi/openapi.json` is the Java
   service's output with three parts taken out, because Java computes them at runtime too:
   - `servers`;
   - the operation `enum`;
   - the error `examples`.

   `openApiDocument(baseUrl)` fills them in for each request:
   - `servers` from `Host`;
   - `enum` from `Operation.entries` (as `OpenApiConfig`);
   - examples from `invalidRequest()` and `domainProblem(...)`, with the real domain exception messages (as
     `ProblemExamples`).

   Alternatives:
   - a fully hand-written file: rejected, because the enum and examples would drift;
   - a Kotlin OpenAPI DSL: rejected, because it is more code than the template and the output text would differ.
2. **Key order is kept.** The template is read into Jackson's ordered `ObjectNode`s, and `servers` is put back
   between `info` and `paths`. That matters only for the YAML, which is compared as text.
3. **YAML with swagger-core's settings:** `jackson-dataformat-yaml` 2.22.2 with `MINIMIZE_QUOTES` on,
   `WRITE_DOC_START_MARKER` off, and `ALWAYS_QUOTE_NUMBERS_AS_STRINGS` on. The golden case is the judge: tune the
   flags until the text is identical.
4. **One shared helper for read-only negotiated resources** (`ReadOnlyResource.kt`), lifted from item 4's
   actuator helper: a path, its producible types, and a body function. GET and HEAD negotiate first, `OPTIONS`
   lists the methods, and anything else is 405 with `Allow: GET`. The actuator, the two descriptions and
   swagger-config all use it.
5. **Swagger UI:**
   - A `GET`/`HEAD` route on `/swagger-ui/{file...}` reads `META-INF/resources/webjars/swagger-ui/5.34.0/<file>`
     from the classpath. There is no negotiation (`Accept` is ignored).
   - The content type comes from the extension: html, javascript, css and png, as Java sends them.
   - `swagger-initializer.js` gets springdoc's two config lines inserted after `layout: "StandaloneLayout"`. It
     and `index.html` are `no-store`.
   - Other files carry the jar entry's `Last-Modified`.
   - Conditional requests and ranges are handled in the route, not by Ktor's `ConditionalHeaders` and
     `PartialContent` plugins (changed during apply: item 4 showed Ktor plugins act on every response, and the
     probed Spring rules are short). The route does `If-Modified-Since` (304) and `If-Unmodified-Since` (412) in
     all three HTTP date formats, plus single, suffix and multiple ranges, and 416 with Spring Boot's error JSON.
     It applies Spring's guards: at most 100 ranges, and several ranges must add up to less than the file.
     `If-None-Match` and `If-Range` are ignored, as in Java (no ETag).
   - `OPTIONS` lists `GET,HEAD,OPTIONS`, and other methods are 405 `Allow: GET, HEAD`.
   - A missing file is a 404 problem with the path relative to `/swagger-ui/`.
6. **The redirect** `/swagger-ui.html` → `302 /swagger-ui/index.html` keeps the raw query string.

7. **One `baseUrl(call)`** for the actuator links, `servers[0].url` and `oauth2RedirectUrl`, moved out of item
   4's `links()`. It parses `Host` as Tomcat does: port 80 dropped, no digits read as 0, the local name when
   there is no `Host`. Found in plan review; golden cases `… Host port 80` pin it.

8. **Found in code review:**
    - **Fixed:** an uncapped multi-range request could make the server build a huge body; unknown files now get
      404 for every method; RFC 850 and asctime dates; `If-Unmodified-Since`; empty range entries; Spring's path
      normalisation (only the exact path gets the transformed copy); `.txt` as `text/plain`; Spring's
      reflected-file-download `Content-Disposition` on error bodies (in `Problems.kt`, so it covers every endpoint).
    - **Refactors:** the webjar version is read from the jar's `pom.properties` (one place to upgrade); one
      `optionsAllowingGetHead`.

## Risks / Trade-offs

- [Jackson's YAML output drifts from swagger-core's on an upgrade] → The golden raw-text case fails loudly.
  Pin the version.
- [The jar entry time is read in the JVM's time zone] → Both services run on the same machine and read the same
  jar, so the golden `Last-Modified` matches. Deployed elsewhere, the header could differ by the time-zone
  offset. Recorded under Unknowns.
- [Spring's resource handler has rules nobody probed] → Each probed rule is a golden case (about 100 for Swagger UI).
