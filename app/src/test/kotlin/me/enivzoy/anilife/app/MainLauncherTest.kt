package me.enivzoy.anilife.app

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MainLauncherTest {
	@Test
	fun forwardsSkikoNativeLibraryPathToSetupJre() {
		val properties = Properties().apply {
			setProperty("skiko.library.path", "C:\\AniLife\\app")
			setProperty("compose.application.resources.dir", "C:\\AniLife\\app\\resources")
			setProperty("jpackage.app-version", "1.0.15")
			setProperty("unrelated.property", "ignored")
		}

		val forwarded = forwardedJvmProperties(properties)

		assertContains(forwarded, "-Dskiko.library.path=C:\\AniLife\\app")
		assertContains(forwarded, "-Dcompose.application.resources.dir=C:\\AniLife\\app\\resources")
		assertContains(forwarded, "-Djpackage.app-version=1.0.15")
		assertFalse(forwarded.any { it.startsWith("-Dunrelated.property=") })
	}
}
