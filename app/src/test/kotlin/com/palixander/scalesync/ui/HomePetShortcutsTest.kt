package com.palixander.scalesync.ui

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomePetShortcutsTest : HomePetShortcutsTestCases()
