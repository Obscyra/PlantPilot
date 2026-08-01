package com.plantpilot.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

enum class ButtonState { Pressed, Idle }

fun Modifier.bounceClick(
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) = composed {
    var buttonState by remember { mutableStateOf(ButtonState.Idle) }
    val scale by animateFloatAsState(
        targetValue = if (buttonState == ButtonState.Pressed) 0.96f else 1f,
        label = "bounce_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = { onClick?.invoke() }
        )
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(false)
                    buttonState = ButtonState.Pressed
                    waitForUpOrCancellation()
                    buttonState = ButtonState.Idle
                }
            }
        }
}
