package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp

object DataStore {
    fun Context.getSharedPrefs(): android.content.SharedPreferences {
        return getSharedPreferences("cnc_ext_settings", Context.MODE_PRIVATE)
    }

    fun getFolderName(folder: String, path: String): String {
        return "${folder}/${path}"
    }

    fun <T> Context.setKey(path: String, value: T) {
        CloudStreamApp.setKey(path, value)
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        return CloudStreamApp.getKey<T>(path) ?: defVal
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? {
        return getKey(path, null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? {
        return getKey(getFolderName(folder, path), null)
    }
    
    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        return null
    }
}
