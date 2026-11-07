package com.mtk.otg.rooter.protocol

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbConnection(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface
) {
    private val endpointIn: UsbEndpoint
    private val endpointOut: UsbEndpoint
    private var buffer = ByteArray(0)
    private var nextLocalId = 1

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257

        const val ADB_VERSION = 0x01000000
        const val MAX_DATA = 4096 // התיקון הקריטי: 4KB בלבד
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLIC_KEY = 3
        
        const val TIMEOUT_NORMAL = 15000
        const val TIMEOUT_USER_ACCEPT = 120000 // המתנה ארוכה לאישור המשתמש
        const val WRITE_TIMEOUT = 5000
        const val READ_CHUNK = 16384
        const val MAX_WRITE_CHUNK = 16384
    }

    init {
        connection.claimInterface(usbInterface, true)
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        endpointIn = inEp ?: throw IOException("no bulk IN endpoint")
        endpointOut = outEp ?: throw IOException("no bulk OUT endpoint")
    }

    fun handshake(signToken: (ByteArray) -> ByteArray, getPublicKey: () -> ByteArray) {
        send(A_CNXN, ADB_VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var msg = recv(TIMEOUT_NORMAL)
        var attempts = 0
        
        while (msg.command == A_AUTH) {
            attempts++
            when (attempts) {
                1 -> send(A_AUTH, AUTH_SIGNATURE, 0, signToken(msg.data))
                2 -> send(A_AUTH, AUTH_PUBLIC_KEY, 0, getPublicKey())
                else -> throw IOException("ADB auth rejected")
            }
            msg = recv(TIMEOUT_USER_ACCEPT)
        }
        if (msg.command != A_CNXN) throw IOException("unexpected reply to CNXN")
    }

    fun shell(command: String): String = open("shell:$command")

    fun open(service: String): String {
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, "$service\u0000".toByteArray())
        var msg = recvFor(localId, TIMEOUT_NORMAL)
        
        if (msg.command == A_CLSE) throw IOException("device refused $service")
        if (msg.command != A_OKAY) throw IOException("unexpected reply to OPEN")
        
        val remoteId = msg.arg0
        val out = ByteArrayOutputStream()
        var done = false
        
        while (!done) {
            msg = recvFor(localId, TIMEOUT_NORMAL)
            when (msg.command) {
                A_WRTE -> {
                    out.write(msg.data)
                    send(A_OKAY, localId, remoteId)
                }
                A_CLSE -> done = true
            }
        }
        runCatching { send(A_CLSE, localId, remoteId) }
        return String(out.toByteArray(), Charsets.UTF_8).trim()
    }

    fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    private fun recvFor(localId: Int, timeout: Int): AdbMessage {
        var msg = recv(timeout)
        while (msg.arg1 != localId) msg = recv(timeout)
        return msg
    }

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd).putInt(arg0).putInt(arg1)
            .putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xFF })
            .putInt(cmd.inv())
        
        write(header.array())
        if (data.isNotEmpty()) write(data)
    }

    private fun recv(timeout: Int): AdbMessage {
        val h = ByteBuffer.wrap(read(24, timeout)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val arg0 = h.int
        val arg1 = h.int
        val len = h.int
        val data = if (len > 0) read(len, timeout) else ByteArray(0)
        return AdbMessage(cmd, arg0, arg1, data)
    }

    private fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = data.copyOfRange(off, minOf(data.size, off + MAX_WRITE_CHUNK))
            val n = connection.bulkTransfer(endpointOut, chunk, chunk.size, WRITE_TIMEOUT)
            if (n < 0) throw IOException("USB write failed")
            off += chunk.size
        }
    }

    private fun read(len: Int, timeout: Int): ByteArray {
        while (buffer.size < len) {
            val tmp = ByteArray(READ_CHUNK)
            val n = connection.bulkTransfer(endpointIn, tmp, tmp.size, timeout)
            if (n < 0) throw IOException("USB read failed or timed out")
            buffer += tmp.copyOf(n)
        }
        val out = buffer.copyOf(len)
        buffer = buffer.copyOfRange(len, buffer.size)
        return out
    }
}

data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray
)
