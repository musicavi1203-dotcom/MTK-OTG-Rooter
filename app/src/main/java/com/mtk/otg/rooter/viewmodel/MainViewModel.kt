package com.mtk.otg.rooter.viewmodel

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mtk.otg.rooter.manager.RootProcessManager
import com.mtk.otg.rooter.manager.RootState
import com.mtk.otg.rooter.protocol.AdbCrypto
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    // שינינו את האתחול כך שיעביר את ה-Application (Context) למנהל התהליכים
    private val processManager = RootProcessManager(application)

    init {
        AdbCrypto.init(application)
    }

    val state: StateFlow<RootState> = processManager.state
    val logs: StateFlow<List<String>> = processManager.logs

    fun connectAdb(device: UsbDevice) {
        processManager.connectAdb(device, viewModelScope)
    }

    fun rebootToFastboot() {
        processManager.rebootToFastboot(viewModelScope)
    }

    fun connectFastboot(device: UsbDevice) {
        processManager.connectFastboot(device)
    }

    // הפונקציה החדשה שמפעילה את ה-BROM
    fun loadBromDa(device: UsbDevice) {
        processManager.loadBromDa(device, viewModelScope)
    }

    fun flashPartition(partitionName: String, data: ByteArray) {
        processManager.flashPartition(partitionName, data)
    }

    fun reset() {
        processManager.reset()
    }

    fun addLog(message: String) {
        processManager.addLog(message)
    }
}
