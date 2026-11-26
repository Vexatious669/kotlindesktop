package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileResponseCacheTest {

    private val directory = Files.createTempDirectory("anilife-cache").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun roundTripsTheStoredPayload() {
        val cache = FileResponseCache(directory)
        val payload = buildJsonObject { put("index", 21) }

        cache.put(KEY, payload)
        val cached = cache.get(KEY)

        assertNotNull(cached)
        assertEquals(21, cached.data.getValue("index").jsonPrimitive.int)
        assertTrue(cached.savedAtMillis > 0)
    }

    @Test
    fun aMissingKeyReadsAsNull() {
        assertNull(FileResponseCache(directory).get(KEY))
    }

    @Test
    fun aCorruptFileReadsAsNull() {
        File(directory, "$KEY.json").writeText("not json")

        assertNull(FileResponseCache(directory).get(KEY))
    }

    @Test
    fun unsafeKeysAreIgnored() {
        val cache = FileResponseCache(directory)
        cache.put("../../escape", buildJsonObject { put("index", 1) })

        assertNull(cache.get("../../escape"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun writesAreMovedIntoPlaceWithoutLeavingTemporaryFiles() {
        val cache = FileResponseCache(directory)
        cache.put(KEY, buildJsonObject { put("index", 1) })

        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun theCacheNeverGrowsPastTheEntryLimit() {
        val cache = FileResponseCache(directory, maxEntries = 3)
        repeat(5) { index ->
            cache.put(index.toString(16).padStart(64, '0'), buildJsonObject { put("index", index) })
        }

        val files = directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        assertEquals(3, files.size)
    }

    @Test
    fun aStoredObjectSurvivesARestart() {
        FileResponseCache(directory).put(KEY, buildJsonObject { put("index", 7) })

        val reopened = FileResponseCache(directory).get(KEY)

        assertNotNull(reopened)
        assertEquals(7, reopened.data.getValue("index").jsonPrimitive.int)
        assertEquals(1, reopened.data.jsonObject.size)
    }

    private companion object {
        val KEY = "0".repeat(64)
    }
}