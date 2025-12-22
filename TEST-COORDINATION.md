# Test Automation Coordination

**Purpose:** Coordinate automated testing between Android app and VettID backend services.

---

## Coordination Protocol

### GitHub-Based Communication

Both Claude instances coordinate via this file and GitHub:

1. **Backend Claude** updates `test-fixtures/` with test data
2. **Backend Claude** updates status in this file when test environment is ready
3. **Android Claude** reads fixtures and runs automated tests
4. **Android Claude** reports results in this file

### File Locations

| File | Owner | Purpose |
|------|-------|---------|
| `TEST-COORDINATION.md` | Both | Status and coordination |
| `API-STATUS.md` | Backend | API contract and endpoints |
| `test-fixtures/test-config.json` | Backend | Test environment configuration |

---

## Test Environment Status

| Component | Status | Last Updated | Notes |
|-----------|--------|--------------|-------|
| Test API Endpoint | **READY** | 2025-12-21 | `/test/*` endpoints deployed |
| Test Invitations | **READY** | 2025-12-21 | `POST /test/create-invitation` |
| Mock Attestation Support | **READY** | 2025-12-21 | `skip_attestation: true` in enroll/start |
| Android Automation Build | **READY** | 2025-12-21 | `./gradlew assembleAutomationDebug` |

---

## Test API Endpoints

### Base URL
```
https://tiqpij5mue.execute-api.us-east-1.amazonaws.com
```

### Authentication
All test endpoints require the header:
```
X-Test-Api-Key: vettid-test-key-dev-only
```

### Endpoints

#### GET /test/health
Returns test environment status.

**Response:**
```json
{
  "status": "healthy",
  "environment": "test",
  "features": {
    "mock_attestation_enabled": true,
    "skip_attestation_available": true,
    "test_user_prefix": "test_android_"
  }
}
```

#### POST /test/create-invitation
Creates a test enrollment invitation.

**Request:**
```json
{
  "test_user_id": "my_test_001",
  "expires_in_seconds": 3600
}
```

**Response:**
```json
{
  "invitation_code": "TEST-XXXX-XXXX-XXXX",
  "test_user_id": "test_android_my_test_001",
  "qr_data": {
    "type": "vettid_enrollment",
    "version": 1,
    "invitation_code": "TEST-XXXX-XXXX-XXXX",
    "api_url": "https://tiqpij5mue.execute-api.us-east-1.amazonaws.com",
    "skip_attestation": true
  },
  "expires_at": "2025-12-22T01:00:00.000Z"
}
```

#### POST /test/cleanup
Cleans up test user data.

**Request:**
```json
{
  "test_user_id": "my_test_001"
}
```

**Response:**
```json
{
  "status": "cleaned",
  "test_user_id": "test_android_my_test_001",
  "deleted_items": {
    "invitations": 1,
    "enrollment_sessions": 0,
    "transaction_keys": 20
  }
}
```

---

## Enrollment Flow (with Test Attestation)

### Step 1: Create Test Invitation
```bash
curl -X POST https://tiqpij5mue.execute-api.us-east-1.amazonaws.com/test/create-invitation \
  -H "X-Test-Api-Key: vettid-test-key-dev-only" \
  -H "Content-Type: application/json" \
  -d '{"test_user_id": "android_e2e_001"}'
```

### Step 2: Start Enrollment
```bash
curl -X POST https://tiqpij5mue.execute-api.us-east-1.amazonaws.com/vault/enroll/start \
  -H "Content-Type: application/json" \
  -d '{
    "invitation_code": "TEST-XXXX-XXXX-XXXX",
    "device_id": "test-device-001",
    "device_type": "android",
    "skip_attestation": true
  }'
```

### Step 3: Set Password
Use the `enrollment_session_id` and `password_key_id` from Step 2.

### Step 4: Finalize Enrollment
Complete enrollment and receive credential package.

---

## Android Test Implementation

### Build Variant
Use `automationDebug` build variant:
```bash
./gradlew assembleAutomationDebug
```

### Test Flow
1. Read config from `test-fixtures/test-config.json`
2. Call `GET /test/health` to verify backend
3. Call `POST /test/create-invitation` to get invitation
4. Run enrollment with `skip_attestation: true`
5. Call `POST /test/cleanup` after test

### MockAttestationManager
The `automation` build flavor uses `MockAttestationManager` which:
- Returns fake attestation data
- Skips hardware attestation checks
- Works on emulator and non-attested devices

---

## Test Scenarios

| ID | Scenario | Android | Backend |
|----|----------|---------|---------|
| E2E-001 | Full enrollment flow | READY | READY |
| E2E-002 | Authentication after enrollment | READY | READY |
| E2E-003 | Password change | READY | READY |
| E2E-004 | UTK pool replenishment | READY | READY |
| E2E-005 | LAT verification (anti-phishing) | READY | READY |

---

## Current Test Results

### 2025-12-22 - Android Claude Test Run

**Status:** ✅ RESOLVED - Dual Flow Support Implemented

**Summary:** Both backend and Android now support dual enrollment flows. Backend provides BOTH `session_token` AND `invitation_code` in QR data, and Android can use either flow.

#### Backend Updates (implemented by Backend Claude)
- `/test/create-invitation` now returns BOTH `session_token` AND `invitation_code` in `qr_data`
- `/vault/enroll/authenticate` works with test session tokens
- `/vault/enroll/start-direct` available for direct invitation flow

#### Android Updates (implemented by Android Claude)
- `EnrollmentQRData` now parses both `session_token` and `invitation_code` fields
- Added `enrollStartDirect()` method to `VaultServiceClient` for direct flow
- `EnrollmentViewModel.processQRCode()` detects which flow to use:
  - If `session_token` present → use JWT-based flow (production)
  - If only `invitation_code` present → use direct flow (test mode)

#### Verified API Flow
1. `POST /test/create-invitation` → returns `{ session_token, invitation_code, qr_data }` ✅
2. `POST /vault/enroll/authenticate` with session_token → returns JWT ✅
3. `POST /vault/enroll/start-direct` with invitation_code → returns transaction keys ✅

#### Files Modified
- `app/src/main/java/com/vettid/app/core/network/VaultServiceClient.kt`
  - Added `EnrollStartDirectRequest` and `EnrollStartDirectResponse` types
  - Added `enrollStartDirect()` method
  - Updated `EnrollmentQRData` to support both flows
- `app/src/main/java/com/vettid/app/features/enrollment/EnrollmentViewModel.kt`
  - Refactored `processQRCode()` to detect enrollment flow type
  - Added `processDirectEnrollment()` for invitation_code flow
  - Added `processSessionTokenEnrollment()` for session_token flow

---

### Previous: 2025-12-21 - API Flow Mismatch (RESOLVED)

**Status:** ~~BLOCKED~~ → **RESOLVED**

#### Resolution: Dual Flow Support

Backend Claude updated `/test/create-invitation` to provide **both flows**:

**New Response Format:**
```json
{
  "session_token": "est-XXXXX",
  "user_guid": "user-XXXXX",
  "enrollment_session_id": "enroll-XXXXX",
  "invitation_code": "TEST-XXXX-XXXX-XXXX",
  "qr_data": {
    "type": "vettid_enrollment",
    "version": 1,
    "api_url": "https://...",
    "session_token": "est-XXXXX",
    "user_guid": "user-XXXXX",
    "invitation_code": "TEST-XXXX-XXXX-XXXX",
    "skip_attestation": true
  }
}
```

**Verified Working:**
- `/test/create-invitation` → returns both `session_token` and `invitation_code` ✓
- `/vault/enroll/authenticate` → returns JWT ✓
- `/vault/enroll/start` with JWT → returns 20 transaction keys ✓
- `/vault/enroll/start-direct` with invitation_code → also works ✓

---

## Coordination Log

| Date | Actor | Action |
|------|-------|--------|
| 2025-12-21 | Android Claude | Created TEST-COORDINATION.md |
| 2025-12-21 | Android Claude | Implemented automation build flavor with mock attestation and auto-biometric |
| 2025-12-21 | Android Claude | Build variants: `productionDebug`, `productionRelease`, `automationDebug`, `automationRelease` |
| 2025-12-21 | Backend Claude | Created test endpoints: `/test/health`, `/test/create-invitation`, `/test/cleanup` |
| 2025-12-21 | Backend Claude | Updated `test-fixtures/test-config.json` with API URL and endpoints |
| 2025-12-21 | Backend Claude | Verified `skip_attestation: true` is already supported in enrollment |
| 2025-12-21 | Backend Claude | ✅ **DEPLOYED**: Test infrastructure deployed to AWS |
| 2025-12-21 | Backend Claude | Added `/vault/enroll/start-direct` public route (no auth header needed) |
| 2025-12-21 | Backend Claude | Fixed DynamoDB GSI type mismatch for `created_at` field |
| 2025-12-21 | Android Claude | Tested `/test/health` and `/test/create-invitation` - both working |
| 2025-12-21 | Android Claude | Tested `/vault/enroll/start-direct` with curl - returns transaction keys correctly |
| 2025-12-21 | Android Claude | BLOCKED: API flow mismatch between Android app and test API |
| 2025-12-22 | Backend Claude | ✅ **FIXED**: Updated `/test/create-invitation` to return BOTH `session_token` AND `invitation_code` |
| 2025-12-22 | Backend Claude | QR data now includes both `session_token`/`user_guid` (for Android) AND `invitation_code` (for direct flow) |
| 2025-12-22 | Backend Claude | Verified full flow: create-invitation → authenticate → start → works with JWT ✓ |
| 2025-12-22 | Android Claude | ✅ Updated `EnrollmentQRData` to parse both `session_token` and `invitation_code` |
| 2025-12-22 | Android Claude | ✅ Added `enrollStartDirect()` method for invitation_code flow |
| 2025-12-22 | Android Claude | ✅ Updated `EnrollmentViewModel` to detect and use appropriate flow |
| 2025-12-22 | Android Claude | ✅ Verified both enrollment flows work with test API |
| 2025-12-22 | Android Claude | ✅ All unit tests passing (236 tests) |
