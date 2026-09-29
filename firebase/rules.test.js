const fs = require('fs');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const {
  doc,
  setDoc,
  getDoc,
  updateDoc,
  deleteDoc,
} = require('firebase/firestore');
const {
  ref,
  set,
  get,
  update,
} = require('firebase/database');
const {
  ref: storageRef,
  uploadBytes,
  getBytes,
  deleteObject,
} = require('firebase/storage');

const projectId = 'guardianlink-rules-test';
let testEnv;

const device = (deviceId, parentId, authUid) => ({
  deviceId,
  parentId,
  authUid,
  isActive: true,
});

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId,
    firestore: { rules: fs.readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8080 },
    database: { rules: fs.readFileSync('database.rules.json', 'utf8'), host: '127.0.0.1', port: 9000 },
    ...(process.env.TEST_STORAGE === '1' ? {
      storage: { rules: fs.readFileSync('storage.rules', 'utf8'), host: '127.0.0.1', port: 9199 },
    } : {}),
  });

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const rtdb = context.database();
    await Promise.all([
      setDoc(doc(db, 'devices/childA'), device('childA', 'parentA', 'child-auth-a')),
      setDoc(doc(db, 'devices/childB'), device('childB', 'parentB', 'child-auth-b')),
      setDoc(doc(db, 'alerts/alertA'), { deviceId: 'childA', isRead: false, isResolved: false }),
      setDoc(doc(db, 'call_logs/callA'), { deviceId: 'childA', number: '+15551234567', timestamp: new Date() }),
      setDoc(doc(db, 'sms_logs/smsA'), { deviceId: 'childA', number: '+15551234567', body: 'test', timestamp: new Date() }),
      setDoc(doc(db, 'app_usage/usageA'), { deviceId: 'childA', packageName: 'example', timestamp: new Date() }),
      setDoc(doc(db, 'settings/childA'), { deviceId: 'childA', locationTrackingEnabled: true }),
      setDoc(doc(db, 'reports/reportA'), { deviceId: 'childA', date: '2026-09-24' }),
      setDoc(doc(db, 'devices/childA/location_history/locationA'), { deviceId: 'childA', latitude: 1, longitude: 2 }),
      setDoc(doc(db, 'devices/childA/usage_summaries/usageSummaryA'), { deviceId: 'childA', apps: [] }),
      setDoc(doc(db, 'stream_sessions/sessionA'), {
        sessionId: '123e4567-e89b-12d3-a456-426614174000',
        deviceId: 'childA', parentId: 'parentA', type: 'CAMERA_FRONT',
        status: 'PENDING', isActive: false,
        createdAt: new Date(), expiresAt: new Date(Date.now() + 600000),
        lastUpdatedAt: new Date(), failureReason: '',
      }),
      setDoc(doc(db, 'pairing_codes/codeA'), {
        code: '123456', parentId: 'parentA', isUsed: false,
        createdAt: new Date(), expiresAt: new Date(Date.now() + 600000),
      }),
      set(ref(rtdb, 'device_acl/childA/parentA'), { role: 'parent' }),
      set(ref(rtdb, 'device_acl/childA/child-auth-a'), { role: 'device' }),
      set(ref(rtdb, 'device_acl/childB/parentB'), { role: 'parent' }),
      set(ref(rtdb, 'device_acl/childB/child-auth-b'), { role: 'device' }),
      set(ref(rtdb, 'location/childA'), { lat: 1, lng: 2 }),
      set(ref(rtdb, 'location/childB'), { lat: 3, lng: 4 }),
      set(ref(rtdb, 'status/childA'), { online: true }),
      set(ref(rtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000'), {
        sessionId: '123e4567-e89b-12d3-a456-426614174000',
        deviceId: 'childA', parentId: 'parentA', type: 'CAMERA_FRONT',
        status: 'PENDING', expiresAt: Date.now() + 600000,
      }),
      set(ref(rtdb, 'stream_frames/childA/screen'), {
        frame: 'frame', sessionId: '123e4567-e89b-12d3-a456-426614174000', ts: 1,
      }),
    ]);
  });
});

afterAll(async () => testEnv.cleanup());

const parentA = () => testEnv.authenticatedContext('parentA').firestore();
const parentB = () => testEnv.authenticatedContext('parentB').firestore();
const childA = () => testEnv.authenticatedContext('child-auth-a', {
  role: 'device', deviceId: 'childA',
}).firestore();
const childB = () => testEnv.authenticatedContext('child-auth-b', {
  role: 'device', deviceId: 'childB',
}).firestore();
const anonymous = () => testEnv.unauthenticatedContext().firestore();

 describe('Firestore family authorization', () => {
  test('Parent A can read and update Child A, but not Child B', async () => {
    await assertSucceeds(getDoc(doc(parentA(), 'devices/childA')));
    await assertSucceeds(updateDoc(doc(parentA(), 'devices/childA'), { childName: 'A' }));
    await assertFails(getDoc(doc(parentA(), 'devices/childB')));
    await assertFails(updateDoc(doc(parentA(), 'devices/childB'), { childName: 'tamper' }));
  });

  test('Parent B can access Child B, but not Child A', async () => {
    await assertSucceeds(getDoc(doc(parentB(), 'devices/childB')));
    await assertFails(getDoc(doc(parentB(), 'devices/childA')));
  });

  test('unauthenticated and the wrong child cannot read another family', async () => {
    await assertFails(getDoc(doc(anonymous(), 'devices/childA')));
    await assertSucceeds(getDoc(doc(childA(), 'devices/childA')));
    await assertFails(getDoc(doc(childA(), 'devices/childB')));
    await assertFails(getDoc(doc(childB(), 'devices/childA')));
  });

  test('device creation is backend-only and cannot claim a parent', async () => {
    await assertFails(setDoc(doc(parentA(), 'devices/forged'), device('forged', 'parentB', 'parentA')));
    await assertFails(setDoc(doc(childA(), 'devices/forged'), device('forged', 'parentB', 'child-auth-a')));
  });

  test('device-scoped CRUD is ownership checked', async () => {
    const ownAlert = doc(childA(), 'alerts/newAlert');
    await assertSucceeds(setDoc(ownAlert, { deviceId: 'childA', isRead: false, isResolved: false }));
    await assertFails(setDoc(doc(childA(), 'alerts/foreignAlert'), { deviceId: 'childB' }));
    await assertSucceeds(updateDoc(doc(parentA(), 'alerts/alertA'), { isRead: true }));
    await assertFails(updateDoc(doc(parentB(), 'alerts/alertA'), { isRead: true }));
    await assertSucceeds(deleteDoc(doc(parentA(), 'alerts/newAlert')));
    await assertFails(deleteDoc(doc(parentB(), 'alerts/alertA')));
  });

    test('sensitive collections enforce family-scoped reads and writes', async () => {
      const ownPaths = [
        'call_logs/callA', 'sms_logs/smsA', 'app_usage/usageA', 'reports/reportA',
        'devices/childA/location_history/locationA',
        'devices/childA/usage_summaries/usageSummaryA',
        'settings/childA', 'alerts/alertA',
      ];
      for (const path of ownPaths) {
        await assertSucceeds(getDoc(doc(parentA(), path)));
        await assertSucceeds(getDoc(doc(childA(), path)));
        await assertFails(getDoc(doc(parentB(), path)));
        await assertFails(getDoc(doc(anonymous(), path)));
      }

      await assertSucceeds(setDoc(doc(childA(), 'call_logs/call-new'), { deviceId: 'childA' }));
      await assertSucceeds(setDoc(doc(childA(), 'sms_logs/sms-new'), { deviceId: 'childA' }));
      await assertSucceeds(setDoc(doc(childA(), 'app_usage/usage-new'), { deviceId: 'childA' }));
      await assertFails(setDoc(doc(childB(), 'call_logs/call-foreign'), { deviceId: 'childA' }));
      await assertSucceeds(deleteDoc(doc(parentA(), 'call_logs/call-new')));
      await assertSucceeds(deleteDoc(doc(parentA(), 'sms_logs/sms-new')));
      await assertSucceeds(deleteDoc(doc(parentA(), 'app_usage/usage-new')));
    });

    test('direct device deletion is blocked in favor of cascade deletion', async () => {
      await assertFails(deleteDoc(doc(parentA(), 'devices/childA')));
    });

  test('pairing codes are parent-owned, unexpired, and not client-redeemable', async () => {
    await assertSucceeds(getDoc(doc(parentA(), 'pairing_codes/codeA')));
    await assertFails(getDoc(doc(parentB(), 'pairing_codes/codeA')));
    await assertFails(getDoc(doc(childA(), 'pairing_codes/codeA')));
    await assertFails(updateDoc(doc(parentA(), 'pairing_codes/codeA'), { isUsed: true }));
    await assertFails(deleteDoc(doc(parentA(), 'pairing_codes/codeA')));
  });
});

describe('Realtime Database family authorization', () => {
  let parentARtdb;
  let parentBRtdb;
  let childARtdb;
  let unauthRtdb;

  beforeAll(() => {
    parentARtdb = testEnv.authenticatedContext('parentA').database();
    parentBRtdb = testEnv.authenticatedContext('parentB').database();
    childARtdb = testEnv.authenticatedContext('child-auth-a', {
      role: 'device', deviceId: 'childA',
    }).database();
    unauthRtdb = testEnv.unauthenticatedContext().database();
  });

  test('parents read only their family location/status/frames', async () => {
    await assertSucceeds(get(ref(parentARtdb, 'location/childA')));
    await assertFails(get(ref(parentARtdb, 'location/childB')));
    await assertSucceeds(get(ref(parentARtdb, 'status/childA')));
    await assertFails(get(ref(parentARtdb, 'stream_frames/childB/screen')));
    await assertFails(get(ref(unauthRtdb, 'location/childA')));
  });

  test('only the authorized parent reads stream data and the device writes it', async () => {
    await assertSucceeds(get(ref(parentARtdb, 'stream_frames/childA/screen')));
    await assertFails(get(ref(parentBRtdb, 'stream_frames/childA/screen')));
    await assertFails(get(ref(childARtdb, 'stream_frames/childA/screen')));
    await assertSucceeds(set(ref(childARtdb, 'stream_frames/childA/camera'), {
      frame: 'camera-frame',
      sessionId: '123e4567-e89b-12d3-a456-426614174000',
      ts: Date.now(),
    }));
    await assertFails(set(ref(childARtdb, 'stream_frames/childA/audio'), {
      chunk: 'audio-frame', sessionId: 'expired-session', ts: Date.now(),
    }));
  });

  test('WebRTC signaling is family-bound and expires', async () => {
    await assertSucceeds(get(ref(parentARtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000')));
    await assertFails(get(ref(parentBRtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000')));
    await assertSucceeds(set(ref(parentARtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000/offer'), 'offer-sdp'));
    await assertSucceeds(set(ref(childARtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000/answer'), 'answer-sdp'));
    await assertFails(set(ref(parentBRtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000/offer'), 'forged-offer'));
  });

  test('a device writes only its own telemetry', async () => {
    await assertSucceeds(set(ref(childARtdb, 'location/childA'), { lat: 8, lng: 9 }));
    await assertFails(set(ref(childARtdb, 'location/childB'), { lat: 8, lng: 9 }));
    await assertSucceeds(update(ref(childARtdb, 'status/childA'), { online: false }));
    await assertFails(update(ref(childARtdb, 'status/childB'), { online: false }));
  });

  test('commands require the authorized parent or the target device', async () => {
    const command = {
      id: 'commandA', parentId: 'parentA', childDeviceId: 'childA',
      type: 'PING', payload: {}, status: 'PENDING', createdAt: 1,
    };
    await assertFails(set(ref(parentARtdb, 'commands/childA/commandA'), command));
    await assertFails(set(ref(parentBRtdb, 'commands/childA/commandB'), command));
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await set(ref(context.database(), 'commands/childA/commandA'), command);
    });
    await assertSucceeds(update(ref(childARtdb, 'commands/childA/commandA'), { status: 'COMPLETED' }));
    await assertFails(update(ref(childARtdb, 'commands/childB/commandA'), { status: 'COMPLETED' }));
  });

  test('a device cannot alter immutable command fields while updating status', async () => {
    const command = {
      id: 'commandImmutable', parentId: 'parentA', childDeviceId: 'childA',
      type: 'PING', payload: { a: 1 }, status: 'PENDING', createdAt: 1,
    };
    await assertFails(set(ref(parentARtdb, 'commands/childA/commandImmutable'), command));
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await set(ref(context.database(), 'commands/childA/commandImmutable'), command);
    });
    // Changing payload alongside status must be rejected outright...
    await assertFails(update(ref(childARtdb, 'commands/childA/commandImmutable'), {
      status: 'COMPLETED', payload: { a: 999 },
    }));
    // ...as must changing type, parentId, or createdAt.
    await assertFails(update(ref(childARtdb, 'commands/childA/commandImmutable'), {
      status: 'COMPLETED', type: 'REBOOT',
    }));
    await assertFails(update(ref(childARtdb, 'commands/childA/commandImmutable'), {
      status: 'COMPLETED', parentId: 'parentB',
    }));
    await assertFails(update(ref(childARtdb, 'commands/childA/commandImmutable'), {
      status: 'COMPLETED', createdAt: 999,
    }));
    // A status-only update to the same untouched command must still succeed.
    await assertSucceeds(update(ref(childARtdb, 'commands/childA/commandImmutable'), {
      status: 'COMPLETED',
    }));
  });

  test('unauthenticated and cross-family users cannot access stream status or signaling', async () => {
    await assertFails(get(ref(unauthRtdb, 'stream_status/childA')));
    await assertFails(get(ref(parentBRtdb, 'stream_status/childA')));
    await assertSucceeds(get(ref(parentARtdb, 'stream_status/childA')));
    await assertFails(get(ref(unauthRtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000')));
    await assertFails(get(ref(parentBRtdb, 'webrtc/123e4567-e89b-12d3-a456-426614174000')));
  });
});

describe('Storage family authorization', () => {
  (process.env.TEST_STORAGE === '1' ? test : test.skip)('child writes and authorized parent reads/deletes media', async () => {
    const childStorage = testEnv.authenticatedContext('child-auth-a').storage();
    const parentStorage = testEnv.authenticatedContext('parentA').storage();
    const wrongParentStorage = testEnv.authenticatedContext('parentB').storage();
    const anonymousStorage = testEnv.unauthenticatedContext().storage();
    const path = 'photos/childA/rules-test.jpg';
    await assertSucceeds(uploadBytes(storageRef(childStorage, path), new Uint8Array([1, 2, 3]), {
      contentType: 'image/jpeg',
    }));
    await assertSucceeds(getBytes(storageRef(parentStorage, path)));
    await assertFails(getBytes(storageRef(wrongParentStorage, path)));
    await assertFails(getBytes(storageRef(anonymousStorage, path)));
    await assertFails(deleteObject(storageRef(childStorage, path)));
    await assertSucceeds(deleteObject(storageRef(parentStorage, path)));
  });
});

describe('Stream session lifecycle', () => {
  test('parent creates pending session and only its child can advance it', async () => {
    const sessionId = '123e4567-e89b-12d3-a456-426614174001';
    const session = doc(parentA(), `stream_sessions/${sessionId}`);
    await assertSucceeds(setDoc(session, {
      sessionId, deviceId: 'childA', parentId: 'parentA',
      type: 'AUDIO', status: 'PENDING', isActive: false,
      createdAt: new Date(), expiresAt: new Date(Date.now() + 600000),
    }));
    await assertSucceeds(updateDoc(doc(childA(), `stream_sessions/${sessionId}`), {
      status: 'ACTIVE', isActive: true, lastUpdatedAt: new Date(),
    }));
    await assertFails(updateDoc(doc(childB(), `stream_sessions/${sessionId}`), {
      status: 'ACTIVE', isActive: true,
    }));
  });
});

describe('Secure pairing contract', () => {
  const validPairingCode = {
    code: '123456',
    parentId: 'parentA',
    createdAt: new Date(),
    expiresAt: new Date(Date.now() + 600000),
    isUsed: false,
  };

  test('valid pairing is parent-owned and active', async () => {
    await assertSucceeds(setDoc(doc(testEnv.authenticatedContext('parentA').firestore(), 'pairing_codes/validPair'), validPairingCode));
    await assertFails(setDoc(doc(testEnv.authenticatedContext('parentB').firestore(), 'pairing_codes/validPair'), validPairingCode));
  });

  test('expired codes are rejected', async () => {
    await assertFails(setDoc(doc(testEnv.authenticatedContext('parentA').firestore(), 'pairing_codes/expiredPair'), {
      ...validPairingCode,
      code: '111111',
      expiresAt: new Date(Date.now() - 60000),
    }));
  });

  test('reused codes remain unusable', async () => {
    await assertFails(updateDoc(doc(testEnv.authenticatedContext('parentA').firestore(), 'pairing_codes/codeA'), { isUsed: true }));
  });

  test('wrong parent cannot claim a child device', async () => {
    await assertFails(setDoc(doc(testEnv.authenticatedContext('parentB').firestore(), 'devices/childA'), {
      deviceId: 'childA',
      parentId: 'parentB',
      authUid: 'child-auth-a',
      isActive: true,
    }));
  });

  test('wrong device cannot be paired to the authenticated child', async () => {
    await assertFails(setDoc(doc(testEnv.authenticatedContext('child-auth-a').firestore(), 'devices/childB'), {
      deviceId: 'childB',
      parentId: 'parentA',
      authUid: 'child-auth-a',
      isActive: true,
    }));
  });

  test('simultaneous unauthorized pairing attempts fail closed', async () => {
    const wrongParent = testEnv.authenticatedContext('parentB').firestore();
    const wrongChild = testEnv.authenticatedContext('child-auth-a').firestore();
    await Promise.all([
      assertFails(setDoc(doc(wrongParent, 'devices/childA'), {
        deviceId: 'childA',
        parentId: 'parentB',
        authUid: 'child-auth-a',
        isActive: true,
      })),
      assertFails(setDoc(doc(wrongChild, 'devices/childB'), {
        deviceId: 'childB',
        parentId: 'parentA',
        authUid: 'child-auth-a',
        isActive: true,
      })),
    ]);
  });

  test('unauthorized pairing is denied', async () => {
    await assertFails(setDoc(doc(testEnv.unauthenticatedContext().firestore(), 'devices/childA'), {
      deviceId: 'childA',
      parentId: 'parentA',
      authUid: 'anonymous',
      isActive: true,
    }));
  });
});
