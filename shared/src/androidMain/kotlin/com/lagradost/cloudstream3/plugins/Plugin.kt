package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

abstract class Plugin : BasePlugin() {
    open var openSettings: ((Context) -> Unit)? = null
    var resources: Resources? = null

    open fun load(context: Context) {
        load()
    }
}