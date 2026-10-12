package com.vettid.core.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * The keyboard for a secret the member must see while typing it (a recovery phrase, a one-time-code seed, a setup or
 * transfer code, the values of a secret or critical item; A6 audit S2): the password class of text keyboard, with
 * autocorrect off. For that class a keyboard offers no suggestions and does not add what is typed to its dictionary;
 * the characters stay visible as long as the field has no password visual transformation. The same choice as
 * [SecretField] (masked) and the typed recovery code (grouped). Wrap the field in [NoKeyboardLearning] for the explicit
 * no-learning flag. Pinned by `SecretKeyboardTest`: the app-lock keystroke leak (2026-10-09) is the precedent for
 * testing what reaches the keyboard.
 */
fun visibleSecretKeyboard(
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Default,
): KeyboardOptions = KeyboardOptions(
    capitalization = capitalization,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = imeAction,
)

/**
 * Tells the keyboard not to learn from the text fields in [content] (`IME_FLAG_NO_PERSONALIZED_LEARNING`): what is
 * typed there is not added to its dictionary, its typing history or its suggestions. Compose's keyboard options
 * cannot carry that flag (`PlatformImeOptions` holds private options only), so it is added to the editor info as the
 * input connection is made. With [enabled] false the fields are left as they are (so that a field can change between
 * the two without being recreated). Used with [visibleSecretKeyboard].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(enabled: Boolean = true, content: @Composable () -> Unit) {
    val on by rememberUpdatedState(enabled)
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                PlatformTextInputMethodRequest { info ->
                    request.createInputConnection(info).also {
                        if (on) info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
                },
            )
        },
        content = content,
    )
}
