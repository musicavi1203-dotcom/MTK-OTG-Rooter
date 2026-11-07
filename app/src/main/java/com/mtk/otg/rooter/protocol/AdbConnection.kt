package com.mtk.otg.rooter.protocol

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AdbConnection(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    private var isConnected = false
    private var readJob: Job? = null
    
    // כאן נשדר הודעות שהתקבלו (למשל OKAY, WRTE) למי שמקשיב
    private val _incomingMessages = MutableSharedFlow<AdbMessage>()
    val incomingMessages: SharedFlow<AdbMessage> = _incomingMessages.asSharedFlow()

    companion object {
        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 4096
        private const val TIMEOUT_MS = 3000
    }

    // פתיחת החיבור ושליחת בקשת CNXN
    fun connect(scope: CoroutineScope) {
        if (isConnected) return

        val cnxnMessage = AdbMessage(
            command = AdbProtocol.A_CNXN,
            arg0 = A_VERSION,
            arg1 = MAX_PAYLOAD,
            // התיקון כאן: החלפנו את \0 ב-\u0000
            data = "host::features=cmd,shell_v2\u0000".toByteArray()
        )

        sendRaw(cnxnMessage)
        isConnected = true

        // מתחילים להקשיב לתשובות מהמכשיר ברקע
        readJob = scope.launch(Dispatchers.IO) {
            readLoop()
        }
    }

    // חשיפה של פונקציית השליחה כדי שנוכל להשתמש בה גם מה-AdbController
    fun sendRaw(message: AdbMessage) {
        val bytes = message.toByteArray()
        connection.bulkTransfer(endpointOut, bytes, bytes.size, TIMEOUT_MS)
    }

    // לולאה שקוראת נתונים מה-USB כל הזמן
    private suspend fun readLoop() {
        val buffer = ByteArray(16384)
        
        while (isConnected) {
            val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, TIMEOUT_MS)
            
            if (bytesRead > 0) {
                val receivedBytes = buffer.copyOf(bytesRead)
                processIncomingData(receivedBytes)
            } else if (bytesRead < 0) {
                delay(100) 
            }
        }
    }

    // מטפל בנתונים גולמיים שמגיעים
    private fun processIncomingData(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < AdbProtocol.HEADER_SIZE) break
            
            val headerBytes = bytes.copyOfRange(offset, offset + AdbProtocol.HEADER_SIZE)
            val message = AdbMessage.fromByteArray(headerBytes + ByteArray(0)) ?: break
            
            val dataLength = message.data.size
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

    // מגיב לסוגי ההודעות השונים
    private fun handleMessage(message: AdbMessage) {
        when (message.command) {
            AdbProtocol.A_CNXN -> {
                println("ADB: Connected successfully!")
            }
            AdbProtocol.A_AUTH -> {
                println("ADB: Auth requested (Token received)")
                _incomingMessages.tryEmit(message)
            }
            AdbProtocol.A_OKAY -> {
                _incomingMessages.tryEmit(message)
            }
            AdbProtocol.A_WRTE -> {
                _incomingMessages.tryEmit(message)
            }
            AdbProtocol.A_CLSE -> {
                println("ADB: Channel closed")
                _incomingMessages.tryEmit(message)
            }
        }
    }

    // סגירת החיבור
    fun disconnect() {
        isConnected = false
        readJob?.cancel()
        connection.close()
    }
}
