import assert from "node:assert/strict";
import test from "node:test";
import worker from "../src/index.js";

const env = {
  FIREBASE_PROJECT_ID: "guardianlink-b9f5d",
  FIREBASE_DATABASE_URL: "https://guardianlink-b9f5d-default-rtdb.asia-southeast1.firebasedatabase.app"
};

test("health endpoint responds without backend credentials", async () => {
  const response = await worker.fetch(new Request("https://worker.test/health"), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true });
});

test("command endpoint requires a Firebase ID token", async () => {
  const response = await worker.fetch(new Request("https://worker.test/v1/commands", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceId: "child-1", commandId: "command-1", command: {} })
  }), env);
  assert.equal(response.status, 401);
});

test("private media endpoint requires a Firebase ID token", async () => {
  const response = await worker.fetch(new Request("https://worker.test/v1/media/photos/child-1/photo.jpg"), env);
  assert.equal(response.status, 401);
});
