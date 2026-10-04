package com.vettid.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val VettIdShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Shapes specific to VettID components. */
object VettIdShape {
    /** Rounded square for initial tiles: ~28 % of the side, as in Proton. */
    fun tile(sizeDp: Int) = RoundedCornerShape((sizeDp * 0.28f).dp)

    /** Grouped settings cards and detail cards. */
    val card = RoundedCornerShape(20.dp)

    /** Pills: filter chip, floating action bar, badges. */
    val pill = RoundedCornerShape(percent = 50)
}
