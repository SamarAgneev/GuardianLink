# GUARDIANLINK — PASS 4 FINAL REPORT (Closure + Deep Verification)

Scope: this report covers only Pass 4's work, building on
`GuardianLink_Implementation_Report.md` (Pass 2) and
`GuardianLink_fixed_v3` Pass 3 report. Environment constraints are
unchanged from both previous passes unless stated otherwise below (one
constraint — npm registry access — turned out to be *more* permissive
than previously assumed, which materially changed what could be verified
this pass; see §6/§9).

No existing feature, screen, service, command, Firebase path, or rule was
removed in this pass. Every change below is either an additive fix to a
concretely-identified defect, or a new/expanded test. Where nothing was
broken, nothing was rewritten.

---

## 1. Files Changed

```
 parent-app/.../data/webrtc/ParentWebRtcClient.kt        | modified (Phase 1 fix)
 parent-app/.../ui/live/LiveViewActivity.kt               | modified (call-site update for above)
 child-app/.../receiver/BootReceiver.kt                   | modified (Phase 7 — boot-recovery deadlock fix)
 child-app/src/main/AndroidManifest.xml                   | modified (Phase 7 — MY_PACKAGE_REPLACED)
 firebase/functions/package.json                          | NEW (Phase 3 — did not exist before this pass)
 firebase/functions/validateCommandWrite.test.js          | NEW (Phase 3 — 15 tests, executed, all pass)
 firebase/rules.test.js                                   | modified (Phase 4 — +1 test, statically reasoned, not executed — see §10)
 .github/workflows/ci.yml                                 | NEW (Phase 14)
 verification/nv21-stride-check/Nv21StrideTest.kt         | NEW (Phase 6 — executed, 6/6 pass)
 verification/nv21-stride-check/README.md                 | NEW (Phase 6 — how to reproduce)
```

No file was deleted. No existing test was weakened or removed to make
something else pass.

---

## 2. Bugs Found

1. **Stale/foreign WebRTC session reuse (ParentWebRtcClient.kt).**
   `StreamSessionPolicy.canStart()`/`isAuthorized()` — which check
   session-id format, expiry, `deviceId` match, `parentId` match, **and**
   `status == PENDING` — existed but were dead code everywhere in the
   codebase (parent and child). Every real call site used only the
   weaker `isValidSessionId()` + expiry check. Concretely, this meant the
   parent client could hand a still-unexpired but already
   `ACTIVE`/`ENDED`/`FAILED` session record to `startSession()`, spinning
   up a peer connection and pushing an offer that nothing on the child
   side would ever answer.
2. **Boot-recovery deadlock (BootReceiver.kt) — the most serious finding
   this pass.** `BootReceiver` gated its own decision to start
   `MonitoringService` on `ProtectionStateEvaluator.evaluate()`, passing
   `monitoringServiceRunning = false` and `vpnEnabled = false` as
   hardcoded literals (true statements at boot time — neither has
   started yet). Both are classified `CRITICAL` by the evaluator, which
   forces `ProtectionState.DISABLED` whenever either is false. Since
   `BootReceiver` only proceeded to start the service when the result was
   `PROTECTED`, this was a hard deadlock: **the service could never
   restart after a reboot, under any circumstances, regardless of
   pairing/consent/permission state.** `TamperDetector` (the evaluator's
   only other caller) uses it correctly, with real live-state checks —
   confirming the evaluator itself is fine and the bug was specific to
   how `BootReceiver` (mis)used it as a pre-start gate.
3. **Missing `functions/package.json`.** The Cloud Functions directory
   had no package.json at all before this pass — `firebase deploy --only
   functions` would fail at the dependency-install step. Also, the
   file's `require("firebase-functions")` v1-style builder calls
   (`functions.database.ref().onWrite()`, `functions.pubsub.schedule()`,
   etc.) are incompatible with `firebase-functions` v5+ (whose default
   export moved these behind `firebase-functions/v1`), so simply adding
   *any* current-version package.json would have broken the file at
   `require` time.
4. **No `MY_PACKAGE_REPLACED` handling.** An app update kills the running
   foreground service exactly like a reboot does, but only
   `BOOT_COMPLETED`-class actions were registered — monitoring would stay
   off from the moment an update installs until the device's next
   organic reboot.
5. **Rules-test gap (not a shipped-code bug, a test-coverage gap):** no
   existing rules test verified that the RTDB command-update rule itself
   (as opposed to the Cloud Function's tamper-detection backstop) rejects
   a device changing immutable command fields while "just updating
   status."

## 3. Bugs Fixed

Items 1, 2, 3, and 4 above were fixed this pass (see §1 for the file
list; see each phase section below for the reasoning). Item 5 was closed
by adding a test, not a code fix (there was no code defect there).

**Reviewed and found already correct, left untouched:** `ChildWebRtcClient.kt`
lifecycle (duplicate start/stop safety, listener cleanup), `ScreenMirrorService`/
`MediaProjectionConsentActivity` (re-confirmed from Pass 3, not re-touched),
Firestore/RTDB authorization rules for `stream_sessions` and `webrtc/{sessionId}`
(traced the actual `isAuthorizedDevice`/`isParentOfDevice` rule functions —
server-side enforcement is real, not merely client-side filtering), all
`android:exported="true"` manifest components (each is either a
protected-broadcast-only receiver or requires a `BIND_*` system permission —
no unprotected exported surface found), and both `PendingIntent` construction
sites (both already use `FLAG_IMMUTABLE`).

## 4. Tests Added

- `firebase/functions/validateCommandWrite.test.js` — 15 tests against the
  real `validateCommandWrite` handler logic (mocking only `firebase-admin`,
  not the function itself). Covers all 10 cases Phase 3 required plus 5
  more (unregistered device, route/childDeviceId mismatch, expiry, unsupported
  type, terminal-status update).
- `firebase/rules.test.js` — 1 new test: a device cannot alter `payload`,
  `type`, `parentId`, or `createdAt` while updating `status`, but a pure
  status-only update still succeeds.
- `verification/nv21-stride-check/Nv21StrideTest.kt` — 6 synthetic-data
  cases against the exact shipped stride-copy logic (tight-pack fast
  path, row-padded Y, pixelStride=2 interleaved chroma — the actual bug
  scenario — padded chroma rows, and asymmetric dimensions).

## 5. Tests Actually Executed

- **`validateCommandWrite.test.js`: EXECUTED. 15/15 pass.** Real Kotlin—
  sorry, real JS handler code path exercised via `firebase-functions-test`'s
  `wrap()`, against a hand-controlled fake Firestore `devices` collection.
  No emulator involved or needed.
- **`Nv21StrideTest.kt`: EXECUTED. 6/6 pass.** Compiled and run with the
  real Kotlin compiler 1.9.24 (downloaded from GitHub's release CDN,
  which this sandbox's egress allowlist permits) against synthetic
  `java.nio.ByteBuffer` plane data. Pure JVM — no Android SDK involved or
  needed for this specific check.
- Both existing Kotlin unit test files from Pass 3
  (`EncryptionUtilsPinTest.kt`, `CommunicationUtilsTest.kt`): **still not
  executed** — see §6.

## 6. Tests NOT Executable, and Exact Reason

- **`rules.test.js` (20 existing + 1 new = 21 tests): NOT EXECUTED.**
  Reproduced the exact failure directly this pass:
  `npx firebase-tools emulators:start` reaches the point of
  `downloading cloud-firestore-emulator-v1.19.8.jar...` and then fails
  with `Error: download failed, status 403: Host not in allowlist:
  storage.googleapis.com`. This is a hard network-allowlist block in this
  sandbox, not a missing dependency or missing tool — `firebase-tools`
  itself installs and runs fine via npm; only the emulator binary
  download (hosted on Google Cloud Storage) is refused.
- **`EncryptionUtilsPinTest.kt`, `CommunicationUtilsTest.kt` (Pass 3):
  NOT EXECUTED.** These need a real Gradle/Android toolchain
  (`:common:testDebugUnitTest`) — no Android SDK or `gradlew` in this
  sandbox, and Google's Maven repository (needed for AndroidX/Room/etc.
  dependencies) is not in the network allowlist either.
- **Any instrumentation/Robolectric/emulator-based Android test:**
  NOT EXECUTABLE for the same reason.

## 7. ParentWebRtcClient Findings

Traced the full lifecycle: parent UI (`LiveViewActivity`) → Firestore
session lookup (`ParentFirebaseManager.findLatestStreamSession`, itself
backed by real server-side Firestore rules, not just the client-side
`parentId` filter it also applies) → `startSession()` → offer creation →
`listenForAnswer()`/`listenForChildCandidates()` (one-shot vs. ongoing
listeners, matching the child side's pattern exactly) → `onIceCandidate`
(correctly null-guards against a stopped session) → `stopSession()`
(nils all state, removes listeners, closes peer connection and data
channel).

**Concrete defect found and fixed:** see §2 item 1 (dead
`StreamSessionPolicy.canStart()`). Fix: wired `canStart()` into
`startSession()`, threading `expectedDeviceId` through from
`LiveViewActivity`. Also fixed a minor state-hygiene issue: if
`factory.createPeerConnection()` returned null, `session`/`onFrame` were
previously left non-null (a harmless-but-untidy partial state that
self-healed on the next `startSession()`/`stopSession()` call) — now
reset to null immediately on that failure path.

**Reviewed, no defect found:** duplicate start is safe
(`stopSession()` called unconditionally first); duplicate stop is safe
(all null-safe operators); SDP/ICE path matches the child side's path
naming exactly (`offer`/`answer`/`candidates`/`parent_candidates`); no
sensitive logging.

**Noted, not fixed (design fragility, not a live defect):**
`LiveViewActivity` hardcodes `StreamType.CAMERA_FRONT` when looking up
the session, and `DashboardFragment`'s `START_CAMERA_STREAM` command
defaults to front camera when no payload is given — these two
happen to agree today, but there is no structural link between them; if
a future change ever requests back-camera at session-creation time
without updating both spots, the parent would silently fail to find the
session (falling back to the slower Firestore JPEG-frame polling path
with no error surfaced). Not fixed — doing so would mean threading
`StreamType` through the whole call chain, which is a larger change than
this pass's scope justifies without a concrete trigger.

**Known residual race (STATICALLY VERIFIED as low-impact, not
device-verified):** native WebRTC observer callbacks (`onIceCandidate`,
`onDataChannel`) can in principle fire on a background thread between
`peerConnection.close()` and the Kotlin-level nulling of `session`/
`onFrame` in `stopSession()`. This is symmetric with the same
class of behavior already present on the child side (not something this
pass introduced), and the worst outcome traced is a stray, harmless
extra RTDB write to an already-abandoned (soon swept) session — not a
crash, not data corruption, not a security issue. Not fixed: introducing
a session-generation token to close this narrow window would be a
speculative rewrite of working code without a concretely observed
failure to justify it.

## 8. WebRTC Cleanup Decision

**Decision: explicit deletion of `/webrtc/{sessionId}` on `stopSession()`
is NOT implemented, and should not be.** Reproduced this directly from
the deployed rule text, not inferred: the top-level node's rule is
`".write": "... && !data.exists() && ..."` for creation, with no
corresponding delete-when-owner clause. Firebase RTDB evaluates a delete
as a write against the pre-write `data` at that exact path; once the
node exists, `!data.exists()` is false and **any** client-SDK write
(create, update, or delete) to that top-level path is rejected — from
either the parent or the child. Concretely:
- A client-side delete call would fail 100% of the time under the
  current rules, not merely race sometimes.
- Loosening the rule to permit deletion introduces exactly the race the
  master prompt warned about: either peer can call `stopSession()`
  independently (user taps "stop", or the other side times out first),
  so a client-initiated delete could remove the record while the other
  peer still has an in-flight offer/answer/candidate to consume.

The existing scheduled `cleanupStreamFrames`-class sweep (Admin SDK,
bypasses these rules) remains the correct mechanism and was left
untouched. No code was added that would only fail silently in
production.

## 9. Cloud Function Validation Test Results

**UNIT TEST VERIFIED — 15/15 pass**, executed against the real
`validateCommandWrite` handler (see §5). Also uncovered and fixed a real,
independent deployability defect in the process (§2 item 3, §3): no
`functions/package.json` existed, and the file's v1-style
`firebase-functions` API usage requires pinning a compatible major
version (fixed: `firebase-functions@^4.9.0` / `firebase-admin@^12.0.0` —
verified this specific pair resolves cleanly with `npm install` and that
`functions.database.ref` etc. are present as v1-style builders at that
version; v7, the latest as of this pass, moved them behind
`firebase-functions/v1` and would have failed at `require()` time).

## 10. Firebase Rules Test Results

**NOT EMULATOR-EXECUTED** (see §6 for the exact reproduced failure).
Existing 20-test suite reviewed by reading — genuinely solid coverage of
family/device/parent authorization, cross-device and cross-parent
denial, pairing-code contract, and WebRTC signaling authorization. One
real gap found and closed with a new test (§2 item 5, §4): a device
tampering with immutable command fields while updating status. That new
test is **STATICALLY VERIFIED by manual rule trace** (traced the exact
boolean condition in `database.rules.json` against what `newData` vs.
`data` would evaluate to for each sub-case) but not run against a real
emulator — flagged here explicitly rather than claimed as passing.

## 11. Camera Verification Status

**UNIT TEST VERIFIED (synthetic data, real Kotlin compiler) — 6/6
pass**, NOT EMULATOR/DEVICE VERIFIED. See §5 and
`verification/nv21-stride-check/README.md` for exact reproduction steps.
This confirms the byte-index arithmetic (`copyPlaneRespectingStride` and
the chroma interleave loop) is correct for: tightly-packed planes, row
padding, pixelStride=2 interleaved chroma (the exact real-world layout
described as the original bug in Pass 3), padded chroma rows, and
asymmetric image dimensions. It does not and cannot confirm behavior
against a real `ImageProxy` from a real camera sensor — no Android SDK
or emulator was available to instantiate one.

## 12. Boot Recovery Status

**Reboot path: FIXED, STATICALLY VERIFIED (not device-verified).** Found
and fixed a real deadlock (§2 item 2) that would have prevented
monitoring from ever restarting after any reboot, unconditionally. Fix
replaces the misused live-status evaluator with a direct check of the
actual boot-time preconditions (device admin active, accessibility
enabled, location permission granted).

**App-update path: FIXED, STATICALLY VERIFIED.** Added
`MY_PACKAGE_REPLACED` handling (manifest + Kotlin action allowlist) —
previously absent entirely.

**Not verified beyond static reading:** actual behavior on process death
(not full reboot) and VPN-service-specifically-dying were reviewed via
code reading only (existing `ServiceRestartWorker`/WorkManager scheduling
appears intact and was not touched) — no emulator/device was available
to observe the real OS behavior (Doze, OEM battery-optimization
aggressiveness, etc.), which varies significantly by device/OEM and
genuinely cannot be assessed from source alone.

## 13. Offline Queue Status

Reviewed `OfflineQueue.kt`/`OfflineQueueDao.kt`/`OfflineQueueEntity.kt`/
`OfflineQueuePolicy.kt` in full. **No defect found; nothing changed.**
Confirmed by reading: dedup is real (Room composite primary key
`(type, itemId)` + `INSERT ... OR IGNORE`, not merely named
"insertIfAbsent" without a backing constraint); retry uses capped
exponential backoff (2s base, 15min cap, `DEAD` after 8 attempts, not
infinite); drain loop re-checks network availability before every batch
and every item; `resetInFlight()` on drain start correctly recovers
items stranded `IN_FLIGHT` by a prior process death. No unbounded-growth
mechanism identified — queue size is bounded by genuine offline
monitoring events, not by any runaway enqueue path.

## 14. Protection-State Status

The evaluator itself (`ProtectionStateEvaluator`) is correct and was not
changed. Its **misuse** in `BootReceiver` was the actual bug (§2 item 2,
§12) — fixed by no longer using it as a pre-start gate for a service
that, definitionally, isn't running yet. `TamperDetector`'s live-polling
use of the same evaluator (real `isMonitoringServiceRunning()`,
`isOwnVpnActive()`, etc. checks, polled every 30s) was reviewed and is
correct — this is the legitimate design for reporting current, real
status. No false-`PROTECTED` reporting path was found.

## 15. Resource-Leak Findings

Camera/audio/screen-mirror/WebRTC (parent + child) all reviewed this
pass and across Pass 3; no new leak found beyond the one already fixed
in Pass 3 (audio) and the WebRTC dead-validation-code issue fixed this
pass (§2 item 1, which is an authorization gap, not a leak per se).
`ParentWebRtcClient.stopSession()` correctly closes the data channel and
peer connection and clears all listener references on every path,
including the now-fixed peer-connection-creation-failure path.

## 16. Security Findings

- Fixed: stale WebRTC session reuse (§2 item 1) — dead authorization
  check now wired in.
- Confirmed (not a finding, a verification): Firestore/RTDB
  authorization for devices, alerts, stream sessions, and WebRTC
  signaling is genuinely server-side (`isAuthorizedDevice`/
  `isParentOfDevice` helper functions trace back to real device-ACL
  lookups), not merely client-side query filtering.
- Confirmed: all exported Android components are either protected-
  broadcast-only (`BOOT_COMPLETED`, `SMS_RECEIVED`, `PACKAGE_ADDED`,
  `MY_PACKAGE_REPLACED` — none of these can be spoofed by a third-party
  app without system-level broadcast privilege) or require a `BIND_*`
  system permission (`AppBlockerAccessibilityService`,
  `GuardianDeviceAdminReceiver`).
- Confirmed: both `PendingIntent.getActivity()` call sites already use
  `FLAG_IMMUTABLE`.
- No privilege escalation, IDOR, or command-injection path identified in
  this pass's tracing of the command/session/signaling authorization
  chain beyond the one fixed above.

## 17. Build Status

**NOT BUILT.** No Android SDK, no `gradlew`, no network access to
Google's Maven repository in this sandbox (confirmed again this pass —
unchanged from Pass 2/3). Exact commands still needed on a real machine:
```
./gradlew clean build test lint
```

## 18. Release-Build Status

**NOT ATTEMPTED.** Requires the above plus a real signing keystore. Not
fabricated; documented as a requirement in `.github/workflows/ci.yml`'s
disabled `android-build` job rather than silently assumed.

## 19. CI Status

**Added** (` .github/workflows/ci.yml`), previously nonexistent. Two jobs
run for real on a standard GitHub-hosted runner with no secrets: Node/XML/
JSON static checks, and the Cloud Function unit tests from this pass
(§5/§9). Two jobs are present but explicitly disabled (`if: false`) with
inline documentation of exactly what they'd need: the rules-emulator
job (network access to `storage.googleapis.com`, which this authoring
sandbox blocks but a GitHub-hosted runner likely allows — noted as
unconfirmed either way) and the Android build/test/lint job (SDK +
Google Maven + signing secrets). No secrets are hardcoded or invented
anywhere in the workflow.

## 20. Remaining Risks

- Rules tests (21 total) remain unexecuted in this environment; the one
  new test added this pass is reasoned from the rule text, not emulator-
  confirmed.
- Pass 3's Kotlin unit tests remain unexecuted (no Gradle/Android
  toolchain here, unchanged from Pass 2/3).
- No Android build has ever been performed on this codebase across all
  four passes — everything is static-analysis- and unit-test-verified,
  never compiler- or device-verified.
- The `LiveViewActivity` hardcoded-`CAMERA_FRONT` fragility noted in §7
  is unfixed (low current risk, no live bug given today's only call
  path, but worth a real fix if back-camera live-view is ever wired up).
- Process-death/Doze/OEM-battery-optimization behavior for the
  monitoring service is unverified beyond source reading (§12) —
  this class of issue is notoriously OEM-specific and genuinely needs a
  real device to assess properly.
- `ParentWebRtcClient`'s narrow async-callback race window (§7) is
  understood and judged low-impact but not eliminated.

## 21. Exact Commands Executed This Pass

```
$ unzip -o GuardianLink_fixed_v3.zip
$ find . -iname "gradlew*" ; echo $ANDROID_HOME                       → both empty/unset
$ curl -sI https://dl.google.com/dl/android/maven2/                   → blocked by egress proxy
$ npm install firebase-functions-test firebase-functions firebase-admin jest   → succeeds (npmjs.org allowed)
$ curl -sL -o kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip
                                                                        → succeeds, 91MB (github.com/release-assets allowed)
$ (in firebase/functions) npm install ; npx jest --runInBand           → 15/15 pass
$ ./kotlinc/bin/kotlinc Nv21StrideTest.kt -include-runtime -d nv21test.jar
$ java -jar nv21test.jar                                               → 6/6 pass
$ npx firebase-tools emulators:start --only firestore                  → fails: 403, storage.googleapis.com not in allowlist
$ python3 <brace-balance scan on 3 edited .kt files>                   → all balanced (0)
$ python3 <XML well-formedness scan>                                   → AndroidManifest.xml OK
$ grep/view-based tracing of ParentWebRtcClient.kt, ChildWebRtcClient.kt,
  ParentFirebaseManager.kt, LiveViewActivity.kt, DashboardFragment.kt,
  StreamSessionPolicy.kt, FirebasePaths.kt, firestore.rules,
  database.rules.json, BootReceiver.kt, ProtectionStateEvaluator.kt,
  TamperDetector.kt, PackageReceiver.kt, OfflineQueue*.kt,
  ChildFirebaseManager.kt (SMS section), SmsReceiver.kt,
  EncryptionUtils.kt, PinManager.kt, GuardianFCMService.kt,
  ParentFCMService.kt, both AndroidManifest.xml files, validateCommandWrite
  (index.js)
```

## 22. Exact Commands Still Needed On a Real Android Environment

```
./gradlew clean build test lint
./gradlew :common:testDebugUnitTest --tests "*EncryptionUtilsPinTest*"
./gradlew :common:testDebugUnitTest --tests "*CommunicationUtilsTest*"
firebase emulators:exec --project <real-or-dummy-project-id> \
  --only firestore,database "cd firebase && npm test -- rules.test.js"
# (the above needs network access to storage.googleapis.com for the
#  emulator JAR download the first time it runs — confirm this is not
#  blocked on whatever machine runs it)
./gradlew assembleRelease   # requires a real signing keystore
```

---

## Non-Negotiables Checklist

- NO FEATURE DELETION — confirmed: manifest component counts, Cloud
  Function export count (9), Firestore rule collection count (16), and
  RTDB top-level path set (8: `device_acl`, `location`, `status`,
  `commands`, `webrtc`, `stream_frames`, `stream_status`, plus the
  deny-by-default `$other` catch-all) were all spot-checked and are
  unchanged.
- NO SECURITY REGRESSION — one authorization gap was *closed* (§2 item
  1); nothing was loosened.
- NO PERMISSION BYPASS — verified, not merely asserted (§16).
- NO FAKE TEST RESULTS — every "pass" claimed above was actually
  executed in this session (§5); everything else is explicitly labeled
  NOT EXECUTED or STATICALLY VERIFIED, never conflated with "passed."
- NO FAKE BUILD SUCCESS — §17/§18 state plainly that no build was
  performed.
- NO SPECULATIVE REWRITE — the async-callback race (§7) and the
  hardcoded-camera-type fragility (§7) were both left alone, with the
  reasoning for not touching them written out rather than silently
  ignored.
