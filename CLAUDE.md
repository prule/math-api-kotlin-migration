# math-api (Kotlin migration)

This repo migrates `../mathi-api-one-agent-2`, a stateless Spring Boot 4 / Java 25 REST API for exact decimal
arithmetic, to Kotlin + Ktor. It keeps the same clean architecture and the same behaviour on the wire.

**The exercise:** the Java **code** is the only spec. You may read its `src/`, `build.gradle.kts`,
`settings.gradle.kts`, `src/main/resources/` and `.github/`. Never read its `docs/`, `openspec/`, `reports/`,
`logs/`, `README.md`, `CLAUDE.md` or git history; treat them as if they don't exist. The one exception is the
last backlog item, which compares results with them. Comments in the Java code that cite `BR-n` or `PRD §x` are
hints that a rule exists. They are not the spec.

Read before working:
- `MIGRATION-PLAN.md`: the approach, recovered behaviour seed (RB-n), decisions, hard spots
- `docs/BACKLOG.md`: ordered OpenSpec changes; update an item's status when you start or archive it
- `docs/STANDARDS.md`: Kotlin standards (written by backlog item 1)
- `docs/MIGRATION-LOG.md`: unknowns, accepted differences, and source defects (written by backlog item 2)

## Where things live

- **Requirements:** `openspec/specs/<capability>/spec.md`, written from the Java code. They are the source of truth.
- **Everything that is not a requirement:** `docs/MIGRATION-LOG.md`, in three sections:
  - **Unknowns:** behaviour the code doesn't settle (auth, CORS, port, logging format).
  - **Accepted differences:** each place the Kotlin service behaves differently from Java, with the reason.
  - **Source defects:** bugs found in the Java code. Port them faithfully; fix them only after the migration, as new backlog items.

## Evidence rule

Every requirement in a spec delta cites its evidence: a Java `path:line`, a Java test method, or, for behaviour
that comes from framework defaults, an observation of the running Java service (a golden case in
`src/contractTest/resources/golden/responses.json`, or a contract test that is green against Java). A requirement
with no evidence is not allowed; record it under **Unknowns** instead. Never invent behaviour the Java code
doesn't show. The only exception is backlog item 7, which is new behaviour and says so.

## Reference service

The contract suite runs against both services over HTTP:
- **Java (reference):** `cd ../mathi-api-one-agent-2 && ./gradlew bootRun` (runs in the background, port 8080).
- **Kotlin:** `PORT=8081 ./gradlew run`. The default is 8080, the same as Java; 8081 lets both run at once.
- `BASE_URL=http://localhost:8080 ./gradlew contractTest` runs the suite against Java;
  `BASE_URL=http://localhost:8081 ./gradlew contractTest` runs it against Kotlin.

- `BASE_URL=http://localhost:8080 ./gradlew contractTest -Precord --tests '*Golden*'` re-records
  `src/contractTest/resources/golden/responses.json` from Java. It refuses any other service. Re-record only
  when the corpus changes, and review the golden diff like code.

CI runs the contract suite against the Kotlin service on every PR (the `build` check). A golden case or contract
test that fails there is a broken equivalence, not a flaky test: fix Kotlin, or agree an accepted difference.

Never edit the Java repo.

## Keep the docs current

Any change that alters scope, the API contract, a capability, or the engineering standards MUST update the
matching file (`openspec/specs`, `docs/STANDARDS.md`, `docs/MIGRATION-LOG.md`, `MIGRATION-PLAN.md`) in the same
change. Never renumber or reuse an RB id.

## Change workflow

Every change (usually one `docs/BACKLOG.md` item) goes through these steps in order. Run each step yourself,
without waiting to be asked. Stop only where a step needs the user's decision: a material ambiguity while
proposing, or a choice a review raises.

1. **Propose:** run `/opsx:propose` with the item's prompt from `docs/BACKLOG.md`. Done when every planning
   artifact exists, every requirement meets the evidence rule, and `openspec validate <change> --strict` passes.
2. **Review the plan:** run `/ponytail:ponytail-review` on the change's artifacts, checked against the Java code
   they cite. Apply every review point. Done when the artifacts reflect each one and strict validation passes again.
3. **Apply:** create branch `feat/<change>` from an up-to-date `main`, set the item to In progress in
   `docs/BACKLOG.md`, then run `/opsx:apply`. Done when every task is checked, `./gradlew clean build` passes
   (tests, ArchUnit, Spotless), and the item's contract tests pass against **both** services. Item 7 is the one
   exception: it may change only what its spec says it changes. Fix formatting with `./gradlew spotlessApply`.
   Never weaken an ArchUnit rule, a unit test or a contract test to get green. A contract test that fails against
   Kotlin means Kotlin is wrong, unless the difference goes into **Accepted differences** with the user's agreement.
4. **Review the code:** run both reviews on the uncommitted diff, then apply every point from either, each with a
   test that fails without it, and rerun `./gradlew build`.
   - `/ponytail:ponytail-review` on the diff and the code it touches: is it correct, safe, tested and minimal?
   - `/code-review` on the change since `main` (`main` is the starting point it diffs from, not what it reviews),
     with `openspec/changes/<change>/` as the spec: does it follow `docs/STANDARDS.md` and these rules, and meet
     every scenario in the spec delta?
5. **Archive:** run `/opsx:archive`. It syncs the delta specs into `openspec/specs/` (check with
   `openspec validate <capability> --type spec --strict`) and moves the change into `openspec/changes/archive/`.
   Set the item to Done in `docs/BACKLOG.md`.
6. **Retrospective:** run `/ponytail:ponytail-debt`, then write `retrospective.md` in the archived change's folder
   with five sections:
   - **Caught by review:** each finding from both reviews and how it was fixed.
   - **Hidden behaviour found:** each behaviour the code showed only when tested or run, not when read (for
     example, framework defaults). Give its evidence and the test that now pins it.
   - **Surprises:** each failed check, spec deviation or bug found during apply or archive, with its cause, or
     "cause unknown".
   - **Shortcuts taken:** each `shortcut:` comment this change added, with its file, its limit and when to upgrade.
   - **Lessons:** what to do differently next time, each naming the step, file or habit it changes.

   Write "None" under a section with nothing to report.
7. **Commit and PR:** commit on `feat/<change>` with a Conventional Commits message, push, and open the PR with
   the `pr` skill.
8. **Merge on green:** once the PR's CI `build` check passes, run `gh pr merge <n> --squash --delete-branch`,
   then pull `main`. Never merge on a pending or failing check. `main` has no branch protection, so this rule is
   the gate.

Every review is mandatory. Ponytail asks whether what was built is right and minimal. `/code-review` asks
whether it matches what the repo has written down.

## Milestone gates

- **Before item 1:** run `/ponytail:ponytail-audit` on `../mathi-api-one-agent-2` (code only, per the exercise
  rules). Record each defect it finds under **Source defects** once `docs/MIGRATION-LOG.md` exists (item 2). Do
  not fix them during the port.
- **Before item 7** (the first post-migration item): run `/ponytail:ponytail-audit` on this repo. Turn each
  finding you act on into a backlog item, and note the ones you skip and why. Also add one backlog item for each
  **Source defect** you decide to fix.
