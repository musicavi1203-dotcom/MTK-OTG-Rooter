package com.mtk.otg.rooter.protocol

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbConnection(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    private var isConnected = false
    private var readJob: Job? = null
    
    private val _incomingMessages = MutableSharedFlow<AdbMessage>()
    val incomingMessages: SharedFlow<AdbMessage> = _incomingMessages.asSharedFlow()

    private val _logMessages = MutableSharedFlow<String>()
    val logMessages: SharedFlow<String> = _logMessages.asSharedFlow()

    companion object {
        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 256 * 1024 // 256KB
        private const val TIMEOUT_MS = 3000
    }

    fun connect(scope: CoroutineScope) {
        if (isConnected) return

        val cnxnMessage = AdbMessage(
            command = AdbProtocol.A_CNXN,
            arg0 = A_VERSION,
            arg1 = MAX_PAYLOAD,
            data = "host::\u0000".toByteArray()
        )

        sendRaw(cnxnMessage)
        isConnected = true

        readJob = scope.launch(Dispatchers.IO) {
            readLoop()
        }
    }

    fun sendRaw(message: AdbMessage) {
        try {
            val bytes = message.toByteArray()
            val result = connection.bulkTransfer(endpointOut, bytes, bytes.size, TIMEOUT_MS)
            if (result < 0) {
                _logMessages.tryEmit("שגיאת שליחה ב-USB: $result")
            }
        } catch (e: Exception) {
            _logMessages.tryEmit("חריגה בשליחת USB: ${e.message}")
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(16384)
        try {
            while (isConnected) {
                val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, TIMEOUT_MS)
                if (bytesRead > 0) {
                    val receivedBytes = buffer.copyOf(bytesRead)
                    processIncomingData(receivedBytes)
                } else if (bytesRead < 0) {
                    delay(100) 
                }
            }
        } catch (e: Exception) {
            _logMessages.tryEmit("חריגה בקריאת USB: ${e.message}")
        }
    }

    private fun processIncomingData(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < AdbProtocol.HEADER_SIZE) break
            
            // התיקון הקריטי: שימוש במיקום אבסולוטי (offset + 12) כדי לקרוא נכון את גודל החבילה
            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(offset + 12)
            val dataLength = buffer.int
            
            // הגנה מפני מידע פגום
            if (dataLength < 0 || dataLength > 16384) {
                _logMessages.tryEmit("אורך נתונים לא תקין: $dataLength")
                break
            }
            
            val totalPacketLength = AdbProtocol.HEADER_SIZE + dataLength
            if (bytes.size - offset < totalPacketLength) break

            val fullPacketBytes = bytes.copyOfRange(offset, offset + totalPacketLength)
            val fullMessage = AdbMessage.fromByteArray(fullPacketBytes)

            if (fullMessage != null) {
                handleMessage(fullMessage)
            }
            
            offset += totalPacketLength
        }
    }

    private fun handleMessage(message: AdbMessage) {
        when (message.command) {
            AdbProtocol.A_CNXN -> _incomingMessages.tryEmit(message)
            AdbProtocol.A_AUTH -> _incomingMessages.tryEmit(message)
            AdbProtocol.A_OKAY -> _incomingMessages.tryEmit(message)
            AdbProtocol.A_WRTE -> _incomingMessages.tryEmit(message)
            AdbProtocol.A_CLSE -> _incomingMessages.tryEmit(message)
        }
    }

    fun disconnect() {
        isConnected = false
        readJob?.cancel()
        connection.close()
    }
}
