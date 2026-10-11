package com.vettid.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R

/**
 * The brand kit's horizontal logo (the rook and the VETTID wordmark, no tagline: app screens never show the
 * tagline), [width] wide. The wordmark is artwork: never type "VettID" next to the rook instead.
 *
 * The kit's rules (BRAND.md) are the caller's to keep: at least [VettIdLogoDefaults.MinWidth] wide (smaller, use
 * [RookLogo]), and [VettIdLogoDefaults.clearSpace] of empty space on every side. The same file serves light and
 * dark surfaces (the kit's `-on-light` file differs only in the tagline).
 */
@Composable
fun VettIdLogo(
    modifier: Modifier = Modifier,
    width: Dp = VettIdLogoDefaults.MinWidth,
    decorative: Boolean = false,
) {
    require(width >= VettIdLogoDefaults.MinWidth) { "the horizontal logo is at least 160dp wide; use RookLogo" }
    Image(
        painter = painterResource(R.drawable.ic_logo_horizontal),
        contentDescription = if (decorative) null else stringResource(R.string.core_ui_cd_logo),
        modifier = modifier.size(width = width, height = width / VettIdLogoDefaults.ASPECT),
    )
}

object VettIdLogoDefaults {
    private const val ARTWORK_WIDTH = 631.6f
    private const val ARTWORK_HEIGHT = 200f

    /** The battlements are 96 of the rook's 344 units, and the rook is the artwork's height. */
    private const val BATTLEMENTS = ARTWORK_HEIGHT * 96f / 344f

    /** BRAND.md, minimum size of the horizontal logo on screen (the artwork's width). */
    val MinWidth: Dp = 160.dp

    /** Width : height of the artwork (631.6 x 200 of the kit's 752 x 300 canvas). */
    const val ASPECT: Float = ARTWORK_WIDTH / ARTWORK_HEIGHT

    /** The kit's clear space X, the height of the rook's battlements, for a logo [width] wide. */
    fun clearSpace(width: Dp): Dp = width * (BATTLEMENTS / ARTWORK_WIDTH)
}
