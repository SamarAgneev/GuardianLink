# GuardianLink

GuardianLink is an Android project with separate parent and child applications and a shared Kotlin module. Firebase provides identity and data services; the Cloudflare Worker provides the configured HTTP backend and media integration.

In the GitHub repository, this Android project lives in the `GuardianLink/` subdirectory. Open that directory in Android Studio and run the Android Gradle commands from it.

The child app is intended for transparent, consent-based family safety use. Its capabilities depend on Android permissions, device support, and backend configuration. Review the setup and privacy requirements before testing or distributing a build.

## Repository Layout

| Path | Purpose |
| --- | --- |
| `parent-app/` | Parent-facing Android application |
| `child-app/` | Child-device Android application |
| `common/` | Kotlin code shared by both applications |
| `firebase/` | Firebase rules, indexes, emulator config, and function sources |
| `cloudflare/worker/` | Cloudflare Worker API and tests |
| `verification/nv21-stride-check/` | Standalone JVM check for camera-plane stride handling |

## Requirements

- Android Studio or Android SDK 34 command-line tooling
- JDK 17
- Gradle 8.7 installed locally; this checkout does not include `gradlew` or `gradlew.bat`
- Node.js 18 or newer for Firebase tooling and the Worker
- Firebase CLI for emulator and deployment workflows

## Configure

1. Follow [SETUP_GUIDE.md](SETUP_GUIDE.md) to configure Firebase and local properties.
2. Provide a matching `google-services.json` in both Android app modules. Debug builds use `com.guardianlink.parent.debug` and `com.guardianlink.child.debug`.
3. Keep local properties, service-account credentials, signing keys, and other secrets out of source control. These files are ignored by `.gitignore`.

## Build Android Apps

From the repository root, with Gradle 8.7 and JDK 17 available on `PATH`:

```powershell
gradle :child-app:assembleDebug :parent-app:assembleDebug
```

The debug APKs are written to:

```text
child-app/build/outputs/apk/debug/child-app-debug.apk
parent-app/build/outputs/apk/debug/parent-app-debug.apk
```

## Test

```powershell
gradle :common:testDebugUnitTest :child-app:testDebugUnitTest :parent-app:testDebugUnitTest
npm test --prefix cloudflare/worker
npm test --prefix firebase/functions
```

Firebase rules tests require the Firebase emulators. Run `npm ci --prefix firebase` followed by `npm run test:rules --prefix firebase`.

## Backend

Firebase rules and indexes are configured by `firebase/firebase.json`. The Cloudflare Worker has its own dependencies, tests, secrets, and deployment configuration; see [cloudflare/worker/README.md](cloudflare/worker/README.md). Firebase Function sources remain under `firebase/functions/`, but they are not currently declared as a deploy target in `firebase/firebase.json`.

## Verification Notes

The NV21 stride harness tests byte-copy arithmetic using synthetic JVM data. It does not replace testing camera capture on supported physical devices. Run builds and tests before release, and review permissions, user disclosures, backend rules, and data-retention behavior for the target deployment.
