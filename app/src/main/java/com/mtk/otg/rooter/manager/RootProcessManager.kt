package com.mtk.otg.rooter.manager

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.mtk.otg.rooter.adb.AdbController
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
    FLASHING,
    SUCCESS,
    ERROR
}

class RootProcessManager(
    private val usbManager: UsbManager
) {
    private var adbController: AdbController? = null
    private var fastbootController: FastbootController? = null

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
        
        adbController = AdbController(usbManager, device)
        
        // מקשיבים למצב האמיתי של החיבור ל-ADB
        scope.launch {
            adbController?.connectionState?.collect { status ->
                addLog("ADB Status: $status")
                
                when {
                    status.contains("מחובר ומאומת") -> {
                        _state.value = RootState.ADB_READY
                        addLog("החיבור ל-ADB הושלם בהצלחה. מוכן לשלב הבא.")
                    }
                    status.contains("שגיאה") -> {
                        _state.value = RootState.ERROR
                    }
                }
            }
        }
        
        adbController?.connect(scope)
    }

    fun rebootToFastboot() {
        if (adbController == null) {
            addLog("שגיאה: אין חיבור ADB")
            _state.value = RootState.ERROR
            return
        }

        _state.value = RootState.REBOOTING_TO_FASTBOOT
        addLog("שולח פקודת reboot bootloader...")
        
        adbController?.executeShellCommand("reboot bootloader")
        
        _state.value = RootState.WAITING_FOR_FASTBOOT
        addLog("ממתין שהמכשיר יעלה מחדש במצב Fastboot... אנא המתן.")
    }

    fun connectFastboot(device: UsbDevice) {
        addLog("מכשיר זוהה מחדש במצב Fastboot. מתחבר...")
        fastbootController = FastbootController(usbManager, device)
        fastbootController?.connect()
        
        // בדיקה אם החיבור הצליח (לפי ה-State של ה-Controller)
        if (fastbootController?.state?.value?.contains("מחובר") == true) {
            _state.value = RootState.FASTBOOT_CONNECTED
            addLog("Fastboot מחובר ומוכן לצריבה.")
        } else {
            _state.value = RootState.ERROR
            addLog("שגיאה בחיבור ל-Fastboot. ודא שהמכשיר במצב Fastboot.")
        }
    }

    fun flashPartition(partitionName: String, fileData: ByteArray) {
        if (fastbootController == null) {
            addLog("שגיאה: אין חיבור Fastboot")
            return
        }

        _state.value = RootState.FLASHING
        addLog("מתחיל צריבת מחיצת $partitionName...")

        try {
            val downloadCmd = "download:${fileData.size.toString(16).padStart(8, '0')}"
            val downloadResponse = fastbootController?.sendCommand(downloadCmd)
            addLog("תגובת download: $downloadResponse")

            if (downloadResponse?.startsWith("DATA") == true) {
                // (בהמשך נחבר את שליחת הנתונים האמיתית כאן)
                addLog("המכשיר מוכן לקבלת הנתונים. (פונקציית צריבה אמיתית תתווסף בהמשך)")
                _state.value = RootState.ERROR 
            } else {
                _state.value = RootState.ERROR
                addLog("המכשיר סירב לקבל את הנתונים (download failed).")
            }
        } catch (e: Exception) {
            _state.value = RootState.ERROR
            addLog("שגיאה בצריבה: ${e.message}")
        }
    }

    fun reset() {
        adbController?.disconnect()
        fastbootController?.disconnect()
        adbController = null
        fastbootController = null
        _state.value = RootState.IDLE
        _logs.value = emptyList()
    }
}
