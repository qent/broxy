package io.qent.broxy.ui.liquidglass.macos

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.DisposableHandle
import java.awt.Frame
import java.awt.Window

private const val NS_VISUAL_EFFECT_BLENDING_BEHIND_WINDOW = 0L
private const val NS_VISUAL_EFFECT_MATERIAL_UNDER_WINDOW_BACKGROUND = 21L
private const val NS_VISUAL_EFFECT_STATE_ACTIVE = 1L
private const val NS_WINDOW_ABOVE = 1L
private const val NS_WINDOW_BELOW = -1L

private object EmptyHandle : DisposableHandle {
    override fun dispose() = Unit
}

fun isMacOsDesktop(): Boolean = System.getProperty("os.name")?.contains("mac", ignoreCase = true) == true

fun systemReduceTransparencyEnabled(): Boolean {
    if (!isMacOsDesktop()) return false
    return runCatching {
        MainThread.call {
            val workspaceClass = Objc.classByName("NSWorkspace") ?: return@call false
            val workspace = Objc.sendPointer(workspaceClass, "sharedWorkspace") ?: return@call false
            Objc.sendBool(workspace, "accessibilityDisplayShouldReduceTransparency")
        }
    }.getOrDefault(false)
}

fun installMacosVibrancyBackground(window: Window): DisposableHandle {
    if (!isMacOsDesktop()) return EmptyHandle

    return runCatching {
        MainThread.call {
            val nsWindow = findNsWindow(window) ?: return@call EmptyHandle
            val originalContentView = Objc.sendPointer(nsWindow, "contentView") ?: return@call EmptyHandle
            val effectHost =
                createGlassEffectHost(originalContentView)
                    ?: createVisualEffectHost(originalContentView)
                    ?: return@call EmptyHandle
            Objc.sendVoid(nsWindow, "setContentView:", effectHost)

            object : DisposableHandle {
                override fun dispose() {
                    runCatching {
                        MainThread.run {
                            Objc.sendVoid(nsWindow, "setContentView:", originalContentView)
                            Objc.sendVoid(effectHost, "removeFromSuperview")
                        }
                    }
                }
            }
        }
    }.getOrElse { EmptyHandle }
}

private fun createEffectView(): Pointer? {
    val cls = Objc.classByName("NSVisualEffectView") ?: return null
    val allocated = Objc.sendPointer(cls, "alloc") ?: return null
    val view = Objc.sendPointer(allocated, "init") ?: return null

    Objc.sendVoid(view, "setBlendingMode:", NS_VISUAL_EFFECT_BLENDING_BEHIND_WINDOW)
    Objc.sendVoid(view, "setMaterial:", NS_VISUAL_EFFECT_MATERIAL_UNDER_WINDOW_BACKGROUND)
    Objc.sendVoid(view, "setState:", NS_VISUAL_EFFECT_STATE_ACTIVE)
    Objc.sendVoid(view, "setTranslatesAutoresizingMaskIntoConstraints:", 0)

    return view
}

private fun createGlassEffectHost(contentView: Pointer): Pointer? {
    val cls = Objc.classByName("NSGlassEffectView") ?: return null
    val allocated = Objc.sendPointer(cls, "alloc") ?: return null
    val view = Objc.sendPointer(allocated, "init") ?: return null
    Objc.sendVoid(view, "setContentView:", contentView)
    return view
}

private fun createVisualEffectHost(contentView: Pointer): Pointer? {
    val effectView = createEffectView() ?: return null
    Objc.sendVoid(effectView, "addSubview:positioned:relativeTo:", contentView, NS_WINDOW_ABOVE, Pointer.NULL)
    pinToContainer(contentView, effectView)
    return effectView
}

private fun pinToContainer(
    view: Pointer,
    container: Pointer,
) {
    val constraints =
        listOfNotNull(
            anchorConstraint(view, "leadingAnchor", container, "leadingAnchor"),
            anchorConstraint(view, "trailingAnchor", container, "trailingAnchor"),
            anchorConstraint(view, "topAnchor", container, "topAnchor"),
            anchorConstraint(view, "bottomAnchor", container, "bottomAnchor"),
        )
    constraints.forEach { constraint ->
        Objc.sendVoid(constraint, "setActive:", 1)
    }
}

private fun anchorConstraint(
    view: Pointer,
    viewAnchorSelector: String,
    container: Pointer,
    containerAnchorSelector: String,
): Pointer? {
    val viewAnchor = Objc.sendPointer(view, viewAnchorSelector) ?: return null
    val containerAnchor = Objc.sendPointer(container, containerAnchorSelector) ?: return null
    return Objc.sendPointer(viewAnchor, "constraintEqualToAnchor:", containerAnchor)
}

private fun findNsWindow(window: Window): Pointer? {
    val nsAppClass = Objc.classByName("NSApplication") ?: return null
    val app = Objc.sendPointer(nsAppClass, "sharedApplication") ?: return null

    val targetTitle = (window as? Frame)?.title?.trim()?.takeIf { it.isNotBlank() }
    val windows = Objc.sendPointer(app, "windows")
    val count = windows?.let { Objc.sendLong(it, "count").toInt().coerceAtLeast(0) } ?: 0

    var firstWindow: Pointer? = null
    repeat(count) { index ->
        val candidate = Objc.sendPointer(windows, "objectAtIndex:", index.toLong()) ?: return@repeat
        if (firstWindow == null) {
            firstWindow = candidate
        }
        if (targetTitle != null) {
            val candidateTitle = nsStringToString(Objc.sendPointer(candidate, "title"))
            if (candidateTitle == targetTitle) {
                return candidate
            }
        }
    }

    return Objc.sendPointer(app, "mainWindow")
        ?: Objc.sendPointer(app, "keyWindow")
        ?: firstWindow
}

private fun nsStringToString(value: Pointer?): String? {
    value ?: return null
    val utf8Ptr = Objc.sendPointer(value, "UTF8String") ?: return null
    return utf8Ptr.getString(0)
}

private object MainThread {
    private val system = NativeLibrary.getInstance("System")
    private val dispatchGetMainQueue: Function = system.getFunction("dispatch_get_main_queue")
    private val dispatchSyncF: Function = system.getFunction("dispatch_sync_f")
    private val pthreadMainNp: Function = system.getFunction("pthread_main_np")

    private fun interface DispatchWork : com.sun.jna.Callback {
        fun invoke(context: Pointer?)
    }

    fun run(block: () -> Unit) {
        if (isMainThread()) {
            block()
            return
        }
        var error: Throwable? = null
        val work =
            DispatchWork {
                runCatching(block).onFailure { failure -> error = failure }
            }
        dispatchSyncF.invokeVoid(arrayOf(mainQueue(), Pointer.NULL, work))
        error?.let { throw it }
    }

    fun <T> call(block: () -> T): T {
        if (isMainThread()) {
            return block()
        }
        var error: Throwable? = null
        var result: Any? = null
        val work =
            DispatchWork {
                runCatching { block() }
                    .onSuccess { value -> result = value }
                    .onFailure { failure -> error = failure }
            }
        dispatchSyncF.invokeVoid(arrayOf(mainQueue(), Pointer.NULL, work))
        error?.let { throw it }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun isMainThread(): Boolean = pthreadMainNp.invokeInt(emptyArray()) == 1

    private fun mainQueue(): Pointer = dispatchGetMainQueue.invokePointer(emptyArray())
}

private object Objc {
    private val runtime = NativeLibrary.getInstance("objc")
    private val objcGetClass: Function = runtime.getFunction("objc_getClass")
    private val selRegisterName: Function = runtime.getFunction("sel_registerName")
    private val msgSend: Function = runtime.getFunction("objc_msgSend")

    private val selectorCache = mutableMapOf<String, Pointer>()

    fun classByName(name: String): Pointer? = objcGetClass.invokePointer(arrayOf(name))

    fun sendPointer(
        receiver: Pointer?,
        selector: String,
        vararg args: Any?,
    ): Pointer? {
        receiver ?: return null
        val sel = selector(selector) ?: return null
        return msgSend.invokePointer(arrayOf(receiver, sel, *args))
    }

    fun sendLong(
        receiver: Pointer,
        selector: String,
        vararg args: Any?,
    ): Long {
        val sel = selector(selector) ?: return 0L
        return msgSend.invokeLong(arrayOf(receiver, sel, *args))
    }

    fun sendBool(
        receiver: Pointer,
        selector: String,
        vararg args: Any?,
    ): Boolean {
        val sel = selector(selector) ?: return false
        return msgSend.invokeInt(arrayOf(receiver, sel, *args)) != 0
    }

    fun sendVoid(
        receiver: Pointer,
        selector: String,
        vararg args: Any?,
    ) {
        val sel = selector(selector) ?: return
        msgSend.invokeVoid(arrayOf(receiver, sel, *args))
    }

    private fun selector(name: String): Pointer? =
        selectorCache.getOrPut(name) {
            selRegisterName.invokePointer(arrayOf(name))
        }
}
