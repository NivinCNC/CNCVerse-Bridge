package com.cncverse.stremiobridge.plugin

import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Tracks the active UI activity required by plugins that create Android settings UI. */
object PluginUIContext {
    private val activityState = MutableStateFlow<AppCompatActivity?>(null)

    var currentActivity: AppCompatActivity?
        get() = activityState.value
        set(value) {
            activityState.value = value
            com.lagradost.cloudstream3.CommonActivity.setActivityInstance(value)
        }

    suspend fun awaitActivity(): AppCompatActivity = activityState.filterNotNull().first()
}