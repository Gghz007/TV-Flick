package app.tvflick.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowScope
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser.WindowProc
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Use the OS move loop so Windows owns Aero Snap and drag-to-restore behavior. */
@Composable
internal fun WindowScope.NativeWindowDraggableArea(content: @Composable () -> Unit) {
    var bridge by remember(window) { mutableStateOf<WindowsSnapBridge?>(null) }
    DisposableEffect(window) {
        val installed = if (Platform.isWindows()) runCatching { WindowsSnapBridge(window) }
            .onFailure { System.err.println("Windows Snap initialization failed: ${it.message}") }
            .getOrNull() else null
        bridge = installed
        onDispose { installed?.close() }
    }
    val nativeDrag = bridge
    if (nativeDrag == null) WindowDraggableArea(content = content)
    else Box(Modifier.pointerInput(nativeDrag) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
                    event.changes.none { it.isConsumed }) {
                    event.changes.forEach { it.consume() }
                    nativeDrag.beginDrag()
                }
            }
        }
    }, propagateMinConstraints = true) { content() }
}

internal interface CaptureApi : StdCallLibrary {
    fun ReleaseCapture(): Boolean
}

/** Retain the callback for the complete lifetime of the subclassed HWND. */
internal class WindowsSnapBridge(window: Window) : AutoCloseable {
    private val user = User32.INSTANCE
    private val capture = Native.load("user32", CaptureApi::class.java)
    private val hwnd = HWND(Native.getWindowPointer(window))
    private val oldStyle = user.GetWindowLong(hwnd, GWL_STYLE)
    private val oldProcedure = Pointer(user.GetWindowLongPtr(hwnd, GWLP_WNDPROC).toLong())
    private val procedure = WindowProc { handle, message, wParam, lParam ->
        when (message) {
            // Keep the supplied title bar/client geometry despite enabling the native resize style.
            WM_NCCALCSIZE -> LRESULT(0)
            WM_BEGIN_NATIVE_MOVE -> {
                // This callback runs on the owning Windows thread; release capture there,
                // not on Compose's EDT. DefWindowProc enters the normal Windows move loop.
                capture.ReleaseCapture()
                user.DefWindowProc(handle, WM_NCLBUTTONDOWN, WPARAM(HTCAPTION.toLong()), lParam)
            }
            else -> user.CallWindowProc(oldProcedure, handle, message, wParam, lParam)
        }
    }
    private var closed = false

    init {
        check(Native.POINTER_SIZE == 8) { "Windows Snap requires the x64 desktop runtime" }
        val previous = user.SetWindowLongPtr(hwnd, GWLP_WNDPROC, CallbackReference.getFunctionPointer(procedure))
        check(previous != null) { "Cannot install Windows Snap callback: ${Native.getLastError()}" }
        try {
            // Both flags are needed for Windows Snap on an undecorated AWT window.
            user.SetWindowLong(hwnd, GWL_STYLE, oldStyle or WS_THICKFRAME or WS_MAXIMIZEBOX)
            check(user.GetWindowLong(hwnd, GWL_STYLE) and WS_THICKFRAME != 0) { "Cannot enable Windows Snap style" }
            check(user.SetWindowPos(hwnd, null, 0, 0, 0, 0, FRAME_CHANGED_FLAGS)) { "Cannot refresh native frame" }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun beginDrag() {
        if (closed) return
        val point = POINT()
        if (user.GetCursorPos(point)) {
            val coordinates = (point.x.toLong() and 0xffff) or ((point.y.toLong() and 0xffff) shl 16)
            // Asynchronous: never block Compose's EDT while the native move loop runs.
            user.PostMessage(hwnd, WM_BEGIN_NATIVE_MOVE, WPARAM(0), LPARAM(coordinates))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (user.IsWindow(hwnd)) {
            user.SetWindowLongPtr(hwnd, GWLP_WNDPROC, oldProcedure)
            user.SetWindowLong(hwnd, GWL_STYLE, oldStyle)
            user.SetWindowPos(hwnd, null, 0, 0, 0, 0, FRAME_CHANGED_FLAGS)
        }
    }

    private companion object {
        const val GWL_STYLE = -16
        const val GWLP_WNDPROC = -4
        const val WS_THICKFRAME = 0x00040000
        const val WS_MAXIMIZEBOX = 0x00010000
        const val WM_NCCALCSIZE = 0x0083
        const val WM_NCLBUTTONDOWN = 0x00A1
        const val WM_BEGIN_NATIVE_MOVE = 0x8546
        const val HTCAPTION = 2
        // SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
        const val FRAME_CHANGED_FLAGS = 0x0037
    }
}
