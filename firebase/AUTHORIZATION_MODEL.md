# GuardianLink authorization model

## Identity and ownership

- A parent authenticates with Firebase Auth. `devices/{deviceId}.parentId` is the source of truth for parent ownership.
- A child device is provisioned by the trusted pairing backend. Its device document contains `authUid`; the device Auth token contains `role=device` and `deviceId`.
- The pairing backend atomically validates an unexpired unused code, creates the device and settings documents, marks the code used, and writes the RTDB ACL.
- Clients cannot create devices, consume pairing codes, or write the RTDB ACL.

## RTDB ACL

RTDB cannot query Firestore from rules. The backend mirrors membership at `device_acl/{deviceId}/{uid}` with `role=parent` or `role=device`. Every location, status, command, stream, and WebRTC rule checks that ACL. A parent can only read/write paths for an ACL-authorized device; a device can only publish its own telemetry and update its own command status.

## Pairing migration requirement

The current child client directly reads pairing codes and creates the device document. That flow must be changed to call the trusted pairing backend. Until that client/backend migration is complete, pairing is intentionally fail-closed rather than relying on a client claim.
