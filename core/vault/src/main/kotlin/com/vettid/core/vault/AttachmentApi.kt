package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Photo attachments (VAULT-MESSAGING 0.32.0 §10.5.1, release A): the app's calls. A desktop is answered `forbidden`.

/**
 * Whether the vault has attachments (§10.5.1 "Compatibility"): `message.attachment.get{}` is `bad_request` on a
 * vault of release A (held or not) and `unsupported_type` on an older one. Null when the answer said neither.
 */
suspend fun VaultApi.attachmentsSupported(): Boolean? = try {
    device.op("message.attachment.get")
    null
} catch (e: VaultOpException) {
    when (e.code) {
        "bad_request" -> true
        "unsupported_type" -> false
        else -> null
    }
}

/** Uploads a sealed photo blob to the vault's own mailbox (§10.5.1 "Sending" step 1); returns its `blob_id`. */
suspend fun VaultApi.uploadBlob(blob: ByteArray): String = device.uploadBlobToVault(blob)

/** Fetches a blob the vault uploaded to this device's mailbox (§10.5.1 "Fetching"). */
suspend fun VaultApi.fetchBlob(blobId: String): ByteArray = device.fetchOwnBlob(blobId)

/** Deletes a blob in this device's mailbox, whether it passed its checks or not. */
suspend fun VaultApi.deleteBlob(blobId: String) = device.deleteOwnBlob(blobId)

/**
 * `message.send` with a photo (§10.5.1 step 2): [attachment] is the device send view (`blob`, `kind`, `width`,
 * `height`, `thumb`); [text] the caption (0 bytes to 16 KiB, absent when empty). Sent with inner id [requestId], so
 * that a Retry after a lost answer is the same request.
 */
suspend fun VaultApi.messageSendPhoto(connectionId: String, text: String?, attachment: JsonObject, requestId: String): SentAttachment =
    VaultJson.decode(
        SentAttachment.serializer(),
        device.op(
            "message.send",
            buildJsonObject {
                put("connection_id", connectionId)
                if (!text.isNullOrEmpty()) put("text", text)
                put("attachment", attachment)
            },
            innerId = requestId,
        ),
    )

/** The thumbnail of a message's attachment, inline (`part: "thumb"`): its decoded bytes. */
suspend fun VaultApi.attachmentThumb(connectionId: String, messageId: String): ByteArray {
    val o = device.op("message.attachment.get", attachmentRef(connectionId, messageId, "thumb"))
    return Base64s.decodeStd(VaultJson.str(o, "thumb") ?: throw VaultStateException("message.attachment.get without thumb"))
}

/** The content of a message's attachment (`part: "content"`): a fresh blob in this device's mailbox. */
suspend fun VaultApi.attachmentContent(connectionId: String, messageId: String): FetchedAttachment =
    VaultJson.decode(FetchedAttachment.serializer(), device.op("message.attachment.get", attachmentRef(connectionId, messageId, "content")))

/** Asks the vault to delete a blob this device uploaded and will not send (Cancel before `message.send`). */
suspend fun VaultApi.messageAttachmentDiscard(blobId: String) {
    device.op("message.attachment.discard", buildJsonObject { put("blob_id", blobId) })
}

private fun attachmentRef(connectionId: String, messageId: String, part: String) = buildJsonObject {
    put("connection_id", connectionId)
    put("message_id", messageId)
    put("part", part)
}
