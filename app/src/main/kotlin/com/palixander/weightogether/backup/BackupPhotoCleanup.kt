package com.palixander.weightogether.backup

import androidx.room.withTransaction
import com.palixander.weightogether.data.AppDatabase
import com.palixander.weightogether.data.ProfilePhotoReferenceCoordinator
import com.palixander.weightogether.profile.ProfilePhotoStore

internal suspend fun cleanupBackupPhotosAtStartup(
    database: AppDatabase,
    archive: BackupArchiveCodec,
    photoReferences: ProfilePhotoReferenceCoordinator,
    photoStore: ProfilePhotoStore,
) {
    archive.clearAbandonedSessions()
    photoReferences.withStableReferences {
        // Startup needs only current photo links, not the potentially large measurement history.
        val referencedPaths = database.withTransaction {
            buildSet {
                addAll(database.accountDao().getPhotoPaths())
                addAll(database.petDao().getPhotoPaths())
            }
        }
        photoStore.removeAbandonedBackupPhotos(referencedPaths)
    }
}
