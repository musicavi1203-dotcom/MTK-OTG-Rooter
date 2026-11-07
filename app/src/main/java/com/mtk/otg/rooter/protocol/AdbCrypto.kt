package com.mtk.otg.rooter.protocol

import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

object AdbCrypto {

    // יצירת זוג מפתחות RSA (2048 ביט - חובה ל-ADB מודרני)
    fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        return keyGen.generateKeyPair()
    }

    // חתימה על האתגר (Token) ששלח המכשיר
    fun signToken(token: ByteArray, keyPair: KeyPair): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA") // ADB משתמש ב-SHA1
        signature.initSign(keyPair.private)
        signature.update(token)
        return signature.sign()
    }

    // הפיכת המפתח הציבורי לפורמט ADB (Base64 של בלוב בינארי)
    fun encodePublicKey(publicKey: RSAPublicKey, username: String = "mtk-otg-rooter@android"): String {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        
        // ADB דורש גודל מודולוס של 256 בתים (2048 ביט)
        val n = toLittleEndian(modulus, 256)
        
        // חישוב n0inv (ההופכי המודולרי של 32 הביטים התחתונים של n)
        val n0 = modulus.and(BigInteger("FFFFFFFF", 16)).toLong()
        val n0inv = BigInteger.valueOf(n0).modInverse(BigInteger("100000000", 16)).toLong()

        // חישוב rr (R^2 mod n, כש-R הוא 2^2048)
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(modulus)
        val rrBytes = toLittleEndian(rr, 256)

        // הרכבת הבלוב הבינארי שהמכשיר מצפה לו
        val buffer = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4 + username.length + 1)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(2048) // אורך המפתח בביטים
        buffer.putInt(n0inv.toInt()) // n0inv
        buffer.put(n) // n (modulus)
        buffer.put(rrBytes) // rr
        buffer.putInt(65537) // מעריך (Exponent) - תמיד 65537
        buffer.put(username.toByteArray())
        buffer.put(0) // תו Null בסוף

        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    // פונקציית עזר להמרת BigInteger למערך בתים ב-Little-Endian בגודל קבוע
    private fun toLittleEndian(value: BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        val result = ByteArray(length)
        
        // BigInteger מוסיף לפעמים בייט 0 בהתחלה אם המספר חיובי, צריך לטפל בזה
        val startIndex = if (bytes.size > length) bytes.size - length else 0
        val actualLength = bytes.size - startIndex
        
        // העתקה והיפוך (כי BigInteger הוא Big-Endian ו-ADB רוצה Little-Endian)
        for (i in 0 until actualLength) {
            result[i] = bytes[bytes.size - 1 - i]
        }
        return result
    }
}
