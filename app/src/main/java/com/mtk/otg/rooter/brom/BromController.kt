package com.mtk.otg.rooter.brom

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.mtk.otg.rooter.protocol.BromProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BromController(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null

    // פונקציה ראשית שמבצעת את כל תהליך ה-Handshake ושליחת ה-DA
    suspend fun loadAndRunDA(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. מציאת הממשק וה-Endpoints
            if (!setupUsb()) throw IOException("שגיאה: לא נמצא ממשק USB מתאים ל-BROM")

            // 2. לחיצת יד (Handshake)
            if (!handshake()) throw IOException("שגיאה: ה-Handshake נכשל. המכשיר לא במצב BROM או שהפרוטוקול שונה.")

            // 3. קריאת קובץ ה-DA מתיקיית ה-Assets
            val daBytes = readDaFromAssets("DA_MT6765.bin")

            // 4. שליחת פקודת SEND_DA ושליחת הנתונים
            if (!sendDa(daBytes)) throw IOException("שגיאה: שליחת ה-DA נכשלה.")

            // 5. שליחת פקודת JUMP_DA כדי להריץ את ה-DA
            if (!jumpDa()) throw IOException("שגיאה: הפעלת ה-DA (Jump) נכשלה.")

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun setupUsb(): Boolean {
        // במצב BROM, לרוב הממשק הראשון מכיל את ה-Endpoints
        if (device.interfaceCount == 0) return false
        
        usbInterface = device.getInterface(0)
        
        for (i in 0 until usbInterface!!.endpointCount) {
            val ep = usbInterface!!.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
                else endpointOut = ep
            }
        }

        if (endpointIn == null || endpointOut == null) return false

        connection = usbManager.openDevice(device) ?: return false
        return connection!!.claimInterface(usbInterface, true)
    }

    private fun handshake(): Boolean {
        // שליחת בייט ה-Handshake (0xA0)
        val buf = byteArrayOf(BromProtocol.HANDSHAKE_BYTE.toByte())
        val sent = connection?.bulkTransfer(endpointOut, buf, 1, 2000)
        
        if (sent != 1) return false

        // מחכים לתגובה (בדרך כלל 0x5A, אבל לפעמים לא חוזר כלום)
        val response = ByteArray(1)
        val read = connection?.bulkTransfer(endpointIn, response, 1, 2000)
        
        // אם הצלחנו לקרוא את ה-Echo, נבדוק שהוא שווה ל-0x5A (אופציונלי, תלוי בשבב)
        // אם לא קראנו כלום, נמשיך הלאה ונראה אם השבב מגיב לפקודה הבאה
        return true 
    }

    private fun readDaFromAssets(fileName: String): ByteArray {
        return context.assets.open(fileName).use { it.readBytes() }
    }

    private fun sendDa(daData: ByteArray): Boolean {
        val conn = connection ?: return false
        val epOut = endpointOut ?: return false
        val epIn = endpointIn ?: return false

        val length = daData.size

        // הכנת ה-Header של SEND_DA
        // [0xD7][Length (4 Bytes LE)][Length (4 Bytes LE)]
        val header = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        header.put(BromProtocol.CMD_SEND_DA.toByte())
        header.putInt(length)
        header.putInt(length)

        // שליחת ה-Header
        val headerBytes = header.array()
        var sent = conn.bulkTransfer(epOut, headerBytes, headerBytes.size, 5000)
        if (sent != headerBytes.size) return false

        // המתנה לתגובת 0xC0 (אופציונלי, תלוי בשבב, לפעמים השבב ממשיך מיד)
        val status = ByteArray(1)
        conn.bulkTransfer(epIn, status, 1, 2000)
        
        // שליחת הנתונים עצמם בצ'אנקים של 4KB
        var offset = 0
        val chunkSize = 4096
        while (offset < length) {
            val chunkLength = minOf(chunkSize, length - offset)
            val chunk = daData.copyOfRange(offset, offset + chunkLength)
            
            sent = conn.bulkTransfer(epOut, chunk, chunkLength, 5000)
            if (sent < 0) return false
            
            offset += sent
        }

        // קריאת סטטוס סופי (אמור להיות 0x5A)
        conn.bulkTransfer(epIn, status, 1, 5000)
        
        return true
    }

    private fun jumpDa(): Boolean {
        val conn = connection ?: return false
        val epOut = endpointOut ?: return false
        
        // הכנת פקודת JUMP_DA
        // [0xD5][Address (4 Bytes LE)]
        val buffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(BromProtocol.CMD_JUMP_DA.toByte())
        buffer.putInt(BromProtocol.DA_LOAD_ADDRESS)

        val bytes = buffer.array()
        val sent = conn.bulkTransfer(epOut, bytes, bytes.size, 2000)
        
        return sent == bytes.size
    }

    fun disconnect() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) {
            // התעלם
        }
    }
}
