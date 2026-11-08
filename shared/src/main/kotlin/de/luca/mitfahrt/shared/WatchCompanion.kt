package de.luca.mitfahrt.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

data class WatchEntry(val kind: String, val id: String, val title: String, val detail: String = "")
data class WatchDeparture(val id: String, val line: String, val destination: String,
    val at: Instant?, val live: Boolean, val cancelled: Boolean = false)
data class WatchTarget(val index: Int, val name: String)
data class WatchTile(val kind: String, val id: String, val title: String, val line: String,
    val destination: String, val at: Instant?, val live: Boolean, val fetchedAt: Instant)
data class WatchLibrary(val sentAt: Instant, val entries: List<WatchEntry> = emptyList(),
    val selected: String = "", val departures: List<WatchDeparture> = emptyList(),
    val targets: List<WatchTarget> = emptyList(), val settings: Map<String, String> = emptyMap(),
    val message: String = "", val tile: WatchTile? = null, val ride: WatchRide? = null)
data class WatchCommand(val action: String, val id: String = "", val journey: String = "", val contextId: String = "")

/** A separate optional companion channel keeps installed v1 ride clients compatible. */
object WatchCompanion {
    const val LIBRARY = "/mitfahrt/library/v1"
    const val COMMAND = "/mitfahrt/command/v1"
    const val RESULT = "/mitfahrt/command-result/v1"
    const val MAX_BYTES = 65_536
    private val actions = setOf("browse", "start", "board", "arrival", "stop", "target", "refresh")

    fun encode(value: WatchLibrary): ByteArray = write { out ->
        out.writeLong(value.sentAt.toEpochMilli())
        out.writeInt(value.entries.take(60).size)
        value.entries.take(60).forEach { out.text(it.kind); out.text(it.id); out.text(it.title); out.text(it.detail) }
        out.text(value.selected)
        out.writeInt(value.departures.take(12).size)
        value.departures.take(12).forEach {
            out.text(it.id); out.text(it.line); out.text(it.destination)
            out.writeBoolean(it.at != null); it.at?.let { at -> out.writeLong(at.toEpochMilli()) }
            out.writeBoolean(it.live); out.writeBoolean(it.cancelled)
        }
        out.writeInt(value.targets.take(150).size)
        value.targets.take(150).forEach { out.writeInt(it.index); out.text(it.name) }
        out.writeInt(value.settings.size.coerceAtMost(64))
        value.settings.entries.take(64).forEach { out.text(it.key); out.text(it.value) }
        out.text(value.message)
        out.writeBoolean(value.tile != null)
        value.tile?.let {
            out.text(it.kind); out.text(it.id); out.text(it.title); out.text(it.line); out.text(it.destination)
            out.writeBoolean(it.at != null); it.at?.let { at -> out.writeLong(at.toEpochMilli()) }
            out.writeBoolean(it.live); out.writeLong(it.fetchedAt.toEpochMilli())
        }
        out.writeBoolean(value.ride != null)
        value.ride?.let { ride ->
            val snapshot = WatchProtocol.encode(ride)
            out.writeInt(snapshot.size); out.write(snapshot)
        }
    }

    fun decode(bytes: ByteArray): WatchLibrary = read(bytes) { input ->
        val sent = Instant.ofEpochMilli(input.readLong())
        val entries = List(input.count(60)) { WatchEntry(input.text(), input.text(), input.text(), input.text()) }
        val selected = input.text()
        val departures = List(input.count(12)) { WatchDeparture(input.text(), input.text(), input.text(),
            if (input.readBoolean()) Instant.ofEpochMilli(input.readLong()) else null, input.readBoolean(), input.readBoolean()) }
        val targets = List(input.count(150)) { WatchTarget(input.readInt().also { require(it in 0..1000) }, input.text()) }
        val settings = List(input.count(64)) { input.text() to input.text() }.toMap()
        val message = input.text()
        val tile = if (input.available() > 0 && input.readBoolean()) WatchTile(input.text(), input.text(), input.text(),
            input.text(), input.text(), if (input.readBoolean()) Instant.ofEpochMilli(input.readLong()) else null,
            input.readBoolean(), Instant.ofEpochMilli(input.readLong())) else null
        val ride = if (input.available() > 0 && input.readBoolean()) {
            val size = input.count(WatchProtocol.MAX_BYTES)
            WatchProtocol.decode(ByteArray(size).also(input::readFully))
        } else null
        WatchLibrary(sent, entries, selected, departures, targets, settings, message, tile, ride)
    }

    fun encodeCommand(command: WatchCommand): ByteArray {
        require(command.action in actions)
        return write { it.text(command.action); it.text(command.id); it.text(command.journey); it.text(command.contextId) }
    }

    fun decodeCommand(bytes: ByteArray): WatchCommand = read(bytes) {
        val action = it.text(); val id = it.text(); val journey = it.text()
        WatchCommand(action, id, journey, if (it.available() > 0) it.text() else "").also { command -> require(command.action in actions) }
    }

    private fun write(body: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out -> out.writeInt(1); body(out) }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    private fun <T> read(bytes: ByteArray, body: (DataInputStream) -> T): T {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readInt() == 1)
            body(it).also { _ -> require(it.available() == 0) }
        }
    }
    private fun DataOutputStream.text(value: String) { writeUTF(value.take(768)) }
    private fun DataInputStream.text(): String = readUTF().also { require(it.length <= 768) }
    private fun DataInputStream.count(max: Int) = readInt().also { require(it in 0..max) }
}
