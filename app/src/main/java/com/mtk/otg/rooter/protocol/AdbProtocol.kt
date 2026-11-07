package com.mtk.otg.rooter.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AdbProtocol {
    // קודי הפקודה (Commands) של פרוטוקול ADB - אלה בעצם 4 האותיות כפי שהן מיוצגות ב-Hex
    const val A_CNXN = 0x4e584e43 // "CNXN" - בקשת חיבור
    const val A_AUTH = 0x48545541 // "AUTH" - אימות מפתח
    const val A_OPEN = 0x4e45504f // "OPEN" - פתיחת ערוץ (למשל ל-shell)
    const val A_OKAY = 0x59414b4f // "OKAY" - אישור שהערוץ פתוח
    const val A_CLSE = 0x45534c43 // "CLSE" - סגירת ערוץ
    const val A_WRTE = 0x45545257 // "WRTE" - כתיבת נתונים לערוץ

    // גודל ההידר (Header) של ADB הוא תמיד 24 בתים
    const val HEADER_SIZE = 24
}

// מחלקה שמייצגת הודעת ADB בודדת
data class AdbMessage(
    val command: Int,
    val arg0: Int = 0,
    val arg1: Int = 0,
    val data: ByteArray = ByteArray(0)
) {
    // חישוב ה-Checksum: סכום כל הבתים (ללא סימן)
    val dataChecksum: Int
        get() {
            var sum = 0
            for (b in data) {
                sum += (b.toInt() and 0xFF)
            }
            return sum
        }

    // ה-Magic הוא הופכי סיביות (Bitwise NOT) של ה-Command. שיטה של ADB לוודא שלא חתכת את החבילה.
    val magic: Int
        get() = command.inv()

    // הופך את ההודעה למערך בתים (ByteArray) כדי שנוכל לשלוח אותה דרך ה-USB
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(AdbProtocol.HEADER_SIZE + data.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN) // ADB משתמש ב-Little-Endian!

        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(data.size)
        buffer.putInt(dataChecksum)
        buffer.putInt(magic)
        buffer.put(data)

        return buffer.array()
    }

    companion object {
        // פונקציה לקרוא מערך בתים שהגיע מהמכשיר ולהפוך אותו חזרה לאובייקט AdbMessage
        fun fromByteArray(bytes: ByteArray): AdbMessage? {
            if (bytes.size < AdbProtocol.HEADER_SIZE) return null

            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val dataLength = buffer.int
            val checksum = buffer.int
            val magic = buffer.int

            // בדיקה שהחבילה לא נחתכה ולא נשלחה שגוי
            if (command.inv() != magic) {
                return null 
            }

            val data = if (dataLength > 0 && buffer.remaining() >= dataLength) {
                val d = ByteArray(dataLength)
                buffer.get(d)
                d
            } else {
                ByteArray(0)
            }

            return AdbMessage(command, arg0, arg1, data)
        }
    }
}
