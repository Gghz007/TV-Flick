package app.tvflick.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsSnapBridgeTest {
    @Test
    fun `native snap styles and callback install and restore on an invisible window`() {
        if (!Platform.isWindows() || GraphicsEnvironment.isHeadless()) return
        EventQueue.invokeAndWait {
            val frame = Frame().apply {
                isUndecorated = true
                setSize(800, 600)
                addNotify() // Create the HWND without showing a window or launching TV Flick.
            }
            try {
                val hwnd = HWND(Native.getWindowPointer(frame))
                val user = User32.INSTANCE
                val beforeStyle = user.GetWindowLong(hwnd, -16)
                val beforeProcedure = user.GetWindowLongPtr(hwnd, -4).toLong()
                WindowsSnapBridge(frame).use {
                    val style = user.GetWindowLong(hwnd, -16)
                    assertTrue(style and 0x00040000 != 0)
                    assertTrue(style and 0x00010000 != 0)
                    assertTrue(user.GetWindowLongPtr(hwnd, -4).toLong() != beforeProcedure)
                    assertTrue(!frame.isVisible)
                }
                assertEquals(beforeStyle, user.GetWindowLong(hwnd, -16))
                assertEquals(beforeProcedure, user.GetWindowLongPtr(hwnd, -4).toLong())
                println("Windows Snap native install/restore verified on invisible HWND")
            } finally {
                frame.dispose()
            }
        }
    }
}
