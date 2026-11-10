# Tasks

## 1. Connection behaviour

- [x] 1.1 Set the Netty read and write timeouts to 60 s and the shutdown timeout to 30 s in `Application.kt`.
  Verify that `./gradlew build` passes, and that an idle connection to Kotlin closes after about 60 s (probe).

## 2. CI

- [ ] 2.1 Extend `.github/workflows/build.yml`: start Kotlin, wait for health, run `contractTest`, and upload the
  report on failure. Verify that the PR's `build` check runs the suite and passes.

## 3. Proof and docs

- [x] 3.1 Run the full suite against Java and Kotlin, and diff `build/golden-actual.json` against the recordings
  plus the overlay. Verify zero unexplained differences.
- [x] 3.2 Fill every Unknown's Kotlin column, add Accepted difference 5, and write the "Equivalence" section in
  MIGRATION-LOG. Mark v1 complete in BACKLOG at archive. Verify that no "(item 6)" placeholder is left.

## Workflow follow-up

- Review the code (ponytail and `/code-review`), archive, write the retrospective, commit, open the PR, and merge on green.
