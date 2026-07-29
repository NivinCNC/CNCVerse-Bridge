package com.lagradost.cloudstream3.ui.settings

object Globals {
    const val TV = 1
    
    fun isLayout(type: Int): Boolean {
        return false // We are not a TV layout (or doesn't matter for bridge)
    }
}
