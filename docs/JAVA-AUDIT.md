# Audit of the Java source (gate before item 1)

Run 2026-11-10 with `/ponytail:ponytail-audit` on `../mathi-api-one-agent-2`, code only. Each finding was checked
with curl against the running Java service. Backlog item 2 moves the defects into `docs/MIGRATION-LOG.md` →
**Source defects**. **Port them faithfully. Fix them only after the migration.**

**What the repo does:** a stateless REST API that does exact decimal arithmetic (add, subtract, multiply,
divide) on numbers sent as JSON. It has one endpoint, RFC 9457 error bodies, a health check and OpenAPI docs.
Assumed load: a small public API with many independent callers and no stored state.

## Should fix

1. **The size limit counts bytes, but the error says characters** (`src/main/resources/application.yaml:58`,
   `ProblemDetailsAdvice.java:218-220`)
   - **What this is:** Jackson stops reading a body longer than 16,384 units, and the error text promises
     "at most 16,384 characters".
   - **Problem:** Spring hands Jackson raw bytes, so the limit is 16,384 **bytes**. A body of 7,295 characters
     (21,695 UTF-8 bytes, because of `€` in an extra field) is rejected with that message.
   - **Fix:** Change the wording to "16,384 bytes" (one string).
   - **If we skip it:** A client sending non-ASCII text is told it broke a limit it did not break.

2. **`operation` accepts numbers and booleans** (`CalculationRequest.java:165-168`)
   - **What this is:** `operation` is a `String`, and Jackson's default scalar coercion turns `5` into `"5"`
     and `true` into `"true"`.
   - **Problem:** `{"operation":5,…}` returns 400 `unknown-operation` ("Unknown operation '5'"), but
     `{"a":true,…}` returns `invalid-request`. The two fields break the same kind of rule (wrong JSON type) and
     get different errors.
   - **Fix:** Turn off scalar-to-string coercion for `String` (`spring.jackson` coercion config or
     `@JsonFormat`), so a non-string `operation` becomes `invalid-request`.
   - **If we skip it:** Clients get a misleading error type. The impact is low.

3. **Duplicate keys are accepted silently** (Jackson default; no config)
   - **What this is:** JSON with the same key twice.
   - **Problem:** `{"operation":"ADD","a":1,"a":9,"b":2}` returns `a = 9, result 11`. A client bug that sends
     two values is hidden.
   - **Fix:** Turn on `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`. Duplicates then become `invalid-request`.
   - **If we skip it:** Rare wrong results caused by client bugs. The impact is low.

## Hidden behaviour (not defects, but the port must keep it)

These all come from Spring/Jackson defaults, are confirmed by curl, and no test asserts them:
- Unknown extra fields are ignored (`"extra":1` → 200).
- Operand strings are trimmed (`" 1 "` → `"1"`) and a leading `+` is accepted (`"+1"` → `"1"`).
- A string exponent is accepted and echoed in plain notation (`"1e1"` → `"10"`).

**Verdict:** healthy. Nothing breaks at the expected load. The three findings are input-handling details. Fix
them after the migration, so the equivalence proof stays clean.

`PerformCalculation` only passes the call through (YAGNI), but it is kept on purpose, because the clean
architecture is a goal of the migration.

**Not checked:** behaviour under real concurrent load (none of the code holds shared state, so none was expected),
and the `build/` output. Each finding was confirmed against the running service on port 8080.
