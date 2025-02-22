package com.viaversion.aas.protocol.id47toid5.chunks

import com.viaversion.aas.readByteArray
import com.viaversion.aas.readRemainingBytes
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled

class Chunk1_8to1_7_6_10(
    data: ByteArray,
    private val primaryBitMask: Int,
    additionalBitMask: Int,
    private val skyLight: Boolean,
    private val groundUp: Boolean
) {
    var storageSections = arrayOfNulls<ExtendedBlockStorage>(16)
    var blockBiomeArray = ByteArray(256)

    fun filterChunk(storageArray: ExtendedBlockStorage?, i: Int) =
        storageArray != null && (primaryBitMask.and(1 shl i) != 0)
                && (!groundUp || storageArray.isEmpty)

    fun get1_8Data(): ByteArray {
        val buf = ByteBufAllocator.DEFAULT.buffer()
        try {
            val filteredChunks = storageSections.filterIndexed { i, value -> filterChunk(value, i) }.filterNotNull()
            filteredChunks.forEach {
                val blockIds = it.blockLSBArray
                val nibblearray = it.metadataArray
                for (iBlock in blockIds.indices) {
                    val id = blockIds[iBlock].toInt() and 0xFF
                    val x = iBlock and 0xF
                    val y = iBlock.shr(8).and(0xF)
                    val z = iBlock.shr(4).and(0xF)
                    val data = nibblearray[x, y, z].toInt()

                    //data = SpigotDebreakifier.getCorrectedData(id, data);
                    buf.writeShortLE(id.shl(4).or(data))
                }
            }
            filteredChunks.forEach {
                buf.writeBytes(it.blocklightArray.handle)
            }
            if (skyLight) {
                filteredChunks.forEach {
                    buf.writeBytes(it.skylightArray!!.handle)
                }
            }
            if (groundUp) {
                buf.writeBytes(blockBiomeArray)
            }
            return readRemainingBytes(buf)
        } finally {
            buf.release()
        }
    }

    fun filterBitmask(bitmask: Int, i: Int) = (bitmask and (1 shl i)) != 0

    init {
        val input = Unpooled.wrappedBuffer(data)
        for (i in storageSections.indices) {
            if (filterBitmask(primaryBitMask, i)) {
                var storageSection = storageSections[i]
                if (storageSection == null) {
                    storageSection = ExtendedBlockStorage(i shl 4, skyLight)
                    storageSections[i] = storageSection
                }
                storageSection.blockLSBArray = input.readByteArray(4096)
            } else if (storageSections[i] != null && groundUp) {
                storageSections[i] = null
            }
        }
        for (i in storageSections.indices) {
            if (filterBitmask(primaryBitMask, i) && storageSections[i] != null) {
                storageSections[i]!!.metadataArray.handle = input.readByteArray(4096 / 2)
            }
        }
        for (i in storageSections.indices) {
            if (filterBitmask(primaryBitMask, i) && storageSections[i] != null) {
                storageSections[i]!!.blocklightArray.handle = input.readByteArray(4096 / 2)
            }
        }
        if (skyLight) {
            for (i in storageSections.indices) {
                if (filterBitmask(primaryBitMask, i) && storageSections[i] != null) {
                    storageSections[i]!!.skylightArray!!.handle = input.readByteArray(4096 / 2)
                }
            }
        }
        for (i in storageSections.indices) {
            if (filterBitmask(additionalBitMask, i)) {
                if (storageSections[i] == null) {
                    input.skipBytes(2048)
                } else {
                    val msbArray = storageSections[i]!!.blockMSBArray ?: storageSections[i]!!.createBlockMSBArray()
                    msbArray.handle = input.readByteArray(4096 / 2)
                }
            } else if (groundUp && storageSections[i] != null && storageSections[i]!!.blockMSBArray != null) {
                storageSections[i]!!.clearMSBArray()
            }
        }
        if (groundUp) {
            blockBiomeArray = input.readByteArray(256)
        }
    }
}
