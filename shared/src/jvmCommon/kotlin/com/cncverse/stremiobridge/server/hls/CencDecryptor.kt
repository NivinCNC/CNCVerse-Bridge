package com.cncverse.stremiobridge.server.hls

import com.cncverse.stremiobridge.state.ServerState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts CENC encrypted MP4 segments.
 * Port of EasyProxy utils/drm_decrypter.py
 */
object CencDecryptor {

    private const val TAG = "CencDecryptor"

    /**
     * Decrypts a combined (init + media) MP4 segment.
     *
     * @param initSegment Init segment (ftyp + moov) - needed for decryption context
     * @param mediaSegment Media segment (styp + sidx + moof + mdat)
     * @param keyIdHex Key ID in hex format
     * @param keyHex Decryption key in hex format
     * @param excludeInit If true, excludes ftyp/moov from output (for HLS fMP4 with EXT-X-MAP)
     *                    EasyProxy behavior: FALSE - include init in every segment
     */
    fun decryptSegment(
        initSegment: ByteArray?,
        mediaSegment: ByteArray,
        keyIdHex: String,
        keyHex: String,
        excludeInit: Boolean = false  // EasyProxy: include init in every segment
    ): ByteArray {
        val keyIdBytes = hexStringToByteArray(keyIdHex)
        val keyBytes = hexStringToByteArray(keyHex)
        val keyMap = mapOf(ByteBuffer.wrap(keyIdBytes) to keyBytes)

        val combinedData = if (initSegment != null) {
            val combined = ByteArray(initSegment.size + mediaSegment.size)
            System.arraycopy(initSegment, 0, combined, 0, initSegment.size)
            System.arraycopy(mediaSegment, 0, combined, initSegment.size, mediaSegment.size)
            combined
        } else {
            mediaSegment
        }

        val decrypter = MP4Decrypter(keyMap, excludeInit)
        return decrypter.decryptSegment(combinedData)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

private class MP4Decrypter(
    private val keyMap: Map<ByteBuffer, ByteArray>,
    private val excludeInit: Boolean = true
) {
    private var currentKey: ByteArray? = null
    private var trunSampleSizes: IntArray = IntArray(0)
    private var currentSampleInfo: List<CencSampleInfo> = emptyList()
    private var encryptionOverhead = 0
    private var defaultIvSize = 8

    // Atoms that are part of init segment (excluded when excludeInit=true)
    private val initAtoms = setOf("ftyp", "moov", "free", "skip")

    fun decryptSegment(data: ByteArray): ByteArray {
        val parser = MP4Parser(data)
        val atoms = parser.listAtoms()

        val result = ByteBuffer.allocate(data.size)
        var i = 0
        while (i < atoms.size) {
            val atom = atoms[i]

            if (excludeInit && initAtoms.contains(atom.typeString)) {
                i++
                continue
            }

            when (atom.typeString) {
                "moov" -> {
                    result.put(processMoov(atom).pack())
                    i++
                }
                "sidx" -> {
                    result.put(processSidx(atom).pack())
                    i++
                }
                "moof" -> {
                    // processMoof sets state scoped to THIS fragment
                    val processedMoof = processMoof(atom)
                    result.put(processedMoof.pack())
                    i++
                    // Process matching mdat immediately
                    if (i < atoms.size && atoms[i].typeString == "mdat") {
                        result.put(decryptMdat(atoms[i]).pack())
                        i++
                    }
                }
                "mdat" -> {
                    // Orphan mdat
                    result.put(decryptMdat(atom).pack())
                    i++
                }
                "pssh" -> {
                    i++ // Drop top-level pssh
                }
                else -> {
                    result.put(atom.pack())
                    i++
                }
            }
        }
        return Arrays.copyOf(result.array(), result.position())
    }

    private fun processAtom(type: String, atom: MP4Atom): MP4Atom {
        return when (type) {
            "moov" -> processMoov(atom)
            "moof" -> processMoof(atom)
            "sidx" -> processSidx(atom)
            "mdat" -> decryptMdat(atom)
            else -> atom
        }
    }

    private fun processMoov(moov: MP4Atom): MP4Atom {
        val parser = MP4Parser(moov.data)
        val newMoovData = ByteBuffer.allocate(moov.data.size)
        
        while (true) {
            val atom = parser.readAtom() ?: break
            if (atom.typeString == "trak") {
                newMoovData.put(processTrak(atom).pack())
            } else if (atom.typeString != "pssh") {
                 // Skip PSSH
                newMoovData.put(atom.pack())
            }
        }
        return MP4Atom("moov", Arrays.copyOf(newMoovData.array(), newMoovData.position()))
    }

    private fun processMoof(moof: MP4Atom): MP4Atom {
        val parser = MP4Parser(moof.data)
        val atoms = parser.listAtoms()
        val newMoofData = ByteBuffer.allocate(moof.data.size)

        // First pass: calculate total encryption overhead from all trafs
        // This is needed because all trun data_offsets are relative to the start of the moof box,
        // so if we remove boxes from any traf (or moof), ALL data_offsets must be reduced by the total bytes removed.
        encryptionOverhead = 0
        for (atom in atoms) {
            if (atom.typeString == "pssh") {
                encryptionOverhead += atom.size
            } else if (atom.typeString == "traf") {
                val trafParser = MP4Parser(atom.data)
                for (trafAtom in trafParser.listAtoms()) {
                    if (trafAtom.typeString == "senc" || trafAtom.typeString == "saiz" || trafAtom.typeString == "saio"
                        || trafAtom.typeString == "sbgp" || trafAtom.typeString == "sgpd") {
                        encryptionOverhead += trafAtom.size
                    }
                }
            }
        }

        // Second pass: process atoms
        for (atom in atoms) {
            if (atom.typeString == "traf") {
                newMoofData.put(processTraf(atom).pack())
            } else if (atom.typeString != "pssh") {
                newMoofData.put(atom.pack())
            }
        }
        return MP4Atom("moof", Arrays.copyOf(newMoofData.array(), newMoofData.position()))
    }

    private fun processTraf(traf: MP4Atom): MP4Atom {
        val parser = MP4Parser(traf.data)
        val newTrafData = ByteBuffer.allocate(traf.data.size)
        var tfhd: MP4Atom? = null
        var sampleCount = 0
        var sampleInfo = emptyList<CencSampleInfo>()
        
        val atoms = parser.listAtoms()

        // (Overhead is now calculated in processMoof for the entire moof box)

        for (atom in atoms) {
            when (atom.typeString) {
                "tfhd" -> {
                    tfhd = atom
                    newTrafData.put(atom.pack())
                }
                "trun" -> {
                    sampleCount = processTrun(atom)
                    newTrafData.put(modifyTrun(atom).pack())
                }
                "senc" -> {
                    sampleInfo = parseSenc(atom, sampleCount)
                }
                "saiz", "saio", "sbgp", "sgpd" -> {
                     // Skip - strip all encryption-related boxes so ExoPlayer
                     // doesn't expect senc data in decrypted segments
                }
                else -> {
                    newTrafData.put(atom.pack())
                }
            }
        }

        if (tfhd != null) {
            val tfhdBuffer = ByteBuffer.wrap(tfhd.data)
            tfhdBuffer.position(4) // Skip version/flags
            val trackId = tfhdBuffer.int
            currentKey = getKeyForTrack(trackId)
            currentSampleInfo = sampleInfo
        }

        return MP4Atom("traf", Arrays.copyOf(newTrafData.array(), newTrafData.position()))
    }

    private fun decryptMdat(mdat: MP4Atom): MP4Atom {
        if (currentKey == null || currentSampleInfo.isEmpty()) {
            return mdat
        }

        val decryptedSamples = ByteBuffer.allocate(mdat.data.size)
        val mdatData = ByteBuffer.wrap(mdat.data)
        
        for ((i, info) in currentSampleInfo.withIndex()) {
            if (!mdatData.hasRemaining()) break // Should not happen

            val sampleSize = if (i < trunSampleSizes.size) trunSampleSizes[i] else mdatData.remaining()
            if (sampleSize > mdatData.remaining()) break // Error case

            val sample = ByteArray(sampleSize)
            mdatData.get(sample)
            
            val decryptedSample = processSample(sample, info, currentKey!!)
            decryptedSamples.put(decryptedSample)
        }

        // Append any remaining data in mdat (could be padding or multi-track data not handled here)
        if (mdatData.hasRemaining()) {
            val remaining = ByteArray(mdatData.remaining())
            mdatData.get(remaining)
            decryptedSamples.put(remaining)
        }

        return MP4Atom("mdat", Arrays.copyOf(decryptedSamples.array(), decryptedSamples.position()))
    }

    private fun parseSenc(senc: MP4Atom, defaultSampleCount: Int): List<CencSampleInfo> {
        val data = ByteBuffer.wrap(senc.data)
        val versionFlags = data.int
        val version = versionFlags ushr 24
        val flags = versionFlags and 0xFFFFFF
        
        var sampleCount = defaultSampleCount
        sampleCount = data.int

        val sampleInfo = mutableListOf<CencSampleInfo>()
        for (i in 0 until sampleCount) {
             if (!data.hasRemaining()) break

             val iv = ByteArray(defaultIvSize)
             data.get(iv)

             val subSamples = mutableListOf<SubSample>()
             if ((flags and 0x000002) != 0 && data.remaining() >= 2) {
                 val subSampleCount = data.short.toInt() and 0xFFFF
                 for (j in 0 until subSampleCount) {
                     if (data.remaining() >= 6) {
                         val clearBytes = data.short.toInt() and 0xFFFF
                         val encryptedBytes = data.int
                         subSamples.add(SubSample(clearBytes, encryptedBytes))
                     }
                 }
             }
             sampleInfo.add(CencSampleInfo(true, iv, subSamples))
        }
        return sampleInfo
    }
    
    private fun getKeyForTrack(trackId: Int): ByteArray {
        if (keyMap.size == 1) return keyMap.values.first()
        // Assuming track IDs in map are 4-byte Big Endian
        val trackIdBytes = ByteBuffer.allocate(4).putInt(trackId).array()
        return keyMap.entries.find { Arrays.equals(it.key.array(), trackIdBytes) }?.value 
            ?: throw IllegalArgumentException("No key found for track ID $trackId")
    }

    private fun processSample(sample: ByteArray, info: CencSampleInfo, key: ByteArray): ByteArray {
        if (!info.isEncrypted) return sample

        // Pad IV to 16 bytes
        val iv = ByteArray(16)
        System.arraycopy(info.iv, 0, iv, 0, info.iv.size)
        
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        if (info.subSamples.isEmpty()) {
            return cipher.doFinal(sample)
        }

        val result = ByteBuffer.allocate(sample.size)
        var offset = 0
        for (sub in info.subSamples) {
            val clear = sub.clearBytes
            val encrypted = sub.encryptedBytes
            
            result.put(sample, offset, clear)
            offset += clear
            
            val encryptedData = Arrays.copyOfRange(sample, offset, offset + encrypted)
            result.put(cipher.update(encryptedData))
            offset += encrypted
        }
        
        if (offset < sample.size) {
             val remaining = Arrays.copyOfRange(sample, offset, sample.size)
             result.put(cipher.doFinal(remaining))
        } else {
             // Ensure any remaining bytes in cipher buffer are flushed
             val finalBytes = cipher.doFinal()
             if (finalBytes != null && finalBytes.isNotEmpty()) {
                 result.put(finalBytes)
             }
        }
        
        return result.array()
    }

    private fun processTrun(trun: MP4Atom): Int {
        val data = ByteBuffer.wrap(trun.data)
        val flags = data.int // version + flags
        val trunFlags = flags and 0xFFFFFF
        
        var offset = 8
        if ((trunFlags and 0x000001) != 0) offset += 4 // data-offset-present
        if ((trunFlags and 0x000004) != 0) offset += 4 // first-sample-flags-present

        val sampleCount = data.getInt(4)
        trunSampleSizes = IntArray(sampleCount)
        
        // Skip to sample loop
        // We need to calculate size of each row to find sample size offset
        var rowSize = 0
        if ((trunFlags and 0x000100) != 0) rowSize += 4 // duration
        val sizePresent = (trunFlags and 0x000200) != 0
        if (sizePresent) rowSize += 4 // size
        if ((trunFlags and 0x000400) != 0) rowSize += 4 // flags
        if ((trunFlags and 0x000800) != 0) rowSize += 4 // cto

        var currentPos = offset
        for (i in 0 until sampleCount) {
             if ((trunFlags and 0x000100) != 0) currentPos += 4
             if (sizePresent) {
                 trunSampleSizes[i] = data.getInt(currentPos)
                 currentPos += 4
             } else {
                 trunSampleSizes[i] = 0 // Or default size
             }
             if ((trunFlags and 0x000400) != 0) currentPos += 4
             if ((trunFlags and 0x000800) != 0) currentPos += 4
        }
        return sampleCount
    }
    
    private fun modifyTrun(trun: MP4Atom): MP4Atom {
        val data = ByteBuffer.wrap(trun.data.clone()) // clone to modify
        val flags = data.getInt(0)
        val trunFlags = flags and 0xFFFFFF
        
        if ((trunFlags and 0x000001) != 0) {
             val currentOffset = data.getInt(8)
             data.putInt(8, currentOffset - encryptionOverhead)
        }
        return MP4Atom("trun", data.array())
    }
    
    private fun processSidx(sidx: MP4Atom): MP4Atom {
         val data = ByteBuffer.wrap(sidx.data.clone())
         // referenced_size is at offset 32 (version 0? Python code assumes offset 32)
         // Python code: unpacking >I at 32. 
         // Assuming this matches Python logic logic exactly without deep format validation
         val currentSize = data.getInt(32)
         val referenceType = currentSize ushr 31
         val referencedSize = currentSize and 0x7FFFFFFF
         
         val newReferencedSize = referencedSize - encryptionOverhead
         val newSize = (referenceType shl 31) or (newReferencedSize and 0x7FFFFFFF)
         data.putInt(32, newSize)
         
         return MP4Atom("sidx", data.array())
    }

    private fun processTrak(trak: MP4Atom): MP4Atom {
        val parser = MP4Parser(trak.data)
        val newData = ByteBuffer.allocate(trak.data.size)
        
        while (true) {
            val atom = parser.readAtom() ?: break
            if (atom.typeString == "mdia") {
                newData.put(processMdia(atom).pack())
            } else {
                newData.put(atom.pack())
            }
        }
        return MP4Atom("trak", Arrays.copyOf(newData.array(), newData.position()))
    }
    
    private fun processMdia(mdia: MP4Atom): MP4Atom {
        val parser = MP4Parser(mdia.data)
        val newData = ByteBuffer.allocate(mdia.data.size)
        while(true){
             val atom = parser.readAtom() ?: break
             if (atom.typeString == "minf") {
                 newData.put(processMinf(atom).pack())
             } else {
                 newData.put(atom.pack())
             }
        }
        return MP4Atom("mdia", Arrays.copyOf(newData.array(), newData.position()))
    }
    
    private fun processMinf(minf: MP4Atom): MP4Atom {
        val parser = MP4Parser(minf.data)
        val newData = ByteBuffer.allocate(minf.data.size)
        while(true){
             val atom = parser.readAtom() ?: break
             if (atom.typeString == "stbl") {
                 newData.put(processStbl(atom).pack())
             } else {
                 newData.put(atom.pack())
             }
        }
        return MP4Atom("minf", Arrays.copyOf(newData.array(), newData.position()))
    }
    
    private fun processStbl(stbl: MP4Atom): MP4Atom {
        val parser = MP4Parser(stbl.data)
        val newData = ByteBuffer.allocate(stbl.data.size)
        while(true){
             val atom = parser.readAtom() ?: break
             if (atom.typeString == "stsd") {
                 newData.put(processStsd(atom).pack())
             } else {
                 newData.put(atom.pack())
             }
        }
        return MP4Atom("stbl", Arrays.copyOf(newData.array(), newData.position()))
    }
    
    private fun processStsd(stsd: MP4Atom): MP4Atom {
         val data = ByteBuffer.wrap(stsd.data)
         // version(1) + flags(3) + count(4)
         val entryCount = data.getInt(4)
         
         val newData = ByteBuffer.allocate(stsd.data.size)
         newData.put(stsd.data, 0, 8)
         
         val parser = MP4Parser(stsd.data)
         parser.skip(8)
         
         for (i in 0 until entryCount) {
             val entry = parser.readAtom() ?: break
             newData.put(processSampleEntry(entry).pack())
         }
         return MP4Atom("stsd", Arrays.copyOf(newData.array(), newData.position()))
    }
    
    private fun processSampleEntry(entry: MP4Atom): MP4Atom {
        val type = entry.typeString
        val fixedSize = when (type) {
            "mp4a", "enca" -> 28
            "mp4v", "encv", "avc1", "hev1", "hvc1" -> 78
            else -> 16
        }
        
        val newData = ByteBuffer.allocate(entry.data.size)
        if (entry.data.size < fixedSize) {
            return entry // Too short?
        }
        newData.put(entry.data, 0, fixedSize)
        
        val parser = MP4Parser(Arrays.copyOfRange(entry.data, fixedSize, entry.data.size))
        var codecFormat: String? = null
        
        while (true) {
            val atom = parser.readAtom() ?: break
            if (atom.typeString == "sinf" || atom.typeString == "schi" || atom.typeString == "tenc" || atom.typeString == "schm") {
                if (atom.typeString == "sinf") {
                    codecFormat = extractCodecFormat(atom)
                }
                // Skip
            } else {
                newData.put(atom.pack())
            }
        }
        
        val newType = codecFormat ?: type
        return MP4Atom(newType, Arrays.copyOf(newData.array(), newData.position()))
    }
    
    private fun extractCodecFormat(sinf: MP4Atom): String? {
        val parser = MP4Parser(sinf.data)
        var codecFormat: String? = null
        while (true) {
            val atom = parser.readAtom() ?: break
            if (atom.typeString == "frma") {
                codecFormat = String(atom.data, Charsets.UTF_8).trim() // Often returns 4 chars
            } else if (atom.typeString == "schi") {
                val schiParser = MP4Parser(atom.data)
                while (true) {
                    val schiAtom = schiParser.readAtom() ?: break
                    if (schiAtom.typeString == "tenc") {
                        if (schiAtom.data.size > 7) {
                            val ivSize = schiAtom.data[7].toInt() and 0xFF
                            if (ivSize == 0 || ivSize == 8 || ivSize == 16) {
                                defaultIvSize = if (ivSize > 0) ivSize else 16
                            }
                        }
                    }
                }
            }
        }
        return codecFormat
    }

}

private class MP4Parser(private val data: ByteArray) {
    var position = 0

    fun readAtom(): MP4Atom? {
        if (position + 8 > data.size) return null

        val buffer = ByteBuffer.wrap(data, position, 8)
        var size = buffer.int.toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        buffer.get(typeBytes)
        val type = String(typeBytes, Charsets.ISO_8859_1)

        var headerSize = 8
        if (size == 1L) {
             if (position + 16 > data.size) return null
             size = ByteBuffer.wrap(data, position + 8, 8).long
             headerSize = 16
        }

        if (size < headerSize || position + size > data.size) return null

        val atomData = Arrays.copyOfRange(data, position + headerSize, (position + size).toInt())
        position += size.toInt()
        
        return MP4Atom(type, atomData)
    }

    fun listAtoms(): List<MP4Atom> {
        val originalPos = position
        position = 0
        val list = mutableListOf<MP4Atom>()
        while (true) {
            val atom = readAtom() ?: break
            list.add(atom)
        }
        position = originalPos
        return list
    }
    
    fun skip(bytes: Int) {
        position += bytes
    }
}

private class MP4Atom(val typeString: String, val data: ByteArray) {
    val size: Int get() = data.size + 8

    fun pack(): ByteArray {
        val buffer = ByteBuffer.allocate(size)
        buffer.putInt(size)
        buffer.put(typeString.toByteArray(Charsets.ISO_8859_1))
        buffer.put(data)
        return buffer.array()
    }
}

private data class CencSampleInfo(
    val isEncrypted: Boolean,
    val iv: ByteArray,
    val subSamples: List<SubSample>
)

private data class SubSample(
    val clearBytes: Int,
    val encryptedBytes: Int
)
