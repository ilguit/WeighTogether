package com.palixander.scalesync.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Launch handles shared by account and pet editors; rendering and error text stay with the caller. */
class ProfilePhotoPicker internal constructor(
    val chooseFromGallery: () -> Unit,
    val takePhoto: () -> Unit,
)

@Composable
fun rememberProfilePhotoPicker(
    store: ProfilePhotoStore,
    onPhotoPrepared: (PreparedProfilePhoto) -> Unit,
    onError: (ProfilePhotoError) -> Unit,
): ProfilePhotoPicker {
    val scope = rememberCoroutineScope()
    // Keep only the validated, app-private filename in saved state. The Uri is rebuilt by the
    // store, so an Activity/process recreation cannot lose the destination before the result.
    var pendingCaptureIdentifier by rememberSaveable { mutableStateOf<String?>(null) }

    fun prepare(block: suspend () -> PreparedProfilePhoto) {
        scope.launch {
            try {
                onPhotoPrepared(block())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: ProfilePhotoException) {
                onError(known.error)
            } catch (_: Exception) {
                onError(ProfilePhotoError.PROCESSING_FAILED)
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepare { store.prepare(uri) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capture = pendingCaptureIdentifier?.let(store::restoreCapture)
        pendingCaptureIdentifier = null
        if (capture != null) {
            if (success) prepare { store.prepareCapture(capture) } else capture.cancel()
        } else if (success) {
            onError(ProfilePhotoError.PROCESSING_FAILED)
        }
    }

    return remember(store, gallery, camera) {
        ProfilePhotoPicker(
            chooseFromGallery = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            takePhoto = {
                pendingCaptureIdentifier?.let(store::restoreCapture)?.cancel()
                try {
                    store.createCapture().also {
                        pendingCaptureIdentifier = it.identifier
                        camera.launch(it.uri)
                    }
                } catch (_: Exception) {
                    pendingCaptureIdentifier?.let(store::restoreCapture)?.cancel()
                    pendingCaptureIdentifier = null
                    onError(ProfilePhotoError.PROCESSING_FAILED)
                }
            },
        )
    }
}
