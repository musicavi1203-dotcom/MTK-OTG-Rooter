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
            
            // שלב 1: שליחת ה-Payload (עקיפת האבטחה)
            val payloadBytes = readAsset("generic_patcher_payload.bin")
            sendData(BromProtocol.CMD_SEND_DA, payloadBytes, 0x201000, "Payload (Exploit)")
            
            // המתנה קצרה כדי שה-Payload יספיק לעקוף את האבטחה בזיכרון
            delay(500)
            
            // שלב 2: שליחת ה-DA האמיתי (עכשיו הוא אמור לעבור בלי שגיאת 0x40)
            val daBytes = readAsset("DA_MT6765.bin")
            sendData(BromProtocol.CMD_SEND_DA, daBytes, 0x201000, "DA (Download Agent)")

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

        val buf = byteArrayOf(BromProtocol.HANDSHAKE_BYTE.toByte())
        val sent = conn.bulkTransfer(epOut, buf, 1, 1000)
        if (sent != 1) return false

        val response = ByteArray(1)
        conn.bulkTransfer(epIn, response, 1, 1000)
        return true 
    }

    private fun readAsset(fileName: String): ByteArray {
        return context.assets.open(fileName).use { it.readBytes() }
    }

    // פונקציה מאוחדת לשליחה והרצה של קובץ (בין אם זה Payload או DA)
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
        if (statusRead > 0 && status[0] != 0xC0.toByte() && status[0] != 0x5A.toByte()) {
            // לפעמים ה-Payload מחזיר תגובות אחרות, לא נתעצבן על זה יותר מדי
            // אבל אם זה ה-DA והוא מחזיר משהו אחר, נדפיס
        }
        
        // 3. שליחת הנתונים עצמם
        var offset = 0
        val chunkSize = 4096
        while (offset < length) {
            val chunkLength = minOf(chunkSize, length - offset)
            val chunk = data.copyOfRange(offset, offset + chunkLength)
            
            val sent = conn.bulkTransfer(epOut, chunk, chunkLength, 3000)
            if (sent < 0) {
                throw IOException("שליחת נתוני ה-$dataName נכשלה במיקום $offset. המכשיר ניתק.")
            }
            offset += sent
        }

        // 4. קריאת סטטוס סופי
        val finalStatusRead = conn.bulkTransfer(epIn, status, 1, 3000)
        if (finalStatusRead > 0 && status[0] != 0x5A.toByte() && dataName.contains("DA")) {
             throw IOException("המכשיר דחה את ה-$dataName. סטטוס: ${status[0]}. ייתכן שה-Exploit לא עבד.")
        }

        // 5. שליחת פקודת JUMP_DA כדי להריץ את מה ששלחנו
        val jumpBuffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        jumpBuffer.put(BromProtocol.CMD_JUMP_DA.toByte())
        jumpBuffer.putInt(loadAddress)

        val jumpBytes = jumpBuffer.array()
        val jumpSent = conn.bulkTransfer(epOut, jumpBytes, jumpBytes.size, 2000)
        
        if (jumpSent != jumpBytes.size) {
            throw IOException("שליחת פקודת הרצה (Jump) עבור $dataName נכשלה.")
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
