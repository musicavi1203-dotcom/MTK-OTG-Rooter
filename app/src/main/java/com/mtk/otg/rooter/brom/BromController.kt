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

    suspend fun loadAndRunDA(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!setupUsb()) throw IOException("שגיאה: לא נמצא ממשק USB מתאים ל-BROM")
            if (!handshake()) throw IOException("שגיאה: ה-Handshake נכשל. המכשיר לא במצב BROM.")
            
            val daBytes = readDaFromAssets("DA_MT6765.bin")
            
            // שליחת ה-DA עם בדיקות שגיאה מפורטות
            sendDa(daBytes)
            
            if (!jumpDa()) throw IOException("שגיאה: הפעלת ה-DA (Jump) נכשלה.")

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

    private fun readDaFromAssets(fileName: String): ByteArray {
        return context.assets.open(fileName).use { it.readBytes() }
    }

    // פונקציה חדשה שזורקת שגיאות מפורטות
    private fun sendDa(daData: ByteArray) {
        val conn = connection ?: throw IOException("אין חיבור USB")
        val epOut = endpointOut ?: throw IOException("אין Endpoint של OUT")
        val epIn = endpointIn ?: throw IOException("אין Endpoint של IN")

        val length = daData.size

        val header = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        header.put(BromProtocol.CMD_SEND_DA.toByte())
        header.putInt(length)
        header.putInt(length)

        val headerBytes = header.array()
        val headerSent = conn.bulkTransfer(epOut, headerBytes, headerBytes.size, 3000)
        
        if (headerSent != headerBytes.size) {
            throw IOException("שליחת כותרת נכשלה (קוד: $headerSent)")
        }

        val status = ByteArray(1)
        val statusRead = conn.bulkTransfer(epIn, status, 1, 1000)
        if (statusRead > 0 && status[0] != 0xC0.toByte()) {
            throw IOException("תגובת מכשיר שגויה לכותרת: ${status[0]}")
        }
        
        var offset = 0
        val chunkSize = 4096
        while (offset < length) {
            val chunkLength = minOf(chunkSize, length - offset)
            val chunk = daData.copyOfRange(offset, offset + chunkLength)
            
            val sent = conn.bulkTransfer(epOut, chunk, chunkLength, 3000)
            if (sent < 0) {
                throw IOException("שליחת נתונים נכשלה במיקום $offset (קוד: $sent). המכשיר ניתק.")
            }
            offset += sent
        }

        val finalStatusRead = conn.bulkTransfer(epIn, status, 1, 3000)
        if (finalStatusRead > 0 && status[0] != 0x5A.toByte()) {
            throw IOException("המכשיר דחה את ה-DA. סטטוס: ${status[0]}. ייתכן שה-Bootloader מאובטח (SLA/DAA).")
        }
    }

    private fun jumpDa(): Boolean {
        val conn = connection ?: return false
        val epOut = endpointOut ?: return false
        
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
