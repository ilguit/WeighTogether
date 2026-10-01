package com.palixander.weightogether.ui.reference

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

fun interface ReferenceSourceLauncher {
    /** Returns false when no safe external handler can open the source. */
    fun open(url: String): Boolean
}

class AndroidReferenceSourceLauncher(
    private val context: Context,
) : ReferenceSourceLauncher {
    override fun open(url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
