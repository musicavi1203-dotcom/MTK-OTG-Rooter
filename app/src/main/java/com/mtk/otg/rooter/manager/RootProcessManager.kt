package com.mtk.otg.rooter.manager

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.mtk.otg.rooter.adb.AdbController
import com.mtk.otg.rooter.brom.BromController
import com.mtk.otg.rooter.fastboot.FastbootController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RootState {
    IDLE,
    ADB_CONNECTING,
    ADB_READY,
    REBOOTING_TO_FASTBOOT,
    WAITING_FOR_FASTBOOT,
    FASTBOOT_CONNECTED,
    REBOOTING_TO_BROM, // מצב חדש
    WAITING_FOR_BROM,  // מצב חדש
    BROM_LOADING,
    BROM_READY,
    FLASHING,
    SUCCESS,
    ERROR
}

class RootProcessManager(
    private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var adbController: AdbController? = null
    private var fastbootController: FastbootController? = null
    private var bromController: BromController? = null

    private val _state = MutableStateFlow(RootState.IDLE)
    val state: StateFlow<RootState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun addLog(message: String) {
        _logs.value = _logs.value + message
    }

    fun connectAdb(device: UsbDevice, scope: CoroutineScope) {
        if (_state.value == RootState.ADB_CONNECTING || _state.value == RootState.ADB_READY) return

        _state.value = RootState.ADB_CONNECTING
        addLog("מתחבר ל-ADB...")
        
        scope.launch {
            adbController = AdbController(usbManager, device)
            val result = adbController?.connect()
            
            if (result?.isSuccess == true) {
                _state.value = RootState.ADB_READY
                addLog("החיבור ל-ADB הושלם בהצלחה. מוכן לשלב הבא.")
            } else {
                val errorMsg = result?.exceptionOrNull()?.message ?: "שגיאה לא ידועה בחיבור"
                _state.value = RootState.ERROR
                addLog("שגיאה בחיבור ל-ADB: $errorMsg")
            }
        }
    }

    fun rebootToFastboot(scope: CoroutineScope) {
        if (adbController == null) {
            addLog("שגיאה: אין חיבור ADB")
            _state.value = RootState.ERROR
            return
        }

        _state.value = RootState.REBOOTING_TO_FASTBOOT
        addLog("שולח פקודת reboot bootloader...")
        
        scope.launch {
            adbController?.executeShellCommand("reboot bootloader")
            adbController?.disconnect()
            adbController = null
            
            _state.value = RootState.WAITING_FOR_FASTBOOT
            addLog("המכשיר מאתחל... אנא המתן לחיבור מחדש במצב Fastboot.")
        }
    }

    fun connectFastboot(device: UsbDevice) {
        addLog("מכשיר זוהה מחדש במצב Fastboot. מתחבר...")
        fastbootController = FastbootController(usbManager, device)
        fastbootController?.connect()
        
        if (fastbootController?.state?.value?.contains("מחובר") == true) {
            _state.value = RootState.FASTBOOT_CONNECTED
            addLog("Fastboot מחובר ומוכן.")
        } else {
            _state.value = RootState.ERROR
            addLog("שגיאה בחיבור ל-Fastboot. ודא שהמכשיר במצב Fastboot.")
        }
    }

    // פונקציה חדשה למעבר מ-Fastboot ל-BROM
    fun rebootToBrom(scope: CoroutineScope) {
        if (fastbootController == null) {
            addLog("שגיאה: אין חיבור Fastboot")
            _state.value = RootState.ERROR
            return
        }

        _state.value = RootState.REBOOTING_TO_BROM
        addLog("שולח פקודת oem reboot-brom...")
        
        scope.launch {
            // שליחת הפקודה למכשיר
            val response = fastbootController?.sendCommand("oem reboot-brom")
            addLog("תגובת המכשיר: $response")
            
            fastbootController?.disconnect()
            fastbootController = null
            
            _state.value = RootState.WAITING_FOR_BROM
            addLog("המכשיר מאתחל למצב BROM... אנא המתן לחיבור מחדש.")
        }
    }

    fun loadBromDa(device: UsbDevice, scope: CoroutineScope) {
        _state.value = RootState.BROM_LOADING
        addLog("מתחבר למצב BROM ומכין את ה-DA...")

        scope.launch {
            bromController = BromController(context, usbManager, device)
            val result = bromController?.loadAndRunDA()

            if (result?.isSuccess == true) {
                _state.value = RootState.BROM_READY
                addLog("ה-DA נטען והורץ בהצלחה! מוכן לשאיבת מחיצות.")
            } else {
                val errorMsg = result?.exceptionOrNull()?.message ?: "שגיאה לא ידועה בטעינת DA"
                _state.value = RootState.ERROR
                addLog("שגיאה בטעינת ה-BROM: $errorMsg")
            }
        }
    }

    fun flashPartition(partitionName: String, fileData: ByteArray) {
        // ... (נשאיר את זה כמו שהוא בינתיים)
    }

    fun reset() {
        adbController?.disconnect()
        fastbootController?.disconnect()
        bromController?.disconnect()
        adbController = null
        fastbootController = null
        bromController = null
        _state.value = RootState.IDLE
        _logs.value = emptyList()
    }
}
