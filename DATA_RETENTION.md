# GuardianLink Data Lifecycle

The following retention policy applies after deployment of the scheduled cleanup function. A parent can permanently delete all data for a child device at any time through the authenticated `deleteChildData` action.

| Data | Primary storage | Readers | Writers | Retention | Deletion | Sensitivity |
| --- | --- | --- | --- | --- | --- | --- |
| Current location | RTDB `location/{deviceId}` | Authorized parent | Paired child | Replaced by newer data; stale values are removed after 24 hours | Device deletion or telemetry cleanup | Very high |
| Location history | Firestore `devices/{deviceId}/location_history` | Authorized parent/child | Paired child | 30 days | Parent cascade or parent data deletion | Very high |
| Photos | Storage `photos/{deviceId}` and Firestore photo metadata | Authorized parent/child | Paired child | 30 days | Parent Storage/metadata deletion or device cascade | Very high |
| Screenshots | Storage `screenshots/{deviceId}` | Authorized parent/child | Paired child | 30 days | Parent Storage deletion or device cascade | Very high |
| Audio/stream metadata | RTDB stream status/frames and Firestore `stream_sessions` | Authorized parent; child writes its own session | Paired child/authorized parent session creator | Frames 10 seconds; ended sessions 7 days | Expiry cleanup or device cascade | Very high |
| SMS | Firestore `sms_logs` | Authorized parent/child | Paired child | 90 days | Parent cascade or retention cleanup | Extremely high |
| Call logs | Firestore `call_logs` | Authorized parent/child | Paired child | 90 days | Parent cascade or retention cleanup | Extremely high |
| App usage | Firestore `app_usage` and device usage summaries | Authorized parent/child | Paired child | 90 days | Parent cascade or retention cleanup | High |
| Device information | Firestore `devices/{deviceId}` and settings | Authorized parent/paired child | Trusted pairing backend/parent settings | Until device deletion/unpairing | Parent `deleteChildData` cascade | High |
| Alerts | Firestore `alerts` | Authorized parent/child | Paired child | 180 days | Parent alert deletion or retention cleanup | High |
| Reports | Firestore `reports` | Authorized parent/child | Trusted scheduled function | 365 days | Parent cascade or retention cleanup | High |

The cleanup job is bounded and deletes only records older than these documented periods. It does not delete current records merely because the device is temporarily offline. Raw audio is not stored by the current product; only transient stream frames and session metadata exist.