package com.vettid.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import com.vettid.core.ui.R

/**
 * A connection in a list: [VettIdListRow] whose tile turns teal and star turns
 * gold when the connection is a favourite (the owner's `favorite` flag,
 * VAULT-MESSAGING 10.4 `connection.update`). The star toggles it, like Proton's.
 */
@Composable
fun ConnectionRow(
    name: String,
    favorite: Boolean,
    onFavoriteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    meta: String? = null,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
    photo: ImageBitmap? = null,
) {
    VettIdListRow(
        title = name,
        modifier = modifier,
        supporting = supporting,
        meta = meta,
        tileStyle = if (favorite) TileStyle.Favorite else TileStyle.Connection,
        emphasized = emphasized,
        onClick = onClick,
        tilePhoto = photo,
        action = RowAction(
            icon = if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            contentDescription = stringResource(
                if (favorite) R.string.core_ui_favorite_remove else R.string.core_ui_favorite_add,
                name,
            ),
            active = favorite,
            onClick = { onFavoriteChange(!favorite) },
        ),
    )
}
