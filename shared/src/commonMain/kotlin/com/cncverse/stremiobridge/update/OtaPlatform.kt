package com.cncverse.stremiobridge.update

expect fun getOtaDownloadDir(): String
expect fun saveOtaFile(data: ByteArray, fileName: String): String
expect fun installOtaUpdate(filePath: String)
