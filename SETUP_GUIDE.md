# GuardianLink Setup Guide

> Setup notes for the Android applications, Firebase resources, and Cloudflare Worker in this repository. These instructions do not certify a build as production-ready or policy-compliant.

---

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Firebase Project Setup](#firebase-project-setup)
4. [Project Configuration](#project-configuration)
5. [Child App Setup](#child-app-setup)
6. [Parent App Setup](#parent-app-setup)
7. [Feature Activation Checklist](#feature-activation-checklist)
8. [TURN Server Setup (WebRTC)](#turn-server-setup-webrtc)
9. [Firebase Functions](#firebase-functions)
10. [OEM Battery Optimization Handling](#oem-battery-optimization-handling)
11. [Compliance Notes](#compliance-notes)
12. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    GUARDIANLINK SYSTEM                  │
├─────────────────┬───────────────────┬───────────────────┤
│   PARENT APP    │     FIREBASE      │    CHILD APP      │
│                 │                   │                   │
│  Dashboard ─────┼──→ Commands ─────→┼─ MonitoringService│
│  Live View ←────┼── Stream Frames ←─┼─ CameraService    │
│  Location ←─────┼── RTDB Location ←─┼─ LocationService  │
│  Alerts ←───────┼── Firestore ──────┼─ TamperDetector   │
│  Settings ──────┼──→ Firestore ────→┼─ AccessibilityServ│
└─────────────────┴───────────────────┴───────────────────┘
```

### Module Structure
```
GuardianLink/
├── common/              ← Shared models, encryption utils, constants
│   └── src/main/java/com/guardianlink/common/
│       ├── model/       ← All data classes (Command, LocationData, Alert…)
│       ├── security/    ← EncryptionUtils, SecurePreferences
│       └── constants/   ← FirebasePaths, NotificationIds
│
├── child-app/           ← Installed on child's device
│   └── src/main/java/com/guardianlink/child/
│       ├── service/     ← MonitoringService, LocationService, ScreenMirrorService…
│       ├── accessibility/ ← AppBlockerAccessibilityService
│       ├── admin/       ← GuardianDeviceAdminReceiver, TamperDetector
│       ├── receiver/    ← BootReceiver, SmsReceiver, PackageReceiver
│       ├── vpn/         ← ContentFilterVpnService (DNS sinkhole)
│       ├── webrtc/      ← ChildWebRtcClient
│       ├── firebase/    ← ChildFirebaseManager, GuardianFCMService
│       ├── security/    ← PinManager
│       ├── worker/      ← ServiceRestartWorker, AppUsageTracker
│       ├── di/          ← Hilt module
│       └── ui/          ← ConsentActivity, PinLockActivity, MainActivity…
│
├── parent-app/          ← Parent's control dashboard
│   └── src/main/java/com/guardianlink/parent/
│       ├── data/firebase/ ← ParentFirebaseManager
│       ├── ui/
│       │   ├── auth/    ← AuthActivity (sign in / register)
│       │   ├── main/    ← MainActivity (bottom nav host)
│       │   ├── dashboard/ ← DashboardFragment + ViewModel
│       │   ├── live/    ← LiveViewActivity (screen/camera streams)
│       │   ├── location/ ← LocationFragment (Google Maps + geofences)
│       │   ├── alerts/  ← AlertsFragment
│       │   ├── settings/ ← SettingsFragment
│       │   └── pairing/ ← PairingActivity (code generation)
│       ├── firebase/    ← ParentFCMService
│       └── di/          ← Hilt module
│
└── firebase/
    ├── firestore.rules
    ├── database.rules.json
    ├── storage.rules
    └── functions/index.js
```

---

## Prerequisites

| Tool | Minimum Version |
|------|----------------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 |
| Kotlin | 1.9.x |
| Firebase CLI | 13.x (`npm install -g firebase-tools`) |
| Node.js | 18.x (for Cloud Functions) |
| Google account | — |

---

## Firebase Project Setup

### Step 1 — Create Firebase Project

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → name it `GuardianLink`
3. Enable **Google Analytics** (optional but recommended)

### Step 2 — Enable Firebase Services

In the Firebase console, enable:

| Service | Location |
|---------|----------|
| **Authentication** | Build → Authentication → Get started → Email/Password |
| **Firestore** | Build → Firestore → Create database → Production mode |
| **Realtime Database** | Build → Realtime Database → Create database |
| **Storage** | Build → Storage → Get started |
| **Cloud Messaging** | Automatically enabled |
| **Crashlytics** | Build → Crashlytics → Enable |

### Step 3 — Register Android Apps

Register **two** Android apps:

**Child App:**
- Package name: `com.guardianlink.child`
- App nickname: `GuardianLink Child`
- Download `google-services.json` → place in `child-app/`

**Parent App:**
- Package name: `com.guardianlink.parent`
- App nickname: `GuardianLink Parent`
- Download `google-services.json` → place in `parent-app/`

### Step 4 — Deploy Security Rules

```bash
# Install Firebase CLI
npm install -g firebase-tools

# Login
firebase login

# Deploy the rules and indexes configured in firebase/firebase.json
firebase deploy --config firebase/firebase.json --only firestore,database,storage
```

### Step 5 — Firestore Indexes

Create these composite indexes in Firebase Console → Firestore → Indexes:

```
Collection: alerts
  Fields: deviceId ASC, timestamp DESC

Collection: call_logs
  Fields: deviceId ASC, timestamp DESC

Collection: sms_logs
  Fields: deviceId ASC, timestamp DESC

Collection: app_usage
  Fields: deviceId ASC, date ASC, foregroundTimeMs DESC
```

---

## Project Configuration

### 1. Clone and open project

```bash
git clone https://github.com/SamarAgneev/GuardianLink.git
Set-Location GuardianLink\GuardianLink
```

Open in Android Studio.

### 2. Add google-services.json files

```
child-app/google-services.json   ← from Firebase (child app)
parent-app/google-services.json  ← from Firebase (parent app)
```

### 3. Configure secrets

Create `secrets.properties` in the project root (add to `.gitignore`):

```properties
# secrets.properties — DO NOT COMMIT
MAPS_API_KEY=your_google_maps_api_key_here
```

Get a Maps API key:
1. [console.cloud.google.com](https://console.cloud.google.com)
2. APIs & Services → Credentials → Create API Key
3. Restrict to: Maps SDK for Android

### 4. Gradle configuration

`gradle.properties`:
```properties
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
```

### 5. Sync and build

```bash
gradle :child-app:assembleDebug :parent-app:assembleDebug
```

This checkout does not include `gradlew` or `gradlew.bat`; install Gradle 8.7 locally or build through Android Studio.

---

## Child App Setup

### Installation on Child Device

1. Install `child-app-debug.apk` on child's Android device (API 26+)
2. On first launch, the **Consent Screen** appears — explain monitoring to child

### Permissions and Feature Access

Permission needs depend on the features enabled, Android version, and device. Explain data collection before setup, request only necessary access, and let the device user decline or revoke permissions.

| Permission | Purpose |
|------------|---------|
| Fine Location | Real-time GPS tracking |
| Background Location | Location updates when app is backgrounded |
| Camera | Remote camera access |
| Microphone | Audio monitoring |
| Read Call Log | Call monitoring |
| Read SMS | SMS monitoring |
| Package Usage Stats | App usage tracking |
| Draw Over Other Apps | App-block overlay |
| Ignore Battery Optimization | Keep service alive |
| Accessibility Service | App blocking enforcement |
| Device Administrator | Uninstall protection |
| VPN | Website/DNS filtering |

### Pairing with Parent

1. Open **Parent App** → tap **Add Child** → note the 6-digit code
2. On **Child App** → enter child's name + pairing code → tap **Pair**
3. Complete the pairing flow only after the child has received an age-appropriate explanation and agreed to the setup. Grant only the permissions needed for the features being enabled.

---

## Parent App Setup

### Installation on Parent Device

1. Install `parent-app-debug.apk`
2. Register with email/password or sign in
3. Tap **Add Child Device** → generate pairing code → share with child device

### Navigation

```
Bottom Navigation:
├── 🏠 Dashboard  — Device status, quick actions, recent alerts
├── 📺 Live       — Launch screen mirror or camera stream
├── 📍 Location   — Real-time map, location history, geofences
├── 🔔 Alerts     — All alerts with severity badges, read/resolve
└── ⚙️ Settings   — All parental controls per device
```

### Available Commands (sent from parent → child)

| Command | Effect |
|---------|--------|
| `START_SCREEN_MIRROR` | Begin screen streaming |
| `STOP_SCREEN_MIRROR` | Stop screen streaming |
| `START_CAMERA_STREAM` | Begin camera livestream |
| `STOP_CAMERA_STREAM` | Stop camera stream |
| `CAPTURE_PHOTO` | Request a photo capture, subject to Android permissions and system indicators |
| `SWITCH_CAMERA` | Flip front/back camera |
| `START_AUDIO_STREAM` | Start audio monitoring |
| `STOP_AUDIO_STREAM` | Stop audio monitoring |
| `LOCK_DEVICE` | Immediately lock child device |
| `BLOCK_APP` | Add app to blocklist |
| `UNBLOCK_APP` | Remove app from blocklist |
| `GET_LOCATION` | Force location refresh |
| `GET_CALL_LOGS` | Sync latest call logs |
| `GET_SMS_LOGS` | Sync latest SMS logs |
| `GET_APP_USAGE` | Sync app usage stats |
| `SET_DAILY_LIMIT` | Update screen time limit |
| `PING` | Check device is responsive |

---

## Feature Activation Checklist

Use consented test devices to check only the capabilities enabled for that deployment. Test permission denial and revocation as well as the allowed flow. Validate location freshness, app-usage reporting, blocking, alerts, pairing removal, and any camera or screen-sharing flow on physical devices; behavior varies by Android version and manufacturer.

---

## TURN Server Setup (WebRTC)

The repository does not provision a TURN server or store TURN credentials. The following examples are infrastructure references only; configure and test ICE servers in the Android client for the target environment, and keep credentials out of source control.

### Option A: Coturn (self-hosted)

```bash
sudo apt install coturn

# /etc/coturn/turnserver.conf
listening-port=3478
fingerprint
lt-cred-mech
realm=guardianlink.yourdomain.com
user=guardianlink:your_password
log-file=/var/log/coturn/turnserver.log
```

### Option B: Twilio Network Traversal Service

```kotlin
// In ChildWebRtcClient.kt, replace iceServers list:
private val iceServers = listOf(
    PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478")
        .createIceServer(),
    PeerConnection.IceServer.builder("turn:global.turn.twilio.com:3478?transport=udp")
        .setUsername(twilioUsername)
        .setPassword(twilioPassword)
        .createIceServer()
)
```

### Option C: Metered.ca (easy paid TURN)

Replace the ICE server list with credentials from metered.ca.

---

## Firebase Functions

Function source and tests are present in `firebase/functions/`. The current `firebase/firebase.json` does not declare a Functions source or deployment target, so `firebase deploy --only functions` is not configured from this checkout. Review the implementation and runtime requirements, add an explicit Functions configuration when needed, then test against an isolated Firebase project before deployment.

The Cloudflare Worker is configured separately in `cloudflare/worker/wrangler.toml`; follow its README for Worker setup and deployment.

---

## OEM Battery Optimization Handling

Several OEM manufacturers aggressively kill background services:

### Xiaomi / MIUI
```
Settings → Apps → Manage apps → GuardianLink Child
→ Battery saver → No restrictions
→ Autostart → Enable
```

### Huawei / EMUI
```
Phone Manager → Protected apps → GuardianLink Child ✓
Settings → Battery → App launch → GuardianLink Child → Manage manually
→ Auto-launch ✓, Secondary launch ✓, Run in background ✓
```

### OnePlus / OxygenOS
```
Settings → Battery → Battery optimization → All apps
→ GuardianLink Child → Don't optimize
```

### Samsung / OneUI
```
Settings → Device care → Battery → Background usage limits
→ Never sleeping apps → Add GuardianLink Child
```

The app requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission on first run,
which on stock Android opens the system dialog automatically.

---

## Compliance Notes

This repository does not establish compliance with Google Play policy or local law. Before distribution, independently verify the applicable requirements and ensure the implementation matches the disclosures and consent flow. In particular:

| Requirement | Implementation |
|-------------|----------------|
| Transparent disclosure | Consent screen explains all monitoring |
| Not hidden | App has visible icon and launcher entry |
| Parental consent required | Explicit accept required before pairing |
| Persistent notification | Foreground service notification always shown |
| Not marketed as spy tool | App is clearly a parental control tool |
| No deceptive behavior | All monitoring is visible to device user |
| Data minimization | Only necessary data is collected |

Before distributing:
1. Update Privacy Policy URL in consent screen
2. Add Terms of Service
3. Ensure COPPA compliance if targeting users under 13
4. Review Google Play Families Policy

---

## Troubleshooting

### Child service not starting after reboot
- Verify `RECEIVE_BOOT_COMPLETED` permission granted
- Check device admin is still enabled
- Verify WorkManager periodic job is scheduled

### Location not updating
- Ensure background location permission granted (Android 10+ requires separate prompt)
- Check battery optimization exemption is set
- Verify `ACCESS_BACKGROUND_LOCATION` in manifest

### App blocking not working
- Accessibility Service must be enabled in device settings
- Check `SYSTEM_ALERT_WINDOW` permission for overlay
- Verify blocked app list is syncing from Firestore

### Screen mirror shows no frames
- Media projection consent must be granted (shown once)
- If WebRTC fails, frames fall back to Firebase RTDB (may be slower)
- Check TURN server configuration for NAT traversal

### WebRTC signaling fails
- Verify Firebase Realtime DB rules allow read/write for `/webrtc/`
- Check ICE server configuration (add TURN server for production)

### FCM notifications not received
- Ensure `POST_NOTIFICATIONS` permission is granted (Android 13+)
- Check FCM token is stored in Firestore `users` collection
- Verify Cloud Function `onAlertCreated` is deployed

### ProGuard stripping classes
- Add `-keep` rules for any new model classes in `proguard-rules.pro`
- Run `gradle :child-app:assembleRelease` to test a release build

---

**GuardianLink is intended for transparent, consensual use by parents
with their minor children. Unauthorized surveillance is illegal.**
