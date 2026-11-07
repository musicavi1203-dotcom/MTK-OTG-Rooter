package com.mtk.otg.rooter.viewmodel

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mtk.otg.rooter.manager.RootProcessManager
import com.mtk.otg.rooter.manager.RootState
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val usbManager = application.getSystemService(Application.USB_SERVICE) as UsbManager
    
    // המוח של האפליקציה
    private val processManager = RootProcessManager(usbManager)

    // חשיפת המצב והלוגים ל-UI (המסך יקשיב לזה ויתעדכן אוטומטית)
    val state: StateFlow<RootState> = processManager.state
    val logs: StateFlow<List<String>> = processManager.logs

    // חיבור ראשוני למכשיר (ADB)
    fun connectAdb(device: UsbDevice) {
        processManager.connectAdb(device, viewModelScope)
    }

    // מעבר ממצב ADB למצב Fastboot
    fun rebootToFastboot() {
        processManager.rebootToFastboot()
    }

    // חיבור מחדש למכשיר לאחר שהוא עלה במצב Fastboot
    fun connectFastboot(device: UsbDevice) {
        processManager.connectFastboot(device)
    }

    // ביצוע הצריבה
    fun flashPartition(partitionName: String, data: ByteArray) {
        processManager.flashPartition(partitionName, data)
    }

    // איפוס כל התהליך למצב התחלתי
    fun reset() {
        processManager.reset()
    }
}
