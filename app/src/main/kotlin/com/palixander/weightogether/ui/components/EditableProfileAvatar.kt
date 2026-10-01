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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
    const val SplashArtwork = "profile-photo-viewer-splash-artwork"
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
    val openLabel = stringResource(if (photoPath == null) R.string.settings_open else R.string.photo_view_open)
    ProfileAvatar(
        photoPath = photoPath,
        fallbackIcon = fallbackIcon,
        contentDescription = contentDescription,
        store = store,
        modifier = modifier.clickable(role = Role.Button, onClickLabel = openLabel) {
            viewerOpen = true
        },
        size = size,
    )
    if (viewerOpen) {
        ProfilePhotoViewer(photoPath, contentDescription, store, onDismiss = { viewerOpen = false })
    }
}

private data class PhotoViewState(val loading: Boolean = true, val bitmap: ImageBitmap? = null)

@Composable
internal fun ProfilePhotoViewer(
    photoPath: String?,
    contentDescription: String,
    store: ProfilePhotoStore?,
    onDismiss: () -> Unit,
) {
    val state by produceState(PhotoViewState(), store, photoPath) {
        if (photoPath == null) {
            value = PhotoViewState(loading = false)
            return@produceState
        }
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
                        photoPath == null -> SplashArtwork()
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

/** Show the same vector without the transparent padding required by Android's splash mask. */
@Composable
private fun SplashArtwork() {
    val source = painterResource(R.drawable.ic_weigh_together_splash)
    val artwork = remember(source) {
        object : Painter() {
            // The ink lies inside x=67..221, y=89..199 in the 288-unit splash viewport.
            // Keep a small margin around all contours while fitting the complete illustration.
            override val intrinsicSize = Size(160f, 116f)

            override fun DrawScope.onDraw() {
                val scaleX = size.width / intrinsicSize.width
                val scaleY = size.height / intrinsicSize.height
                translate(left = -64f * scaleX, top = -86f * scaleY) {
                    with(source) { draw(Size(288f * scaleX, 288f * scaleY)) }
                }
            }
        }
    }
    Image(
        painter = artwork,
        contentDescription = stringResource(R.string.app_name),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().padding(16.dp)
            .testTag(ProfilePhotoViewerTestTags.SplashArtwork),
    )
}
