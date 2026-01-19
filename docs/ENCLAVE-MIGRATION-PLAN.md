# Enclave-to-Enclave Credential Migration Plan

## Overview

When enclave code is updated, PCRs change. Sealed DEKs bound to old PCRs cannot be unsealed by new enclave code. This document covers migration strategy based on Section 10 of NITRO-ENCLAVE-VAULT-ARCHITECTURE.md, with additional security controls.

---

## Architecture Understanding

### The Challenge
```
Old Enclave (PCR: abc123)     New Enclave (PCR: def456)
├─ Can unseal old keys        ├─ CANNOT unseal old keys
└─ Running                    └─ Running

Problem: How to transition without losing access to user data?
```

### Key Insight
- **Sealed material** (DEK) is stored in S3 per user
- **Old enclave** is the ONLY thing that can unseal old material
- **Nitro KMS** allows re-sealing for different PCRs from within the enclave
- **Migration happens server-side** with user notification after completion
- **Nitro memory isolation** protects plaintext DEK during migration (hardware-enforced)

---

## Migration Process

### Phase Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Phase 1: Preparation                                                    │
│  ─────────────────────                                                  │
│  1. Build new enclave image in CI/CD                                    │
│  2. Extract new PCR values (PCR0, PCR1, PCR2)                           │
│  3. Sign PCR values with deployment key                                 │
│  4. Store signed PCRs in Secrets Manager                                │
│                                                                         │
│  Phase 2: KMS Policy Update                                             │
│  ──────────────────────────                                             │
│  5. Update KMS key policy to allow BOTH old AND new PCRs                │
│     (Safe - new enclave doesn't exist yet)                              │
│                                                                         │
│  Phase 3: Deploy + Migrate                                              │
│  ─────────────────────────                                              │
│  6. Deploy new enclave fleet (idle)                                     │
│  7. Old enclave fetches signed new PCRs, verifies signature             │
│  8. Old enclave runs migration (per-user locking, state tracking)       │
│  9. New enclave runs warmup verification per user                       │
│                                                                         │
│  Phase 4: Cutover + Cleanup                                             │
│  ──────────────────────────                                             │
│  10. Switch traffic to new enclave                                      │
│  11. Wait for verification period (24-48h)                              │
│  12. Update KMS policy to REMOVE old PCRs                               │
│  13. Terminate old enclave fleet                                        │
│  14. Vault manages old sealed_material expiry (7 days)                  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Critical Requirements

> **IMPORTANT:** Both old AND new enclave fleets MUST be running simultaneously during migration.
> - Old enclave: Required to unseal existing material (only it has the old PCRs)
> - New enclave: Required to verify re-sealed material works before cutover
>
> **Migration Window:** Do NOT terminate old enclave until:
> 1. All users' sealed material has been migrated
> 2. Warmup verification has passed for each user on new enclave
> 3. Old sealed_material retained for 7+ days (vault-managed)
>
> **Cannot Migrate If:**
> - Old enclave is already terminated (material cannot be unsealed)
> - New enclave is not yet deployed (cannot verify re-sealed material)
> - KMS key policy doesn't allow both PCR values

---

## User Notification Model

**Approach: Notify After Migration (Server-Side)**

Migration happens automatically without blocking the user. Users are notified after completion.

```
┌─────────────────────────────────────────────────────────────────┐
│  User Experience                                                 │
│                                                                 │
│  1. Migration runs server-side (user unaware)                   │
│                                                                 │
│  2. User opens app after migration complete                     │
│     ┌─────────────────────────────────────────────────────┐    │
│     │  ✓ Security Update Applied                          │    │
│     │                                                     │    │
│     │  Your vault has been migrated to a new secure      │    │
│     │  enclave. No action required.                       │    │
│     │                                                     │    │
│     │  [View Details]              [Dismiss]             │    │
│     └─────────────────────────────────────────────────────┘    │
│                                                                 │
│  3. Audit log entry created (visible in app security settings)  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Why this approach:**
- No user action required for routine security updates
- Users who don't open app frequently are still protected
- Audit trail provides transparency
- Emergency recovery (PIN required) still requires explicit user action

---

## Backend Implementation

### 1. Signed PCR Configuration

PCR values are signed by deployment key in CI/CD, stored in Secrets Manager.

```go
// Stored in AWS Secrets Manager
type SignedPCRConfig struct {
    NewPCRs   PCRValues `json:"new_pcrs"`
    OldPCRs   PCRValues `json:"old_pcrs"`
    ValidFrom time.Time `json:"valid_from"`
    Signature string    `json:"signature"`  // Ed25519 signature
}

// Old enclave fetches and verifies before migration
func getNewPCRsForMigration() (PCRValues, error) {
    // 1. Fetch from Secrets Manager
    config, err := fetchPCRConfig()
    if err != nil {
        return PCRValues{}, err
    }

    // 2. Verify signature with embedded deployment public key
    if !verifyPCRSignature(config, deploymentPublicKey) {
        return PCRValues{}, errors.New("invalid PCR signature - refusing to migrate")
    }

    // 3. Check validity window
    if time.Now().Before(config.ValidFrom) {
        return PCRValues{}, errors.New("PCR config not yet valid")
    }

    return config.NewPCRs, nil
}
```

**Trust chain:**
```
CI/CD Pipeline (trusted)
    │
    ├── Builds enclave image
    ├── Extracts PCR values (deterministic from code)
    ├── Signs with deployment private key
    │
    ▼
Secrets Manager (stores signed config)
    │
    ▼
Old Enclave (has deployment PUBLIC key embedded)
    │
    ├── Fetches signed config
    ├── Verifies signature ← Proves PCRs came from CI/CD
    ├── Seals for new PCRs
    │
    ▼
New Enclave (PCRs match by construction)
```

### 2. Sealed Material Version Management

Vault manages multiple versions with verification and expiry.

```go
type SealedMaterialVersion struct {
    Version     int        `json:"version"`
    PCRVersion  string     `json:"pcr_version"`
    SealedData  []byte     `json:"sealed_data"`
    CreatedAt   time.Time  `json:"created_at"`
    VerifiedAt  *time.Time `json:"verified_at"`   // Set after warmup passes
    ExpiresAt   *time.Time `json:"expires_at"`    // Set when new version verified
}

type UserMigrationState struct {
    UserID          string    `json:"user_id"`
    CurrentVersion  int       `json:"current_version"`
    MigrationStatus string    `json:"status"`  // pending, migrating, verifying, complete, failed
    LockedAt        *time.Time `json:"locked_at"`
    LockedBy        string    `json:"locked_by"`  // enclave instance ID
    LastError       string    `json:"last_error"`
    UpdatedAt       time.Time `json:"updated_at"`
}
```

### 3. Migration with Per-User Locking

```go
// Run in OLD enclave during migration
func migrateSealedMaterialToNewPCRs(newPCRs PCRValues) error {
    users, err := listUsersNeedingMigration()
    if err != nil {
        return err
    }

    for _, userID := range users {
        // Acquire per-user lock (prevents concurrent access issues)
        lock, err := acquireUserMigrationLock(userID, 5*time.Minute)
        if err != nil {
            log.Warn().Str("user", userID).Msg("Could not acquire lock, skipping")
            continue
        }
        defer lock.Release()

        // Update state to migrating
        updateMigrationState(userID, "migrating")

        // Load current sealed material
        currentVersion, err := getCurrentSealedMaterialVersion(userID)
        if err != nil {
            updateMigrationState(userID, "failed", err.Error())
            continue
        }

        // Unseal with current (old) attestation
        material, err := nitro.Unseal(currentVersion.SealedData)
        if err != nil {
            updateMigrationState(userID, "failed", err.Error())
            continue
        }

        // Re-seal for new PCRs
        newSealedMaterial, err := nitro.SealForPCRs(material, newPCRs)
        if err != nil {
            zeroize(material)
            updateMigrationState(userID, "failed", err.Error())
            continue
        }

        // Zero out plaintext material immediately
        zeroize(material)

        // Store new version (alongside old)
        newVersion := &SealedMaterialVersion{
            Version:    currentVersion.Version + 1,
            PCRVersion: newPCRs.String(),
            SealedData: newSealedMaterial,
            CreatedAt:  time.Now(),
        }
        err = storeSealedMaterialVersion(userID, newVersion)
        if err != nil {
            updateMigrationState(userID, "failed", err.Error())
            continue
        }

        // Update state to verifying (warmup will complete it)
        updateMigrationState(userID, "verifying")

        // Create audit log entry
        createAuditLogEntry(userID, "migration_sealed_material", map[string]any{
            "old_version": currentVersion.Version,
            "new_version": newVersion.Version,
            "old_pcrs":    currentVersion.PCRVersion,
            "new_pcrs":    newVersion.PCRVersion,
        })

        log.Info().Str("user", userID).Int("version", newVersion.Version).Msg("Sealed material migrated")
    }

    return nil
}
```

### 4. Warmup Verification (runs in NEW enclave)

```go
// Run in NEW enclave after migration
func verifyMigratedMaterial(userID string) error {
    // Get latest version (should be the migrated one)
    version, err := getLatestSealedMaterialVersion(userID)
    if err != nil {
        return err
    }

    // Verify this version hasn't been verified yet
    if version.VerifiedAt != nil {
        return nil // Already verified
    }

    // Unseal with new enclave (proves new PCRs work)
    plaintext, err := nitro.Unseal(version.SealedData)
    if err != nil {
        updateMigrationState(userID, "failed", "warmup unseal failed: "+err.Error())
        return err
    }

    // Verify checksum/integrity
    if !verifyMaterialIntegrity(plaintext) {
        zeroize(plaintext)
        updateMigrationState(userID, "failed", "integrity check failed")
        return errors.New("integrity check failed")
    }

    // Zero out plaintext
    zeroize(plaintext)

    // Mark as verified
    now := time.Now()
    version.VerifiedAt = &now
    updateSealedMaterialVersion(userID, version)

    // Schedule old version for expiry (7 days)
    oldVersion := version.Version - 1
    expiresAt := now.Add(7 * 24 * time.Hour)
    scheduleVersionExpiry(userID, oldVersion, expiresAt)

    // Update migration state to complete
    updateMigrationState(userID, "complete")

    // Update audit log
    createAuditLogEntry(userID, "migration_verified", map[string]any{
        "version":     version.Version,
        "verified_at": now,
    })

    log.Info().Str("user", userID).Int("version", version.Version).Msg("Migration verified")
    return nil
}
```

### 5. Version Expiry Management

```go
// Background job to cleanup expired versions
func cleanupExpiredVersions() {
    expiredVersions, err := listExpiredVersions()
    if err != nil {
        log.Error().Err(err).Msg("Failed to list expired versions")
        return
    }

    for _, v := range expiredVersions {
        // Double-check newer version is verified before deleting
        newerVersion, err := getSealedMaterialVersion(v.UserID, v.Version+1)
        if err != nil || newerVersion.VerifiedAt == nil {
            log.Warn().Str("user", v.UserID).Msg("Skipping cleanup - newer version not verified")
            continue
        }

        // Delete expired version
        err = deleteSealedMaterialVersion(v.UserID, v.Version)
        if err != nil {
            log.Error().Err(err).Str("user", v.UserID).Int("version", v.Version).Msg("Failed to delete expired version")
            continue
        }

        createAuditLogEntry(v.UserID, "migration_old_version_deleted", map[string]any{
            "deleted_version": v.Version,
            "active_version":  newerVersion.Version,
        })

        log.Info().Str("user", v.UserID).Int("version", v.Version).Msg("Deleted expired sealed material version")
    }
}
```

---

## CI/CD Pipeline

### GitHub Actions Workflow

```yaml
name: Build and Deploy Enclave

on:
  push:
    branches: [main]
    paths: ['enclave/**']

jobs:
  build-enclave:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Build enclave image
        run: |
          nitro-cli build-enclave \
            --docker-uri ${{ env.ENCLAVE_IMAGE }} \
            --output-file enclave.eif

      - name: Extract PCRs
        id: pcrs
        run: |
          nitro-cli describe-eif --eif-path enclave.eif > pcr-output.json
          echo "pcr0=$(jq -r '.Measurements.PCR0' pcr-output.json)" >> $GITHUB_OUTPUT
          echo "pcr1=$(jq -r '.Measurements.PCR1' pcr-output.json)" >> $GITHUB_OUTPUT
          echo "pcr2=$(jq -r '.Measurements.PCR2' pcr-output.json)" >> $GITHUB_OUTPUT

      - name: Sign PCR config
        run: |
          cat > pcr-config.json << EOF
          {
            "new_pcrs": {
              "pcr0": "${{ steps.pcrs.outputs.pcr0 }}",
              "pcr1": "${{ steps.pcrs.outputs.pcr1 }}",
              "pcr2": "${{ steps.pcrs.outputs.pcr2 }}"
            },
            "valid_from": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
          }
          EOF

          # Sign with deployment key
          echo "${{ secrets.DEPLOYMENT_SIGNING_KEY }}" | base64 -d > signing-key.pem
          openssl dgst -sha256 -sign signing-key.pem pcr-config.json | base64 > signature.txt

          # Add signature to config
          jq --arg sig "$(cat signature.txt)" '. + {signature: $sig}' pcr-config.json > signed-pcr-config.json

          # Cleanup
          rm -f signing-key.pem

      - name: Upload to Secrets Manager
        run: |
          aws secretsmanager put-secret-value \
            --secret-id vettid/enclave/migration-pcrs \
            --secret-string file://signed-pcr-config.json

      - name: Upload enclave image to S3
        run: |
          aws s3 cp enclave.eif s3://vettid-enclave-images/enclave-${{ github.sha }}.eif

      - name: Update KMS policy (add new PCRs)
        run: |
          ./scripts/update-kms-policy.sh \
            --add-pcrs "${{ steps.pcrs.outputs.pcr0 }},${{ steps.pcrs.outputs.pcr1 }},${{ steps.pcrs.outputs.pcr2 }}"

  deploy-new-enclave:
    needs: build-enclave
    runs-on: ubuntu-latest
    steps:
      - name: Deploy new enclave fleet (idle)
        run: |
          ./scripts/deploy-enclave-fleet.sh \
            --image s3://vettid-enclave-images/enclave-${{ github.sha }}.eif \
            --traffic-weight 0  # No traffic yet

  run-migration:
    needs: deploy-new-enclave
    runs-on: ubuntu-latest
    steps:
      - name: Trigger migration in old enclave
        run: |
          ./scripts/trigger-migration.sh --target old-enclave-fleet

      - name: Wait for migration completion
        run: |
          ./scripts/wait-for-migration.sh --timeout 4h

      - name: Run warmup verification in new enclave
        run: |
          ./scripts/trigger-warmup-verification.sh --target new-enclave-fleet

      - name: Verify all users migrated
        run: |
          ./scripts/verify-migration-complete.sh

  cutover:
    needs: run-migration
    runs-on: ubuntu-latest
    environment: production  # Requires manual approval
    steps:
      - name: Switch traffic to new enclave
        run: |
          ./scripts/deploy-enclave-fleet.sh \
            --target new-enclave-fleet \
            --traffic-weight 100

      - name: Drain old enclave
        run: |
          ./scripts/deploy-enclave-fleet.sh \
            --target old-enclave-fleet \
            --traffic-weight 0

  cleanup:
    needs: cutover
    runs-on: ubuntu-latest
    if: github.event_name == 'workflow_dispatch'  # Manual trigger after verification period
    steps:
      - name: Remove old PCRs from KMS policy
        run: |
          ./scripts/update-kms-policy.sh --remove-old-pcrs

      - name: Terminate old enclave fleet
        run: |
          ./scripts/terminate-enclave-fleet.sh --target old-enclave-fleet
```

---

## Android Implementation

### 1. Migration Status Client

```kotlin
@Singleton
class MigrationClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) {
    /**
     * Check migration status on app startup.
     */
    suspend fun checkMigrationStatus(): MigrationStatus {
        val response = ownerSpaceClient.sendToVault(
            "credential.migration.status",
            JsonObject()
        )

        return response.fold(
            onSuccess = { json ->
                when (json.get("status")?.asString) {
                    "complete" -> {
                        val migratedAt = json.get("migrated_at")?.asString
                        val notifiedUser = json.get("user_notified")?.asBoolean ?: false
                        MigrationStatus.Complete(
                            migratedAt = migratedAt,
                            userNotified = notifiedUser
                        )
                    }
                    "in_progress" -> MigrationStatus.InProgress(
                        progress = json.get("progress")?.asFloat ?: 0f
                    )
                    "emergency_recovery_required" -> MigrationStatus.EmergencyRecoveryRequired
                    else -> MigrationStatus.None
                }
            },
            onFailure = { MigrationStatus.Unknown }
        )
    }

    /**
     * Mark user as notified about migration.
     */
    suspend fun acknowledgeNotification(): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("acknowledged", true)
            addProperty("acknowledged_at", System.currentTimeMillis())
        }
        return ownerSpaceClient.sendToVault("credential.migration.acknowledge", payload)
            .map { }
    }
}

sealed class MigrationStatus {
    object None : MigrationStatus()
    data class Complete(
        val migratedAt: String?,
        val userNotified: Boolean
    ) : MigrationStatus()
    data class InProgress(val progress: Float) : MigrationStatus()
    object EmergencyRecoveryRequired : MigrationStatus()
    object Unknown : MigrationStatus()
}
```

### 2. Migration Notification Banner

```kotlin
@Composable
fun MigrationNotificationBanner(
    status: MigrationStatus.Complete,
    onDismiss: () -> Unit,
    onViewDetails: () -> Unit
) {
    if (status.userNotified) return  // Already shown

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Security Update Applied",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your vault has been migrated to a new secure enclave.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onViewDetails) {
                Text("View Details")
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
```

### 3. Integration in Main Screen

```kotlin
@Composable
fun MainScreen(
    migrationClient: MigrationClient = hiltViewModel<MainViewModel>().migrationClient
) {
    var migrationStatus by remember { mutableStateOf<MigrationStatus?>(null) }
    var showBanner by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        migrationStatus = migrationClient.checkMigrationStatus()
        showBanner = migrationStatus is MigrationStatus.Complete &&
                     !(migrationStatus as MigrationStatus.Complete).userNotified
    }

    Column {
        // Migration notification banner
        if (showBanner && migrationStatus is MigrationStatus.Complete) {
            MigrationNotificationBanner(
                status = migrationStatus as MigrationStatus.Complete,
                onDismiss = {
                    showBanner = false
                    // Mark as notified
                    viewModelScope.launch {
                        migrationClient.acknowledgeNotification()
                    }
                },
                onViewDetails = {
                    // Navigate to security audit log
                    navController.navigate(Screen.SecurityAuditLog.route)
                }
            )
        }

        // Rest of main screen content
        // ...
    }
}
```

### 4. Security Audit Log Screen

```kotlin
@Composable
fun SecurityAuditLogScreen(
    viewModel: SecurityAuditLogViewModel = hiltViewModel()
) {
    val auditLogs by viewModel.auditLogs.collectAsState()

    LazyColumn {
        items(auditLogs) { entry ->
            AuditLogItem(entry)
        }
    }
}

@Composable
fun AuditLogItem(entry: AuditLogEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (entry.type) {
                        "migration_verified" -> Icons.Default.Security
                        "migration_sealed_material" -> Icons.Default.Sync
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                formatTimestamp(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
```

### 5. Emergency Recovery (Disaster Scenario Only)

```kotlin
@Composable
fun EmergencyRecoveryScreen(
    onRecoveryComplete: () -> Unit,
    viewModel: EmergencyRecoveryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Emergency Recovery Required",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "A system issue requires you to re-authorize your vault. " +
            "Enter your master PIN to restore access.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("Master PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = state !is EmergencyRecoveryState.Recovering,
            modifier = Modifier.fillMaxWidth()
        )

        if (state is EmergencyRecoveryState.Error) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                (state as EmergencyRecoveryState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.performRecovery(pin) },
            enabled = pin.length >= 4 && state !is EmergencyRecoveryState.Recovering,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state is EmergencyRecoveryState.Recovering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onError
                )
            } else {
                Text("Recover Vault")
            }
        }
    }

    LaunchedEffect(state) {
        if (state is EmergencyRecoveryState.Success) {
            onRecoveryComplete()
        }
    }
}
```

---

## Files to Create/Modify

### Backend (vettid/vettid-dev)

| File | Action | Description |
|------|--------|-------------|
| `enclave/migration/pcr_config.go` | Create | Signed PCR config fetching and verification |
| `enclave/migration/sealed_material.go` | Create | Version management for sealed material |
| `enclave/migration/migrate.go` | Create | Main migration logic with locking |
| `enclave/migration/verify.go` | Create | Warmup verification in new enclave |
| `enclave/migration/cleanup.go` | Create | Expired version cleanup |
| `handlers/migration_status.go` | Create | NATS handler for status check |
| `handlers/migration_acknowledge.go` | Create | NATS handler for notification ack |
| `handlers/emergency_recovery.go` | Create | NATS handler for emergency recovery |
| `scripts/update-kms-policy.sh` | Create | KMS policy management |
| `.github/workflows/enclave-deploy.yml` | Create | CI/CD for enclave deployment |

### Android (vettid/vettid-android)

| File | Action | Description |
|------|--------|-------------|
| `core/nats/MigrationClient.kt` | Create | Migration status and acknowledgment |
| `features/main/MigrationNotificationBanner.kt` | Create | Notification UI component |
| `features/security/SecurityAuditLogScreen.kt` | Create | Audit log viewing |
| `features/security/SecurityAuditLogViewModel.kt` | Create | Audit log data loading |
| `features/recovery/EmergencyRecoveryScreen.kt` | Create | Emergency recovery UI |
| `features/recovery/EmergencyRecoveryViewModel.kt` | Create | Emergency recovery logic |
| `VettIDApp.kt` | Modify | Add migration check on startup |

---

## Verification Checklist

### Pre-Migration
- [ ] New enclave image built and PCRs extracted
- [ ] PCR config signed and uploaded to Secrets Manager
- [ ] KMS policy updated to allow both old and new PCRs
- [ ] New enclave fleet deployed (idle, 0% traffic)
- [ ] Migration monitoring dashboards ready

### Migration
- [ ] Migration triggered in old enclave
- [ ] All users' sealed material migrated (check state = "verifying")
- [ ] Warmup verification run in new enclave
- [ ] All users verified (check state = "complete")
- [ ] Audit logs created for all migrations

### Cutover
- [ ] Traffic switched to new enclave (100%)
- [ ] Old enclave drained (0% traffic)
- [ ] Monitor for errors (24-48h)
- [ ] User notifications showing correctly in app

### Cleanup (after verification period)
- [ ] Old PCRs removed from KMS policy
- [ ] Old enclave fleet terminated
- [ ] Old sealed material versions expired (7 days)
- [ ] Cleanup job run successfully
