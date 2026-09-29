import { createRemoteJWKSet, importPKCS8, jwtVerify, SignJWT } from "jose";

const firebaseJwks = createRemoteJWKSet(new URL(
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
));
const oauthCache = new Map();
const firebaseProject = "guardianlink-b9f5d";
const allowedCommandTypes = new Set([
  "START_CAMERA_STREAM", "STOP_CAMERA_STREAM", "CAPTURE_PHOTO", "SWITCH_CAMERA",
  "START_SCREEN_MIRROR", "STOP_SCREEN_MIRROR", "START_AUDIO_STREAM", "STOP_AUDIO_STREAM",
  "LOCK_DEVICE", "UNLOCK_DEVICE", "REBOOT", "BLOCK_APP", "UNBLOCK_APP",
  "GET_INSTALLED_APPS", "GET_LOCATION", "SET_LOCATION_INTERVAL", "SET_DAILY_LIMIT",
  "ENFORCE_SCHEDULE", "SOS_TRIGGERED", "GET_CALL_LOGS", "GET_SMS_LOGS",
  "GET_APP_USAGE", "SYNC_SETTINGS", "PING"
]);

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});
const fail = (status, message) => json({ error: message }, status);
const nowTimestamp = () => new Date().toISOString();
const documentsUrl = env => `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents`;

function encodeFirestoreValue(value) {
  if (value === null || value === undefined) return { nullValue: "NULL_VALUE" };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value)
      ? { integerValue: String(value) }
      : { doubleValue: value };
  }
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encodeFirestoreValue) } };
  if (typeof value === "object") return { mapValue: { fields: encodeFirestoreFields(value) } };
  throw new TypeError(`Unsupported Firestore value: ${typeof value}`);
}

function encodeFirestoreFields(data) {
  return Object.fromEntries(Object.entries(data).map(([key, value]) => [key, encodeFirestoreValue(value)]));
}

function decodeFirestoreValue(value) {
  if ("nullValue" in value) return null;
  if ("stringValue" in value) return value.stringValue;
  if ("booleanValue" in value) return value.booleanValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return value.doubleValue;
  if ("timestampValue" in value) return new Date(value.timestampValue);
  if ("arrayValue" in value) return (value.arrayValue.values || []).map(decodeFirestoreValue);
  if ("mapValue" in value) return decodeFirestoreFields(value.mapValue.fields || {});
  return null;
}

function decodeFirestoreFields(fields = {}) {
  return Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, decodeFirestoreValue(value)]));
}

function decodeDocument(document) {
  return { id: document.name.split("/").at(-1), path: document.name, updateTime: document.updateTime, data: decodeFirestoreFields(document.fields) };
}

async function googleAccessToken(env, scope = "https://www.googleapis.com/auth/cloud-platform") {
  const cached = oauthCache.get(scope);
  if (cached && cached.expiresAt > Date.now() + 60_000) return cached.token;
  const email = env.FIREBASE_SERVICE_ACCOUNT_EMAIL;
  const privateKey = (env.FIREBASE_SERVICE_ACCOUNT_PRIVATE_KEY || "").replace(/\\n/g, "\n");
  if (!email || !privateKey) throw new Error("Firebase service-account secrets are not configured");
  const key = await importPKCS8(privateKey, "RS256");
  const assertion = await new SignJWT({ scope })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(email)
    .setSubject(email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(key);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion })
  });
  const result = await response.json();
  if (!response.ok || !result.access_token) throw new Error("Google service-account token exchange failed");
  oauthCache.set(scope, { token: result.access_token, expiresAt: Date.now() + (result.expires_in || 3600) * 1000 });
  return result.access_token;
}

async function firebaseUser(request, env, required = true) {
  const token = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) {
    if (required) throw new HttpError(401, "Firebase ID token required");
    return null;
  }
  try {
    const { payload } = await jwtVerify(token, firebaseJwks, {
      audience: env.FIREBASE_PROJECT_ID || firebaseProject,
      issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID || firebaseProject}`
    });
    if (typeof payload.sub !== "string" || !payload.sub) throw new Error("Missing Firebase uid");
    return payload;
  } catch {
    throw new HttpError(401, "Invalid Firebase ID token");
  }
}

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function firebaseRequest(env, path, options = {}) {
  const token = await googleAccessToken(env);
  const url = path.startsWith("https://") ? path : `${documentsUrl(env)}/${path.replace(/^\//, "")}`;
  const response = await fetch(url, {
    ...options,
    headers: { authorization: `Bearer ${token}`, ...(options.body ? { "content-type": "application/json" } : {}), ...options.headers }
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new HttpError(response.status, `Firebase API failed (${response.status}): ${detail.slice(0, 300)}`);
  }
  if (response.status === 204) return null;
  return response.json();
}

function fieldFilter(field, op, value) {
  return { fieldFilter: { field: { fieldPath: field }, op, value: encodeFirestoreValue(value) } };
}

async function queryDocuments(env, collectionId, filters = [], options = {}) {
  const parent = options.parent || documentsUrl(env);
  const structuredQuery = {
    from: [{ collectionId, ...(options.allDescendants ? { allDescendants: true } : {}) }],
    ...(filters.length === 1 ? { where: filters[0] } : filters.length > 1 ? { where: { compositeFilter: { op: "AND", filters } } } : {}),
    ...(options.orderBy ? { orderBy: options.orderBy } : {}),
    ...(options.limit ? { limit: options.limit } : {})
  };
  const rows = await firebaseRequest(env, `${parent}:runQuery`, {
    method: "POST",
    body: JSON.stringify({ structuredQuery, ...(options.transaction ? { transaction: options.transaction } : {}) })
  });
  return rows.filter(row => row.document).map(row => decodeDocument(row.document));
}

async function getDocument(env, path) {
  try {
    const document = await firebaseRequest(env, path);
    return decodeDocument(document);
  } catch (error) {
    if (error instanceof HttpError && error.status === 404) return null;
    throw error;
  }
}

async function commitWrites(env, writes, transaction) {
  return firebaseRequest(env, `${documentsUrl(env)}:commit`, {
    method: "POST",
    body: JSON.stringify({ writes, ...(transaction ? { transaction } : {}) })
  });
}

function updateWrite(env, path, data, fieldPaths = Object.keys(data), precondition) {
  return {
    update: { name: `${documentsUrl(env)}/${path}`, fields: encodeFirestoreFields(data) },
    updateMask: { fieldPaths },
    ...(precondition ? { currentDocument: precondition } : {})
  };
}

async function setDocument(env, path, data, createOnly = false) {
  const precondition = createOnly ? { exists: false } : undefined;
  await commitWrites(env, [updateWrite(env, path, data, Object.keys(data), precondition)]);
}

async function patchDocument(env, path, data) {
  await commitWrites(env, [updateWrite(env, path, data)]);
}

async function deleteDocuments(env, documents) {
  for (let index = 0; index < documents.length; index += 400) {
    const batch = documents.slice(index, index + 400).map(document => ({ delete: document.path }));
    await commitWrites(env, batch);
  }
}

async function deleteQuery(env, collectionId, filters, options = {}) {
  let deleted = 0;
  while (true) {
    const documents = await queryDocuments(env, collectionId, filters, { ...options, limit: 400 });
    if (!documents.length) return deleted;
    await deleteDocuments(env, documents);
    deleted += documents.length;
    if (documents.length < 400) return deleted;
  }
}

function realtimeUrl(env, path) {
  const encodedPath = path.split("/").filter(Boolean).map(encodeURIComponent).join("/");
  return `${env.FIREBASE_DATABASE_URL.replace(/\/$/, "")}/${encodedPath}.json`;
}

async function realtimeRequest(env, path, method = "GET", data) {
  const token = await googleAccessToken(env);
  const response = await fetch(realtimeUrl(env, path), {
    method,
    headers: { authorization: `Bearer ${token}`, ...(data === undefined ? {} : { "content-type": "application/json" }) },
    ...(data === undefined ? {} : { body: JSON.stringify(data) })
  });
  if (!response.ok) throw new HttpError(502, `Realtime Database request failed (${response.status})`);
  return response.json();
}

async function makeCustomToken(env, uid, claims) {
  const email = env.FIREBASE_SERVICE_ACCOUNT_EMAIL;
  const key = await importPKCS8((env.FIREBASE_SERVICE_ACCOUNT_PRIVATE_KEY || "").replace(/\\n/g, "\n"), "RS256");
  return new SignJWT({ uid, claims })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(email)
    .setSubject(email)
    .setAudience("https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit")
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(key);
}

async function enforcePairingRateLimit(request, env) {
  const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`${env.FIREBASE_PROJECT_ID}:${clientIp}`)
  );
  const key = [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, "0")).join("");
  const minute = Math.floor(Date.now() / 60_000);
  const path = `worker_pairing_attempts/${key}/${minute}`;
  const limit = Number(env.PAIRING_ATTEMPTS_PER_MINUTE || 5);

  for (let attempt = 0; attempt < 8; attempt++) {
    const token = await googleAccessToken(env);
    const read = await fetch(realtimeUrl(env, path), {
      headers: { authorization: `Bearer ${token}`, "X-Firebase-ETag": "true" }
    });
    if (!read.ok) throw new HttpError(502, "Pairing rate limit check failed");
    const etag = read.headers.get("ETag");
    const current = Number(await read.json()) || 0;
    if (current >= limit) throw new HttpError(429, "Too many pairing attempts; try again in one minute");
    const write = await fetch(realtimeUrl(env, path), {
      method: "PUT",
      headers: { authorization: `Bearer ${token}`, "If-Match": etag || "null_etag", "content-type": "application/json" },
      body: JSON.stringify(current + 1)
    });
    if (write.status === 412) continue;
    if (!write.ok) throw new HttpError(502, "Pairing rate limit update failed");
    return;
  }
  throw new HttpError(503, "Pairing is busy; retry shortly");
}

async function redeemPairing(request, env) {
  await enforcePairingRateLimit(request, env);
  const body = await request.json();
  const code = typeof body.code === "string" ? body.code.trim() : "";
  const childName = typeof body.childName === "string" ? body.childName.trim() : "";
  if (!/^\d{6}$/.test(code) || !childName || childName.length > 100) throw new HttpError(400, "Invalid pairing request");

  const begin = await firebaseRequest(env, `${documentsUrl(env)}:beginTransaction`, {
    method: "POST", body: JSON.stringify({ options: { readWrite: {} } })
  });
  const transaction = begin.transaction;
  const matches = await queryDocuments(env, "pairing_codes", [
    fieldFilter("code", "EQUAL", code),
    fieldFilter("isUsed", "EQUAL", false)
  ], { limit: 1, transaction });
  if (!matches.length) throw new HttpError(404, "Pairing code is invalid or already used");
  const pairing = matches[0];
  const expiresAt = pairing.data.expiresAt instanceof Date ? pairing.data.expiresAt.getTime() : 0;
  if (!pairing.data.parentId || expiresAt <= Date.now()) throw new HttpError(410, "Pairing code expired");

  const deviceId = crypto.randomUUID();
  const parentId = pairing.data.parentId;
  const now = new Date();
  const customToken = await makeCustomToken(env, deviceId, {
    appRole: "child", role: "device", deviceId, parentId
  });
  await commitWrites(env, [
    updateWrite(env, `devices/${deviceId}`, {
      deviceId, authUid: deviceId, parentId, childName,
      pairedAt: now, isActive: true
    }, ["deviceId", "authUid", "parentId", "childName", "pairedAt", "isActive"], { exists: false }),
    updateWrite(env, `settings/${deviceId}`, {
      deviceId, blockedApps: [], blockedWebsites: [], adultContentFilterEnabled: true,
      locationTrackingEnabled: true
    }, ["deviceId", "blockedApps", "blockedWebsites", "adultContentFilterEnabled", "locationTrackingEnabled"], { exists: false }),
    updateWrite(env, pairing.path.slice(`${documentsUrl(env)}/`.length), {
      isUsed: true, usedAt: now
    }, ["isUsed", "usedAt"], { updateTime: pairing.updateTime })
  ], transaction);

  await Promise.all([
    realtimeRequest(env, `device_acl/${deviceId}/${parentId}`, "PUT", { role: "parent" }),
    realtimeRequest(env, `device_acl/${deviceId}/${deviceId}`, "PUT", { role: "device" })
  ]);
  return json({ deviceId, parentId, customToken });
}

async function submitCommand(request, env, user) {
  const body = await request.json();
  const deviceId = typeof body.deviceId === "string" ? body.deviceId.trim() : "";
  const command = body.command;
  if (!deviceId || !command || typeof command !== "object") throw new HttpError(400, "Command and deviceId are required");
  if (command.parentId !== user.sub || command.childDeviceId !== deviceId || !allowedCommandTypes.has(command.type)) {
    throw new HttpError(403, "Command is not authorized for this device");
  }
  if (command.id !== body.commandId || !command.id || command.replayToken !== command.id) {
    throw new HttpError(400, "Command identifier is invalid");
  }
  if (Number(command.expiresAt || 0) <= Date.now()) throw new HttpError(410, "Command expired");
  const device = await getDocument(env, `devices/${deviceId}`);
  if (!device || device.data.parentId !== user.sub || device.data.isActive !== true) {
    throw new HttpError(403, "Device is not owned by this parent");
  }
  await realtimeRequest(env, `commands/${deviceId}/${command.id}`, "PUT", command);
  await setDocument(env, `commands/${command.id}`, {
    ...command, deviceId, loggedAt: new Date()
  });
  return json({ commandId: command.id });
}

async function deleteChildData(request, env, user, deviceId) {
  if (!deviceId || !/^[A-Za-z0-9-]{1,128}$/.test(deviceId)) throw new HttpError(400, "Invalid device ID");
  const device = await getDocument(env, `devices/${deviceId}`);
  if (!device || device.data.parentId !== user.sub) throw new HttpError(403, "Device is not owned by this parent");

  const sessionDocs = await queryDocuments(env, "stream_sessions", [fieldFilter("deviceId", "EQUAL", deviceId)], { allDescendants: true });
  const deletions = {};
  for (const collection of ["alerts", "call_logs", "sms_logs", "app_usage", "reports", "stream_sessions"]) {
    deletions[collection] = await deleteQuery(env, collection, [fieldFilter("deviceId", "EQUAL", deviceId)], { allDescendants: true });
  }
  for (const subcollection of ["location_history", "photos", "usage_summaries", "data_usage"]) {
    const parent = `${documentsUrl(env)}/devices/${encodeURIComponent(deviceId)}`;
    deletions[subcollection] = await deleteQuery(env, subcollection, [], { parent });
  }

  for (const session of sessionDocs) {
    await realtimeRequest(env, `webrtc/${session.id}`, "DELETE");
  }
  for (const prefix of [`photos/${deviceId}/`, `screenshots/${deviceId}/`]) {
    let cursor;
    do {
      const page = await env.MEDIA.list({ prefix, cursor, limit: 500 });
      if (page.objects.length) await env.MEDIA.delete(page.objects.map(object => object.key));
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
  }
  await realtimeRequest(env, `device_acl/${deviceId}`, "DELETE");
  await realtimeRequest(env, `stream_status/${deviceId}`, "DELETE");
  await realtimeRequest(env, `stream_frames/${deviceId}`, "DELETE");
  await realtimeRequest(env, `location/${deviceId}`, "DELETE");
  await realtimeRequest(env, `status/${deviceId}`, "DELETE");
  await realtimeRequest(env, `commands/${deviceId}`, "DELETE");
  await commitWrites(env, [
    { delete: `${documentsUrl(env)}/settings/${encodeURIComponent(deviceId)}` },
    { delete: device.path }
  ]);
  return json({ deviceId, deleted: deletions });
}

async function mediaRoute(request, env, user, segments) {
  const [kind, deviceId, filename] = segments;
  if (kind !== "photos" || !deviceId || (filename && !/^[A-Za-z0-9._-]{1,160}$/.test(filename))) {
    throw new HttpError(404, "Media not found");
  }
  const device = await getDocument(env, `devices/${deviceId}`);
  if (!device) throw new HttpError(404, "Media not found");
  const isChild = user.sub === deviceId && user.role === "device" && user.deviceId === deviceId;
  const isParent = user.sub === device.data.parentId;
  if (!isChild && !isParent) throw new HttpError(403, "Media access denied");

  if (request.method === "GET" && !filename) {
    if (!isParent) throw new HttpError(403, "Only the parent can list photos");
    const parent = `${documentsUrl(env)}/devices/${encodeURIComponent(deviceId)}`;
    const photos = await queryDocuments(env, "photos", [], { parent, limit: 500 });
    return json({ photos: photos.map(photo => photo.data) });
  }

  if (!filename) throw new HttpError(404, "Media not found");
  const key = `photos/${deviceId}/${filename}`;

  if (request.method === "PUT") {
    if (!isChild) throw new HttpError(403, "Only the paired device can upload photos");
    const contentType = request.headers.get("content-type") || "application/octet-stream";
    const contentLength = Number(request.headers.get("content-length") || 0);
    if (!contentType.startsWith("image/") || contentLength > 10 * 1024 * 1024) {
      throw new HttpError(413, "Photo must be an image smaller than 10 MB");
    }
    const body = await request.arrayBuffer();
    if (body.byteLength > 10 * 1024 * 1024) throw new HttpError(413, "Photo exceeds the 10 MB limit");
    const object = await env.MEDIA.put(key, body, {
      httpMetadata: { contentType },
      customMetadata: { deviceId, uploadedAt: new Date().toISOString() }
    });
    const url = `${new URL(request.url).origin}/v1/media/${segments.map(encodeURIComponent).join("/")}`;
    await setDocument(env, `devices/${deviceId}/photos/${encodeURIComponent(filename)}`, {
      deviceId, filename, objectKey: key, url, timestamp: new Date(), etag: object.etag
    }, true);
    return json({ objectKey: key, url });
  }

  if (request.method === "GET") {
    const object = await env.MEDIA.get(key);
    if (!object) throw new HttpError(404, "Media not found");
    const headers = new Headers();
    object.writeHttpMetadata(headers);
    headers.set("etag", object.httpEtag);
    headers.set("cache-control", "private, max-age=60");
    return new Response(object.body, { headers });
  }
  throw new HttpError(405, "Method not allowed");
}

async function sendAlertNotifications(env) {
  const cutoff = new Date(Date.now() - 5 * 60_000);
  const alerts = await queryDocuments(env, "alerts", [fieldFilter("timestamp", "GREATER_THAN_OR_EQUAL", cutoff)], { limit: 100 });
  const accessToken = await googleAccessToken(env);
  for (const alertDoc of alerts) {
    const alert = alertDoc.data;
    if (alert.notificationSentAt || !alert.deviceId) continue;
    const device = await getDocument(env, `devices/${alert.deviceId}`);
    if (!device?.data.parentId) continue;
    const parent = await getDocument(env, `users/${device.data.parentId}`);
    if (!parent?.data.fcmToken) continue;
    const response = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
      body: JSON.stringify({ message: {
        token: parent.data.fcmToken,
        notification: { title: alert.title || "GuardianLink alert", body: alert.message || "New child-device alert" },
        data: Object.fromEntries(Object.entries({
          alertId: alertDoc.id, deviceId: alert.deviceId, type: alert.type,
          severity: alert.severity, title: alert.title, body: alert.message
        }).map(([key, value]) => [key, String(value || "")]))
      } })
    });
    if (response.ok) await patchDocument(env, alertDoc.path.slice(`${documentsUrl(env)}/`.length), { notificationSentAt: new Date() });
  }
}

async function deleteExpiredPairingCodes(env) {
  const expired = await queryDocuments(env, "pairing_codes", [
    fieldFilter("expiresAt", "LESS_THAN", new Date()),
    fieldFilter("isUsed", "EQUAL", false)
  ], { limit: 400 });
  await deleteDocuments(env, expired);
  await cleanupPairingAttempts(env);
}

async function cleanupPairingAttempts(env) {
  const attempts = await realtimeRequest(env, "worker_pairing_attempts") || {};
  const cutoff = Math.floor(Date.now() / 60_000) - 60;
  const updates = {};
  for (const [ipHash, windows] of Object.entries(attempts)) {
    for (const minute of Object.keys(windows || {})) {
      if (Number(minute) < cutoff) updates[`worker_pairing_attempts/${ipHash}/${minute}`] = null;
    }
  }
  if (Object.keys(updates).length) await realtimeRequest(env, "", "PATCH", updates);
}

async function cleanupStreams(env) {
  const now = Date.now();
  const frames = await realtimeRequest(env, "stream_frames");
  const updates = {};
  for (const [deviceId, deviceFrames] of Object.entries(frames || {})) {
    for (const type of ["screen", "camera", "audio"]) {
      const frame = deviceFrames?.[type];
      if (frame?.ts && Number(frame.ts) < now - 10_000) updates[`${deviceId}/${type}`] = null;
    }
  }
  if (Object.keys(updates).length) await realtimeRequest(env, "stream_frames", "PATCH", updates);

  const sessions = await queryDocuments(env, "stream_sessions", [
    fieldFilter("expiresAt", "LESS_THAN_OR_EQUAL", new Date())
  ], { limit: 400 });
  for (const session of sessions) {
    if (["ACTIVE", "PENDING", "STARTING"].includes(session.data.status)) {
      await patchDocument(env, session.path.slice(`${documentsUrl(env)}/`.length), {
        status: "EXPIRED", isActive: false, endedAt: new Date(),
        failureReason: "Stream session expired", lastUpdatedAt: new Date()
      });
      await realtimeRequest(env, `webrtc/${session.id}`, "DELETE");
    }
  }
}

async function generateDailyReports(env) {
  const today = new Date().toISOString().slice(0, 10);
  const start = new Date(`${today}T00:00:00.000Z`);
  const devices = await queryDocuments(env, "devices", [fieldFilter("isActive", "EQUAL", true)], { limit: 400 });
  for (const device of devices) {
    const deviceId = device.id;
    const usage = await queryDocuments(env, "app_usage", [
      fieldFilter("deviceId", "EQUAL", deviceId), fieldFilter("date", "EQUAL", today)
    ], { orderBy: [{ field: { fieldPath: "foregroundTimeMs" }, direction: "DESCENDING" }], limit: 20 });
    const alerts = await queryDocuments(env, "alerts", [
      fieldFilter("deviceId", "EQUAL", deviceId), fieldFilter("timestamp", "GREATER_THAN_OR_EQUAL", start)
    ], { limit: 400 });
    const totalScreenTimeMs = usage.reduce((sum, row) => sum + Number(row.data.foregroundTimeMs || 0), 0);
    await setDocument(env, `reports/${deviceId}_${today}`, {
      deviceId, date: today, totalScreenTimeMs, topApps: usage.slice(0, 10).map(row => row.data),
      alertsTriggered: alerts.length, generatedAt: new Date()
    });
  }
}

async function cleanupSensitiveData(env) {
  const now = Date.now();
  const cutoffs = [
    ["location_history", "timestamp", 30], ["photos", "timestamp", 30],
    ["usage_summaries", "syncedAt", 90], ["alerts", "timestamp", 180],
    ["call_logs", "timestamp", 90], ["sms_logs", "timestamp", 90],
    ["app_usage", "timestamp", 90], ["reports", "generatedAt", 365],
    ["stream_sessions", "expiresAt", 7]
  ];
  for (const [collection, field, days] of cutoffs) {
    await deleteQuery(env, collection, [fieldFilter(field, "LESS_THAN", new Date(now - days * 86_400_000))], { allDescendants: true });
  }
  for (const prefix of ["photos/", "screenshots/"]) {
    let cursor;
    do {
      const page = await env.MEDIA.list({ prefix, cursor, limit: 500 });
      const expired = page.objects.filter(object => object.uploaded.getTime() < now - 30 * 86_400_000);
      if (expired.length) await env.MEDIA.delete(expired.map(object => object.key));
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
  }
  const telemetry = await realtimeRequest(env, "");
  const updates = {};
  for (const section of ["location", "status"]) {
    for (const [id, value] of Object.entries(telemetry?.[section] || {})) {
      const timestamp = Number(value.timestamp || value.lastSeen || 0);
      if (timestamp > 0 && timestamp < now - 86_400_000) updates[`${section}/${id}`] = null;
    }
  }
  if (Object.keys(updates).length) await realtimeRequest(env, "", "PATCH", updates);
}

async function scheduled(controller, env) {
  if (controller.cron === "* * * * *") {
    await Promise.all([cleanupStreams(env), sendAlertNotifications(env)]);
  } else if (controller.cron === "0 * * * *") {
    await deleteExpiredPairingCodes(env);
  } else if (controller.cron === "55 23 * * *") {
    await Promise.all([generateDailyReports(env), cleanupSensitiveData(env)]);
  }
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") return json({ ok: true });
      if (request.method === "POST" && url.pathname === "/v1/pairing/redeem") return await redeemPairing(request, env);

      const user = await firebaseUser(request, env);
      if (request.method === "POST" && url.pathname === "/v1/commands") return await submitCommand(request, env, user);
      if (request.method === "DELETE" && url.pathname.startsWith("/v1/children/")) {
        const deviceId = decodeURIComponent(url.pathname.split("/")[3] || "");
        return await deleteChildData(request, env, user, deviceId);
      }
      if (url.pathname.startsWith("/v1/media/")) {
        const segments = url.pathname.slice("/v1/media/".length).split("/").map(decodeURIComponent);
        return await mediaRoute(request, env, user, segments);
      }
      return fail(404, "Not found");
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      if (status === 500) console.error("Worker request failed", error);
      return fail(status, error.message || "Internal error");
    }
  },
  async scheduled(controller, env, context) {
    context.waitUntil(scheduled(controller, env));
  }
};
