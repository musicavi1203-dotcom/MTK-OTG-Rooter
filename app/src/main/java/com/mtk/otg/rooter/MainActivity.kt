package com.mtk.otg.rooter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtk.otg.rooter.manager.RootState
import com.mtk.otg.rooter.ui.theme.MTKOTGRooterTheme
import com.mtk.otg.rooter.usb.DeviceMode
import com.mtk.otg.rooter.usb.UsbConnectionManager
import com.mtk.otg.rooter.usb.UsbEvent
import com.mtk.otg.rooter.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    
    private lateinit var usbManager: UsbConnectionManager
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = UsbConnectionManager(this)
        usbManager.register()
        
        setContent {
            MTKOTGRooterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RooterScreen(usbManager, viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.unregister()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RooterScreen(usbManager: UsbConnectionManager, viewModel: MainViewModel) {
    val currentState by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    
    var connectedDevices by remember { mutableStateOf(usbManager.getConnectedDevices()) }

    LaunchedEffect(Unit) {
        usbManager.usbEvents.collect { event ->
            when (event) {
                is UsbEvent.DeviceAttached -> {
                    connectedDevices = usbManager.getConnectedDevices()
                    viewModel.addLog("התקבל אירוע: מכשיר USB התחבר.")
                }
                is UsbEvent.DeviceDetached -> {
                    connectedDevices = usbManager.getConnectedDevices()
                    viewModel.addLog("התקבל אירוע: מכשיר USB התנתק.")
                }
                is UsbEvent.PermissionGranted -> {
                    viewModel.addLog("התקבל אירוע: הרשאת USB אושרה!")
                    
                    // מנתבים את הפעולה לפי סוג המכשיר שזוהה
                    when (usbManager.getDeviceMode(event.device)) {
                        DeviceMode.ADB -> viewModel.connectAdb(event.device)
                        DeviceMode.FASTBOOT -> viewModel.connectFastboot(event.device)
                        DeviceMode.BROM -> viewModel.loadBromDa(event.device)
                        DeviceMode.UNKNOWN -> viewModel.addLog("שגיאה: מכשיר לא מזוהה.")
                    }
                }
                UsbEvent.PermissionDenied -> {
                    viewModel.addLog("התקבל אירוע: הרשאת USB נדחתה.")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "מצב נוכחי: ${currentState.name}",
                fontSize = 18.sp,
                color = when (currentState) {
                    RootState.ERROR -> MaterialTheme.colorScheme.error
                    RootState.ADB_READY, RootState.FASTBOOT_CONNECTED, RootState.BROM_READY, RootState.SUCCESS -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onBackground
                },
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when (currentState) {
                RootState.IDLE -> {
                    if (connectedDevices.isEmpty()) {
                        Text("חבר את מכשיר ה-MTK באמצעות כבל OTG.")
                    } else {
                        connectedDevices.forEach { device ->
                            val mode = usbManager.getDeviceMode(device)
                            val buttonText = when (mode) {
                                DeviceMode.ADB -> "בקש הרשאה והתחבר ל-ADB (${device.deviceName})"
                                DeviceMode.FASTBOOT -> "בקש הרשאה והתחבר ל-Fastboot"
                                DeviceMode.BROM -> "בקש הרשאה וטען DA (מצב BROM)"
                                DeviceMode.UNKNOWN -> "מכשיר לא מזוהה - בקש הרשאה בכל זאת"
                            }
                            
                            Button(
                                onClick = { 
                                    viewModel.addLog("מבקש הרשאת USB עבור $mode...")
                                    usbManager.requestPermission(device) 
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text(buttonText)
                            }
                        }
                    }
                }
                RootState.ADB_CONNECTING, RootState.BROM_LOADING -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    Text(if (currentState == RootState.ADB_CONNECTING) "מנסה להתחבר ל-ADB..." else "טוען את קובץ ה-DA (BROM)...")
                }
                RootState.ADB_READY -> {
                    Button(
                        onClick = { viewModel.rebootToFastboot() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("שלב הבא: ריבוט ל-Fastboot")
                    }
                }
                RootState.WAITING_FOR_FASTBOOT -> {
                    Text("המכשיר מאתחל... אנא המתן לחיבור מחדש במצב Fastboot.")
                    if (connectedDevices.isNotEmpty()) {
                        Button(
                            onClick = { usbManager.requestPermission(connectedDevices.first()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("זהה מחדש והתחבר ל-Fastboot")
                        }
                    }
                }
                RootState.FASTBOOT_CONNECTED -> {
                    Button(
                        onClick = { 
                            val dummyData = ByteArray(1024) 
                            viewModel.flashPartition("boot", dummyData) 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("צרוב boot.img (דמו)")
                    }
                }
                RootState.BROM_READY -> {
                    Text("ה-DA נטען בהצלחה! המכשיר מוכן לשאיבת מחיצות.")
                    // כאן בהמשך נוסיף כפתור לשאיבת המחיצות
                    Button(
                        onClick = { viewModel.addLog("פונקציית שאיבת מחיצות תתווסף בקרוב...") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("שאוב מחיצת boot (בקרוב)")
                    }
                }
                RootState.ERROR -> {
                    Text("אירעה שגיאה. אנא אפס ונסה שוב.", color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp),
                    reverseLayout = true 
                ) {
                    items(logs.reversed()) { log ->
                        Text(
                            text = log,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { 
                    viewModel.reset()
                    connectedDevices = usbManager.getConnectedDevices()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("איפוס")
            }
        }
    }
}
