// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import dev.bilidesk.android.R

/** Original upstream BiliDesk art; do not tint or stretch the face. See NOTICE.md. */
@Composable fun BiliDeskLogo(modifier: Modifier = Modifier, description: String? = null) {
    Image(painterResource(R.drawable.bilidesk_brand), description,
        modifier.clip(RoundedCornerShape(percent = 24)), contentScale = ContentScale.Fit)
}
