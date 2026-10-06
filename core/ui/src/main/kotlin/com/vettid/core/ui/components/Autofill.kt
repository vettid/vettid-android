package com.vettid.core.ui.components

import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.requireView
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDataType
import java.util.WeakHashMap

/**
 * Keeps a secret input (vault PIN, credential password, recovery code, transfer code, setup code, the delete
 * confirmation) away from autofill services and password managers: they neither fill it nor offer to save it
 * (owner decision 2026-10-06, after a password manager offered to save the credential password typed during a
 * recovery).
 *
 * Two layers, because Compose's text field marks every `Password`/`NumberPassword` field as
 * `ContentType.Password` on its own:
 * - per field, `contentDataType = ContentDataType.None` (Compose 1.8+ autofill semantics; an outer modifier wins
 *   over the text field's own `ContentDataType.Text`): Compose then never notifies the platform
 *   `AutofillManager` of focus or value changes for this field, so no fill request starts from it and there is no
 *   value to save;
 * - per window, while at least one such field is attached, the parent of the compose view (the `ComposeView`,
 *   or a dialog's layout) is `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`, so the platform no longer counts the
 *   compose view as important for autofill and leaves the screen out of the structure an autofill service is
 *   given (a service that ignores the data type and reads the `password` hint sees nothing). The compose view
 *   itself always reports `IMPORTANT_FOR_AUTOFILL_YES`, hence its parent. The previous value comes back when the
 *   last secret field leaves.
 *
 * Fields that may autofill (the account's email address for a typed setup code) do not use this.
 */
fun Modifier.excludeFromAutofill(): Modifier = this then ExcludeFromAutofillElement

private data object ExcludeFromAutofillElement : ModifierNodeElement<ExcludeFromAutofillNode>() {
    override fun create() = ExcludeFromAutofillNode()

    override fun update(node: ExcludeFromAutofillNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "excludeFromAutofill"
    }
}

private class ExcludeFromAutofillNode : Modifier.Node(), SemanticsModifierNode {
    private var host: View? = null

    override fun SemanticsPropertyReceiver.applySemantics() {
        contentDataType = ContentDataType.None
    }

    override fun onAttach() {
        host = (requireView().parent as? View)?.also(AutofillExclusion::acquire)
    }

    override fun onDetach() {
        host?.let(AutofillExclusion::release)
        host = null
    }
}

/** Counts the secret fields attached under each host view (main thread only, as all composition is). */
internal object AutofillExclusion {
    private class Hold(val previous: Int, var count: Int)

    private val holds = WeakHashMap<View, Hold>()

    fun acquire(view: View) {
        val hold = holds[view]
        if (hold != null) {
            hold.count++
        } else {
            holds[view] = Hold(view.importantForAutofill, 1)
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }

    fun release(view: View) {
        val hold = holds[view] ?: return
        if (--hold.count == 0) {
            holds.remove(view)
            view.importantForAutofill = hold.previous
        }
    }
}
