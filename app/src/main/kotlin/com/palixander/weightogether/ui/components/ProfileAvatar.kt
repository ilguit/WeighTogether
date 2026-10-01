package com.palixander.weightogether.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.profile.ProfilePhotoStore
import com.palixander.weightogether.ScaleSyncApplication

@Composable
fun currentProfilePhotoStore(): ProfilePhotoStore? =
    (LocalContext.current.applicationContext as? ScaleSyncApplication)?.container?.profilePhotos

/** Circular managed profile image with a deterministic icon fallback for missing/corrupt files. */
@Composable
fun ProfileAvatar(
    photoPath: String?,
    fallbackIcon: ImageVector,
    contentDescription: String,
    store: ProfilePhotoStore?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    photoModifier: Modifier = Modifier,
    fallbackModifier: Modifier = Modifier,
    fallbackSize: Dp = size * 0.55f,
) {
    val bitmap = remember(store, photoPath) {
        photoPath?.let { path ->
            runCatching { store?.resolve(path)?.path?.let(BitmapFactory::decodeFile) }.getOrNull()
        }?.asImageBitmap()
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = photoModifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = fallbackModifier.size(fallbackSize),
            )
        }
    }
}
