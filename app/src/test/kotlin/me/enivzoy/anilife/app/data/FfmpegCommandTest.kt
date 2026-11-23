package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FfmpegCommandTest {
    @Test
    fun streamArgumentsUseOptionalMapsAndExactProtocolWhitelist() {
        val arguments = DirectMediaDownloader.streamArguments(
            url = "https://example.com/master.m3u8",
            headers = emptyMap(),
            outputPath = "C:\\anime\\output.mkv.part",
            container = "matroska",
        )

        assertEquals("file,http,https,tcp,tls,crypto", valueAfter(arguments, "-protocol_whitelist"))
        assertEquals(listOf("0:v?", "0:a?"), valuesAfter(arguments, "-map"))
        assertEquals("matroska", valueAfter(arguments, "-f"))
        assertEquals("C:\\anime\\output.mkv.part", arguments.last())
        assertFalse(arguments.contains("0:v:0"))
        assertFalse(arguments.any { it.contains("data") })
    }

    @Test
    fun streamArgumentsForceMp4MuxerForMp4Output() {
        val arguments = DirectMediaDownloader.streamArguments(
            url = "https://example.com/stream.m3u8",
            headers = mapOf("User-Agent" to "AniLife"),
            outputPath = "C:\\anime\\output.mp4.part",
            container = "mp4",
        )

        assertEquals("mp4", valueAfter(arguments, "-f"))
        assertEquals("AniLife", valueAfter(arguments, "-user_agent"))
    }

    private fun valueAfter(arguments: List<String>, flag: String): String {
        val index = arguments.indexOf(flag)
        check(index >= 0 && index + 1 < arguments.size) { "Missing value after $flag" }
        return arguments[index + 1]
    }

    private fun valuesAfter(arguments: List<String>, flag: String): List<String> =
        arguments.withIndex().filter { (index, value) -> value == flag && index + 1 < arguments.size }
            .map { (index, _) -> arguments[index + 1] }
}
