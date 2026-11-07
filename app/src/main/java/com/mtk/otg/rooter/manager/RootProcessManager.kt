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
        
        scope.launch {
            adbController = AdbController(usbManager, device)
            
            // הפונקציה connect() מחזירה Result
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
            // המכשיר הולך להתנתק, לכן אנחנו מתעלמים משגיאות שליחת הפקודה
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
