package com.palixander.weightogether.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.palixander.weightogether.R
import com.palixander.weightogether.profile.ProfilePhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ProfilePhotoViewerTestTags {
    const val Viewer = "profile-photo-viewer"
    const val Image = "profile-photo-viewer-image"
    const val Close = "profile-photo-viewer-close"
    const val Unavailable = "profile-photo-viewer-unavailable"
}

/** Editor-only affordance: viewing never changes the caller's draft or photo ownership. */
@Composable
internal fun EditableProfileAvatar(
    photoPath: String?,
    fallbackIcon: ImageVector,
    contentDescription: String,
    store: ProfilePhotoStore?,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    var viewerOpen by rememberSaveable(photoPath) { mutableStateOf(false) }
    val openLabel = stringResource(R.string.photo_view_open)
    ProfileAvatar(
        photoPath = photoPath,
        fallbackIcon = fallbackIcon,
        contentDescription = contentDescription,
        store = store,
        modifier = modifier.then(
            if (photoPath != null) Modifier.clickable(role = Role.Button, onClickLabel = openLabel) {
                viewerOpen = true
            } else Modifier,
        ),
        size = size,
    )
    if (viewerOpen && photoPath != null) {
        ProfilePhotoViewer(photoPath, contentDescription, store, onDismiss = { viewerOpen = false })
    }
}

private data class PhotoViewState(val loading: Boolean = true, val bitmap: ImageBitmap? = null)

@Composable
internal fun ProfilePhotoViewer(
    photoPath: String,
    contentDescription: String,
    store: ProfilePhotoStore?,
    onDismiss: () -> Unit,
) {
    val state by produceState(PhotoViewState(), store, photoPath) {
        value = PhotoViewState()
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                store?.resolve(photoPath)?.path?.let(BitmapFactory::decodeFile)?.asImageBitmap()
            }.getOrNull()
        }
        value = PhotoViewState(loading = false, bitmap = bitmap)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag(ProfilePhotoViewerTestTags.Viewer)) {
            Column(Modifier.safeDrawingPadding()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).testTag(ProfilePhotoViewerTestTags.Close),
                ) { Text(stringResource(R.string.action_close)) }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val bitmap = state.bitmap
                    when {
                        state.loading -> CircularProgressIndicator()
                        bitmap != null -> Image(
                            bitmap = bitmap,
                            contentDescription = contentDescription,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().testTag(ProfilePhotoViewerTestTags.Image),
                        )
                        else -> Text(
                            stringResource(R.string.photo_view_unavailable),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp).testTag(ProfilePhotoViewerTestTags.Unavailable),
                        )
                    }
                }
            }
        }
    }
}
