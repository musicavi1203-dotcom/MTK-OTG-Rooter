package com.mtk.otg.rooter

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtk.otg.rooter.ui.theme.MTKOTGRooterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MTKOTGRooterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RooterScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RooterScreen() {
    val context = LocalContext.current
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    // State לשמירת רשימת המכשירים המחוברים
    var connectedDevices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }

    // פונקציה לרענון רשימת המכשירים
    fun refreshDevices() {
        connectedDevices = usbManager.deviceList.values.toList()
    }

    // קריאה ראשונית בעליית המסך
    LaunchedEffect(Unit) {
        refreshDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MTK OTG Rooter") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "חבר את מכשיר ה-MTK באמצעות כבל OTG",
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Button(
                onClick = { refreshDevices() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("סרוק מכשירים מחוברים")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (connectedDevices.isEmpty()) {
                Text("לא נמצאו מכשירים מחוברים.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(connectedDevices) { device ->
                        DeviceCard(device = device, usbManager = usbManager, context = context)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: UsbDevice, usbManager: UsbManager, context: Context) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "שם: ${device.deviceName}", fontSize = 16.sp)
            Text(text = "Vendor ID: ${device.vendorId}", fontSize = 14.sp)
            Text(text = "Product ID: ${device.productId}", fontSize = 14.sp)
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val hasPermission = usbManager.hasPermission(device)
            
            if (hasPermission) {
                Text("סטטוס: יש הרשאה ✅", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(
                    onClick = {
                        // בקשת הרשאה ל-USB
                        val intent = Intent("com.mtk.otg.rooter.USB_PERMISSION")
                        intent.setPackage(context.packageName)
                        val pendingIntent = PendingIntent.getBroadcast(
                            context, 
                            0, 
                            intent, 
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        usbManager.requestPermission(device, pendingIntent)
                    }
                ) {
                    Text("בקש הרשאת USB")
                }
            }
        }
    }
}
