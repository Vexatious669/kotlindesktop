package me.enivzoy.anilife.app.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceTest {

    private val directory = Files.createTempDirectory("anilife-instance").toFile()
    private val holder = CopyOnWriteArrayList<SingleInstance>()

    @AfterTest
    fun cleanUp() {
        holder.forEach { it.close() }
        directory.deleteRecursively()
    }

    @Test
    fun theSecondAcquireReturnsNullAndSignalsTheFirst() {
        var activations = 0
        val first = SingleInstance.acquire(directory) { activations++ }
        assertNotNull(first)
        holder += first

        val second = SingleInstance.acquire(directory) { activations++ }
        assertNull(second)

        eventually { activations == 1 }
        assertEquals(1, activations)
    }

    @Test
    fun closingTheHolderLetsANewInstanceTakeOver() {
        val first = SingleInstance.acquire(directory) {}
        assertNotNull(first)
        first.close()

        val second = SingleInstance.acquire(directory) {}
        assertNotNull(second)
        holder += second
    }

    @Test
    fun aStalePortFileDoesNotBreakAcquisition() {
        directory.mkdirs()
        File(directory, "instance.port").writeText("not-a-port")

        val instance = SingleInstance.acquire(directory) {}
        assertNotNull(instance)
        holder += instance
    }

    @Test
    fun thePortFileIsRemovedOnClose() {
        val instance = SingleInstance.acquire(directory) {}
        assertNotNull(instance)
        assertTrue(File(directory, "instance.port").isFile)

        instance.close()

        assertTrue(!File(directory, "instance.port").exists())
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
    }
}