package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.utils.Event
import java.io.File

/**
 * A stub MainActivity class that mimics CloudStream's MainActivity.
 * Many plugins cast the context to MainActivity or reference it directly
 * when opening their settings dialogs. By having our main app activity
 * inherit from this, we prevent NoClassDefFoundError and ClassCastException.
 */
open class MainActivity : AppCompatActivity() {
    companion object {
        var activityResultLauncher: ActivityResultLauncher<Intent>? = null

        const val TAG = "MAINACT"
        const val ANIMATED_OUTLINE: Boolean = false
        var lastError: String? = null

        fun setLastError(context: Context) {}

        const val API_NAME_EXTRA_KEY = "API_NAME_EXTRA_KEY"

        fun deleteFileOnExit(file: File) {}

        var nextSearchQuery: String? = null

        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()

        fun handleAppIntentUrl(
            activity: FragmentActivity?,
            str: String?,
            isWebview: Boolean,
            extraArgs: Bundle? = null
        ): Boolean = false

        fun centerView(view: View?) {}
    }
}
