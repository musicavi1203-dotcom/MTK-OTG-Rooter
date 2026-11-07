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

    // אירועים שה-UI או ה-State Machine יוכלו להקשיב להם
    private val _usbEvents = MutableSharedFlow<UsbEvent>()
    val usbEvents: SharedFlow<UsbEvent> = _usbEvents.asSharedFlow()

    companion object {
        const val ACTION_USB_PERMISSION = "com.mtk.otg.rooter.USB_PERMISSION"
    }

    // ה-BroadcastReceiver שמקשיב לחיבור/ניתוק של USB
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

    // עוזר לשלוף את ההתקן מה-Intent (עובד גם בגרסאות אנדרואיד חדשות)
    private fun getUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    // רושם את המקלט (חייב לקרות ב-onResume של האפליקציה)
    fun register() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        
        // תמיכה בדרישות האבטחה של אנדרואיד 14+
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // מסיר את הרישום (חייב לקרות ב-onPause או ב-onDestroy)
    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // התעלם אם כבר הוסר
        }
    }

    // פונקציה לבקשת הרשאה מהמשתמש
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

    // פונקציה לקבלת רשימת המכשירים המחוברים כרגע
    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }
}

// מחלקת חותם (Sealed Class) שמייצגת את כל האירועים האפשריים ב-USB
sealed class UsbEvent {
    data class DeviceAttached(val device: UsbDevice) : UsbEvent()
    data class DeviceDetached(val device: UsbDevice) : UsbEvent()
    data class PermissionGranted(val device: UsbDevice) : UsbEvent()
    object PermissionDenied : UsbEvent()
}
