package com.vettid.app.features.personaldata

import com.vettid.app.core.nats.PeerProfileData
import com.vettid.app.core.nats.PeerWalletInfo
import java.time.Instant

/**
 * Convert a peer's vault-cached profile data into a PublishedProfileData
 * so peer-profile screens can render through the same BusinessCardView
 * the user sees for their own public-profile preview — giving one
 * canonical layout (hero avatar, clickable contact card, categorized
 * fields, public keys, published timestamp) across:
 *
 *   - scanner reviewing inviter's profile before accepting,
 *   - inviter reviewing scanner's profile before accepting,
 *   - connected-peer detail screen.
 *
 * System fields (first name, last name, email) come through as top-level
 * keys on PeerProfileData and are synthesized into PersonalDataItem
 * entries with the names BusinessCardView looks for ("First Name",
 * "Last Name", "Email"). The profileFields map keys are namespaces
 * like `contact.family.phone` — preserved as-is so the family-phone
 * hero exclusion filter still matches.
 */
fun peerProfileToPublishedProfileData(
    peer: PeerProfileData,
    fallbackDisplayName: String? = null,
    fallbackEmail: String? = null,
): PublishedProfileData {
    val now = Instant.now()
    val items = mutableListOf<PersonalDataItem>()
    var sort = 0

    val firstName = peer.firstName?.takeIf { it.isNotBlank() }
        ?: fallbackDisplayName?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    val lastName = peer.lastName?.takeIf { it.isNotBlank() }
        ?: fallbackDisplayName?.substringAfter(' ', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    val email = peer.email?.takeIf { it.isNotBlank() } ?: fallbackEmail?.takeIf { it.isNotBlank() }

    firstName?.let {
        items.add(
            PersonalDataItem(
                id = "_peer_first_name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = it,
                category = DataCategory.IDENTITY,
                fieldType = FieldType.TEXT,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = sort++,
                createdAt = now,
                updatedAt = now,
            )
        )
    }
    lastName?.let {
        items.add(
            PersonalDataItem(
                id = "_peer_last_name",
                name = "Last Name",
                type = DataType.PUBLIC,
                value = it,
                category = DataCategory.IDENTITY,
                fieldType = FieldType.TEXT,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = sort++,
                createdAt = now,
                updatedAt = now,
            )
        )
    }
    email?.let {
        items.add(
            PersonalDataItem(
                id = "_peer_email",
                name = "Email",
                type = DataType.PUBLIC,
                value = it,
                category = DataCategory.CONTACT,
                fieldType = FieldType.EMAIL,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = sort++,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    peer.fields?.forEach { (namespace, fieldData) ->
        if (namespace.startsWith("_system_")) return@forEach
        val value = fieldData["value"].orEmpty()
        if (value.isBlank()) return@forEach
        val displayName = fieldData["display_name"]?.takeIf { it.isNotBlank() }
            ?: displayNameFromNamespaceOrKey(namespace)
        val category = categoryFromNamespace(namespace)
        val fieldType = fieldTypeFromHint(fieldData["field_type"])
        items.add(
            PersonalDataItem(
                id = "_peer_$namespace",
                name = displayName,
                type = DataType.PUBLIC,
                value = value,
                category = category,
                fieldType = fieldType,
                isSystemField = false,
                isInPublicProfile = true,
                sortOrder = sort++,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    peer.wallets.orEmpty().forEach { wallet ->
        if (wallet.address.isBlank()) return@forEach
        items.add(walletItem(wallet, sort++, now))
    }

    peer.publicKey?.takeIf { it.isNotBlank() }?.let { key ->
        items.add(
            PersonalDataItem(
                id = "_peer_identity_key",
                name = "Identity Key",
                type = DataType.KEY,
                value = key,
                category = DataCategory.IDENTITY,
                fieldType = FieldType.TEXT,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = sort++,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    return PublishedProfileData(
        items = items,
        isFromVault = true,
        updatedAt = null,
        photo = peer.photo,
    )
}

private fun walletItem(wallet: PeerWalletInfo, sortOrder: Int, now: Instant): PersonalDataItem {
    val networkLabel = if (wallet.network.equals("testnet", ignoreCase = true)) "BTC Testnet" else "BTC"
    return PersonalDataItem(
        id = "_peer_wallet_${wallet.walletId.ifBlank { wallet.address.take(8) }}",
        name = "${wallet.label} ($networkLabel)",
        type = DataType.KEY,
        value = wallet.address,
        category = DataCategory.WALLET,
        fieldType = FieldType.TEXT,
        isSystemField = false,
        isInPublicProfile = true,
        sortOrder = sortOrder,
        createdAt = now,
        updatedAt = now,
    )
}

private fun displayNameFromNamespaceOrKey(namespace: String): String {
    val leaf = namespace.substringAfterLast('.')
    return leaf.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { c -> c.uppercaseChar() }
    }
}

private fun categoryFromNamespace(namespace: String): DataCategory = when {
    namespace.startsWith("personal.legal") -> DataCategory.IDENTITY
    namespace.startsWith("personal.info") -> DataCategory.IDENTITY
    namespace.startsWith("identity.") -> DataCategory.IDENTITY
    // Family template fields route to the Family category so the
    // peer's profile shows them grouped separately from their
    // primary contact info.
    namespace.startsWith("contact.family.") -> DataCategory.FAMILY
    namespace.startsWith("contact.") -> DataCategory.CONTACT
    namespace.startsWith("social.") -> DataCategory.CONTACT
    namespace.startsWith("address.") -> DataCategory.ADDRESS
    namespace.startsWith("financial.") -> DataCategory.FINANCIAL
    namespace.startsWith("medical.") -> DataCategory.MEDICAL
    namespace.startsWith("professional.") -> DataCategory.PROFESSIONAL
    namespace.startsWith("education.") -> DataCategory.EDUCATION
    namespace.startsWith("vehicle.") -> DataCategory.VEHICLE
    namespace.startsWith("legal.") -> DataCategory.LEGAL
    namespace.startsWith("digital.") -> DataCategory.DIGITAL
    namespace.startsWith("travel.") -> DataCategory.TRAVEL
    namespace.startsWith("membership.") -> DataCategory.MEMBERSHIP
    namespace.startsWith("property.") -> DataCategory.PROPERTY
    else -> DataCategory.OTHER
}

private fun fieldTypeFromHint(hint: String?): FieldType = when (hint?.lowercase()) {
    "email" -> FieldType.EMAIL
    "phone" -> FieldType.PHONE
    "url" -> FieldType.URL
    "number" -> FieldType.NUMBER
    "date" -> FieldType.DATE
    "password" -> FieldType.PASSWORD
    "note" -> FieldType.NOTE
    else -> FieldType.TEXT
}
