package com.palixander.weightogether.data

/** Receives a managed relative path after its final database reference has been removed. */
fun interface ProfilePhotoLifecycle {
    suspend fun onPhotoDereferenced(photoPath: String)

    companion object {
        val None = ProfilePhotoLifecycle { }
    }
}
