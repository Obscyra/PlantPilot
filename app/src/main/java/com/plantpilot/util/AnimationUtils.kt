package com.plantpilot.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

enum class ButtonState { Pressed, Idle }

fun Modifier.bounceClick(
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "bounce_scale"
    )

    this
        .graphicsLayer {
            scaleX = scaleState.value
            scaleY = scaleState.value
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = { onClick?.invoke() }
        )
}
