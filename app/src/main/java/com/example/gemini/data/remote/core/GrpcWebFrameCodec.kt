package com.example.gemini.data.remote.core

import android.util.Log
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Low-level binary codec for gRPC-Web 5-byte length-prefixed framing.
 *
 * Frame Format:
 * [1 byte: Flag] [4 bytes: Big-Endian Length] [N bytes: Payload]
 * - Flag 0x00: Data Frame (JSON payload)
 * - Flag 0x80: Trailers Frame (text metadata containing grpc-status, grpc-message)
 */
object GrpcWebFrameCodec {
    private const val TAG = "GrpcWebFrameCodec"

    const val FLAG_DATA = 0x00
    const val FLAG_TRAILER = 0x80
    const val HEADER_SIZE = 5

    data class GrpcResult(val frames: List<String>, val status: Int, val message: String?)

    /**
     * Encodes a payload into a 5-byte length-prefixed gRPC-Web data frame (flag 0x00).
     */
    fun encodeDataFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val frame = ByteArray(HEADER_SIZE + len)
        frame[0] = FLAG_DATA.toByte()
        frame[1] = ((len ushr 24) and 0xFF).toByte()
        frame[2] = ((len ushr 16) and 0xFF).toByte()
        frame[3] = ((len ushr 8) and 0xFF).toByte()
        frame[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, len)
        return frame
    }

    /**
     * Encodes a UTF-8 JSON string into a gRPC-Web data frame.
     */
    fun encodeDataFrame(jsonStr: String): ByteArray =
        encodeDataFrame(jsonStr.toByteArray(StandardCharsets.UTF_8))

    /**
     * Parses complete response byte array into a GrpcResult with parsed JSON data frames and status.
     */
    fun parseGrpcWebBody(bodyBytes: ByteArray): GrpcResult {
        val jsonFrames = mutableListOf<String>()
        var status = 0
        var message: String? = null
        var offset = 0

        while (offset + HEADER_SIZE <= bodyBytes.size) {
            val flag = bodyBytes[offset].toInt() and 0xFF
            val len = ((bodyBytes[offset + 1].toInt() and 0xFF) shl 24) or
                    ((bodyBytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bodyBytes[offset + 3].toInt() and 0xFF) shl 8) or
                    (bodyBytes[offset + 4].toInt() and 0xFF)
            offset += HEADER_SIZE

            if (len <= 0) continue
            if (offset + len > bodyBytes.size) break

            val payload = bodyBytes.copyOfRange(offset, offset + len)
            offset += len

            if (flag == FLAG_DATA) {
                jsonFrames.add(String(payload, StandardCharsets.UTF_8))
            } else if (flag == FLAG_TRAILER) {
                val trailerText = String(payload, StandardCharsets.UTF_8)
                val statusMatch = Regex("grpc-status:\\s*(\\d+)").find(trailerText)
                val messageMatch = Regex("grpc-message:\\s*([^\r\n]+)").find(trailerText)
                status = statusMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                message = messageMatch?.groupValues?.getOrNull(1)
            }
        }
        return GrpcResult(jsonFrames, status, message)
    }

    /**
     * Reads 5-byte header and parses frames continuously from an InputStream.
     */
    suspend fun readStreamFrames(
        stream: InputStream,
        onFrame: suspend (String) -> Unit
    ) {
        val header = ByteArray(HEADER_SIZE)
        while (true) {
            var read = 0
            while (read < HEADER_SIZE) {
                val r = stream.read(header, read, HEADER_SIZE - read)
                if (r == -1) return
                read += r
            }

            val flag = header[0].toInt() and 0xFF
            val len = ((header[1].toInt() and 0xFF) shl 24) or
                    ((header[2].toInt() and 0xFF) shl 16) or
                    ((header[3].toInt() and 0xFF) shl 8) or
                    (header[4].toInt() and 0xFF)

            if (len <= 0) continue

            val payload = ByteArray(len)
            var pRead = 0
            while (pRead < len) {
                val r = stream.read(payload, pRead, len - pRead)
                if (r == -1) return
                pRead += r
            }

            if (flag == FLAG_DATA) {
                val jsonStr = String(payload, StandardCharsets.UTF_8)
                onFrame(jsonStr)
            } else if (flag == FLAG_TRAILER) {
                val trailerText = String(payload, StandardCharsets.UTF_8)
                val statusMatch = Regex("grpc-status:\\s*(\\d+)").find(trailerText)
                val statusCode = statusMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                if (statusCode != 0) {
                    val messageMatch = Regex("grpc-message:\\s*([^\r\n]+)").find(trailerText)
                    val msg = messageMatch?.groupValues?.getOrNull(1) ?: "gRPC status $statusCode"
                    Log.w(TAG, "gRPC stream finished with trailer error ($statusCode): $msg")
                }
                break
            }
        }
    }
}

