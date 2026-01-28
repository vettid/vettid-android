# Backend Plan: Store Registration Profile to Vault During Enrollment

## Overview

During enrollment, the backend should send the user's registration profile (firstName, lastName, email) directly to the vault via NATS. The app will then fetch this data from the vault using `profile.get`.

## Current Flow

```
1. App calls /vault/enroll/nats-bootstrap
2. Backend fetches registration from DynamoDB
3. Backend returns registration_profile in API response
4. App stores locally (if present)
```

## Proposed Flow

```
1. App calls /vault/enroll/nats-bootstrap
2. Backend fetches registration from DynamoDB
3. Backend publishes profile.update to vault via NATS  <-- NEW
4. Backend returns registration_profile in API response (for backward compat)
5. App fetches from vault via profile.get
```

## File to Modify

**`cdk/lambda/handlers/vault/enrollNatsBootstrap.ts`**

## Changes

### 1. Add Import

```typescript
import { publishToNats } from '../../common/nats-publisher';
```

### 2. Add Helper Function

After the existing helper functions (~line 410), add:

```typescript
/**
 * Publish registration profile to user's vault via NATS.
 *
 * SECURITY: Uses system credentials with OwnerSpace.> publish permission.
 * The vault will store these as read-only system fields with _system_ prefix.
 *
 * @param ownerSpace - User's OwnerSpace ID (e.g., "OwnerSpace.{guid}")
 * @param profile - Registration profile data
 */
async function publishProfileToVault(
  ownerSpace: string,
  profile: RegistrationProfile
): Promise<void> {
  const subject = `${ownerSpace}.forVault.profile.update`;

  const payload = {
    request_id: `profile_init_${randomUUID()}`,
    fields: {
      '_system_first_name': profile.first_name,
      '_system_last_name': profile.last_name,
      '_system_email': profile.email,
      '_system_stored_at': Date.now().toString(),
    }
  };

  const result = await publishToNats(subject, payload);

  if (result.success) {
    console.log(`Published registration profile to vault for ${ownerSpace}`);
  } else {
    // Log but don't fail enrollment - profile can be synced later
    console.warn(`Failed to publish profile to vault: ${result.error}`);
  }
}
```

### 3. Call the Function

After creating the NATS token (~line 247), before the audit log, add:

```typescript
// Publish registration profile to vault if available
if (registrationProfile) {
  await publishProfileToVault(ownerSpace, registrationProfile);
}
```

## Security Considerations

1. **System Credentials**: Uses the existing system account which has `pub: { allow: ['OwnerSpace.>'] }` permission (line 162 in nats-publisher.ts)

2. **Read-Only Fields**: Profile fields use `_system_` prefix which the app treats as read-only (cannot be edited by user)

3. **Idempotent**: If called multiple times, vault's `profile.update` simply overwrites existing values

4. **Non-Blocking**: Failure to publish doesn't fail enrollment - profile can be synced later via app

5. **No Sensitive Data**: Only contains name and email from registration - no secrets or credentials

## Vault Handler

The vault's `profile.go` already handles `profile.update` messages (lines 129-196). No enclave changes needed.

The message format matches `ProfileUpdateRequest`:
```go
type ProfileUpdateRequest struct {
    Fields map[string]string `json:"fields"`
}
```

## Testing

1. Decommission and re-enroll
2. Check backend logs for "Published registration profile to vault"
3. Open Personal Data screen in app
4. Verify first name, last name, email appear as read-only system fields

## Rollback

If issues arise, simply remove the `publishProfileToVault` call. The existing API response flow remains as fallback.
