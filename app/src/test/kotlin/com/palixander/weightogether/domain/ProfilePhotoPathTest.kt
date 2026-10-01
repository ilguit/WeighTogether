package com.palixander.weightogether.domain

import org.junit.Assert.assertThrows
import org.junit.Test

class ProfilePhotoPathTest {
    @Test
    fun managedRelativePathIsAccepted() {
        validateManagedProfilePhotoPath("profile-photos/accounts/account.webp")
    }

    @Test
    fun absoluteUriAndTraversalPathsAreRejected() {
        listOf(
            "/data/photo.webp",
            "content://media/photo",
            "profile-photos/../photo.webp",
            "profile-photos\\photo.webp",
            "",
        ).forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) {
                validateManagedProfilePhotoPath(path)
            }
        }
    }
}
