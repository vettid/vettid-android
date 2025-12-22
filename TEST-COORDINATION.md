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
| `test-fixtures/test-invitations.json` | Backend | Pre-created test enrollment invitations |
| `test-fixtures/test-config.json` | Backend | Test environment configuration |

---

## Test Environment Status

| Component | Status | Last Updated | Notes |
|-----------|--------|--------------|-------|
| Test API Endpoint | NOT READY | - | Needs `/api/v1/test/*` endpoints |
| Test Invitations | NOT READY | - | Needs pre-generated invitations |
| Mock Attestation Support | NOT READY | - | API must accept `attestation_type: "test"` |
| Android Automation Build | READY | 2025-12-21 | `./gradlew assembleAutomationDebug` |

---

## Required Backend Changes

### 1. Test API Endpoints

```
POST /api/v1/test/create-invitation
Authorization: Bearer {test-api-key}
{
  "test_user_id": "string",
  "expires_in_seconds": 3600
}
→ {
    "invitation_code": "string",
    "qr_data": { ... },  // Full QR payload for direct use
    "expires_at": "ISO8601"
  }

POST /api/v1/test/cleanup
Authorization: Bearer {test-api-key}
{
  "test_user_id": "string"
}
→ { "status": "cleaned" }

GET /api/v1/test/health
→ { "status": "ready", "environment": "test" }
```

### 2. Mock Attestation Support

The enrollment endpoint must accept test attestation:

```json
{
  "invitation_code": "...",
  "device_id": "test-device-001",
  "attestation_data": "TEST_ATTESTATION",
  "attestation_type": "test"  // New field - skip attestation verification
}
```

This should ONLY be enabled when:
- Request includes valid test API key, OR
- Environment is explicitly configured for testing

### 3. Test Configuration

Create `test-fixtures/test-config.json`:

```json
{
  "test_api_base_url": "https://test-api.vettid.com",
  "test_api_key": "test_xxx",
  "test_user_prefix": "test_android_",
  "mock_attestation_enabled": true
}
```

---

## Required Android Changes

### 1. Test Build Flavor

- `testDebug` and `testRelease` build variants
- BuildConfig flags for test mode
- Mock attestation manager
- Auto-bypass biometric prompts
- Programmatic enrollment (no QR scanning required)

### 2. Test Runner

Automated test flow:
1. Read test config from `test-fixtures/`
2. Call backend health check
3. Create test invitation via API
4. Run enrollment flow with mock attestation
5. Run authentication flow
6. Cleanup test user
7. Report results

---

## Test Scenarios

| ID | Scenario | Android | Backend |
|----|----------|---------|---------|
| E2E-001 | Full enrollment flow | Needs test flavor | Needs test endpoints |
| E2E-002 | Authentication after enrollment | Needs test flavor | Ready |
| E2E-003 | Password change | Needs test flavor | Ready |
| E2E-004 | UTK pool replenishment | Needs test flavor | Ready |
| E2E-005 | LAT verification (anti-phishing) | Needs test flavor | Ready |

---

## Current Test Results

*No automated E2E tests run yet.*

---

## Coordination Log

| Date | Actor | Action |
|------|-------|--------|
| 2025-12-21 | Android Claude | Created TEST-COORDINATION.md |
| 2025-12-21 | Android Claude | Implemented automation build flavor with mock attestation and auto-biometric |
| 2025-12-21 | Android Claude | Build variants: `productionDebug`, `productionRelease`, `automationDebug`, `automationRelease` |

