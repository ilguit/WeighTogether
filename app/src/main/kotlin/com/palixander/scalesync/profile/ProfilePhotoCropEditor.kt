package com.palixander.scalesync.profile

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ProfilePhotoCropTestTags {
    const val Editor = "profile-photo-crop-editor"
    const val Viewport = "profile-photo-crop-viewport"
    const val Zoom = "profile-photo-crop-zoom"
    const val ZoomOut = "profile-photo-crop-zoom-out"
    const val ZoomIn = "profile-photo-crop-zoom-in"
    const val Cancel = "profile-photo-crop-cancel"
    const val Done = "profile-photo-crop-done"
    const val Progress = "profile-photo-crop-progress"
    const val Error = "profile-photo-crop-error"
}

/** Saveable bridge between a picker and the common editor. */
class ProfilePhotoCropController internal constructor(
    private val openPhoto: (PreparedProfilePhoto) -> Unit,
) {
    fun open(prepared: PreparedProfilePhoto) = openPhoto(prepared)
}

@Composable
fun rememberProfilePhotoCropController(
    store: ProfilePhotoStore,
    owner: ProfilePhotoOwner,
    onPhotoReady: (String) -> Unit,
    onError: (ProfilePhotoError) -> Unit,
): ProfilePhotoCropController {
    var identifier by rememberSaveable { mutableStateOf<String?>(null) }
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var panX by rememberSaveable { mutableStateOf(0f) }
    var panY by rememberSaveable { mutableStateOf(0f) }
    var prepared by remember(store, identifier) {
        mutableStateOf(identifier?.let(store::restorePrepared))
    }

    LaunchedEffect(identifier) {
        if (identifier != null && prepared == null) {
            identifier = null
            onError(ProfilePhotoError.UNREADABLE_SOURCE)
        }
    }

    val controller = ProfilePhotoCropController { replacement ->
            prepared?.takeIf { it.identifier != replacement.identifier }?.cancel()
            identifier = replacement.identifier
            prepared = replacement
            zoom = 1f
            panX = 0f
            panY = 0f
    }
    prepared?.let { photo ->
        ProfilePhotoCropEditor(
            prepared = photo,
            transform = ProfilePhotoCropTransform(zoom, panX, panY),
            onTransformChanged = {
                zoom = it.zoom
                panX = it.panX
                panY = it.panY
            },
            onCancel = {
                photo.cancel()
                prepared = null
                identifier = null
            },
            onConfirm = { transform, completed ->
                try {
                    store.confirmCrop(owner, photo, transform) { path ->
                        withContext(Dispatchers.Main.immediate) {
                            // Clear the restorable source before ownership is handed to the draft.
                            // These mutations and the callback inherit the store's NonCancellable
                            // handoff even if this composition is being disposed for recreation.
                            prepared = null
                            identifier = null
                            onPhotoReady(path)
                        }
                    }
                    completed(null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (known: ProfilePhotoException) {
                    completed(known.error)
                } catch (_: Exception) {
                    completed(ProfilePhotoError.PROCESSING_FAILED)
                }
            },
        )
    }
    // Deliberately do not delete here: disposal also happens during Activity recreation. Explicit
    // cancel/replacement owns cleanup, and the store expires abandoned prepared files.
    DisposableEffect(Unit) { onDispose {} }
    return controller
}

@Composable
internal fun ProfilePhotoCropEditor(
    prepared: PreparedProfilePhoto,
    transform: ProfilePhotoCropTransform,
    onTransformChanged: (ProfilePhotoCropTransform) -> Unit,
    onCancel: () -> Unit,
    onConfirm: suspend (ProfilePhotoCropTransform, (ProfilePhotoError?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageLoad by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap?, Boolean>>(null to false, prepared.identifier) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(prepared.uri)?.use(BitmapFactory::decodeStream)?.asImageBitmap()
            }.getOrNull() to true
        }
    }
    val bitmap = imageLoad.first
    var viewportPx by remember { mutableStateOf(1f) }
    // An in-flight coroutine belongs to this composition. It is cancelled when the Activity is
    // recreated, so this flag must reset too instead of restoring a permanently disabled editor.
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<ProfilePhotoError?>(null) }
    val scope = rememberCoroutineScope()
    val constrained = transform.constrained()
    BackHandler(enabled = !saving, onBack = onCancel)

    Dialog(onDismissRequest = { if (!saving) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.fillMaxSize().testTag(ProfilePhotoCropTestTags.Editor)) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Настройка фото", style = MaterialTheme.typography.headlineSmall)
                    Text("Перетащите фото и измените масштаб", style = MaterialTheme.typography.bodyMedium)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (!imageLoad.second) CircularProgressIndicator(Modifier.testTag(ProfilePhotoCropTestTags.Progress))
                    if (imageLoad.second && bitmap == null) {
                        Text(
                            "Не удалось открыть фото",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(ProfilePhotoCropTestTags.Error),
                        )
                    }
                    bitmap?.let { image ->
                        Canvas(
                            Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 420.dp)
                                .fillMaxWidth().aspectRatio(1f).padding(16.dp).onSizeChanged { viewportPx = minOf(it.width, it.height).toFloat() }
                                .pointerInput(prepared.identifier, viewportPx, constrained) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        val geometry = ProfilePhotoCropGeometry(prepared.width, prepared.height, viewportPx)
                                        val zoomed = constrained.copy(zoom = constrained.zoom * gestureZoom).constrained()
                                        val scale = geometry.displayScale(zoomed)
                                        onTransformChanged(geometry.panBy(zoomed, -pan.x / scale, -pan.y / scale))
                                    }
                                }.semantics {
                                    contentDescription = "Область кадрирования. Перетаскивайте фото двумя пальцами или одним пальцем"
                                    stateDescription = "Масштаб ${(constrained.zoom * 100).toInt()} процентов; " +
                                        "позиция ${(constrained.panX * 100).toInt()}, ${(constrained.panY * 100).toInt()}"
                                }.testTag(ProfilePhotoCropTestTags.Viewport),
                        ) {
                            val diameter = minOf(size.width, size.height)
                            val origin = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                            val geometry = ProfilePhotoCropGeometry(prepared.width, prepared.height, diameter)
                            val crop = geometry.sourceCropBounds(constrained)
                            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(origin, Size(diameter, diameter))) }
                            clipPath(circle) {
                                drawImage(
                                    image = image,
                                    srcOffset = androidx.compose.ui.unit.IntOffset(crop.left, crop.top),
                                    srcSize = androidx.compose.ui.unit.IntSize(crop.size, crop.size),
                                    dstOffset = androidx.compose.ui.unit.IntOffset(origin.x.toInt(), origin.y.toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(diameter.toInt(), diameter.toInt()),
                                )
                            }
                            drawCircle(Color.White.copy(alpha = .85f), diameter / 2f, origin + Offset(diameter / 2f, diameter / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Масштаб ${(constrained.zoom * 100).toInt()}%")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onTransformChanged(constrained.copy(zoom = constrained.zoom - .25f).constrained()) },
                            enabled = !saving && constrained.zoom > 1f,
                            modifier = Modifier.size(48.dp).testTag(ProfilePhotoCropTestTags.ZoomOut).semantics { contentDescription = "Уменьшить фото" },
                        ) { Text("−", style = MaterialTheme.typography.headlineSmall) }
                        Slider(
                            value = constrained.zoom,
                            onValueChange = { onTransformChanged(constrained.copy(zoom = it).constrained()) },
                            valueRange = 1f..ProfilePhotoCropTransform.DEFAULT_MAX_ZOOM,
                            enabled = !saving,
                            modifier = Modifier.weight(1f).testTag(ProfilePhotoCropTestTags.Zoom).semantics { contentDescription = "Масштаб фото" },
                        )
                        TextButton(
                            onClick = { onTransformChanged(constrained.copy(zoom = constrained.zoom + .25f).constrained()) },
                            enabled = !saving && constrained.zoom < ProfilePhotoCropTransform.DEFAULT_MAX_ZOOM,
                            modifier = Modifier.size(48.dp).testTag(ProfilePhotoCropTestTags.ZoomIn).semantics { contentDescription = "Увеличить фото" },
                        ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
                    }
                    error?.let {
                        Text("Не удалось обработать фото. Попробуйте ещё раз.", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ProfilePhotoCropTestTags.Error))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(ProfilePhotoCropTestTags.Cancel)) { Text("Отмена") }
                        Button(
                            onClick = {
                                saving = true
                                error = null
                                scope.launch {
                                    onConfirm(constrained) { failure ->
                                        error = failure
                                        saving = false
                                    }
                                }
                            },
                            enabled = bitmap != null && !saving,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(ProfilePhotoCropTestTags.Done),
                        ) { Text(if (saving) "Сохранение…" else "Готово") }
                    }
                }
            }
        }
    }
}
