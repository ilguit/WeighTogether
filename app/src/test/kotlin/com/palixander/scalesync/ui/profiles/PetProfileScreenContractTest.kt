package com.palixander.scalesync.ui.profiles

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.ui.theme.ScaleSyncDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class PetProfileScreenContractTest {
    @Test
    fun `profile list adds only thematic bottom content padding`() {
        val padding = petProfileListContentPadding()

        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(0.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(ScaleSyncDimensions.ContentPadding, padding.calculateBottomPadding())
    }
}
