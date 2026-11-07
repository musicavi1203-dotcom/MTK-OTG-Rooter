package com.mtk.otg.rooter.manager

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.mtk.otg.rooter.adb.AdbController
import com.mtk.otg.rooter.fastboot.FastbootController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// מצבי התהליך האפשריים
enum class RootState {
    IDLE,                   // ממתין
    ADB_CONNECTING,         // מתחבר ל-ADB
    ADB_READY,              // מחובר ל-ADB ומאומת
    REBOOTING_TO_FASTBOOT,  // שולח פקודת ריבוט
    WAITING_FOR_FASTBOOT,   // ממתין שהמכשיר יעלה מחדש במצב Fastboot
    FASTBOOT_CONNECTED,     // מחובר ל-Fastboot
    FLASHING,               // בצריבה
    SUCCESS,                // הצליח!
    ERROR                   // נכשל
}

class RootProcessManager(
    private val usbManager: UsbManager
) {
    private var adbController: AdbController? = null
    private var fastbootController: FastbootController? = null

    // מצב נוכחי של התהליך
    private val _state = MutableStateFlow(RootState.IDLE)
    val state: StateFlow<RootState> = _state.asStateFlow()

    // לוגים להיסטוריה
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // פונקציה להוספת לוג למסך
    fun addLog(message: String) {
        _logs.value = _logs.value + message
    }

    // שלב 1: התחברות ל-ADB
    fun connectAdb(device: UsbDevice, scope: CoroutineScope) {
        _state.value = RootState.ADB_CONNECTING
        addLog("מתחבר ל-ADB...")
        
        adbController = AdbController(usbManager, device)
        adbController?.connect(scope)
        
        // כאן (במציאות) נצטרך להקשיב ל-connectionState של ה-Controller
        // כדי לעדכן את ה-State שלנו ל-ADB_READY כשהוא מתחבר.
        // לעת עתה, נדמה את זה (Simulation):
        _state.value = RootState.ADB_READY
        addLog("ADB מוכן. מחכה לפקודה.")
    }

    // שלב 2: ריבוט ל-Fastboot
    fun rebootToFastboot() {
        if (adbController == null) {
            addLog("שגיאה: אין חיבור ADB")
            _state.value = RootState.ERROR
            return
        }

        _state.value = RootState.REBOOTING_TO_FASTBOOT
        addLog("שולח פקודת reboot bootloader...")
        
        // שליחת הפקודה בפועל דרך ה-ADB Controller
        adbController?.executeShellCommand("reboot bootloader")
        
        _state.value = RootState.WAITING_FOR_FASTBOOT
        addLog("ממתין שהמכשיר יעלה מחדש במצב Fastboot...")
    }

    // שלב 3: התחברות ל-Fastboot (אחרי שהמכשיר עלה מחדש)
    fun connectFastboot(device: UsbDevice) {
        addLog("מכשיר זוהה מחדש במצב Fastboot. מתחבר...")
        fastbootController = FastbootController(usbManager, device)
        fastbootController?.connect()
        
        if (fastbootController?.state?.value?.contains("מחובר") == true) {
            _state.value = RootState.FASTBOOT_CONNECTED
            addLog("Fastboot מחובר ומוכן לצריבה.")
        } else {
            _state.value = RootState.ERROR
            addLog("שגיאה בחיבור ל-Fastboot.")
        }
    }

    // שלב 4: צריבת מחיצה (למשל boot.img)
    fun flashPartition(partitionName: String, fileData: ByteArray) {
        if (fastbootController == null) {
            addLog("שגיאה: אין חיבור Fastboot")
            return
        }

        _state.value = RootState.FLASHING
        addLog("מתחיל צריבת מחיצת $partitionName...")

        try {
            // 1. שליחת פקודת download
            val downloadCmd = "download:${fileData.size.toString(16).padStart(8, '0')}"
            val downloadResponse = fastbootController?.sendCommand(downloadCmd)
            addLog("תגובת download: $downloadResponse")

            if (downloadResponse?.startsWith("DATA") == true) {
                // 2. שליחת הנתונים בפועל (צריך לממש את זה ב-FastbootController, בינתיים מדמה)
                // fastbootController?.sendData(fileData)
                // fastbootController?.readResponse() // קריאת OKAY
                
                // 3. שליחת פקודת flash
                val flashCmd = "flash:$partitionName"
                val flashResponse = fastbootController?.sendCommand(flashCmd)
                addLog("תגובת flash: $flashResponse")
                
                if (flashResponse?.startsWith("OKAY") == true) {
                    _state.value = RootState.SUCCESS
                    addLog("הצריבה הושלמה בהצלחה! 🎉")
                } else {
                    _state.value = RootState.ERROR
                    addLog("הצריבה נכשלה.")
                }
            } else {
                _state.value = RootState.ERROR
                addLog("המכשיר סירב לקבל את הנתונים (download failed).")
            }
        } catch (e: Exception) {
            _state.value = RootState.ERROR
            addLog("שגיאה בצריבה: ${e.message}")
        }
    }

    // איפוס התהליך
    fun reset() {
        adbController?.disconnect()
        fastbootController?.disconnect()
        adbController = null
        fastbootController = null
        _state.value = RootState.IDLE
        _logs.value = emptyList()
    }
}
