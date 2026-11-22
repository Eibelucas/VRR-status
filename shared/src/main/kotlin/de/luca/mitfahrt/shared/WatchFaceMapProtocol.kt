package de.luca.mitfahrt.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/**
 * Compact live map and next-stop snapshot pushed from the phone to Wear OS for the
 * Mitfahrt Live Watch Face (PHOTO_IMAGE map complication + LONG_TEXT next-stop/ETA complication).
 */
data class WatchFaceMapSnapshot(
    val sentAt: Instant,
    val active: Boolean = false,
    val running: Boolean = false,
    val line: String = "",
    val destination: String = "",
    val nextStop: String = "",
    val nextStopAt: Instant? = null,
    val arrivalAt: Instant? = null,
    val boarded: Boolean = false,
    val remainingStops: Int = 0,
    val totalStops: Int = 0,
    val progressPercent: Int = 0,
    val mapPng: ByteArray = ByteArray(0)
) {
    fun stale(now: Instant): Boolean =
        !running || now.epochSecond - sentAt.epochSecond !in -60..180

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatchFaceMapSnapshot) return false
        return sentAt == other.sentAt &&
            active == other.active &&
            running == other.running &&
            line == other.line &&
            destination == other.destination &&
            nextStop == other.nextStop &&
            nextStopAt == other.nextStopAt &&
            arrivalAt == other.arrivalAt &&
            boarded == other.boarded &&
            remainingStops == other.remainingStops &&
            totalStops == other.totalStops &&
            progressPercent == other.progressPercent &&
            mapPng.contentEquals(other.mapPng)
    }

    override fun hashCode(): Int {
        var result = sentAt.hashCode()
        result = 31 * result + active.hashCode()
        result = 31 * result + running.hashCode()
        result = 31 * result + line.hashCode()
        result = 31 * result + destination.hashCode()
        result = 31 * result + nextStop.hashCode()
        result = 31 * result + (nextStopAt?.hashCode() ?: 0)
        result = 31 * result + (arrivalAt?.hashCode() ?: 0)
        result = 31 * result + boarded.hashCode()
        result = 31 * result + remainingStops
        result = 31 * result + totalStops
        result = 31 * result + progressPercent
        result = 31 * result + mapPng.contentHashCode()
        return result
    }
}

object WatchFaceMapProtocol {
    const val PATH = "/mitfahrt/watchface-map/v1"
    const val MAX_PNG_BYTES = 56_000
    const val MAX_BYTES = 64_000
    private const val VERSION = 1

    fun encode(snapshot: WatchFaceMapSnapshot): ByteArray {
        val png = if (snapshot.mapPng.size <= MAX_PNG_BYTES) snapshot.mapPng else ByteArray(0)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(VERSION)
                out.writeLong(snapshot.sentAt.toEpochMilli())
                out.writeBoolean(snapshot.active)
                out.writeBoolean(snapshot.running)
                out.writeUTF(snapshot.line.take(64))
                out.writeUTF(snapshot.destination.take(128))
                out.writeUTF(snapshot.nextStop.take(128))
                out.instant(snapshot.nextStopAt)
                out.instant(snapshot.arrivalAt)
                out.writeBoolean(snapshot.boarded)
                out.writeInt(snapshot.remainingStops.coerceAtLeast(0))
                out.writeInt(snapshot.totalStops.coerceAtLeast(0))
                out.writeInt(snapshot.progressPercent.coerceIn(0, 100))
                out.writeInt(png.size)
                if (png.isNotEmpty()) out.write(png)
            }
        }.toByteArray().also { require(it.size <= MAX_BYTES) { "Watch face payload too large" } }
    }

    fun decode(bytes: ByteArray): WatchFaceMapSnapshot {
        require(bytes.size <= MAX_BYTES) { "Watch face payload exceeds max size" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported watch face map protocol version" }
            val sentAt = Instant.ofEpochMilli(input.readLong())
            val active = input.readBoolean()
            val running = input.readBoolean()
            val line = input.readUTF()
            val destination = input.readUTF()
            val nextStop = input.readUTF()
            val nextStopAt = input.instant()
            val arrivalAt = input.instant()
            val boarded = input.readBoolean()
            val remainingStops = input.readInt().coerceAtLeast(0)
            val totalStops = input.readInt().coerceAtLeast(0)
            val progressPercent = input.readInt().coerceIn(0, 100)
            val pngSize = input.readInt()
            require(pngSize in 0..MAX_PNG_BYTES) { "Invalid watch face map PNG length" }
            val png = ByteArray(pngSize)
            if (pngSize > 0) input.readFully(png)
            require(input.available() == 0) { "Trailing bytes in watch face map payload" }
            WatchFaceMapSnapshot(
                sentAt = sentAt,
                active = active,
                running = running,
                line = line,
                destination = destination,
                nextStop = nextStop,
                nextStopAt = nextStopAt,
                arrivalAt = arrivalAt,
                boarded = boarded,
                remainingStops = remainingStops,
                totalStops = totalStops,
                progressPercent = progressPercent,
                mapPng = png
            )
        }
    }

    private fun DataOutputStream.instant(value: Instant?) {
        writeBoolean(value != null)
        value?.let { writeLong(it.toEpochMilli()) }
    }

    private fun DataInputStream.instant(): Instant? =
        if (readBoolean()) Instant.ofEpochMilli(readLong()) else null
}
