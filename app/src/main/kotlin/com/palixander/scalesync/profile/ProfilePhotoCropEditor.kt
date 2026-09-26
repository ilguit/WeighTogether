package com.palixander.scalesync.profile

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import com.palixander.scalesync.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

private enum class ProfilePhotoCropPhase { READY, SAVING, CONSUMED }

internal class ProfilePhotoDeliveryTargets {
    private sealed interface State {
        data object Detached : State
        data object Closed : State
    }

    private data class Target(
        val token: Any,
        val deliver: (String) -> Unit,
        val reportError: (ProfilePhotoError) -> Unit,
    ) : State

    private val current = MutableStateFlow<State>(State.Detached)

    fun attach(token: Any, deliver: (String) -> Unit, reportError: (ProfilePhotoError) -> Unit) {
        val target = Target(token, deliver, reportError)
        while (true) {
            val state = current.value
            if (state === State.Closed || current.compareAndSet(state, target)) return
        }
    }

    fun detach(token: Any) {
        while (true) {
            val state = current.value
            if (state !is Target || state.token !== token) return
            if (current.compareAndSet(state, State.Detached)) return
        }
    }

    fun close() {
        current.value = State.Closed
    }

    suspend fun deliver(dispatcher: CoroutineDispatcher, path: String) {
        withContext(dispatcher) {
            when (val state = current.first { it !== State.Detached }) {
                is Target -> state.deliver(path)
                State.Closed -> throw ProfilePhotoDeliveryClosedException()
                State.Detached -> error("Detached state passed the delivery predicate")
            }
        }
    }

    suspend fun report(dispatcher: CoroutineDispatcher, error: ProfilePhotoError) {
        withContext(dispatcher) {
            when (val state = current.first { it !== State.Detached }) {
                is Target -> state.reportError(error)
                State.Closed -> throw ProfilePhotoDeliveryClosedException()
                State.Detached -> kotlin.error("Detached state passed the delivery predicate")
            }
        }
    }
}

internal class ProfilePhotoDeliveryClosedException : IllegalStateException("Profile photo editor is permanently closed")

/**
 * Owns one crop operation across Activity recreation. The ViewModel, rather than either
 * composition, owns confirmation so a restored editor cannot start a second import while the
 * first composition is completing its handoff.
 */
internal class ProfilePhotoCropStateOwner(
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val deliveryTargets = ProfilePhotoDeliveryTargets()
    var identifier by mutableStateOf(savedState.get<String>(IDENTIFIER))
        private set
    var transform by mutableStateOf(
        ProfilePhotoCropTransform(
            savedState[ZOOM] ?: 1f,
            savedState[PAN_X] ?: 0f,
            savedState[PAN_Y] ?: 0f,
        ),
    )
        private set
    private var phase by mutableStateOf(if (identifier == null) ProfilePhotoCropPhase.CONSUMED else ProfilePhotoCropPhase.READY)

    val saving: Boolean get() = phase == ProfilePhotoCropPhase.SAVING

    fun attach(token: Any, deliver: (String) -> Unit, reportError: (ProfilePhotoError) -> Unit) {
        deliveryTargets.attach(token, deliver, reportError)
    }

    fun detach(token: Any) {
        deliveryTargets.detach(token)
    }

    fun open(prepared: PreparedProfilePhoto, cancelPrevious: (String) -> Unit) {
        if (phase == ProfilePhotoCropPhase.SAVING) {
            cancelPrevious(prepared.identifier)
            return
        }
        identifier?.takeIf { it != prepared.identifier }?.let(cancelPrevious)
        identifier = prepared.identifier
        transform = ProfilePhotoCropTransform()
        phase = ProfilePhotoCropPhase.READY
        save()
    }

    fun updateTransform(value: ProfilePhotoCropTransform) {
        if (phase != ProfilePhotoCropPhase.READY) return
        transform = value
        save()
    }

    fun cancel(cancelPrepared: (String) -> Unit) {
        val current = identifier ?: return
        if (phase != ProfilePhotoCropPhase.READY) return
        phase = ProfilePhotoCropPhase.CONSUMED
        clear()
        cancelPrepared(current)
    }

    fun discardUnreadable() {
        if (phase == ProfilePhotoCropPhase.READY) {
            phase = ProfilePhotoCropPhase.CONSUMED
            clear()
        }
    }

    fun confirm(
        store: ProfilePhotoStore,
        owner: ProfilePhotoOwner,
        prepared: PreparedProfilePhoto,
    ) {
        if (phase != ProfilePhotoCropPhase.READY || identifier != prepared.identifier) return
        phase = ProfilePhotoCropPhase.SAVING
        val requestedTransform = transform
        viewModelScope.launch {
            try {
                store.confirmCrop(owner, prepared, requestedTransform) { path ->
                    deliveryTargets.deliver(Dispatchers.Main.immediate, path)
                }
                phase = ProfilePhotoCropPhase.CONSUMED
                clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: ProfilePhotoException) {
                phase = ProfilePhotoCropPhase.READY
                deliveryTargets.report(Dispatchers.Main.immediate, known.error)
            } catch (_: Exception) {
                phase = ProfilePhotoCropPhase.READY
                deliveryTargets.report(Dispatchers.Main.immediate, ProfilePhotoError.PROCESSING_FAILED)
            }
        }
    }

    private fun save() {
        savedState[IDENTIFIER] = identifier
        savedState[ZOOM] = transform.zoom
        savedState[PAN_X] = transform.panX
        savedState[PAN_Y] = transform.panY
    }

    private fun clear() {
        identifier = null
        savedState[IDENTIFIER] = null
        savedState[ZOOM] = null
        savedState[PAN_X] = null
        savedState[PAN_Y] = null
    }

    override fun onCleared() {
        deliveryTargets.close()
        super.onCleared()
    }

    private companion object {
        const val IDENTIFIER = "identifier"
        const val ZOOM = "zoom"
        const val PAN_X = "pan-x"
        const val PAN_Y = "pan-y"
    }
}

@Composable
fun rememberProfilePhotoCropController(
    store: ProfilePhotoStore,
    owner: ProfilePhotoOwner,
    onPhotoReady: (String) -> Unit,
    onError: (ProfilePhotoError) -> Unit,
): ProfilePhotoCropController {
    val stateOwner: ProfilePhotoCropStateOwner = viewModel(
        key = "profile-photo-crop-${owner.type}-${owner.id}",
    )
    val identifier = stateOwner.identifier
    val prepared = remember(store, identifier) { identifier?.let(store::restorePrepared) }
    val deliveryToken = remember { Any() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(stateOwner, deliveryToken, lifecycleOwner, onPhotoReady, onError) {
        fun updateDeliveryTarget() {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                stateOwner.attach(deliveryToken, onPhotoReady, onError)
            } else {
                stateOwner.detach(deliveryToken)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateDeliveryTarget() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateDeliveryTarget()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stateOwner.detach(deliveryToken)
        }
    }
    LaunchedEffect(identifier, prepared) {
        if (identifier != null && prepared == null && !stateOwner.saving) {
            stateOwner.discardUnreadable()
            onError(ProfilePhotoError.UNREADABLE_SOURCE)
        }
    }

    val controller = ProfilePhotoCropController { replacement ->
        stateOwner.open(replacement) { old -> store.restorePrepared(old)?.cancel() }
    }
    prepared?.let { photo ->
        ProfilePhotoCropEditor(
            prepared = photo,
            transform = stateOwner.transform,
            saving = stateOwner.saving,
            onTransformChanged = stateOwner::updateTransform,
            onCancel = {
                stateOwner.cancel { store.restorePrepared(it)?.cancel() }
            },
            onConfirm = {
                stateOwner.confirm(store, owner, photo)
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
    saving: Boolean = false,
    onTransformChanged: (ProfilePhotoCropTransform) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (ProfilePhotoCropTransform) -> Unit,
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
    val constrained = transform.constrained()
    val latestTransform by rememberUpdatedState(constrained)
    val latestOnTransformChanged by rememberUpdatedState(onTransformChanged)
    BackHandler(enabled = !saving, onBack = onCancel)

    Dialog(onDismissRequest = { if (!saving) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.fillMaxSize().testTag(ProfilePhotoCropTestTags.Editor)) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.photo_crop_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.photo_crop_hint), style = MaterialTheme.typography.bodyMedium)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (!imageLoad.second) CircularProgressIndicator(Modifier.testTag(ProfilePhotoCropTestTags.Progress))
                    if (imageLoad.second && bitmap == null) {
                        Text(
                            stringResource(R.string.photo_crop_error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(ProfilePhotoCropTestTags.Error),
                        )
                    }
                    bitmap?.let { image ->
                        val viewportDescription = stringResource(R.string.photo_crop_viewport)
                        val viewportState = stringResource(R.string.photo_crop_state, (constrained.zoom * 100).toInt(), (constrained.panX * 100).toInt(), (constrained.panY * 100).toInt())
                        Canvas(
                            Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 420.dp)
                                .fillMaxWidth().aspectRatio(1f).padding(16.dp).onSizeChanged { viewportPx = minOf(it.width, it.height).toFloat() }
                                .pointerInput(prepared.identifier) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var gestureTransform = latestTransform
                                        do {
                                            val event = awaitPointerEvent()
                                            val pan = event.calculatePan()
                                            val gestureZoom = event.calculateZoom()
                                            val previousCentroid = event.calculateCentroid(useCurrent = false)
                                            val currentCentroid = event.calculateCentroid(useCurrent = true)
                                            if (!previousCentroid.isFinite() || !currentCentroid.isFinite()) {
                                                continue
                                            }
                                            val geometry = ProfilePhotoCropGeometry(
                                                prepared.width,
                                                prepared.height,
                                                minOf(size.width, size.height).toFloat(),
                                            )
                                            gestureTransform = geometry.transformBy(
                                                transform = gestureTransform,
                                                centroidX = previousCentroid.x,
                                                centroidY = previousCentroid.y,
                                                displayPanX = pan.x,
                                                displayPanY = pan.y,
                                                zoomChange = gestureZoom,
                                            )
                                            latestOnTransformChanged(gestureTransform)
                                            if (pan != Offset.Zero || gestureZoom != 1f) {
                                                event.changes.forEach { it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }.semantics {
                                    contentDescription = viewportDescription
                                    stateDescription = viewportState
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
                    val zoomOutDescription = stringResource(R.string.photo_crop_zoom_out)
                    val zoomDescription = stringResource(R.string.photo_crop_zoom_control)
                    val zoomInDescription = stringResource(R.string.photo_crop_zoom_in)
                    Text(stringResource(R.string.photo_crop_zoom, (constrained.zoom * 100).toInt()))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onTransformChanged(constrained.copy(zoom = constrained.zoom - .25f).constrained()) },
                            enabled = !saving && constrained.zoom > 1f,
                            modifier = Modifier.size(48.dp).testTag(ProfilePhotoCropTestTags.ZoomOut).semantics { contentDescription = zoomOutDescription },
                        ) { Text("−", style = MaterialTheme.typography.headlineSmall) }
                        Slider(
                            value = constrained.zoom,
                            onValueChange = { onTransformChanged(constrained.copy(zoom = it).constrained()) },
                            valueRange = 1f..ProfilePhotoCropTransform.DEFAULT_MAX_ZOOM,
                            enabled = !saving,
                            modifier = Modifier.weight(1f).testTag(ProfilePhotoCropTestTags.Zoom).semantics { contentDescription = zoomDescription },
                        )
                        TextButton(
                            onClick = { onTransformChanged(constrained.copy(zoom = constrained.zoom + .25f).constrained()) },
                            enabled = !saving && constrained.zoom < ProfilePhotoCropTransform.DEFAULT_MAX_ZOOM,
                            modifier = Modifier.size(48.dp).testTag(ProfilePhotoCropTestTags.ZoomIn).semantics { contentDescription = zoomInDescription },
                        ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(ProfilePhotoCropTestTags.Cancel)) { Text(stringResource(R.string.photo_crop_cancel)) }
                        Button(
                            onClick = {
                                onConfirm(constrained)
                            },
                            enabled = bitmap != null && !saving,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(ProfilePhotoCropTestTags.Done),
                        ) { Text(stringResource(if (saving) R.string.photo_crop_saving else R.string.photo_crop_done)) }
                    }
                }
            }
        }
    }
}

private fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()
