package com.cncverse.stremiobridge.update

import android.content.Intent
import androidx.core.content.FileProvider
import com.cncverse.stremiobridge.repo.AndroidContextHolder
import java.io.File

actual fun getOtaDownloadDir(): String {
    val context = AndroidContextHolder.appContext
    val dir = File(context.externalCacheDir, "ota_updates")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}

actual fun saveOtaFile(data: ByteArray, fileName: String): String {
    val file = File(getOtaDownloadDir(), fileName)
    file.writeBytes(data)
    return file.absolutePath
}

actual fun installOtaUpdate(filePath: String) {
    val context = AndroidContextHolder.appContext
    val file = File(filePath)
    
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    context.startActivity(intent)
}
