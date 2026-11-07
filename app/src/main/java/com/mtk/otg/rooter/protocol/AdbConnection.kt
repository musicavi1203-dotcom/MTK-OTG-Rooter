package com.mtk.otg.rooter.protocol

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

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
        // מזהה המכשיר של ADB (בדרך כלל 0x12345678)
        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 4096
        private const val TIMEOUT_MS = 3000
    }

    // פתיחת החיבור ושליחת בקשת CNXN
    fun connect(scope: CoroutineScope) {
        if (isConnected) return

        // הודעת החיבור הראשונית
        val cnxnMessage = AdbMessage(
            command = AdbProtocol.A_CNXN,
            arg0 = A_VERSION,
            arg1 = MAX_PAYLOAD,
            data = "host::features=cmd,shell_v2\0".toByteArray() // מזהה את האפליקציה שלנו כ"מארח"
        )

        write(cnxnMessage)
        isConnected = true

        // מתחילים להקשיב לתשובות מהמכשיר ברקע
        readJob = scope.launch(Dispatchers.IO) {
            readLoop()
        }
    }

    // שליחת חבילה למכשיר
    private fun write(message: AdbMessage) {
        val bytes = message.toByteArray()
        connection.bulkTransfer(endpointOut, bytes, bytes.size, TIMEOUT_MS)
    }

    // לולאה שקוראת נתונים מה-USB כל הזמן
    private suspend fun readLoop() {
        val buffer = ByteArray(16384) // חוצץ לקריאת נתונים
        
        while (isConnected) {
            val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, TIMEOUT_MS)
            
            if (bytesRead > 0) {
                val receivedBytes = buffer.copyOf(bytesRead)
                processIncomingData(receivedBytes)
            } else if (bytesRead < 0) {
                // שגיאה בקריאה או שהמכשיר התנתק
                delay(100) 
            }
        }
    }

    // מטפל בנתונים גולמיים שמגיעים (לפעמים מגיעות כמה חבילות ביחד)
    private fun processIncomingData(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < AdbProtocol.HEADER_SIZE) break // חבילה חלקית
            
            val headerBytes = bytes.copyOfRange(offset, offset + AdbProtocol.HEADER_SIZE)
            val message = AdbMessage.fromByteArray(headerBytes + ByteArray(0)) ?: break
            
            val dataLength = message.data.size
            val totalPacketLength = AdbProtocol.HEADER_SIZE + dataLength

            if (bytes.size - offset < totalPacketLength) break // החבילה עוד לא הגיעה במלואה

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
                // החיבור אושר!
                println("ADB: Connected successfully!")
            }
            AdbProtocol.A_AUTH -> {
                // המכשיר מבקש אימות. כאן נצטרך לשלוח חתימת RSA
                println("ADB: Auth requested (Token received)")
                handleAuth(message.data)
            }
            AdbProtocol.A_OKAY -> {
                // ערוץ נפתח או שנתונים התקבלו
                _incomingMessages.tryEmit(message)
            }
            AdbProtocol.A_WRTE -> {
                // התקבלו נתונים (למשל פלט של פקודת shell)
                _incomingMessages.tryEmit(message)
            }
            AdbProtocol.A_CLSE -> {
                // ערוץ נסגר
                println("ADB: Channel closed")
            }
        }
    }

    // פונקציה זמנית לאימות (בהמשך נממש פה את ה-RSA)
    private fun handleAuth(token: ByteArray) {
        println("Auth token received, size: ${token.size}. (RSA signing not implemented yet)")
        // כאן נשלח את ה-A_AUTH בחזרה עם החתימה
    }

    // סגירת החיבור
    fun disconnect() {
        isConnected = false
        readJob?.cancel()
        connection.close()
    }
}
