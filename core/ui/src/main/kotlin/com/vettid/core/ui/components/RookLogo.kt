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

/** The VettID rook (gold, black keyhole) at [height]; width keeps the 264:350 ratio. */
@Composable
fun RookLogo(
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    decorative: Boolean = false,
) {
    Image(
        painter = painterResource(R.drawable.ic_rook),
        contentDescription = if (decorative) null else stringResource(R.string.core_ui_cd_logo),
        modifier = modifier.size(width = height * ROOK_ASPECT, height = height),
    )
}

private const val ROOK_ASPECT = 264f / 350f
