package com.mtk.otg.rooter.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.mtk.otg.rooter.protocol.AdbConnection
import com.mtk.otg.rooter.protocol.AdbCrypto
import com.mtk.otg.rooter.protocol.AdbMessage
import com.mtk.otg.rooter.protocol.AdbProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey

class AdbController(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var adbConnection: AdbConnection? = null
    private var keyPair: KeyPair? = null
    private var scope: CoroutineScope? = null

    // מצב החיבור (משמש את ה-UI)
    private val _connectionState = MutableStateFlow<String>("מנותק")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    // מזהה ייחודי לערוץ (Stream) שאנחנו פותחים
    private var nextLocalId = 1
    private var currentLocalId = -1
    private var currentRemoteId = -1
    private val commandOutput = ByteArrayOutputStream()

    companion object {
        private const val TAG = "AdbController"
        // המזהה של ממשק ADB בתוך ה-USB (בדרך כלל Class 255, Subclass 66, Protocol 1)
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }

    // פונקציה ראשית להתחברות
    fun connect(scope: CoroutineScope) {
        this.scope = scope
        _connectionState.value = "מתחבר..."

        scope.launch(Dispatchers.IO) {
            try {
                // 1. מציאת ממשק ה-ADB
                val usbInterface = findAdbInterface(device)
                if (usbInterface == null) {
                    _connectionState.value = "שגיאה: ממשק ADB לא נמצא במכשיר"
                    return@launch
                }

                // 2. מציאת ה-Endpoints (קריאה וכתיבה)
                val endpoints = findEndpoints(usbInterface)
                if (endpoints == null) {
                    _connectionState.value = "שגיאה: נקודות קצה (Endpoints) לא נמצאו"
                    return@launch
                }

                // 3. פתיחת החיבור ל-USB
                connection = usbManager.openDevice(device)
                if (connection == null || !connection!!.claimInterface(usbInterface, true)) {
                    _connectionState.value = "שגיאה: לא ניתן להשתלט על ממשק ה-USB"
                    return@launch
                }

                // 4. יצירת החיבור ל-ADB והתחלת האזנה
                adbConnection = AdbConnection(connection!!, endpoints.first, endpoints.second)
                listenToAdbMessages()
                adbConnection?.connect(this@launch)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = "שגיאה בחיבור: ${e.message}"
            }
        }
    }

    // האזנה להודעות שמגיעות מה-ADB (כמו AUTH, OKAY, WRTE)
    private fun listenToAdbMessages() {
        scope?.launch(Dispatchers.IO) {
            adbConnection?.incomingMessages?.collect { message ->
                when (message.command) {
                    AdbProtocol.A_CNXN -> {
                        _connectionState.value = "מחובר ומאומת בהצלחה! ✅"
                    }
                    AdbProtocol.A_AUTH -> {
                        handleAuthRequest(message.data)
                    }
                    AdbProtocol.A_OKAY -> {
                        // הערוץ נפתח, אפשר לשלוח נתונים
                        if (currentRemoteId == -1) {
                            currentRemoteId = message.arg0
                        }
                    }
                    AdbProtocol.A_WRTE -> {
                        // קיבלנו פלט של פקודה
                        commandOutput.write(message.data)
                        // שולחים אישור קבלה (OKAY) כדי שהמכשיר ימשיך לשלוח
                        sendOkay(message.arg0)
                    }
                    AdbProtocol.A_CLSE -> {
                        // הערוץ נסגר, הפקודה הסתיימה
                        currentLocalId = -1
                        currentRemoteId = -1
                    }
                }
            }
        }
    }

    // טיפול בבקשת אימות מהמכשיר (RSA)
    private suspend fun handleAuthRequest(token: ByteArray) {
        _connectionState.value = "מבצע אימות (RSA)..."
        
        // מייצרים מפתח אם אין (פעם אחת)
        if (keyPair == null) {
            keyPair = withContext(Dispatchers.Default) { AdbCrypto.generateKeyPair() }
        }

        // חותמים על הטוקן ושולחים אותו
        val signedToken = AdbCrypto.signToken(token, keyPair!!)
        val authSignature = AdbMessage(AdbProtocol.A_AUTH, 2, 0, signedToken)
        
        // שולחים גם את המפתח הציבורי (במקרה שהמכשיר לא מכיר אותנו וירצה להציג חלון)
        val publicKey = AdbCrypto.encodePublicKey(keyPair!!.public as RSAPublicKey)
        val authPublicKey = AdbMessage(AdbProtocol.A_AUTH, 3, 0, publicKey.toByteArray() + 0)

        // שליחת החבילות דרך החיבור (נצטרך לחשוף פונקציית שליחה ב-AdbConnection)
        adbConnection?.sendRaw(authSignature)
        adbConnection?.sendRaw(authPublicKey)
    }

    // שליחת פקודת Shell למכשיר
    fun executeShellCommand(command: String) {
        if (currentLocalId != -1) {
            Log.w(TAG, "Command already running")
            return
        }

        currentLocalId = nextLocalId++
        commandOutput.reset()

        // פקודת OPEN פותחת ערוץ חדש לפקודת ה-shell
        val openMessage = AdbMessage(
            command = AdbProtocol.A_OPEN,
            arg0 = currentLocalId,
            arg1 = 0,
            data = "shell:$command\0".toByteArray()
        )
        adbConnection?.sendRaw(openMessage)
    }

    // שליחת אישור (OKAY) למכשיר
    private fun sendOkay(remoteId: Int) {
        val okayMessage = AdbMessage(AdbProtocol.A_OKAY, currentLocalId, remoteId)
        adbConnection?.sendRaw(okayMessage)
    }

    // סגירת החיבור
    fun disconnect() {
        adbConnection?.disconnect()
        connection?.close()
        _connectionState.value = "מנותק"
    }

    // --- פונקציות עזר למציאת ה-USB ---

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

    private fun findEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEndpoint = ep
                else outEndpoint = ep
            }
        }

        return if (inEndpoint != null && outEndpoint != null) {
            Pair(inEndpoint, outEndpoint)
        } else null
    }
}
