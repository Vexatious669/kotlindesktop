package me.enivzoy.anilife.app.data

import xyz.orbitary.perigee.ConnectionState
import xyz.orbitary.perigee.activity.Activity
import xyz.orbitary.perigee.json.Json
import xyz.orbitary.perigee.protocol.ActivityPayload
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal user record parsed from Discord's READY frame. */
data class IpcUser(
    val id: String?,
    val username: String?,
    val globalName: String?,
    val avatar: String?,
)

private const val OP_HANDSHAKE = 0
private const val OP_FRAME = 1
private const val OP_CLOSE = 2
private const val OP_PING = 3
private const val OP_PONG = 4
private const val MAX_FRAME = 1 shl 20
private const val REQUEST_TIMEOUT_SECONDS = 10L
private const val MIN_RECONNECT_MS = 2_000L
private const val MAX_RECONNECT_MS = 30_000L

private data class OutgoingFrame(val opcode: Int, val payload: String)

/**
 * A small Discord IPC client that mirrors the raw probe known to work on Windows.
 *
 * Perigee (the previous transport) connects and receives READY, but its SET_ACTIVITY requests hang.
 * The working probe uses a single pipe handle and performs reads and writes sequentially: handshake,
 * READY, SET_ACTIVITY, ACK. A synchronous Windows pipe handle serialises I/O, so writing from a
 * second thread while the reader is blocked in `readFully()` also hangs. This client therefore owns
 * the pipe exclusively on its connection thread; callers enqueue frames and wait on a future for
 * the nonce-matched reply. Perigee is still used only as a data/JSON library (Activity,
 * ActivityPayload, Json).
 */
class DiscordIpcClient(
    private val applicationId: String,
    private val onReady: (IpcUser) -> Unit,
    private val onDisconnected: (Throwable?) -> Unit,
    private val autoReconnect: Boolean = true,
) : AutoCloseable {

    @Volatile private var state: ConnectionState = ConnectionState.IDLE
    @Volatile private var pipe: RandomAccessFile? = null
    private val closed = AtomicBoolean(false)
    private val outgoing = LinkedBlockingQueue<OutgoingFrame>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<Map<String, Any?>>>()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "discord-ipc-timer").apply { isDaemon = true }
    }

    fun state(): ConnectionState = state
    fun isReady(): Boolean = state == ConnectionState.READY

    /** Starts the connection loop in the background. Repeated calls are ignored. */
    fun connect() {
        if (state != ConnectionState.IDLE) return
        state = ConnectionState.CONNECTING
        Thread({ connectionLoop() }, "discord-ipc-connection").apply { isDaemon = true }.start()
    }

    private fun connectionLoop() {
        var attempt = 0
        while (!closed.get()) {
            var active: RandomAccessFile? = null
            var wasReady = false
            try {
                active = openPipe()
                pipe = active
                // Only used for available(): it must not read from the same handle as the
                // connection thread's readFrame(), or writes can block behind that read.
                val availability: InputStream = FileInputStream(active.fd)
                DiscordPresenceLog.d("IPC pipe opened")

                val handshake = mapOf<String, Any>("v" to 1, "client_id" to applicationId)
                writeFrame(active, OP_HANDSHAKE, Json.write(handshake))
                DiscordPresenceLog.d("handshake sent")

                val (_, readyPayload) = readFrame(active)
                val user = parseReadyUser(readyPayload)
                state = ConnectionState.READY
                wasReady = true
                attempt = 0
                DiscordPresenceLog.d("IPC READY received (user=${user.username ?: "unknown"})")
                onReady(user)

                // All reads and writes happen on this thread. available() tells us when a reply
                // is waiting so we never block in readFrame() before queued writes are flushed.
                while (!closed.get()) {
                    flushOutgoing(active)
                    val readable = try {
                        availability.available() > 0
                    } catch (failure: IOException) {
                        throw failure
                    }
                    if (!readable) {
                        try {
                            Thread.sleep(10)
                        } catch (interrupt: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        continue
                    }
                    val (opcode, payload) = readFrame(active)
                    DiscordPresenceLog.d(
                        "IPC frame received opcode=$opcode len=${payload.length} head=${payload.take(80)}",
                    )
                    when (opcode) {
                        OP_FRAME -> dispatchResponse(payload)
                        OP_PING -> writeFrame(active, OP_PONG, payload)
                        OP_CLOSE -> throw IOException("Discord closed the IPC connection")
                        else -> DiscordPresenceLog.w("Ignoring unknown IPC opcode $opcode")
                    }
                }
            } catch (failure: Throwable) {
                if (!closed.get()) {
                    DiscordPresenceLog.w("IPC connection failed: ${failure.message}", failure)
                }
            } finally {
                pipe = null
                outgoing.clear()
                active?.let { runCatching { it.close() } }
                pending.values.forEach { it.completeExceptionally(IOException("IPC connection lost")) }
                pending.clear()
                if (!closed.get()) {
                    state = ConnectionState.BACKOFF
                    if (wasReady) onDisconnected(null)
                }
            }

            if (!autoReconnect || closed.get()) break
            val delay = minOf(MIN_RECONNECT_MS shl minOf(attempt, 4), MAX_RECONNECT_MS)
            attempt++
            DiscordPresenceLog.d("IPC reconnecting in ${delay}ms (attempt $attempt)")
            state = ConnectionState.CONNECTING
            try {
                Thread.sleep(delay)
            } catch (interrupt: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!closed.get()) state = ConnectionState.CLOSED
    }

    /** Send SET_ACTIVITY. Discord usually replies in a few milliseconds. */
    fun setActivity(activity: Activity): CompletableFuture<Void> {
        val args = mapOf<String, Any>(
            "pid" to ProcessHandle.current().pid(),
            "activity" to ActivityPayload.toMap(activity),
        )
        return enqueueRequest("SET_ACTIVITY", args)
    }

    /** Clear the current activity by sending SET_ACTIVITY without an `activity` field. */
    fun clearActivity(): CompletableFuture<Void> =
        enqueueRequest("CLEAR_ACTIVITY", mapOf<String, Any>("pid" to ProcessHandle.current().pid()))

    private fun enqueueRequest(label: String, args: Map<String, Any>): CompletableFuture<Void> {
        if (closed.get() || pipe == null || state != ConnectionState.READY) {
            return CompletableFuture.failedFuture(IOException("Not connected to Discord"))
        }
        val nonce = UUID.randomUUID().toString()
        val response = CompletableFuture<Map<String, Any?>>()
        pending[nonce] = response
        timer.schedule({
            if (pending.remove(nonce, response)) {
                response.completeExceptionally(TimeoutException("Discord did not reply to $label"))
            }
        }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val payload = Json.write(
            mapOf<String, Any>(
                "cmd" to "SET_ACTIVITY",
                "args" to args,
                "nonce" to nonce,
            ),
        )
        outgoing.add(OutgoingFrame(OP_FRAME, payload))
        DiscordPresenceLog.d("$label queued nonce=$nonce len=${payload.length}")
        return response.thenApply { null }
    }

    /** Called only from the connection thread, between reads. */
    @Throws(IOException::class)
    private fun flushOutgoing(active: RandomAccessFile) {
        while (true) {
            val frame = outgoing.poll() ?: break
            DiscordPresenceLog.d("IPC writing frame opcode=${frame.opcode} len=${frame.payload.length}")
            writeFrame(active, frame.opcode, frame.payload)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatchResponse(payload: String) {
        try {
            val message = Json.parse(payload).orElse(null) as? Map<String, Any?> ?: return
            val nonce = message["nonce"] as? String ?: return
            val response = pending.remove(nonce)
            if (response == null) {
                DiscordPresenceLog.w("IPC response for unknown nonce $nonce (pending=${pending.keys})")
                return
            }
            if ("ERROR".equals(message["evt"] as? String, ignoreCase = false)) {
                val data = message["data"] as? Map<String, Any?>
                val detail = data?.get("message") ?: data?.get("code") ?: "unknown error"
                response.completeExceptionally(IOException("Discord rejected IPC request: $detail"))
            } else {
                response.complete(message)
                DiscordPresenceLog.d("IPC ACK received nonce=$nonce")
            }
        } catch (failure: Throwable) {
            DiscordPresenceLog.w("could not parse IPC response", failure)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseReadyUser(payload: String): IpcUser {
        val message = Json.parse(payload).orElse(null) as? Map<String, Any?> ?: return IpcUser(null, null, null, null)
        val data = message["data"] as? Map<String, Any?> ?: return IpcUser(null, null, null, null)
        val user = data["user"] as? Map<String, Any?> ?: return IpcUser(null, null, null, null)
        return IpcUser(
            id = user["id"] as? String,
            username = user["username"] as? String,
            globalName = user["global_name"] as? String,
            avatar = user["avatar"] as? String,
        )
    }

    @Throws(IOException::class)
    private fun openPipe(): RandomAccessFile {
        for (index in 0..9) {
            val path = "\\\\?\\pipe\\discord-ipc-$index"
            try {
                return RandomAccessFile(path, "rw")
            } catch (ignored: IOException) {
                // Try the next pipe index.
            }
        }
        throw IOException("No Discord IPC pipe found")
    }

    @Throws(IOException::class)
    private fun writeFrame(target: RandomAccessFile, opcode: Int, payload: String) {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        // Write the entire frame in one call. Discord's Windows named-pipe reader treats each
        // write as a message boundary; splitting the header and body made it ignore the frame.
        val frame = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(opcode).putInt(body.size).put(body).array()
        target.write(frame)
    }

    @Throws(IOException::class)
    private fun readFrame(target: RandomAccessFile): Pair<Int, String> {
        val header = ByteArray(8)
        target.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val opcode = buffer.int
        val length = buffer.int
        if (length < 0 || length > MAX_FRAME) throw IOException("Invalid IPC frame length $length")
        val body = ByteArray(length)
        target.readFully(body)
        return opcode to String(body, StandardCharsets.UTF_8)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        state = ConnectionState.CLOSED
        timer.shutdownNow()
        outgoing.clear()
        pending.values.forEach { it.completeExceptionally(IOException("IPC client closed")) }
        pending.clear()
        // Never write from here: while the connection thread is blocked on a synchronous pipe read,
        // a write from another thread can hang forever. The daemon read exits when Discord replies
        // or the pipe closes; the OS releases the handle when the process exits.
        DiscordPresenceLog.d("IPC client closed")
    }
}
