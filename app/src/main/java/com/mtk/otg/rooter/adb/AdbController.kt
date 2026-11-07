package com.mtk.otg.rooter.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.mtk.otg.rooter.protocol.AdbConnection
import com.mtk.otg.rooter.protocol.AdbCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

class AdbController(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var adbConnection: AdbConnection? = null

    private val _connectionState = MutableStateFlow<String>("מנותק")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    companion object {
        private const val TAG = "AdbController"
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }

    // פונקציית החיבור עכשיו סינכרונית (רצה ברקע ומחזירה תוצאה)
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        _connectionState.value = "מתחבר..."
        try {
            val usbInterface = findAdbInterface(device)
                ?: throw IOException("ממשק ADB לא נמצא. ודא שניפוי באגים מופעל במכשיר השני.")

            connection = usbManager.openDevice(device)
                ?: throw IOException("לא ניתן לפתוח את החיבור ל-USB (אולי אין הרשאה?).")

            _connectionState.value = "מבצע לחיצת יד (Handshake)..."
            
            // יצירת החיבור החדש (שמקבל את ה-Interface ולא את ה-Endpoints)
            adbConnection = AdbConnection(connection!!, usbInterface)

            // ביצוע הלחיצה עם המפתחות
            adbConnection?.handshake(
                signToken = { token -> AdbCrypto.signToken(token) },
                getPublicKey = { AdbCrypto.encodePublicKeyAdb() }
            )

            _connectionState.value = "מחובר ומאומת בהצלחה! ✅"
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = "שגיאה: ${e.message}"
            disconnect()
            Result.failure(e)
        }
    }

    // שליחת פקודה למכשיר
    suspend fun executeShellCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        val adb = adbConnection ?: return@withContext Result.failure(IOException("אין חיבור ADB פעיל"))
        try {
            val result = adb.shell(command)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disconnect() {
        adbConnection?.close()
        connection?.close()
        adbConnection = null
        connection = null
        _connectionState.value = "מנותק"
    }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == ADB_CLASS && 
                intf.interfaceSubclass == ADB_SUBCLASS && 
                intf.interfaceProtocol == ADB_PROTOCOL) {
                return intf
            }
        }
        return null
    }
}
