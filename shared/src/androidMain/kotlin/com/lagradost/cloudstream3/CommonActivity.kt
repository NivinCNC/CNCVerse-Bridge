package com.lagradost.cloudstream3

import android.app.Activity
import android.util.DisplayMetrics
import android.content.res.Resources

object CommonActivity {
    var activity: Activity? = null
        private set

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    val displayMetrics: DisplayMetrics = Resources.getSystem().displayMetrics

    fun showToast(message: String, duration: Int? = null) {
        activity?.runOnUiThread {
            android.widget.Toast.makeText(activity, message, duration ?: android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
