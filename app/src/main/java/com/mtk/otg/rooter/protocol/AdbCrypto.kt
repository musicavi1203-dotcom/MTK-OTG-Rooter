package com.mtk.otg.rooter.protocol

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object AdbCrypto {
    private var privateKey: PrivateKey? = null
    private var publicKey: PublicKey? = null
    private var isInitialized = false

    // DigestInfo של SHA-1 - חובה כדי לחתום נכון על הטוקן של ADB
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    // טוען את המפתחות מהאחסון, או מייצר חדשים אם אין (פעם אחת)
    fun init(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences("adb_keys", Context.MODE_PRIVATE)
        val kf = KeyFactory.getInstance("RSA")
        val savedPriv = prefs.getString("priv", null)
        val savedPub = prefs.getString("pub", null)
        
        if (savedPriv != null && savedPub != null) {
            privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(savedPriv, Base64.NO_WRAP)))
            publicKey = kf.generatePublic(X509EncodedKeySpec(Base64.decode(savedPub, Base64.NO_WRAP)))
        } else {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val pair = gen.generateKeyPair()
            privateKey = pair.private
            publicKey = pair.public
            prefs.edit()
                .putString("priv", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .putString("pub", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
                .apply()
        }
        isInitialized = true
    }

    // חתימה על האתגר (הטוקן)
    fun signToken(token: ByteArray): ByteArray {
        val priv = privateKey ?: throw IllegalStateException("AdbCrypto not initialized")
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, priv)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    // המרת המפתח הציבורי לפורמט ש-ADB מצפה לו
    fun encodePublicKeyAdb(): ByteArray {
        val pub = publicKey as? RSAPublicKey ?: throw IllegalStateException("AdbCrypto not initialized")
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64).putInt(n0inv)
        buf.put(leBytes(n, 256))
        buf.put(leBytes(rr, 256))
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 adbtool\u0000".toByteArray()
    }

    private fun leBytes(x: BigInteger, size: Int): ByteArray {
        val be = x.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until size) {
            val idx = be.size - 1 - i
            if (idx >= 0) out[i] = be[idx]
        }
        return out
    }
}
