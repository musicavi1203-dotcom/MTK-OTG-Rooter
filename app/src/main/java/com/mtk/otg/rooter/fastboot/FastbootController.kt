package com.mtk.otg.rooter.fastboot

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.mtk.otg.rooter.protocol.FastbootProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

class FastbootController(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val _state = MutableStateFlow<String>("מנותק")
    val state: StateFlow<String> = _state.asStateFlow()

    companion object {
        private const val TAG = "FastbootController"
        // מזהי ממשק של Fastboot (בדרך כלל Class 0xFF, Subclass 0x42, Protocol 0x03)
        private const val FB_CLASS = 0xFF
        private const val FB_SUBCLASS = 0x42
        private const val FB_PROTOCOL = 0x03
        private const val TIMEOUT_MS = 5000
    }

    fun connect() {
        _state.value = "מתחבר ל-Fastboot..."
        val intf = findFastbootInterface(device)
        if (intf == null) {
            _state.value = "שגיאה: ממשק Fastboot לא נמצא"
            return
        }

        if (!findEndpoints(intf)) {
            _state.value = "שגיאה: נקודות קצה של Fastboot לא נמצאו"
            return
        }

        connection = usbManager.openDevice(device)
        if (connection == null || !connection!!.claimInterface(intf, true)) {
            _state.value = "שגיאה: לא ניתן להשתלט על ממשק ה-USB"
            return
        }

        _state.value = "מחובר ל-Fastboot ✅"
    }

    // שליחת פקודת Fastboot (למשל "getvar:version")
    fun sendCommand(command: String): String {
        val conn = connection ?: return "FAIL: Not connected"
        val outEp = endpointOut ?: return "FAIL: No output endpoint"
        val inEp = endpointIn ?: return "FAIL: No input endpoint"

        // פקודות Fastboot נשלחות כטקסט המסתיים בתו Null
        val commandBytes = (command + "\u0000").toByteArray(Charsets.US_ASCII)
        val sent = conn.bulkTransfer(outEp, commandBytes, commandBytes.size, TIMEOUT_MS)
        
        if (sent < 0) return "FAIL: Failed to send command"

        // קריאת התגובה (OKAY / FAIL / DATA / INFO)
        val buffer = ByteArray(4096)
        val read = conn.bulkTransfer(inEp, buffer, buffer.size, TIMEOUT_MS)
        
        if (read <= 0) return "FAIL: No response"

        val responseBytes = buffer.copyOf(read)
        return String(responseBytes, Charsets.US_ASCII).trimEnd('\u0000')
    }

    // קריאת נתונים נוספים (למשל כשהמכשיר מחזיר הרבה מידע)
    fun readResponse(): String {
        val conn = connection ?: return ""
        val inEp = endpointIn ?: return ""
        val buffer = ByteArray(4096)
        val read = conn.bulkTransfer(inEp, buffer, buffer.size, TIMEOUT_MS)
        if (read <= 0) return ""
        return String(buffer.copyOf(read), Charsets.US_ASCII).trimEnd('\u0000')
    }

    fun disconnect() {
        connection?.close()
        _state.value = "מנותק"
    }

    private fun findFastbootInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == FB_CLASS && 
                intf.interfaceSubclass == FB_SUBCLASS) {
                return intf
            }
        }
        return null
    }

    private fun findEndpoints(intf: UsbInterface): Boolean {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
                else endpointOut = ep
            }
        }
        return endpointIn != null && endpointOut != null
    }
}
