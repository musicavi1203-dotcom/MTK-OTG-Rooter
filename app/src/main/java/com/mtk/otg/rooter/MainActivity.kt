package com.mtk.otg.rooter

import android.hardware.usb.UsbDevice
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtk.otg.rooter.ui.theme.MTKOTGRooterTheme
import com.mtk.otg.rooter.usb.UsbConnectionManager
import com.mtk.otg.rooter.usb.UsbEvent

class MainActivity : ComponentActivity() {
    
    private lateinit var usbManager: UsbConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = UsbConnectionManager(this)
        
        setContent {
            MTKOTGRooterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RooterScreen(usbManager)
                }
            }
        }
    }

    // רושמים את המקלט כשהאפליקציה חוזרת לקדמה
    override fun onResume() {
        super.onResume()
        usbManager.register()
    }

    // מסירים את הרישום כשהאפליקציה עוברת לרקע (חוסך סוללה ומונע דליפות זיכרון)
    override fun onPause() {
        super.onPause()
        usbManager.unregister()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RooterScreen(usbManager: UsbConnectionManager) {
    // אתחול רשימת המכשירים המחוברים
    var connectedDevices by remember { mutableStateOf(usbManager.getConnectedDevices()) }
    
    // הודעת לוג אחרונה (למשל "מכשיר התחבר" או "התקבלה הרשאה")
    var logMessage by remember { mutableStateOf("ממתין לחיבור מכשיר...") }

    // האזנה לאירועי USB בזמן אמת
    LaunchedEffect(Unit) {
        usbManager.usbEvents.collect { event ->
            when (event) {
                is UsbEvent.DeviceAttached -> {
                    logMessage = "מכשיר חדש זוהה: ${event.device.deviceName}"
                    connectedDevices = usbManager.getConnectedDevices()
                }
                is UsbEvent.DeviceDetached -> {
                    logMessage = "מכשיר התנתק."
                    connectedDevices = usbManager.getConnectedDevices()
                }
                is UsbEvent.PermissionGranted -> {
                    logMessage = "התקבלה הרשאת USB בהצלחה!"
                }
                UsbEvent.PermissionDenied -> {
                    logMessage = "הרשאת USB נדחתה על ידי המשתמש."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MTK OTG Rooter") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // חיווי סטטוס דינמי
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = logMessage,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (connectedDevices.isEmpty()) {
                Text("אנא חבר את מכשיר ה-MTK באמצעות מתאם OTG.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(connectedDevices) { device ->
                        DeviceCard(device = device, usbManager = usbManager)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: UsbDevice, usbManager: UsbConnectionManager) {
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
            
            Button(
                onClick = { usbManager.requestPermission(device) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("בקש הרשאת גישה (USB Permission)")
            }
        }
    }
}
