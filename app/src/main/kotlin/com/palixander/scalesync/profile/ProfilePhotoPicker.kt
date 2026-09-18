package com.palixander.scalesync.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    owner: ProfilePhotoOwner,
    onPhotoReady: (String) -> Unit,
    onError: (ProfilePhotoError) -> Unit,
): ProfilePhotoPicker {
    val scope = rememberCoroutineScope()
    var pendingCapture by remember { mutableStateOf<PendingProfilePhotoCapture?>(null) }

    fun import(block: suspend () -> String) {
        scope.launch {
            try {
                onPhotoReady(block())
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
        if (uri != null) import { store.import(owner, uri) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capture = pendingCapture
        pendingCapture = null
        if (capture != null) {
            if (success) import { store.completeCapture(owner, capture) } else capture.cancel()
        }
    }
    DisposableEffect(store, owner) {
        onDispose { pendingCapture?.cancel() }
    }

    return remember(store, owner, gallery, camera) {
        ProfilePhotoPicker(
            chooseFromGallery = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            takePhoto = {
                pendingCapture?.cancel()
                try {
                    store.createCapture().also {
                        pendingCapture = it
                        camera.launch(it.uri)
                    }
                } catch (_: Exception) {
                    pendingCapture = null
                    onError(ProfilePhotoError.PROCESSING_FAILED)
                }
            },
        )
    }
}
