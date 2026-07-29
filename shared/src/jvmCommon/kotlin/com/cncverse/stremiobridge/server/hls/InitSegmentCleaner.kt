package com.cncverse.stremiobridge.server.hls

import java.io.ByteArrayOutputStream

/**
 * Cleaner for init segments - removes encryption metadata (sinf box)
 * and changes sample entry format from 'encv'/'enca' to original format.
 *
 * This is required for players to play back decrypted content without
 * trying to decrypt already clear data.
 */
object InitSegmentCleaner {

    /**
     * Removes encryption metadata from init segment.
     * Changes encv/enca to original format (avc1, hvc1, mp4a, etc.)
     * and removes sinf/schi/schm boxes.
     */
    fun removeEncryptionMetadata(initSegment: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        var offset = 0

        while (offset + 8 <= initSegment.size) {
            val boxSize = readInt32BE(initSegment, offset)
            if (boxSize < 8 || offset + boxSize > initSegment.size) break

            val boxType = String(initSegment, offset + 4, 4, Charsets.US_ASCII)

            when (boxType) {
                "moov" -> {
                    val moovContent = processMoovBox(initSegment, offset + 8, boxSize - 8)
                    val newSize = moovContent.size + 8
                    result.write(intToBytes(newSize))
                    result.write("moov".toByteArray(Charsets.US_ASCII))
                    result.write(moovContent)
                }
                "pssh" -> {
                    // Skip top-level PSSH boxes
                }
                else -> {
                    result.write(initSegment, offset, boxSize)
                }
            }

            offset += boxSize
        }

        return result.toByteArray()
    }

    /**
     * Cleans a single moov box removing encryption metadata.
     * Used for serving cleaned init segment via EXT-X-MAP.
     */
    fun cleanMoovBox(data: ByteArray, offset: Int, boxSize: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val moovContent = processMoovBox(data, offset + 8, boxSize - 8)
        val newSize = moovContent.size + 8
        result.write(intToBytes(newSize))
        result.write("moov".toByteArray(Charsets.US_ASCII))
        result.write(moovContent)
        return result.toByteArray()
    }

    private fun processMoovBox(data: ByteArray, offset: Int, size: Int): ByteArray {
        val result = ByteArrayOutputStream()
        var pos = offset
        val end = offset + size

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)

            when (boxType) {
                "trak" -> {
                    val trakContent = processTrakBox(data, pos + 8, boxSize - 8)
                    val newSize = trakContent.size + 8
                    result.write(intToBytes(newSize))
                    result.write("trak".toByteArray(Charsets.US_ASCII))
                    result.write(trakContent)
                }
                "pssh" -> {
                    // Skip PSSH boxes (DRM related)
                }
                else -> {
                    result.write(data, pos, boxSize)
                }
            }

            pos += boxSize
        }

        return result.toByteArray()
    }

    private fun processTrakBox(data: ByteArray, offset: Int, size: Int): ByteArray {
        val result = ByteArrayOutputStream()
        var pos = offset
        val end = offset + size

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)

            when (boxType) {
                "mdia" -> {
                    val mdiaContent = processMdiaBox(data, pos + 8, boxSize - 8)
                    val newSize = mdiaContent.size + 8
                    result.write(intToBytes(newSize))
                    result.write("mdia".toByteArray(Charsets.US_ASCII))
                    result.write(mdiaContent)
                }
                else -> {
                    result.write(data, pos, boxSize)
                }
            }

            pos += boxSize
        }

        return result.toByteArray()
    }

    private fun processMdiaBox(data: ByteArray, offset: Int, size: Int): ByteArray {
        val result = ByteArrayOutputStream()
        var pos = offset
        val end = offset + size

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)

            when (boxType) {
                "minf" -> {
                    val minfContent = processMinfBox(data, pos + 8, boxSize - 8)
                    val newSize = minfContent.size + 8
                    result.write(intToBytes(newSize))
                    result.write("minf".toByteArray(Charsets.US_ASCII))
                    result.write(minfContent)
                }
                else -> {
                    result.write(data, pos, boxSize)
                }
            }

            pos += boxSize
        }

        return result.toByteArray()
    }

    private fun processMinfBox(data: ByteArray, offset: Int, size: Int): ByteArray {
        val result = ByteArrayOutputStream()
        var pos = offset
        val end = offset + size

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)

            when (boxType) {
                "stbl" -> {
                    val stblContent = processStblBox(data, pos + 8, boxSize - 8)
                    val newSize = stblContent.size + 8
                    result.write(intToBytes(newSize))
                    result.write("stbl".toByteArray(Charsets.US_ASCII))
                    result.write(stblContent)
                }
                else -> {
                    result.write(data, pos, boxSize)
                }
            }

            pos += boxSize
        }

        return result.toByteArray()
    }

    private fun processStblBox(data: ByteArray, offset: Int, size: Int): ByteArray {
        val result = ByteArrayOutputStream()
        var pos = offset
        val end = offset + size

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)

            when (boxType) {
                "stsd" -> {
                    val stsdContent = processStsdBox(data, pos, boxSize)
                    result.write(stsdContent)
                }
                else -> {
                    result.write(data, pos, boxSize)
                }
            }

            pos += boxSize
        }

        return result.toByteArray()
    }

    private fun processStsdBox(data: ByteArray, offset: Int, boxSize: Int): ByteArray {
        val result = ByteArrayOutputStream()

        // stsd header: size(4) + type(4) + version(1) + flags(3) + entry_count(4)
        result.write(data, offset, 16)

        var pos = offset + 16
        val end = offset + boxSize

        while (pos + 8 <= end) {
            val entrySize = readInt32BE(data, pos)
            if (entrySize < 8 || pos + entrySize > end) break

            val entryType = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (entryType == "encv" || entryType == "enca") {
                // Standard CENC: sample entry is encv/enca with sinf containing original format
                val originalFormat = findOriginalFormat(data, pos + 8, entrySize - 8, entryType)
                if (originalFormat != null) {
                    val cleanedEntry = rebuildSampleEntry(data, pos, entrySize, originalFormat)
                    result.write(cleanedEntry)
                } else {
                    result.write(data, pos, entrySize)
                }
            } else if (hasSinfBox(data, pos, entrySize, entryType)) {
                // Non-standard CENC: sample entry keeps original format (e.g., avc1, hvc1, mp4a)
                // but has an embedded sinf box. ExoPlayer will see sinf and expect encrypted
                // segments with senc data, which crashes after server-side decryption.
                // Strip sinf (and related boxes) while keeping the original entry type.
                val cleanedEntry = rebuildSampleEntry(data, pos, entrySize, entryType)
                result.write(cleanedEntry)
            } else {
                result.write(data, pos, entrySize)
            }

            pos += entrySize
        }

        // Update stsd box size
        val resultBytes = result.toByteArray()
        val newSize = resultBytes.size
        resultBytes[0] = ((newSize shr 24) and 0xFF).toByte()
        resultBytes[1] = ((newSize shr 16) and 0xFF).toByte()
        resultBytes[2] = ((newSize shr 8) and 0xFF).toByte()
        resultBytes[3] = (newSize and 0xFF).toByte()

        return resultBytes
    }

    /**
     * Find original format from frma box inside sinf.
     * Sample entries have fixed fields BEFORE child boxes:
     * - Video (encv): 78 bytes
     * - Audio (enca): 28 bytes
     */
    private fun getFixedFieldsSize(data: ByteArray, offset: Int, entryType: String): Int {
        return when (entryType) {
            "avc1", "avc3", "hev1", "hvc1", "vp08", "vp09", "av01", "encv" -> 78
            "mp4a", "ac-3", "ec-3", "Opus", "fLaC", "enca" -> {
                // Check AudioSampleEntry version (offset 16 from start of box)
                // Box start is `offset - 8` since `offset` skips size/type
                // Wait, `offset` passed to this function:
                // In `findOriginalFormat`, `offset` is `pos + 8`. So `pos` is `offset - 8`.
                // `version` is at `pos + 16`.
                var version = 0
                if (offset + 10 <= data.size) { // `offset - 8 + 16` = `offset + 8`
                    version = (data[offset + 8].toInt() and 0xFF shl 8) or (data[offset + 9].toInt() and 0xFF)
                }
                if (version == 1) 44 else 28
            }
            else -> 16
        }
    }

    private fun findOriginalFormat(data: ByteArray, offset: Int, size: Int, entryType: String): String? {
        val end = offset + size

        val fixedFieldsSize = when (entryType) {
            "encv" -> 78
            "enca" -> getFixedFieldsSize(data, offset, entryType)
            else -> {
                // Try to find sinf by scanning
                var scanPos = offset
                while (scanPos + 8 <= end) {
                    val potentialType = try {
                        String(data, scanPos + 4, 4, Charsets.US_ASCII)
                    } catch (e: Exception) { "" }
                    if (potentialType == "sinf") {
                        return findSinfAndFormat(data, scanPos, end)
                    }
                    scanPos++
                }
                return null
            }
        }

        val boxesStart = offset + fixedFieldsSize
        return findSinfAndFormat(data, boxesStart, end)
    }

    private fun findSinfAndFormat(data: ByteArray, startPos: Int, end: Int): String? {
        var pos = startPos

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (boxType == "sinf") {
                // Search frma inside sinf
                var sinfPos = pos + 8
                val sinfEnd = pos + boxSize

                while (sinfPos + 8 <= sinfEnd) {
                    val sinfBoxSize = readInt32BE(data, sinfPos)
                    if (sinfBoxSize < 8 || sinfPos + sinfBoxSize > sinfEnd) break

                    val sinfBoxType = String(data, sinfPos + 4, 4, Charsets.US_ASCII)

                    if (sinfBoxType == "frma" && sinfBoxSize >= 12) {
                        // frma: size(4) + type(4) + original_format(4)
                        return String(data, sinfPos + 8, 4, Charsets.US_ASCII)
                    }

                    sinfPos += sinfBoxSize
                }
            }

            pos += boxSize
        }

        return null
    }

    /**
     * Checks if a sample entry contains a sinf box (encryption metadata).
     * This handles the case where the entry type is NOT encv/enca but still
     * has CENC encryption via an embedded sinf box (e.g., avc1 with sinf).
     */
    private fun hasSinfBox(data: ByteArray, offset: Int, entrySize: Int, entryType: String): Boolean {
        // Here `offset` is the start of the box (`pos`).
        val fixedFieldsSize = getFixedFieldsSize(data, offset + 8, entryType)

        val boxesStart = offset + 8 + fixedFieldsSize
        val end = offset + entrySize
        var pos = boxesStart

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = try {
                String(data, pos + 4, 4, Charsets.US_ASCII)
            } catch (e: Exception) { "" }

            if (boxType == "sinf") return true

            pos += boxSize
        }

        return false
    }

    /**
     * Rebuild sample entry without sinf box and with original format type.
     */
    private fun rebuildSampleEntry(data: ByteArray, offset: Int, size: Int, originalFormat: String): ByteArray {
        val result = ByteArrayOutputStream()

        // Determine entry type from data
        val entryType = String(data, offset + 4, 4, Charsets.US_ASCII)

        // Here `offset` is the start of the box (`pos`).
        val fixedFieldsSize = getFixedFieldsSize(data, offset + 8, entryType)

        val headerEnd = offset + 8 + fixedFieldsSize
        val boxesStart = headerEnd

        // Write new size (placeholder) + new type
        result.write(ByteArray(4)) // Size placeholder
        result.write(originalFormat.toByteArray(Charsets.US_ASCII))

        // Copy fixed fields (after original size+type)
        if (fixedFieldsSize > 0 && offset + 8 + fixedFieldsSize <= offset + size) {
            result.write(data, offset + 8, fixedFieldsSize)
        }

        // Copy child boxes except sinf, schi, schm
        var pos = boxesStart
        val end = offset + size

        while (pos + 8 <= end) {
            val boxSize = readInt32BE(data, pos)
            if (boxSize < 8 || pos + boxSize > end) break

            val boxType = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (boxType != "sinf" && boxType != "schi" && boxType != "schm" && boxType != "tenc") {
                result.write(data, pos, boxSize)
            }

            pos += boxSize
        }

        // Update size in result
        val resultBytes = result.toByteArray()
        val newSize = resultBytes.size
        resultBytes[0] = ((newSize shr 24) and 0xFF).toByte()
        resultBytes[1] = ((newSize shr 16) and 0xFF).toByte()
        resultBytes[2] = ((newSize shr 8) and 0xFF).toByte()
        resultBytes[3] = (newSize and 0xFF).toByte()

        return resultBytes
    }

    private fun readInt32BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
               ((data[offset + 1].toInt() and 0xFF) shl 16) or
               ((data[offset + 2].toInt() and 0xFF) shl 8) or
               (data[offset + 3].toInt() and 0xFF)
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }
}
