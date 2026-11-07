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

    companion object {
        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 4096
        private const val TIMEOUT_MS = 3000
    }

    fun connect(scope: CoroutineScope) {
        if (isConnected) return

        val cnxnMessage = AdbMessage(
            command = AdbProtocol.A_CNXN,
            arg0 = A_VERSION,
            arg1 = MAX_PAYLOAD,
            data = "host::features=cmd,shell_v2\u0000".toByteArray()
        )

        sendRaw(cnxnMessage)
        isConnected = true

        readJob = scope.launch(Dispatchers.IO) {
            readLoop()
        }
    }

    fun sendRaw(message: AdbMessage) {
        val bytes = message.toByteArray()
        val result = connection.bulkTransfer(endpointOut, bytes, bytes.size, TIMEOUT_MS)
        if (result < 0) {
            println("USB Send Error: $result")
        }
    }

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

    private fun processIncomingData(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < AdbProtocol.HEADER_SIZE) break
            
            // התיקון: קוראים את הכותרת כדי לדעת את הגודל האמיתי של המידע (Data Length)
            val buffer = ByteBuffer.wrap(bytes, offset, AdbProtocol.HEADER_SIZE)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(12) // מדלגים על command, arg0, arg1
            val dataLength = buffer.int
            
            val totalPacketLength = AdbProtocol.HEADER_SIZE + dataLength
            if (bytes.size - offset < totalPacketLength) break

            // מחלצים את החבילה המלאה (כותרת + מידע)
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
            AdbProtocol.A_CNXN -> {
                println("ADB: Connected successfully!")
                _incomingMessages.tryEmit(message)
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

    fun disconnect() {
        isConnected = false
        readJob?.cancel()
        connection.close()
    }
}
