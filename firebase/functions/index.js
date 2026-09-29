// firebase/functions/index.js
//
// GuardianLink — Firebase Cloud Functions
// ─────────────────────────────────────────────────────────────────────────────
// Deploy: firebase deploy --only functions

const functions = require("firebase-functions");
const admin     = require("firebase-admin");

admin.initializeApp();

const db      = admin.firestore();
const messaging = admin.messaging();

// ── Pairing authorization contract ──────────────────────────────────────────
// The child app must authenticate as an enrolled child identity carrying
// appRole=child. This callable is the only client-facing provisioning path.
exports.redeemPairingCode = functions.https.onCall(async (data, context) => {
  if (!context.auth || context.auth.token.appRole !== "child") {
    throw new functions.https.HttpsError("permission-denied", "Child authentication required");
  }

  const code = typeof data.code === "string" ? data.code.trim() : "";
  const childName = typeof data.childName === "string" ? data.childName.trim() : "";
  if (!/^\d{6}$/.test(code) || childName.length === 0 || childName.length > 100) {
    throw new functions.https.HttpsError("invalid-argument", "Invalid pairing request");
  }

  const now = admin.firestore.Timestamp.now();
  const pairingQuery = db.collection("pairing_codes")
    .where("code", "==", code)
    .where("isUsed", "==", false)
    .limit(1);
  const deviceId = context.auth.uid;
  let parentId;

  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(pairingQuery);
    if (snapshot.empty) {
      throw new functions.https.HttpsError("not-found", "Pairing code is invalid or used");
    }

    const pairingDoc = snapshot.docs[0];
    const pairing = pairingDoc.data();
    if (!pairing.parentId || !pairing.expiresAt || pairing.expiresAt.toMillis() <= now.toMillis()) {
      throw new functions.https.HttpsError("failed-precondition", "Pairing code is expired");
    }

    parentId = pairing.parentId;
    transaction.create(db.collection("devices").doc(deviceId), {
      deviceId,
      authUid: deviceId,
      parentId,
      childName,
      pairedAt: now,
      isActive: true,
    });
    transaction.create(db.collection("settings").doc(deviceId), {
      deviceId,
      blockedApps: [],
      blockedWebsites: [],
      adultContentFilterEnabled: true,
      locationTrackingEnabled: true,
    });
    transaction.update(pairingDoc.ref, { isUsed: true, usedAt: now });
  });

  await admin.auth().setCustomUserClaims(deviceId, {
    appRole: "child",
    role: "device",
    deviceId,
    parentId,
  });
  await admin.database().ref(`device_acl/${deviceId}/${parentId}`).set({ role: "parent" });
  await admin.database().ref(`device_acl/${deviceId}/${deviceId}`).set({ role: "device" });

  return { deviceId, parentId };
});

// ── Command validation and hardening ───────────────────────────────────────
// Reject unsigned or expired commands before they reach the device.
exports.validateCommandWrite = functions.database
  .ref("commands/{deviceId}/{commandId}")
  .onWrite(async (change, context) => {
    const command = change.after.val();
    const deviceId = context.params.deviceId;
    const commandId = context.params.commandId;

    if (!command || !command.id || !command.parentId || !command.childDeviceId || !command.type) {
      console.warn(`Rejecting malformed command for ${deviceId}`);
      return change.after.ref.update({
        status: "REJECTED",
        error: "Command is malformed"
      });
    }

    // This function corrects tampered records in place (see the immutable-body
    // check below), and that correction write is itself a write to this same
    // ref — which would re-trigger this very function. Without this guard,
    // that would loop indefinitely (the corrected record still differs from
    // the tampered "before" it's compared against on the next invocation).
    // Once a record already carries this specific rejection, there's nothing
    // further for this function to do to it.
    if (command.status === "REJECTED" && command.error === "Command body was modified after creation") {
      return null;
    }

    // Defense-in-depth mirror of the client-side check in
    // CommandSecurity.validateForExecution: the payload's own `id` must
    // match the RTDB key it's actually stored under. A mismatch here would
    // mean this payload was found under the wrong node — reject rather
    // than trust the client-side check alone.
    if (command.id !== commandId) {
      console.warn(`Rejecting command whose id does not match its own key: ${command.id} !== ${commandId}`);
      return change.after.ref.update({
        status: "REJECTED",
        error: "Command id does not match the key it was stored under"
      });
    }

    // replayToken, when supplied, must match the command's own id (see
    // ParentFirebaseManager.sendCommand, which always sets both to the
    // same generated value, and CommandSecurity.validateForExecution's
    // client-side enforcement of the same invariant). Not hard-required
    // here either, for the same backward-compatibility reason as the
    // client-side check: a command already queued before this field/check
    // existed must not be locked out.
    if (command.replayToken && command.replayToken !== command.id) {
      console.warn(`Rejecting command with mismatched replayToken for ${deviceId}/${commandId}`);
      return change.after.ref.update({
        status: "REJECTED",
        error: "Command replay token does not match command id"
      });
    }

    if (command.childDeviceId !== deviceId) {
      console.warn(`Rejecting command with mismatched device target: ${command.childDeviceId} !== ${deviceId}`);
      return change.after.ref.update({
        status: "REJECTED",
        error: "Target device does not match the route"
      });
    }

    const deviceDoc = await db.collection("devices").doc(deviceId).get();
    if (!deviceDoc.exists) {
      return change.after.ref.update({
        status: "REJECTED",
        error: "Device is not registered"
      });
    }

    const device = deviceDoc.data();
    const ownerUid = device && device.parentId;
    if (!ownerUid || command.parentId !== ownerUid) {
      return change.after.ref.update({
        status: "REJECTED",
        error: "Parent is not authorized for this device"
      });
    }

    const expiresAt = Number(command.expiresAt || 0);
    if (expiresAt > 0 && expiresAt <= Date.now()) {
      return change.after.ref.update({
        status: "EXPIRED",
        error: "Command has expired"
      });
    }

    const allowedTypes = [
      "START_CAMERA_STREAM", "STOP_CAMERA_STREAM", "CAPTURE_PHOTO", "SWITCH_CAMERA",
      "START_SCREEN_MIRROR", "STOP_SCREEN_MIRROR",
      "START_AUDIO_STREAM", "STOP_AUDIO_STREAM",
      "LOCK_DEVICE", "UNLOCK_DEVICE", "REBOOT",
      "BLOCK_APP", "UNBLOCK_APP", "GET_INSTALLED_APPS",
      "GET_LOCATION", "SET_LOCATION_INTERVAL",
      "SET_DAILY_LIMIT", "ENFORCE_SCHEDULE",
      "SOS_TRIGGERED",
      "GET_CALL_LOGS", "GET_SMS_LOGS", "GET_APP_USAGE",
      "SYNC_SETTINGS", "PING"
    ];

    if (!allowedTypes.includes(command.type)) {
      return change.after.ref.update({
        status: "REJECTED",
        error: "Unsupported command type"
      });
    }

    // Immutable-body tamper check: on an *update* (change.before already
    // existed — i.e. this write is the child device reporting status, per
    // the RTDB rules' design that only the body's status/error/
    // lastUpdatedAt fields may change after creation), verify none of the
    // immutable identity/authorization fields actually changed. The RTDB
    // security rules are the primary enforcement of this already; this is
    // a second, server-admin-level check that doesn't depend on the rules
    // being correctly deployed, and actively repairs the record rather
    // than merely rejecting (a write already happened; there is no way to
    // "reject" it after the fact from an onWrite trigger, only correct it).
    if (change.before.exists()) {
      const before = change.before.val() || {};
      const immutableFields = [
        "id", "parentId", "childDeviceId", "type", "payload",
        "replayToken", "expiresAt", "createdAt"
      ];
      const tamperedFields = immutableFields.filter((field) => {
        const beforeValue = JSON.stringify(before[field] !== undefined ? before[field] : null);
        const afterValue = JSON.stringify(command[field] !== undefined ? command[field] : null);
        return beforeValue !== afterValue;
      });
      if (tamperedFields.length > 0) {
        console.error(
          `Command body tampering detected on ${deviceId}/${commandId}: fields changed after creation: ${tamperedFields.join(", ")}`
        );
        const restore = {};
        immutableFields.forEach((field) => {
          restore[field] = before[field] !== undefined ? before[field] : null;
        });
        restore.status = "REJECTED";
        restore.error = "Command body was modified after creation";
        return change.after.ref.update(restore);
      }
    }

    if (command.status && command.status !== "PENDING") {
      if (['EXECUTING', 'COMPLETED', 'FAILED', 'REJECTED', 'EXPIRED'].includes(command.status)) {
        return null;
      }
    }

    return null;
  });

// ── 1. Push notification when a new alert is created ─────────────────────────

exports.onAlertCreated = functions.firestore
  .document("alerts/{alertId}")
  .onCreate(async (snap, context) => {
    const alert = snap.data();
    if (!alert) return null;

    const deviceId = alert.deviceId;

    // Look up the parent's device FCM token
    const deviceDoc = await db.collection("devices").doc(deviceId).get();
    if (!deviceDoc.exists) {
      console.warn(`Device ${deviceId} not found`);
      return null;
    }

    const parentId = deviceDoc.data().parentId;

    // Get parent user's FCM token (stored in users collection)
    const parentDoc = await db.collection("users").doc(parentId).get();
    const fcmToken  = parentDoc.exists ? parentDoc.data().fcmToken : null;

    if (!fcmToken) {
      console.warn(`No FCM token for parent ${parentId}`);
      return null;
    }

    // Determine notification priority from severity
    const priority = alert.severity === "CRITICAL" || alert.severity === "HIGH"
      ? "high" : "normal";

    const message = {
      token: fcmToken,
      notification: {
        title: alert.title,
        body:  alert.message,
      },
      data: {
        alertId:  context.params.alertId,
        deviceId: deviceId,
        type:     alert.type,
        severity: alert.severity,
        title:    alert.title,
        body:     alert.message,
      },
      android: {
        priority: priority,
        notification: {
          channelId: "alerts",
          priority:  priority === "high" ? "max" : "default",
          sound:     "default",
        },
      },
    };

    try {
      await messaging.send(message);
      console.log(`Alert notification sent to parent ${parentId}`);
    } catch (error) {
      console.error("FCM send error:", error);
    }

    return null;
  });

// ── 2. Clean up expired pairing codes every hour ─────────────────────────────

exports.cleanupExpiredPairingCodes = functions.pubsub
  .schedule("every 60 minutes")
  .onRun(async () => {
    const now     = admin.firestore.Timestamp.now();
    const expired = await db.collection("pairing_codes")
      .where("expiresAt", "<", now)
      .where("isUsed", "==", false)
      .get();

    const batch = db.batch();
    expired.docs.forEach(doc => batch.delete(doc.ref));
    await batch.commit();

    console.log(`Cleaned up ${expired.size} expired pairing codes`);
    return null;
  });

// ── 3. Auto-expire stream frames in Realtime DB (older than 5s) ──────────────

exports.cleanupStreamFrames = functions.pubsub
  .schedule("every 1 minutes")
  .onRun(async () => {
    const rtdb      = admin.database();
    const threshold = Date.now() - 10_000; // 10 seconds

    const ref = rtdb.ref("stream_frames");
    const snapshot = await ref.once("value");

    const updates = {};
    snapshot.forEach(deviceSnap => {
      ["screen", "camera", "audio"].forEach(type => {
        const frameSnap = deviceSnap.child(type);
        const ts = frameSnap.child("ts").val();
        if (ts && ts < threshold) {
          updates[`${deviceSnap.key}/${type}`] = null;
        }
      });
    });

    if (Object.keys(updates).length > 0) {
      await ref.update(updates);
      console.log(`Cleared ${Object.keys(updates).length} stale stream frames`);
    }

    const now = admin.firestore.Timestamp.now();
    const sessionsSnapshot = await db.collection("stream_sessions")
      .where("expiresAt", "<=", now)
      .get();
    const sessionBatch = db.batch();
    const signalingDeletes = [];
    sessionsSnapshot.forEach(sessionDoc => {
      const session = sessionDoc.data();
      if (session.isActive === true || session.status === "PENDING" || session.status === "STARTING") {
        sessionBatch.update(sessionDoc.ref, {
          status: "EXPIRED",
          isActive: false,
          endedAt: now,
          failureReason: "Stream session expired",
          lastUpdatedAt: now,
        });
        signalingDeletes.push(rtdb.ref(`webrtc/${sessionDoc.id}`).remove());
      }
    });
    await sessionBatch.commit();
    await Promise.all(signalingDeletes);

    return null;
  });

// ── 4. Generate daily reports ─────────────────────────────────────────────────

exports.generateDailyReports = functions.pubsub
  .schedule("every day 23:55")
  .timeZone("UTC")
  .onRun(async () => {
    const today = new Date().toISOString().split("T")[0];

    // Get all active devices
    const devicesSnap = await db.collection("devices")
      .where("isActive", "==", true)
      .get();

    const reportPromises = devicesSnap.docs.map(async (deviceDoc) => {
      const deviceId = deviceDoc.id;

      // Aggregate app usage for today
      const usageSnap = await db.collection("app_usage")
        .where("deviceId", "==", deviceId)
        .where("date", "==", today)
        .orderBy("foregroundTimeMs", "desc")
        .limit(20)
        .get();

      // Aggregate alert count
      const alertsSnap = await db.collection("alerts")
        .where("deviceId", "==", deviceId)
        .where("timestamp", ">=", admin.firestore.Timestamp.fromDate(
          new Date(today + "T00:00:00Z")
        ))
        .get();

      const totalScreenMs = usageSnap.docs.reduce(
        (sum, doc) => sum + (doc.data().foregroundTimeMs || 0), 0
      );

      const report = {
        deviceId:         deviceId,
        date:             today,
        totalScreenTimeMs: totalScreenMs,
        topApps:          usageSnap.docs.slice(0, 10).map(d => d.data()),
        alertsTriggered:  alertsSnap.size,
        generatedAt:      admin.firestore.Timestamp.now(),
      };

      return db.collection("reports")
        .doc(`${deviceId}_${today}`)
        .set(report);
    });

    await Promise.all(reportPromises);
    console.log(`Generated ${devicesSnap.size} daily reports for ${today}`);
    return null;
  });

// ── 5. SOS panic button handler ───────────────────────────────────────────────

exports.onSosPanic = functions.firestore
  .document("alerts/{alertId}")
  .onCreate(async (snap, context) => {
    const alert = snap.data();
    if (!alert || alert.type !== "SOS_PANIC") return null;

    console.error(`🚨 SOS PANIC from device ${alert.deviceId}`);

    // For SOS, also set CRITICAL priority and send emergency notification
    // (handled by onAlertCreated above, which is triggered for all alerts)
    // Additional logic could include: SMS via Twilio, email via SendGrid, etc.

    return null;
  });

// ── 6. Parent-controlled child data deletion ────────────────────────────────

async function deleteQuery(query) {
  let deleted = 0;
  while (true) {
    const snapshot = await query.limit(400).get();
    if (snapshot.empty) break;
    const batch = db.batch();
    snapshot.docs.forEach(doc => batch.delete(doc.ref));
    await batch.commit();
    deleted += snapshot.size;
    if (snapshot.size < 400) break;
  }
  return deleted;
}

async function deleteDeviceScopedCollection(collection, deviceId) {
  return deleteQuery(db.collection(collection).where("deviceId", "==", deviceId));
}

async function deleteStoragePrefix(prefix) {
  const bucket = admin.storage().bucket();
  const [files] = await bucket.getFiles({ prefix });
  await Promise.all(files.map(file => file.delete()));
  return files.length;
}

exports.deleteChildData = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("permission-denied", "Parent authentication required");
  }

  const deviceId = typeof data.deviceId === "string" ? data.deviceId.trim() : "";
  if (!deviceId) {
    throw new functions.https.HttpsError("invalid-argument", "Device ID is required");
  }

  const deviceRef = db.collection("devices").doc(deviceId);
  const deviceSnap = await deviceRef.get();
  if (!deviceSnap.exists || deviceSnap.data().parentId !== context.auth.uid) {
    throw new functions.https.HttpsError("permission-denied", "Device is not owned by this parent");
  }

  const sessionSnapshot = await db.collection("stream_sessions")
    .where("deviceId", "==", deviceId).get();
  const sessionIds = sessionSnapshot.docs.map(doc => doc.id);

  const deleted = {};
  for (const collection of ["alerts", "call_logs", "sms_logs", "app_usage", "reports", "stream_sessions"]) {
    deleted[collection] = await deleteDeviceScopedCollection(collection, deviceId);
  }
  for (const subcollection of ["location_history", "photos", "usage_summaries", "data_usage"]) {
    deleted[subcollection] = await deleteQuery(deviceRef.collection(subcollection));
  }

  await Promise.all([
    deleteStoragePrefix(`photos/${deviceId}/`),
    deleteStoragePrefix(`screenshots/${deviceId}/`),
    ...sessionIds.map(sessionId => admin.database().ref(`webrtc/${sessionId}`).remove()),
  ]);

  const rtdb = admin.database();
  await Promise.all([
    rtdb.ref(`location/${deviceId}`).remove(),
    rtdb.ref(`status/${deviceId}`).remove(),
    rtdb.ref(`stream_status/${deviceId}`).remove(),
    rtdb.ref(`stream_frames/${deviceId}`).remove(),
    rtdb.ref(`commands/${deviceId}`).remove(),
    rtdb.ref(`device_acl/${deviceId}`).remove(),
  ]);

  await Promise.all([
    db.collection("settings").doc(deviceId).delete(),
    deviceRef.delete(),
  ]);

  console.log(`Deleted all sensitive data for device ${deviceId}`);
  return { deviceId, deleted };
});

// ── 7. Documented sensitive-data retention cleanup ──────────────────────────

async function cleanupOlderThan(collection, field, cutoff) {
  return deleteQuery(db.collection(collection).where(field, "<", cutoff));
}

exports.cleanupSensitiveData = functions.pubsub
  .schedule("every 24 hours")
  .timeZone("UTC")
  .onRun(async () => {
    const now = Date.now();
    const timestamp = ms => admin.firestore.Timestamp.fromMillis(ms);
    const counts = {};

    const telemetryCutoff = now - 24 * 60 * 60 * 1000;
    const rtdb = admin.database();
    const [locations, statuses] = await Promise.all([
      rtdb.ref("location").once("value"),
      rtdb.ref("status").once("value"),
    ]);
    const telemetryDeletes = {};
    locations.forEach(child => {
      const value = child.val() || {};
      if (Number(value.timestamp || 0) > 0 && Number(value.timestamp) < telemetryCutoff) {
        telemetryDeletes[`location/${child.key}`] = null;
      }
    });
    statuses.forEach(child => {
      const value = child.val() || {};
      if (Number(value.lastSeen || 0) > 0 && Number(value.lastSeen) < telemetryCutoff) {
        telemetryDeletes[`status/${child.key}`] = null;
      }
    });
    if (Object.keys(telemetryDeletes).length > 0) {
      await rtdb.ref().update(telemetryDeletes);
    }
    counts.staleTelemetry = Object.keys(telemetryDeletes).length;

    counts.locationHistory = await deleteQuery(
      db.collectionGroup("location_history")
        .where("timestamp", "<", timestamp(now - 30 * 24 * 60 * 60 * 1000))
    );
    counts.photosMetadata = await deleteQuery(
      db.collectionGroup("photos")
        .where("timestamp", "<", timestamp(now - 30 * 24 * 60 * 60 * 1000))
    );
    counts.usageSummaries = await deleteQuery(
      db.collectionGroup("usage_summaries")
        .where("syncedAt", "<", timestamp(now - 90 * 24 * 60 * 60 * 1000))
    );
    counts.alerts = await cleanupOlderThan("alerts", "timestamp", timestamp(now - 180 * 24 * 60 * 60 * 1000));
    counts.callLogs = await cleanupOlderThan("call_logs", "timestamp", timestamp(now - 90 * 24 * 60 * 60 * 1000));
    counts.smsLogs = await cleanupOlderThan("sms_logs", "timestamp", timestamp(now - 90 * 24 * 60 * 60 * 1000));
    counts.appUsage = await cleanupOlderThan("app_usage", "timestamp", timestamp(now - 90 * 24 * 60 * 60 * 1000));
    counts.reports = await cleanupOlderThan("reports", "generatedAt", timestamp(now - 365 * 24 * 60 * 60 * 1000));
    counts.streamSessions = await cleanupOlderThan("stream_sessions", "expiresAt", timestamp(now - 7 * 24 * 60 * 60 * 1000));

    counts.photos = await deleteStorageOlderThan("photos/", now - 30 * 24 * 60 * 60 * 1000);
    counts.screenshots = await deleteStorageOlderThan("screenshots/", now - 30 * 24 * 60 * 60 * 1000);

    console.log("Sensitive-data retention cleanup completed", counts);
    return null;
  });

async function deleteStorageOlderThan(prefix, cutoffMs) {
  const bucket = admin.storage().bucket();
  const [files] = await bucket.getFiles({ prefix });
  const expired = files.filter(file => {
    const created = file.metadata && file.metadata.timeCreated;
    return created && new Date(created).getTime() < cutoffMs;
  });
  await Promise.all(expired.map(file => file.delete()));
  return expired.length;
}
