package me.enivzoy.anilife.app.data

import java.awt.EventQueue
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.concurrent.thread

class SingleInstance private constructor(
    private val directory: File?,
    private val channel: FileChannel?,
    private val lock: FileLock?,
    private val server: ServerSocket?,
    private val onActivate: () -> Unit,
) : AutoCloseable {

    private val listener: Thread? = server?.let {
        thread(name = "anilife-single-instance", isDaemon = true) {
            while (!it.isClosed) {
                val socket = runCatching { it.accept() }.getOrNull() ?: break
                socket.use { client ->
                    val request = runCatching { client.getInputStream().bufferedReader().readLine() }.getOrNull()
                    if (request == FOCUS_COMMAND) EventQueue.invokeLater(onActivate)
                }
            }
        }
    }

    override fun close() {
        runCatching { server?.close() }
        directory?.let { runCatching { File(it, PORT_FILE).delete() } }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        listener?.let { runCatching { it.join(500) } }
    }

    companion object {
        private const val LOCK_FILE = "instance.lock"
        private const val PORT_FILE = "instance.port"
        private const val FOCUS_COMMAND = "focus"

        fun acquire(directory: File, onActivate: () -> Unit): SingleInstance? {
            runCatching { directory.mkdirs() }
            val lockFile = File(directory, LOCK_FILE)
            val channel = runCatching { RandomAccessFile(lockFile, "rw").channel }.getOrNull()
                ?: return SingleInstance(null, null, null, null, onActivate)

            val lock = try {
                channel.tryLock()
            } catch (blocked: OverlappingFileLockException) {
                null
            } catch (failure: Exception) {
                null
            }

            if (lock == null) {
                runCatching { channel.close() }
                forward(directory)
                return null
            }

            return runCatching {
                val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                File(directory, PORT_FILE).writeText(server.localPort.toString())
                SingleInstance(directory, channel, lock, server, onActivate)
            }.getOrElse {
                runCatching { lock.release() }
                runCatching { channel.close() }
                SingleInstance(null, null, null, null, onActivate)
            }
        }

        private fun forward(directory: File) {
            val port = runCatching {
                File(directory, PORT_FILE).takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull()
            }.getOrNull() ?: return
            repeat(2) { attempt ->
                val delivered = runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                        socket.getOutputStream().bufferedWriter().apply {
                            write(FOCUS_COMMAND)
                            newLine()
                            flush()
                        }
                    }
                }.isSuccess
                if (delivered) return
                if (attempt == 0) Thread.sleep(250)
            }
        }
    }
}