// firebase/functions/validateCommandWrite.test.js
//
// Unit tests for exports.validateCommandWrite (firebase/functions/index.js).
//
// These tests do NOT use the Firestore/RTDB emulator — there is no emulator
// binary available in this environment (it must be downloaded from Google's
// servers, which this sandbox cannot reach). Instead, `firebase-admin` is
// fully mocked so the function's actual decision logic executes for real,
// against a fake Firestore `mockDevices` collection we control per test. This
// means: the JS logic in validateCommandWrite is genuinely exercised and
// asserted against, but the separately-deployed RTDB *rules themselves*
// (database.rules.json) are NOT exercised by these tests — that would
// require `test:rules`/the emulator, which is a different, pre-existing
// test path in ../package.json and remains emulator-only exactly as before.
//
// firebase-functions-test is used in "offline" mode (test.wrap()) to pull
// the real handler out of the exports.validateCommandWrite CloudFunction
// wrapper, per Phase 3's instruction to use firebase-functions-test.

const path = require("path");

// ── Fake Firestore "mockDevices" collection ─────────────────────────────────────
// Tests populate this before each case via setDevice()/clearDevices().
let mockDevices = {};
function setDevice(deviceId, data) {
  mockDevices[deviceId] = data;
}
function clearDevices() {
  mockDevices = {};
}

jest.mock("firebase-admin", () => {
  const TimestampNow = { now: () => ({ toMillis: () => Date.now() }) };
  const firestoreFn = () => ({
    collection: (name) => ({
      doc: (id) => ({
        get: async () => {
          if (name !== "devices") {
            return { exists: false, data: () => undefined };
          }
          const data = mockDevices[id];
          return {
            exists: data !== undefined,
            data: () => data,
          };
        },
      }),
    }),
  });
  firestoreFn.Timestamp = TimestampNow;
  return {
    initializeApp: jest.fn(),
    firestore: firestoreFn,
    messaging: () => ({}),
    database: () => ({
      ref: () => ({ set: jest.fn().mockResolvedValue(undefined) }),
    }),
    auth: () => ({ setCustomUserClaims: jest.fn().mockResolvedValue(undefined) }),
  };
});

const functionsTest = require("firebase-functions-test")();
const myFunctions = require(path.join(__dirname, "index.js"));
const wrapped = functionsTest.wrap(myFunctions.validateCommandWrite);

// ── Change/context builder ──────────────────────────────────────────────────
// validateCommandWrite only ever touches change.before.exists()/.val() and
// change.after.val()/.ref.update(...) — build the minimal real shape rather
// than going through the RTDB DataSnapshot emulation machinery, which needs
// a live databaseURL we don't have here.
function makeChange(beforeVal, afterVal) {
  const updateCalls = [];
  const change = {
    before: {
      exists: () => beforeVal !== null,
      val: () => beforeVal,
    },
    after: {
      val: () => afterVal,
      ref: {
        update: jest.fn(async (obj) => {
          updateCalls.push(obj);
          return null;
        }),
      },
    },
  };
  change._updateCalls = updateCalls;
  return change;
}

function baseCommand(overrides = {}) {
  return {
    id: "cmd-1",
    parentId: "parent-1",
    childDeviceId: "device-1",
    type: "PING",
    payload: {},
    status: "PENDING",
    replayToken: "cmd-1",
    expiresAt: Date.now() + 5 * 60_000,
    createdAt: Date.now(),
    ...overrides,
  };
}

const CONTEXT = { params: { deviceId: "device-1", commandId: "cmd-1" } };

beforeEach(() => {
  clearDevices();
  setDevice("device-1", { parentId: "parent-1" });
});

afterAll(() => {
  functionsTest.cleanup();
});

// ── 1. Legitimate command creation ──────────────────────────────────────────
test("1. legitimate command creation is accepted with no correction", async () => {
  const change = makeChange(null, baseCommand());
  await wrapped(change, CONTEXT);
  expect(change.after.ref.update).not.toHaveBeenCalled();
});

// ── 2. Legitimate status update (child reporting progress) ─────────────────
test("2. legitimate status update (only status/error/lastUpdatedAt changed) is accepted", async () => {
  const before = baseCommand({ status: "PENDING" });
  const after = { ...before, status: "EXECUTING", lastUpdatedAt: Date.now(), error: null };
  const change = makeChange(before, after);
  await wrapped(change, CONTEXT);
  expect(change.after.ref.update).not.toHaveBeenCalled();
});

// ── 3. Command ID/key mismatch ───────────────────────────────────────────────
test("3. command whose id does not match its own RTDB key is rejected", async () => {
  const change = makeChange(null, baseCommand({ id: "some-other-id", replayToken: "some-other-id" }));
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "REJECTED", error: "Command id does not match the key it was stored under" },
  ]);
});

// ── 4. replayToken mismatch ──────────────────────────────────────────────────
test("4. command with a replayToken that doesn't match its id is rejected", async () => {
  const change = makeChange(null, baseCommand({ replayToken: "not-cmd-1" }));
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "REJECTED", error: "Command replay token does not match command id" },
  ]);
});

// ── 5. Immutable field modification attempt (payload tampered) ─────────────
test("5. tampering with an immutable field (payload) is detected and reverted", async () => {
  const before = baseCommand({ payload: { front: true } });
  const after = { ...before, payload: { front: true, injected: "evil" } };
  const change = makeChange(before, after);
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toHaveLength(1);
  const restore = change._updateCalls[0];
  expect(restore.status).toBe("REJECTED");
  expect(restore.error).toBe("Command body was modified after creation");
  expect(restore.payload).toEqual(before.payload); // restored, not the tampered value
});

// ── 6. Immutable field tampering (type) is reverted to the pre-write value ──
test("6. tampering with `type` is reverted to the pre-write value, not merely rejected", async () => {
  const before = baseCommand({ type: "PING" });
  const after = { ...before, type: "REBOOT" };
  const change = makeChange(before, after);
  await wrapped(change, CONTEXT);
  const restore = change._updateCalls[0];
  expect(restore.type).toBe("PING"); // reverted to before, NOT "REBOOT"
  expect(restore.status).toBe("REJECTED");
});

// ── 7. Self-recursion / corrective-write guard ──────────────────────────────
test("7. the function's own corrective write does not re-trigger correction (no infinite loop)", async () => {
  const before = baseCommand({ type: "PING" });
  // Simulate the state AFTER this function already corrected a tampered
  // record: `after` already carries the rejection this function itself
  // would have written.
  const corrected = { ...before, type: "PING", status: "REJECTED", error: "Command body was modified after creation" };
  const change = makeChange(before, corrected);
  await wrapped(change, CONTEXT);
  expect(change.after.ref.update).not.toHaveBeenCalled();
});

// ── 8. Unauthorized command (parentId doesn't own the device) ──────────────
test("8. a command from a parentId that doesn't own the device is rejected", async () => {
  setDevice("device-1", { parentId: "someone-else" });
  const change = makeChange(null, baseCommand({ parentId: "parent-1" }));
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "REJECTED", error: "Parent is not authorized for this device" },
  ]);
});

// ── 9. Malformed command data ───────────────────────────────────────────────
test("9. malformed command data (missing type) is rejected", async () => {
  const cmd = baseCommand();
  delete cmd.type;
  const change = makeChange(null, cmd);
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "REJECTED", error: "Command is malformed" },
  ]);
});

// ── 10. Valid command remains unchanged (no gratuitous mutation) ───────────
test("10. a fully valid, unmodified command is left completely alone", async () => {
  const cmd = baseCommand();
  const change = makeChange(null, cmd);
  const result = await wrapped(change, CONTEXT);
  expect(result).toBeNull();
  expect(change.after.ref.update).not.toHaveBeenCalled();
});

// ── Extra coverage beyond the required 10 ───────────────────────────────────

test("11. childDeviceId not matching the RTDB route deviceId is rejected", async () => {
  const change = makeChange(null, baseCommand({ childDeviceId: "some-other-device" }));
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "REJECTED", error: "Target device does not match the route" },
  ]);
});

test("12. command for a device that isn't registered is rejected", async () => {
  clearDevices(); // no mockDevices/device-1 doc at all
  const change = makeChange(null, baseCommand());
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "REJECTED", error: "Device is not registered" },
  ]);
});

test("13. an already-expired command is marked EXPIRED, not REJECTED", async () => {
  const change = makeChange(null, baseCommand({ expiresAt: Date.now() - 1000 }));
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "EXPIRED", error: "Command has expired" },
  ]);
});

test("14. an unsupported command type is rejected", async () => {
  const change = makeChange(null, baseCommand({ type: "DELETE_EVERYTHING" }));
  await wrapped(change, CONTEXT);
  expect(change._updateCalls).toEqual([
    { status: "REJECTED", error: "Unsupported command type" },
  ]);
});

test("15. a legitimate status update to a terminal state is accepted without correction", async () => {
  const before = baseCommand({ status: "EXECUTING" });
  const after = { ...before, status: "COMPLETED", lastUpdatedAt: Date.now() };
  const change = makeChange(before, after);
  await wrapped(change, CONTEXT);
  expect(change.after.ref.update).not.toHaveBeenCalled();
});
