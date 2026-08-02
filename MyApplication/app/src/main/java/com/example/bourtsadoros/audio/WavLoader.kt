package com.example.bourtsadoros.audio

import android.content.res.Resources
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavData(
    val sampleRate: Int,
    val numChannels: Int,
    val bitsPerSample: Int,
    val data: FloatArray
)

object WavLoader {
    fun load(res: Resources, rawResId: Int): WavData {
        val inputStream = res.openRawResource(rawResId)
        val bytes = inputStream.readBytes()
        val dis = DataInputStream(ByteArrayInputStream(bytes))

        dis.skip(12)
        while (true) {
            val chunkId = ByteArray(4)
            dis.readFully(chunkId)
            val chunkSize = Integer.reverseBytes(dis.readInt())

            if (String(chunkId) == "fmt ") {
                val audioFormat = java.lang.Short.reverseBytes(dis.readShort()).toShort()
                require(audioFormat == 1.toShort()) { "Only PCM format supported" }
                val channels = java.lang.Short.reverseBytes(dis.readShort()).toInt()
                val sampleRate = Integer.reverseBytes(dis.readInt())
                dis.skip(6) // byte rate, block align
                val bitsPerSample = java.lang.Short.reverseBytes(dis.readShort()).toInt()
                dis.skip((chunkSize - 16).toLong()) // safely skip remaining fmt bytes
                while (true) {
                    val dataChunkId = ByteArray(4)
                    dis.readFully(dataChunkId)
                    val dataSize = Integer.reverseBytes(dis.readInt())
                    if (String(dataChunkId) == "data") {
                        val pcmBytes = ByteArray(dataSize)
                        dis.readFully(pcmBytes)
                        val samples = pcmBytesToFloat(pcmBytes, bitsPerSample)
                        return WavData(sampleRate, channels, bitsPerSample, samples)
                    } else {
                        dis.skip(dataSize.toLong())
                    }
                }
            } else {
                dis.skip(chunkSize.toLong())
            }
        }
    }

    private fun pcmBytesToFloat(bytes: ByteArray, bitsPerSample: Int): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numFrames = bytes.size / (bitsPerSample / 8)
        val samples = FloatArray(numFrames)
        val maxValue = (1 shl (bitsPerSample - 1)).toFloat()
        for (i in samples.indices) {
            val sample = when (bitsPerSample) {
                16 -> buffer.short.toFloat()
                else -> throw IllegalArgumentException("Bits per sample $bitsPerSample not supported")
            }
            samples[i] = sample / maxValue
        }
        return samples
    }
}