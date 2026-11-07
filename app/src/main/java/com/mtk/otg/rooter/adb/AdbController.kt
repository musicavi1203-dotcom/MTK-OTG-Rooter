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

    private val _connectionState = MutableStateFlow<String>("מנותק")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private var nextLocalId = 1
    private var currentLocalId = -1
    private var currentRemoteId = -1
    private val commandOutput = ByteArrayOutputStream()

    companion object {
        private const val TAG = "AdbController"
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }

    fun connect(scope: CoroutineScope) {
        this.scope = scope
        _connectionState.value = "מתחבר..."

        scope.launch(Dispatchers.IO) {
            try {
                val usbInterface = findAdbInterface(device)
                if (usbInterface == null) {
                    _connectionState.value = "שגיאה: ממשק ADB לא נמצא במכשיר"
                    return@launch
                }

                val endpoints = findEndpoints(usbInterface)
                if (endpoints == null) {
                    _connectionState.value = "שגיאה: נקודות קצה (Endpoints) לא נמצאו"
                    return@launch
                }

                connection = usbManager.openDevice(device)
                if (connection == null || !connection!!.claimInterface(usbInterface, true)) {
                    _connectionState.value = "שגיאה: לא ניתן להשתלט על ממשק ה-USB"
                    return@launch
                }

                adbConnection = AdbConnection(connection!!, endpoints.first, endpoints.second)
                listenToAdbMessages()
                adbConnection?.connect(this@launch)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = "שגיאה בחיבור: ${e.message}"
            }
        }
    }

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
                        if (currentRemoteId == -1) {
                            currentRemoteId = message.arg0
                        }
                    }
                    AdbProtocol.A_WRTE -> {
                        commandOutput.write(message.data)
                        sendOkay(message.arg0)
                    }
                    AdbProtocol.A_CLSE -> {
                        currentLocalId = -1
                        currentRemoteId = -1
                    }
                }
            }
        }
    }

    private suspend fun handleAuthRequest(token: ByteArray) {
        _connectionState.value = "מבצע אימות (RSA)..."
        
        if (keyPair == null) {
            keyPair = withContext(Dispatchers.Default) { AdbCrypto.generateKeyPair() }
        }

        val signedToken = AdbCrypto.signToken(token, keyPair!!)
        val authSignature = AdbMessage(AdbProtocol.A_AUTH, 2, 0, signedToken)
        
        val publicKey = AdbCrypto.encodePublicKey(keyPair!!.public as RSAPublicKey)
        // התיקון: שימוש ב-byteArrayOf(0) במקום סתם 0
        val publicKeyBytes = publicKey.toByteArray() + byteArrayOf(0)
        val authPublicKey = AdbMessage(AdbProtocol.A_AUTH, 3, 0, publicKeyBytes)

        adbConnection?.sendRaw(authSignature)
        adbConnection?.sendRaw(authPublicKey)
    }

    fun executeShellCommand(command: String) {
        if (currentLocalId != -1) {
            Log.w(TAG, "Command already running")
            return
        }

        currentLocalId = nextLocalId++
        commandOutput.reset()

        // התיקון: החלפנו את \0 ב-\u0000
        val openMessage = AdbMessage(
            command = AdbProtocol.A_OPEN,
            arg0 = currentLocalId,
            arg1 = 0,
            data = "shell:$command\u0000".toByteArray()
        )
        adbConnection?.sendRaw(openMessage)
    }

    private fun sendOkay(remoteId: Int) {
        val okayMessage = AdbMessage(AdbProtocol.A_OKAY, currentLocalId, remoteId)
        adbConnection?.sendRaw(okayMessage)
    }

    fun disconnect() {
        adbConnection?.disconnect()
        connection?.close()
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
