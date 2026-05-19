---
description: Remove legacy Android/Kotlin code from this project with ultrawork mode, LSP/Gradle-verified safety, atomic commits
---

<command-instruction>

Legacy code cleanup for the current Android cluster project. You are the ORCHESTRATOR — scan, verify, batch, then delegate removals/refactors to parallel agents when safe.

<rules>
- **Safety first.** Verify every removal candidate with `lsp_find_references(includeDeclaration=false)` when possible, or with repo-wide grep/reference checks when LSP cannot prove usage.
- **Android entry points are protected.** Never remove manifest-declared components, intent actions, resource IDs, runtime config keys, or reflection/string-based hooks without explicit evidence they are dead.
- **Do not edit OEM reference files.** `oem/` is read-only.
- **Release build is mandatory.** Final verification must include `./gradlew assembleRelease`.
- **You may batch and delegate edits, but only after verification.**
</rules>

<false-positive-guards>
NEVER mark as legacy/dead without extra proof:
- `app/src/main/AndroidManifest.xml` components, permissions, intent-filters, actions
- `ClusterApp.kt`, service entry points, `MainActivity`, `MediaCoverActivity`, proxy activities, BroadcastReceivers
- `RuntimeConfig.Keys`, `ProductConfig`, developer/runtime field specs, persisted preference keys
- Resource IDs referenced from XML, manifest, reflection, `RemoteViews`, notifications, or strings-based lookups
- Code referenced only from tests, scripts, adb/root shell commands, or string constants
- `oem/`, `AGENTS.md`, Gradle wrapper/bootstrap files, signing config, release build flow
- Public-facing actions/constants used by `adb shell am`, notifications, or external launcher paths
</false-positive-guards>

---

## PHASE 1: SCAN — Find Legacy Candidates

Run all of these in parallel.

<parallel-scan>

**Direct scans:**
```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --continue
```

```bash
./gradlew :app:testDebugUnitTest --continue
```

Use grep/ripgrep for legacy markers across Kotlin/XML/Gradle:
- `TODO|FIXME|HACK|LEGACY|deprecated|obsolete|temporary|compat|remove after|unused`
- `@Deprecated`
- legacy update/search modes like `INTERNAL_ONLY|USB_FIRST`
- duplicate old/new code paths around wake recovery, FTP retry, route gating, overlays, exporters

**Explore agents (fire simultaneously as background):**

```
task(subagent_type="explore", run_in_background=true, load_skills=[],
  description="Find legacy Kotlin paths",
  prompt="Find likely legacy or superseded Kotlin code in app/src/main/java/ru/foric27/cluster. Look for duplicate old/new flows, deprecated branches, stale fallbacks, old wake/sleep paths, dead retry logic, compatibility code, and comments indicating temporary behavior. Return file paths, symbols, and why each looks legacy.")

task(subagent_type="explore", run_in_background=true, load_skills=[],
  description="Find stale Android resources",
  prompt="Find likely stale XML/resources in app/src/main/res referenced by no active runtime path or superseded by newer behavior. Check layouts, strings, colors, and xml configs. Exclude anything still referenced from manifest, code, tests, or resources. Return file paths and evidence.")
```

</parallel-scan>

Collect all findings into a master candidate list.

---

## PHASE 2: VERIFY — Zero-False-Positive Confirmation

For EACH candidate:

1. Read the file and surrounding code.
2. If it is a symbol, run:
```typescript
lsp_find_references(filePath, line, character, includeDeclaration=false)
```
3. If LSP is unavailable or the candidate is XML/resource/config-driven, verify through:
   - repo-wide grep for symbol/resource/action usage
   - manifest references
   - layout/resource references
   - Gradle/build references
   - test references
4. If the candidate is “legacy branch” rather than dead symbol, prove the replacement path exists and covers the same responsibility.

Build a confirmed table:

```
| # | File | Candidate | Type | Evidence | Action |
|---|------|-----------|------|----------|--------|
| 1 | ...  | ...       | dead symbol / stale branch / deprecated mode / unused resource | ... | REMOVE / INLINE / DELETE FILE / KEEP |
```

If zero confirmed candidates remain: report `No safe legacy cleanup candidates found` and stop.

---

## PHASE 3: BATCH — Group for Conflict-Free Cleanup

<batching-rules>

1. Group by file first. Never send the same file to two agents.
2. Keep tightly coupled implementation + direct test in the same batch.
3. Separate unrelated domains:
   - overlays / display / UI transparency
   - logging / export / persistence
   - wake/sleep / service lifecycle
   - networking / FTP / OTA
   - resources / XML
4. Prefer 3-10 batches total. If only a few candidates, keep one batch per domain.

</batching-rules>

---

## PHASE 4: EXECUTE — Delegate Safe Cleanup Batches

For each batch, fire a deep agent:

```
task(
  category="deep",
  load_skills=["android-native-dev", "git-master"],
  run_in_background=true,
  description="Clean legacy batch N",
  prompt="[see template below]"
)
```

<agent-prompt-template>

```
## TASK: Clean legacy code from [file list]

## CONFIRMED CANDIDATES
- [file] line [N] — [symbol/branch/resource] — [REMOVE / INLINE / DELETE FILE / REWRITE]

## PROTOCOL
1. Read every listed file completely.
2. Re-verify each candidate with `lsp_find_references` or grep before editing.
3. Apply the minimal cleanup only for confirmed items.
4. Do not touch unrelated dirty files.
5. Run targeted verification for your batch:
   - `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --continue`
   - related unit tests if they exist
6. If verification fails, revert only your batch files and report failure.
7. If verification passes, stage ONLY your batch files and create one atomic commit:
   `GIT_MASTER=1 git add [specific files] && GIT_MASTER=1 git commit -m "refactor(legacy): clean [brief scope]"`
8. Report removed items, files touched, and commit hash.

## CRITICAL
- Never edit `oem/`.
- Never remove manifest/resource/runtime-config entry points without explicit proof.
- Comments added/updated must remain in Russian.
- Do not run `assembleDebug` as final proof; release verification is reserved for the orchestrator.
```

</agent-prompt-template>

Wait for all batches to finish.

---

## PHASE 5: FINAL VERIFICATION

After all cleanup batches complete:

```bash
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease
```

If relevant resources/layouts changed, also verify the nearest runnable behavior manually (screenshots/log file presence/service stop-start behavior).

Produce summary:

```markdown
## Legacy Cleanup Complete

### Removed / Simplified
| # | Candidate | File | Action | Commit |
|---|-----------|------|--------|--------|

### Skipped
| # | Candidate | File | Why skipped |
|---|-----------|------|-------------|

### Verification
- Debug/unit compile: PASS/FAIL
- JVM tests: PASS/FAIL
- Release build: PASS/FAIL
- Manual QA: PASS/FAIL
- Total cleaned: N items across M files
- Atomic commits created: K
```

---

## SCOPE CONTROL

If `$ARGUMENTS` is provided, narrow the cleanup:
- file path → only that file
- directory → only that directory
- symbol/class/resource name → only matching candidates
- `all` or empty → full project scan

## ABORT CONDITIONS

STOP and report if:
- More than 40 confirmed candidates are found (ask user to narrow scope or confirm full cleanup)
- A candidate depends on unclear external ownership (for example OEM/external NAV component behavior)
- Release build fails and the failure cannot be cleanly reverted to batch-local changes

</command-instruction>

<user-request>
$ARGUMENTS
</user-request>
