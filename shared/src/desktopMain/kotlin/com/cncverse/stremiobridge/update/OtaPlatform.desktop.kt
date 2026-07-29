package com.cncverse.stremiobridge.update

actual fun getOtaDownloadDir(): String {
    return System.getProperty("java.io.tmpdir")
}

actual fun saveOtaFile(data: ByteArray, fileName: String): String {
    val file = java.io.File(getOtaDownloadDir(), fileName)
    file.writeBytes(data)
    return file.absolutePath
}

actual fun installOtaUpdate(filePath: String) {
    // No-op for desktop
}
