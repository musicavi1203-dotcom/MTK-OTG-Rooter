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
import kotlinx.coroutines.delay
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

    suspend fun loadAndRunDA(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!setupUsb()) throw IOException("שגיאה: לא נמצא ממשק USB מתאים ל-BROM")
            if (!handshake()) throw IOException("שגיאה: ה-Handshake נכשל. המכשיר לא במצב BROM.")
            
            // שלב 1: שליחת ה-Payload (עקיפת האבטחה) עם ניסיונות חוזרים
            val payloadBytes = readAsset("generic_patcher_payload.bin")
            sendDataWithRetry(BromProtocol.CMD_SEND_DA, payloadBytes, 0x201000, "Payload (Exploit)")
            
            // המתנה קריטית: נותנים ל-Payload זמן לרוץ ולעקוף את האבטחה
            delay(800)
            
            // שלב 2: שליחת ה-DA האמיתי
            val daBytes = readAsset("DA_MT6765.bin")
            sendDataWithRetry(BromProtocol.CMD_SEND_DA, daBytes, 0x201000, "DA (Download Agent)")

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun setupUsb(): Boolean {
        if (device.interfaceCount == 0) return false
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                    else outEp = ep
                }
            }
            if (inEp != null && outEp != null) {
                usbInterface = intf
                endpointIn = inEp
                endpointOut = outEp
                break
            }
        }
        if (endpointIn == null || endpointOut == null) return false
        connection = usbManager.openDevice(device) ?: return false
        return connection!!.claimInterface(usbInterface, true)
    }

    private fun handshake(): Boolean {
        val conn = connection ?: return false
        val epOut = endpointOut ?: return false
        val epIn = endpointIn ?: return false

        try {
            val buf = byteArrayOf(BromProtocol.HANDSHAKE_BYTE.toByte())
            val sent = conn.bulkTransfer(epOut, buf, 1, 1000)
            if (sent != 1) return false

            val response = ByteArray(1)
            conn.bulkTransfer(epIn, response, 1, 1000)
            return true 
        } catch (e: Exception) {
            return false
        }
    }

    private fun readAsset(fileName: String): ByteArray {
        return context.assets.open(fileName).use { it.readBytes() }
    }

    // פונקציה חדשה עם ניסיונות חוזרים (Retries) וניקוי תקיעות
    private fun sendDataWithRetry(cmd: Int, data: ByteArray, loadAddress: Int, dataName: String) {
        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                sendData(cmd, data, loadAddress, dataName)
                return // אם עבר בהצלחה, יוצאים מהלופ
            } catch (e: Exception) {
                lastException = e
                // ניסיון לנקות את ה-Endpoint שנתקע לפני שמנסים שוב
                clearHalt(endpointOut)
                clearHalt(endpointIn)
                Thread.sleep(200) // מחכים שהמכשיר יתאושש
            }
        }
        throw IOException("נכשל לאחר 3 ניסיונות: ${lastException?.message}")
    }

    private fun sendData(cmd: Int, data: ByteArray, loadAddress: Int, dataName: String) {
        val conn = connection ?: throw IOException("אין חיבור USB")
        val epOut = endpointOut ?: throw IOException("אין Endpoint של OUT")
        val epIn = endpointIn ?: throw IOException("אין Endpoint של IN")

        val length = data.size

        // 1. שליחת כותרת הפקודה
        val header = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        header.put(cmd.toByte())
        header.putInt(length)
        header.putInt(length)

        val headerBytes = header.array()
        val headerSent = conn.bulkTransfer(epOut, headerBytes, headerBytes.size, 3000)
        
        if (headerSent != headerBytes.size) {
            throw IOException("שליחת כותרת ה-$dataName נכשלה (קוד: $headerSent)")
        }

        // 2. בדיקת תגובת המכשיר (אם יש)
        val status = ByteArray(1)
        val statusRead = conn.bulkTransfer(epIn, status, 1, 1000)
        
        // 3. שליחת הנתונים עצמם - מקטינים את הצ'אנק ל-1024 ונותנים טיפה השהיה
        var offset = 0
        val chunkSize = 1024 // צ'אנק קטן יותר כדי לא להציף את ה-BROM
        while (offset < length) {
            val chunkLength = minOf(chunkSize, length - offset)
            val chunk = data.copyOfRange(offset, offset + chunkLength)
            
            val sent = conn.bulkTransfer(epOut, chunk, chunkLength, 3000)
            if (sent < 0) {
                throw IOException("שליחת נתוני ה-$dataName נכשלה במיקום $offset (קוד: $sent).")
            }
            offset += sent
            Thread.sleep(1) // השהיה של 1 מילי-שנייה כדי לתת ל-BROM לעכל
        }

        // 4. קריאת סטטוס סופי
        val finalStatusRead = conn.bulkTransfer(epIn, status, 1, 3000)
        if (finalStatusRead > 0 && status[0] != 0x5A.toByte() && dataName.contains("DA")) {
             throw IOException("המכשיר דחה את ה-$dataName. סטטוס: ${status[0]}.")
        }

        // 5. שליחת פקודת JUMP_DA
        val jumpBuffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        jumpBuffer.put(BromProtocol.CMD_JUMP_DA.toByte())
        jumpBuffer.putInt(loadAddress)

        val jumpBytes = jumpBuffer.array()
        val jumpSent = conn.bulkTransfer(epOut, jumpBytes, jumpBytes.size, 2000)
        
        if (jumpSent != jumpBytes.size) {
            throw IOException("שליחת פקודת הרצה (Jump) עבור $dataName נכשלה.")
        }
    }

    // פונקציה חדשה שמנקה את ה-Endpoint אם הוא נתקע (USB Stall)
    private fun clearHalt(endpoint: UsbEndpoint?) {
        val conn = connection ?: return
        val ep = endpoint ?: return
        try {
            conn.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD,
                0x01, // CLEAR_FEATURE
                0x00, // ENDPOINT_HALT
                ep.address,
                null, 0, 0
            )
        } catch (e: Exception) {
            // התעלם
        }
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
