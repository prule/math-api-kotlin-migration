# Proposal

## Why

The calculation endpoint is the service's whole reason to exist, and none of it is in Kotlin yet. Its contract
tests (item 2) are green against Java and red against Kotlin. This change ports the endpoint so they pass against
both. It also turns RB-1 to RB-14 and the calculation parts of RB-22 to RB-30 into the first recovered spec.

## What Changes

- Port the domain:
  - `Operation`: closed set, exact lookup, `apply`;
  - `Calculation`: operand limits, normalised result;
  - the three domain exceptions.
- Port the application use case: `PerformCalculation`.
- Port the web adapter with Ktor:
  - `POST /api/v1/calculations` and `OPTIONS` on it;
  - 405 for every other method;
  - RFC 9457 Problem Details for every error, with Spring's framework wording copied;
  - Spring-compatible content negotiation;
  - strict Jackson parsing with the same stream limits.
- Wire it in `config`, and remove item 1's three empty-layer `shortcut:` allowances. Add an ArchUnit live fixture for
  the domain rule's Jackson ban.
- **Accepted difference (user decision):** no YAML. YAML requests get 415, and `Accept: application/yaml` gets 406.
  The contract tests branch on the service. Golden comparisons against Kotlin apply
  `golden/accepted-differences.json` on top of the Java recordings.
- Extend the contract suite and golden corpus with the RB-27 to RB-30 cases probed for this item. Re-record from Java.

## Capabilities

### New Capabilities

- `calculation`: performing an exact decimal calculation over HTTP: request parsing and limits, the four
  operations, normalisation, error precedence, Problem Details errors, and content negotiation for the endpoint.

### Modified Capabilities

None.

## Impact

- New main code in all four layers. `Application.kt` delegates to `config`.
- New dependencies: `jackson-module-kotlin` and `jackson-databind` 2.22.2 (the versions `ktor-serialization-jackson`
  uses). Ktor `status-pages`. No `ContentNegotiation` plugin (design Decision 2).
- Contract suite: an `isJavaReference` helper, branching YAML tests, new RB-27 to RB-30 tests, an accepted-differences
  overlay, and a re-recorded golden file.
- Docs: MIGRATION-PLAN (RB-27 to RB-30), MIGRATION-LOG (accepted difference 1), CLAUDE.md and the OpenSpec config
  (the evidence rule now accepts observations of the Java service), and STANDARDS.md if a rule changes.
