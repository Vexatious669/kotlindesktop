package me.enivzoy.anilife.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.oshai.kotlinlogging.KotlinLogging
import me.enivzoy.anilife.core.diagnostics.ActionBreadcrumbs
import me.enivzoy.anilife.core.diagnostics.DiagnosticsPaths
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.AniLifeIcon
import me.enivzoy.anilife.app.ui.AniLifeTheme
import me.enivzoy.anilife.app.ui.GlassHost
import me.enivzoy.anilife.app.ui.ProvideHighRefresh
import me.enivzoy.anilife.app.data.AppSettingsStore
import me.enivzoy.anilife.app.data.DependencyManager
import me.enivzoy.anilife.app.data.dependencyDirectory
import me.enivzoy.anilife.app.screens.SetupScreen
import java.awt.Color
import java.io.File
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.geom.AffineTransform

private val logger = KotlinLogging.logger {}

fun main() {
    runCatching { DiagnosticsPaths.ensure() }
    installFatalExceptionHandler()
    if (relaunchWithSetupJre()) return
    application {
    val setupScope = rememberCoroutineScope()
    val dependencyManager = remember { DependencyManager(setupScope, dependencyDirectory()) }
    val settingsStore = remember { AppSettingsStore() }
    val appSettings by settingsStore.settings.collectAsState()
     
     
    val state = rememberWindowState(
        size = initialWindowSize(),
        position = WindowPosition(Alignment.Center),
    )
    val previousPlacement = remember { androidx.compose.runtime.mutableStateOf(state.placement) }
    val windowedBounds = remember { androidx.compose.runtime.mutableStateOf<Rectangle?>(null) }
    val windowedWorkArea = remember { androidx.compose.runtime.mutableStateOf<Rectangle?>(null) }
    val windowedStyle = remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val windowedScale = remember { androidx.compose.runtime.mutableStateOf<AffineTransform?>(null) }
    val icon = remember { loadWindowIcon() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "AniLife",
        state = state,
        icon = icon,
    ) {
        LaunchedEffect(appSettings) {
            window.minimumSize = Dimension(800, 480)
            AniLifeColors.update(appSettings)
            val background = awtColor(AniLifeColors.Background)
            window.background = background
            window.contentPane.background = background
            window.rootPane.background = background
            applyDarkTitleBar(window)
        }
        AniLifeTheme(appSettings) {
            GlassHost {
                ProvideHighRefresh(
                    enabled = appSettings.highRefreshRateUi,
                    reducedMotion = appSettings.reducedMotion,
                ) {
                    LaunchedEffect(dependencyManager) { dependencyManager.configurePaths() }
                    var setupComplete by remember { mutableStateOf(dependencyManager.isSetupComplete()) }
                    if (!setupComplete) {
                        SetupScreen(
                            manager = dependencyManager,
                            onContinue = {
                                dependencyManager.configurePaths()
                                setupComplete = true
                            },
                        )
                    } else AniLifeApp(settingsStore = settingsStore, onPlayerFullscreen = { fullscreen ->
                    val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")
                    if (isWindows) {
                        if (fullscreen && windowedStyle.value == null) {
                            if (state.placement != WindowPlacement.Fullscreen) previousPlacement.value = state.placement
                            val configuration = window.graphicsConfiguration
                            windowedBounds.value = Rectangle(window.bounds)
                            windowedWorkArea.value = usableScreenBounds(configuration)
                            windowedScale.value = configuration.defaultTransform
                            val hwnd = Native.getWindowPointer(window)
                            val style = WindowsWindowApi.INSTANCE.GetWindowLongPtrW(hwnd, GWL_STYLE)
                            check(style != 0L) { "Couldn't read the window style for fullscreen mode." }
                            windowedStyle.value = style
                            val borderlessStyle = (style and WS_OVERLAPPEDWINDOW.inv()) or WS_POPUP
                            check(WindowsWindowApi.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, borderlessStyle) != 0L) {
                                "Couldn't remove the window frame for fullscreen mode."
                            }
                            val bounds = window.graphicsConfiguration.bounds
                            val scale = window.graphicsConfiguration.defaultTransform
                            check(
                                WindowsWindowApi.INSTANCE.SetWindowPos(
                                    hwnd,
                                    null,
                                    (bounds.x * scale.scaleX).toInt(),
                                    (bounds.y * scale.scaleY).toInt(),
                                    (bounds.width * scale.scaleX).toInt(),
                                    (bounds.height * scale.scaleY).toInt(),
                                    SWP_FRAMECHANGED or SWP_NOOWNERZORDER or SWP_NOZORDER,
                                ) != 0,
                            ) { "Couldn't resize the window to the display bounds." }
                        } else if (!fullscreen && windowedStyle.value != null) {
                            val hwnd = Native.getWindowPointer(window)
                            val style = windowedStyle.value!!
                            check(WindowsWindowApi.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, style) != 0L) {
                                "Couldn't restore the window frame."
                            }
                            if (previousPlacement.value == WindowPlacement.Maximized) {
                                check(
                                    WindowsWindowApi.INSTANCE.SetWindowPos(
                                        hwnd,
                                        null,
                                        0,
                                        0,
                                        0,
                                        0,
                                        SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOOWNERZORDER or SWP_NOZORDER,
                                    ) != 0,
                                ) { "Couldn't restore the maximized window frame." }
                                WindowsWindowApi.INSTANCE.ShowWindow(hwnd, SW_RESTORE)
                                WindowsWindowApi.INSTANCE.ShowWindow(hwnd, SW_MAXIMIZE)
                            } else {
                                val bounds = fitWindowBoundsToWorkArea(
                                    requireNotNull(windowedBounds.value),
                                    requireNotNull(windowedWorkArea.value),
                                )
                                val scale = requireNotNull(windowedScale.value)
                                check(
                                    WindowsWindowApi.INSTANCE.SetWindowPos(
                                        hwnd,
                                        null,
                                        (bounds.x * scale.scaleX).toInt(),
                                        (bounds.y * scale.scaleY).toInt(),
                                        (bounds.width * scale.scaleX).toInt(),
                                        (bounds.height * scale.scaleY).toInt(),
                                        SWP_FRAMECHANGED or SWP_NOOWNERZORDER or SWP_NOZORDER,
                                    ) != 0,
                                ) { "Couldn't restore the window size." }
                            }
                            windowedBounds.value = null
                            windowedWorkArea.value = null
                            windowedStyle.value = null
                            windowedScale.value = null
                            state.placement = previousPlacement.value
                        }
                    } else if (fullscreen) {
                        if (state.placement != WindowPlacement.Fullscreen) previousPlacement.value = state.placement
                        state.placement = WindowPlacement.Fullscreen
                    } else if (state.placement == WindowPlacement.Fullscreen) {
                        state.placement = previousPlacement.value.takeUnless { it == WindowPlacement.Fullscreen }
                            ?: WindowPlacement.Floating
                    }
            }, onPlayerPip = { pip -> state.isMinimized = pip }, onExitApplication = ::exitApplication)
                    }
                }
            }
        }
    }
}

private fun installFatalExceptionHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            logger.error(throwable) { "FATAL uncaught exception on ${thread.name}" }
            if (throwable is OutOfMemoryError) ActionBreadcrumbs.dump()
            previous?.uncaughtException(thread, throwable) ?: throwable.printStackTrace()
        }
    }
}

private fun relaunchWithSetupJre(): Boolean {
    if (System.getProperty("AniLife.usingSetupJre") == "true") return false
    val launcher = System.getProperty("jpackage.app-path") ?: return false
    val directory = dependencyDirectory()
    val java = File(directory, "jre/bin/java.exe")
    if (!File(directory, "use-jre").isFile || !java.isFile) return false
    val classpath = System.getProperty("java.class.path")?.takeIf(String::isNotBlank) ?: return false
    val appDir = File(launcher).parentFile.resolve("app")
    val forwarded = System.getProperties().stringPropertyNames()
        .filter { it.startsWith("compose.") || it.startsWith("jpackage.") || it == "file.encoding" }
        .flatMap { listOf("-D$it=${System.getProperty(it)}") }
    val command = buildList {
        add(java.absolutePath)
        add("-DAniLife.usingSetupJre=true")
        addAll(forwarded)
        add("-Djava.library.path=${appDir.absolutePath}")
        add("-cp")
        add(classpath)
        add("me.enivzoy.anilife.app.MainKt")
    }
    ProcessBuilder(command).inheritIO().start()
    return true
}

private fun usableScreenBounds(configuration: java.awt.GraphicsConfiguration): Rectangle {
    val bounds = configuration.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

internal fun fitWindowBoundsToWorkArea(bounds: Rectangle, workArea: Rectangle): Rectangle {
    val width = bounds.width.coerceAtMost(workArea.width)
    val height = bounds.height.coerceAtMost(workArea.height)
    return Rectangle(
        bounds.x.coerceIn(workArea.x, workArea.x + workArea.width - width),
        bounds.y.coerceIn(workArea.y, workArea.y + workArea.height - height),
        width,
        height,
    )
}

 
private fun initialWindowSize(): DpSize {
    val density = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .defaultTransform
        .scaleX
        .takeIf { it > 0.0 } ?: 1.0
    val screen = Toolkit.getDefaultToolkit().screenSize
    val width = (screen.width / density - 48).coerceIn(800.0, 1280.0)
    val height = (screen.height / density - 96).coerceIn(480.0, 760.0)
    return DpSize(width.dp, height.dp)
}

 
private fun loadWindowIcon(): BitmapPainter? = AniLifeIcon.load(64)?.let(::BitmapPainter)

 
private fun awtColor(color: androidx.compose.ui.graphics.Color) =
    Color(color.red, color.green, color.blue)

private fun applyDarkTitleBar(window: java.awt.Window) {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("windows")) return
    runCatching {
        val dwm = Native.load("dwmapi", DwmApi::class.java)
        val hwnd = Native.getWindowPointer(window)
        val enabled = Memory(4).apply { setInt(0, 1) }
         
        if (dwm.DwmSetWindowAttribute(hwnd, 20, enabled, 4) != 0) {
            dwm.DwmSetWindowAttribute(hwnd, 19, enabled, 4)
        }
    }
}

interface DwmApi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
}

private interface WindowsWindowApi : Library {
    fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
    fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
    fun SetWindowPos(hwnd: Pointer, insertAfter: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Int
    fun ShowWindow(hwnd: Pointer, command: Int): Int

    companion object {
        val INSTANCE: WindowsWindowApi = Native.load("user32", WindowsWindowApi::class.java)
    }
}

private const val GWL_STYLE = -16
private const val WS_POPUP = 0x80000000L
private const val WS_OVERLAPPEDWINDOW = 0x00CF0000L
private const val SWP_NOOWNERZORDER = 0x0200
private const val SWP_FRAMECHANGED = 0x0020
private const val SWP_NOZORDER = 0x0004
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOSIZE = 0x0001
private const val SW_RESTORE = 9
private const val SW_MAXIMIZE = 3

