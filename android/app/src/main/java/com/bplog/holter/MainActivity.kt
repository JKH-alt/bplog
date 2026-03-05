package com.bplog.holter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bplog.holter.db.AppDatabase
import com.bplog.holter.db.Measurement
import com.bplog.holter.db.MeasurementDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private var pendingStart: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingStart?.invoke()
        } else {
            Toast.makeText(this, "BLE permissions required", Toast.LENGTH_LONG).show()
        }
        pendingStart = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).measurementDao()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HolterScreen(
                        measurements = dao.getAll(),
                        dao = dao,
                        onStart = { intervalMin -> startWithPermissionCheck(intervalMin) },
                        onStop = { stopHolter() }
                    )
                }
            }
        }
    }

    private fun startWithPermissionCheck(intervalMinutes: Int) {
        val missing = blePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startHolter(intervalMinutes)
        } else {
            pendingStart = { startHolter(intervalMinutes) }
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startHolter(intervalMinutes: Int) {
        val intent = Intent(this, HolterService::class.java).apply {
            putExtra(HolterService.EXTRA_INTERVAL, intervalMinutes * 60)
        }
        startForegroundService(intent)
    }

    private fun stopHolter() {
        stopService(Intent(this, HolterService::class.java))
    }
}

fun formatMeasurement(m: Measurement): String {
    val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    val time = fmt.format(Date(m.timestamp))
    return if (m.status == 2)
        "$time ${m.systolic}/${m.diastolic} BPM ${m.hr}"
    else
        "$time ERROR (status ${m.status})"
}

fun shareMeasurements(context: Context, items: List<Measurement>) {
    val text = items
        .filter { it.status == 2 }
        .joinToString("\n") {
            val line = formatMeasurement(it)
            if (it.note.isNullOrBlank()) line else "$line | ${it.note}"
        }
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share measurements"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolterScreen(
    measurements: Flow<List<Measurement>>,
    dao: MeasurementDao,
    onStart: (Int) -> Unit,
    onStop: () -> Unit
) {
    val items by measurements.collectAsStateWithLifecycle(initialValue = emptyList())
    var intervalText by remember { mutableStateOf("5") }
    var isRunning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State for delete confirmation dialog
    var deleteTarget by remember { mutableStateOf<Measurement?>(null) }
    // State for note edit dialog
    var noteTarget by remember { mutableStateOf<Measurement?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("BPLog") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Controls row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                    label = { Text("Interval (min)") },
                    modifier = Modifier.width(130.dp),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val mins = intervalText.toIntOrNull() ?: 5
                        onStart(mins)
                        isRunning = true
                    },
                    enabled = !isRunning
                ) { Text("Start") }
                Button(
                    onClick = {
                        onStop()
                        isRunning = false
                    },
                    enabled = isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Stop") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Share + count row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${items.size} measurements",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { shareMeasurements(context, items) },
                    enabled = items.any { it.status == 2 }
                ) { Text("Share") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Measurement list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { m ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatMeasurement(m),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                            if (!m.note.isNullOrBlank()) {
                                Text(
                                    text = m.note,
                                    fontSize = 12.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Box {
                            var menuExpanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Note") },
                                    onClick = {
                                        menuExpanded = false
                                        noteTarget = m
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        menuExpanded = false
                                        deleteTarget = m
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete measurement?") },
            text = { Text(formatMeasurement(deleteTarget!!)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteTarget!!.id
                    deleteTarget = null
                    scope.launch { dao.deleteById(id) }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Note edit dialog
    if (noteTarget != null) {
        var noteText by remember(noteTarget!!.id) {
            mutableStateOf(noteTarget!!.note ?: "")
        }
        AlertDialog(
            onDismissRequest = { noteTarget = null },
            title = { Text("Note") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = noteTarget!!.id
                    val note = noteText.ifBlank { null }
                    noteTarget = null
                    scope.launch { dao.updateNote(id, note) }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { noteTarget = null }) { Text("Cancel") }
            }
        )
    }
}
