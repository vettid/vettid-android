package com.vettid.feature.approvals

import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.DataView
import com.vettid.core.data.social.HeldRequest

/**
 * What a desktop's or agent's request shows and allows (ANDROID-PLAN 0.1.116 §4 "Approvals for devices and agents (AA5)").
 * Pure: the screens and the view model both read it. Nothing is ever approved that is not shown in full (decisions 10, 15).
 */
internal sealed interface HeldShow {
    /** Whether Approve is offered. */
    val approvable: Boolean

    /** `item.use`: the item and field names (cleaned), and the data as the member reads it (decision 18). */
    data class Use(val item: String, val field: String, val data: DataView) : HeldShow {
        override val approvable get() = true

        override fun toString(): String = "Use"

    }

    /** `item.get`: the item and the field labels, or null for all fields and notes. */
    data class Get(val item: String, val fields: List<String>?) : HeldShow {
        override val approvable get() = true

        override fun toString(): String = "Get"

    }

    data object Catalog : HeldShow {
        override val approvable get() = true
    }

    /** The item (or a field) is not in the app's cache: "an item you no longer have", deny-only. */
    data class NoItem(val use: Boolean) : HeldShow {
        override val approvable get() = false
    }

    /** `item.run` and `item.proxy` (decision 15), an item named when known. */
    data class Later(val item: String?) : HeldShow {
        override val approvable get() = false

        override fun toString(): String = "Later"

    }

    data object Desktop : HeldShow {
        override val approvable get() = false
    }

    data object Other : HeldShow {
        override val approvable get() = false
    }

    /** Known only from its feed item: the body is gone, deny-only (decision 10). */
    data object Gone : HeldShow {
        override val approvable get() = false
    }
}

internal object DeviceAsks {
    /** 15 minutes, 1 hour, 4 hours, 8 hours, 24 hours (seconds). */
    val CHOICES = listOf(900, 3_600, 14_400, 28_800, 86_400)

    /** What a request known only from its feed item preselects (it does not know the length asked for). */
    const val DEFAULT_SECONDS = 3_600
    private const val MIN_SECONDS = 60

    /** The choices with [requested] added when it is not one of them (decision 6), in order. */
    fun choices(requested: Int?): List<Int> {
        val r = requested?.takeIf { it in MIN_SECONDS..CHOICES.last() }
        return if (r == null || r in CHOICES) CHOICES else (CHOICES + r).sorted()
    }

    /** The length preselected: the requested one, else 1 hour. */
    fun preselected(requested: Int?): Int = choices(requested).let { c -> requested?.takeIf { it in c } ?: DEFAULT_SECONDS }

    /** What [a] shows, given the app's item cache. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun show(a: Approval.DeviceRequest, items: List<ItemSummary>): HeldShow {
        if (!a.isHeld) return HeldShow.Other
        if (a.fromFeed) return HeldShow.Gone
        fun item(id: String) = items.firstOrNull { it.itemId == id }
        return when (val h = a.held) {
            is HeldRequest.ItemUse -> {
                val i = item(h.itemId)
                val f = i?.fieldLabels?.firstOrNull { it.fieldId == h.fieldId }
                val name = AccountNames.cleaned(i?.name)
                val label = AccountNames.cleaned(f?.label)
                if (name == null || label == null) HeldShow.NoItem(use = true) else HeldShow.Use(name, label, DataView.of(h.data))
            }
            is HeldRequest.ItemGet -> {
                val i = item(h.itemId)
                val name = AccountNames.cleaned(i?.name)
                val labels = h.fieldIds?.map { id -> AccountNames.cleaned(i?.fieldLabels?.firstOrNull { it.fieldId == id }?.label) }
                // A field this phone cannot name is not shown unseen.
                if (name == null || labels?.any { it == null } == true) {
                    HeldShow.NoItem(use = false)
                } else {
                    HeldShow.Get(name, labels?.filterNotNull())
                }
            }
            HeldRequest.Catalog -> HeldShow.Catalog
            is HeldRequest.Later -> HeldShow.Later(AccountNames.cleaned(h.itemId?.let { id -> item(id)?.name }))
            HeldRequest.DesktopStepUp -> HeldShow.Desktop
            HeldRequest.Unshown, null -> HeldShow.Other
        }
    }

    /** [a] with the name and role of its device when it is known only from its feed item (the name is from `device.list`). */
    @Suppress("ReturnCount")
    fun named(a: Approval, devices: List<DeviceInfo>): Approval {
        if (a !is Approval.DeviceRequest || !a.fromFeed) return a
        val d = devices.firstOrNull { it.id == a.deviceId && !it.thisPhone } ?: return a
        val role = when {
            d.isAgent -> "agent"
            d.kind == DeviceInfo.KIND_DESKTOP -> "desktop"
            else -> null
        }
        return a.copy(deviceName = d.name.ifEmpty { null }, role = role)
    }

    /** Whether the device of a request known only from its feed item is in `device.list`. */
    fun listed(a: Approval.DeviceRequest, devices: List<DeviceInfo>): Boolean =
        devices.any { it.id == a.deviceId && !it.thisPhone }
}

/** How a decision on a device request ended (shown before the screen closes). */
sealed interface DeviceOutcome {
    /** `device.session.approve` answered: access until [until]. */
    data class Allowed(val until: java.time.Instant) : DeviceOutcome

    /** `approval.decide{approve: true}` answered [result] (`ok`, or the code the held request was answered with). */
    data class Approved(val result: String?) : DeviceOutcome

    data object Denied : DeviceOutcome

    /** The vault answered an error (replaced, decided elsewhere, dropped): "This request is no longer waiting." */
    data object NoLongerWaiting : DeviceOutcome
}
