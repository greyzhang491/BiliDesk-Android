// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** A single composition slot for the native player survives portrait, dual-pane and fullscreen. */
@Composable fun AdaptivePlayerLayout(fullscreen: Boolean, modifier: Modifier = Modifier, video: @Composable () -> Unit, details: @Composable () -> Unit) {
    Layout(content = { video(); if (!fullscreen) details() }, modifier = modifier) { children, constraints ->
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val spec = PlayerLayoutPolicy.calculate(w / density, h / density, fullscreen)
        val videoW = (spec.videoWidth * density).roundToInt().coerceIn(0, w)
        val videoH = (spec.videoHeight * density).roundToInt().coerceIn(0, h)
        val player = children[0].measure(Constraints.fixed(videoW, videoH))
        val detailW = if (spec.mode == PlayerLayoutMode.SIDE_BY_SIDE) w - videoW else w
        val detailH = if (spec.mode == PlayerLayoutMode.SIDE_BY_SIDE) h else h - videoH
        val info = children.getOrNull(1)?.measure(Constraints.fixed(detailW, detailH))
        layout(w, h) {
            player.placeRelative(0, 0)
            info?.placeRelative(if (spec.mode == PlayerLayoutMode.SIDE_BY_SIDE) videoW else 0, if (spec.mode == PlayerLayoutMode.SIDE_BY_SIDE) 0 else videoH)
        }
    }
}
