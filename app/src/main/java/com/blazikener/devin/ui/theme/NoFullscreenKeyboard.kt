package com.blazikener.devin.ui.theme

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Stops the keyboard from switching to its full-screen "extract" editor in landscape, which hides
 * the screen (labels, errors, buttons, the chat) while typing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoFullscreenKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val inline = PlatformTextInputMethodRequest { outAttributes: EditorInfo ->
                val connection: InputConnection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = outAttributes.imeOptions or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                connection
            }
            nextHandler.startInputMethod(inline)
        },
        content = content,
    )
}
