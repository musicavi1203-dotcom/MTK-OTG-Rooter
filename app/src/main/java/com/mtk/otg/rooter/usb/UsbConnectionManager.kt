package com.mtk.otg.rooter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class UsbConnectionManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // התיקון: הוספנו extraBufferCapacity כדי שאירועים לא יאבדו אם ה-UI עסוק
    private val _usbEvents = MutableSharedFlow<UsbEvent>(extraBufferCapacity = 1)
    val usbEvents: SharedFlow<UsbEvent> = _usbEvents.asSharedFlow()

    companion object {
        const val ACTION_USB_PERMISSION = "com.mtk.otg.rooter.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = getUsbDevice(intent)
                    device?.let {
                        _usbEvents.tryEmit(UsbEvent.DeviceAttached(it))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = getUsbDevice(intent)
                    device?.let {
                        _usbEvents.tryEmit(UsbEvent.DeviceDetached(it))
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = getUsbDevice(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) {
                        _usbEvents.tryEmit(UsbEvent.PermissionGranted(device))
                    } else {
                        _usbEvents.tryEmit(UsbEvent.PermissionDenied)
                    }
                }
            }
        }
    }

    private fun getUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // התעלם אם כבר הוסר
        }
    }

    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            _usbEvents.tryEmit(UsbEvent.PermissionGranted(device))
            return
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        usbManager.requestPermission(device, pendingIntent)
    }

    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }
}

sealed class UsbEvent {
    data class DeviceAttached(val device: UsbDevice) : UsbEvent()
    data class DeviceDetached(val device: UsbDevice) : UsbEvent()
    data class PermissionGranted(val device: UsbDevice) : UsbEvent()
    object PermissionDenied : UsbEvent()
}
