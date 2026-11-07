package com.mtk.otg.rooter

import android.hardware.usb.UsbDevice
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
import com.mtk.otg.rooter.usb.UsbConnectionManager
import com.mtk.otg.rooter.usb.UsbEvent
import com.mtk.otg.rooter.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    
    private lateinit var usbManager: UsbConnectionManager
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = UsbConnectionManager(this)
        
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

    override fun onResume() {
        super.onResume()
        usbManager.register()
    }

    override fun onPause() {
        super.onPause()
        usbManager.unregister()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RooterScreen(usbManager: UsbConnectionManager, viewModel: MainViewModel) {
    // אוסף את המצב והלוגים מה-ViewModel
    val currentState by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    
    // רשימת מכשירים מחוברים (נשלפת מה-UsbManager)
    var connectedDevices by remember { mutableStateOf(usbManager.getConnectedDevices()) }

    // האזנה לאירועי USB (התחברות, התנתקות, הרשאה)
    LaunchedEffect(Unit) {
        usbManager.usbEvents.collect { event ->
            when (event) {
                is UsbEvent.DeviceAttached -> {
                    connectedDevices = usbManager.getConnectedDevices()
                }
                is UsbEvent.DeviceDetached -> {
                    connectedDevices = usbManager.getConnectedDevices()
                }
                is UsbEvent.PermissionGranted -> {
                    // ברגע שיש הרשאה, מחליטים מה לעשות לפי המצב
                    if (currentState == RootState.IDLE) {
                        viewModel.connectAdb(event.device)
                    } else if (currentState == RootState.WAITING_FOR_FASTBOOT) {
                        viewModel.connectFastboot(event.device)
                    }
                }
                UsbEvent.PermissionDenied -> {
                    // אפשר להוסיף פה התראה למשתמש
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
                .verticalScroll(rememberScrollState()), // מאפשר גלילה של כל המסך
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // תצוגת מצב נוכחי
            Text(
                text = "מצב נוכחי: ${currentState.name}",
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // אזור הכפתורים (משתנה לפי המצב)
            when (currentState) {
                RootState.IDLE -> {
                    if (connectedDevices.isEmpty()) {
                        Text("חבר את מכשיר ה-MTK באמצעות כבל OTG.")
                    } else {
                        connectedDevices.forEach { device ->
                            Button(
                                onClick = { usbManager.requestPermission(device) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text("בקש הרשאה והתחבר ל-${device.deviceName}")
                            }
                        }
                    }
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
                            // קריאה לדוגמה לצריבה (בהמשך נחבר לשאיבת קובץ אמיתית)
                            val dummyData = ByteArray(1024) // דמי
                            viewModel.flashPartition("boot", dummyData) 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("צרוב boot.img (דמו)")
                    }
                }
                else -> {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // חלון לוגים (טרמינל)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp),
                    reverseLayout = true // הודעות חדשות למטה
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
                onClick = { viewModel.reset() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("איפוס")
            }
        }
    }
}
