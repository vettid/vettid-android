package com.vettid.core.data.vault

/** (ANDROID-PLAN 0.1.57 §4 "Vault error codes the app does not map") What the app makes of the vault's error codes. */
object VaultCodes {
    const val CREDENTIAL_REQUIRED = "credential_required"
    const val FORBIDDEN = "forbidden"

    /** (0.1.58 point 2) A resync answered with an empty list again: the app's own code, shown as a vault code. */
    const val UTK_EMPTY = "utk_empty"

    /** The type of the UTK pool's top-up and resync (VAULT-MESSAGING 0.52.0 §3.5.4). */
    const val TYPE_UTK_GET = "credential.utk.get"

    /**
     * The holder-only types (VAULT-MESSAGING §3.5.9): `forbidden` to one of them means this phone is not the holder
     * ([FailureKind.NOT_HOLDER]). A `forbidden` to any other type keeps its screen's text, or `error_vault_code`.
     */
    val HOLDER_ONLY: Set<String> = setOf(
        "vault.owner-check", "pin.change", "vault.secret.renew", "account.name.set", "credential.get", "credential.ack",
        "credential.reset", "device.transfer.create", "device.transfer.reject", "vault.delete",
    )

    /**
     * (VAULT-MESSAGING 0.83.11 §11.14.10) The requests the vault answers `transfer_incomplete` before the new phone's
     * first unlock from storage: [FailureKind.MOVE_NOT_FINISHED]. Other types keep [FailureKind.TRANSFER_INCOMPLETE].
     */
    val MOVE_GATED: Set<String> = setOf(
        "connection.authenticate.approve", "leash.grant.issue", "device.pair.approve", "share.rule.set",
    )

    const val TRANSFER_INCOMPLETE = "transfer_incomplete"

    private val SHOWN = Regex("[a-z0-9_]{1,40}")

    /** [code] as `error_vault_code` shows it: the code itself when it matches `[a-z0-9_]{1,40}`, otherwise `unknown`. */
    fun shown(code: String): String = if (SHOWN.matches(code)) code else UNKNOWN

    const val UNKNOWN = "unknown"
}
