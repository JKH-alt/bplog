package com.bplog.holter

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

data class BpReading(
    val systolic: Int,
    val diastolic: Int,
    val map: Int,
    val hr: Int,
    val status: Int
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID: UUID = UUID.fromString("b1e71568-047b-47c4-88c9-0f90e397acf7")
        val MEASUREMENT_UUID: UUID = UUID.fromString("a6b40002-003d-4e65-9208-08f4db958863")
        val STATUS_UUID: UUID = UUID.fromString("a6b40003-003d-4e65-9208-08f4db958863")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val ADVERTISING_PREFIX = "AKTIIA C"
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * Perform one complete measurement cycle: scan, connect, wait for data, disconnect.
     * Returns a BpReading on success, null on failure/timeout.
     */
    suspend fun takeMeasurement(): BpReading? = withContext(Dispatchers.IO) {
        val device = scan() ?: run {
            Log.w(TAG, "Scan failed: no cuff found")
            return@withContext null
        }
        Log.i(TAG, "Found cuff: ${device.name} (${device.address})")

        val reading = connectAndMeasure(device)
        reading
    }

    private suspend fun scan(): BluetoothDevice? = suspendCancellableCoroutine { cont ->
        val scanner = adapter?.bluetoothLeScanner ?: run {
            cont.resume(null) {}
            return@suspendCancellableCoroutine
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.startsWith(ADVERTISING_PREFIX)) {
                    scanner.stopScan(this)
                    cont.resume(result.device) {}
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                cont.resume(null) {}
            }
        }

        scanner.startScan(listOf(filter), settings, callback)

        // 10s scan timeout
        cont.invokeOnCancellation { scanner.stopScan(callback) }
        GlobalScope.launch {
            delay(10_000)
            if (cont.isActive) {
                scanner.stopScan(callback)
                cont.resume(null) {}
            }
        }
    }

    private suspend fun connectAndMeasure(device: BluetoothDevice): BpReading? =
        suspendCancellableCoroutine { cont ->
            var finished = false
            fun finish(result: BpReading?) {
                if (!finished && cont.isActive) {
                    finished = true
                    cont.resume(result) {}
                }
            }

            // Timeout for entire connect+measure cycle
            val timeoutJob = GlobalScope.launch {
                delay(135_000) // 15s connect + 120s measurement
                finish(null)
            }

            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "Connected, discovering services...")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "Disconnected")
                        gatt.close()
                        timeoutJob.cancel()
                        finish(null)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Service discovery failed: $status")
                        gatt.disconnect()
                        return
                    }
                    Log.i(TAG, "Services discovered, subscribing to notifications...")
                    val service = gatt.getService(SERVICE_UUID) ?: run {
                        Log.e(TAG, "Measurement service not found")
                        gatt.disconnect()
                        return
                    }
                    val char = service.getCharacteristic(MEASUREMENT_UUID) ?: run {
                        Log.e(TAG, "Measurement characteristic not found")
                        gatt.disconnect()
                        return
                    }
                    gatt.setCharacteristicNotification(char, true)
                    val cccd = char.getDescriptor(CCCD_UUID)
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid == MEASUREMENT_UUID) {
                        val data = characteristic.value
                        Log.i(TAG, "Measurement received: ${data.joinToString("") { "%02x".format(it) }}")
                        if (data.size >= 4) {
                            // Read status characteristic
                            val statusService = gatt.getService(SERVICE_UUID)
                            val statusChar = statusService?.getCharacteristic(STATUS_UUID)
                            if (statusChar != null) {
                                // Store measurement data for use in onCharacteristicRead
                                statusChar.let {
                                    it.writeType = 0 // tag for our use
                                }
                                // Save data in a field accessible from the read callback
                                pendingData = data
                                gatt.readCharacteristic(statusChar)
                            } else {
                                val reading = BpReading(
                                    diastolic = data[0].toInt() and 0xFF,
                                    systolic = data[1].toInt() and 0xFF,
                                    map = data[2].toInt() and 0xFF,
                                    hr = data[3].toInt() and 0xFF,
                                    status = -1
                                )
                                gatt.disconnect()
                                timeoutJob.cancel()
                                finish(reading)
                            }
                        }
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (characteristic.uuid == STATUS_UUID && pendingData != null) {
                        val data = pendingData!!
                        val statusByte = if (status == BluetoothGatt.GATT_SUCCESS)
                            characteristic.value[0].toInt() and 0xFF else -1
                        pendingData = null
                        val reading = BpReading(
                            diastolic = data[0].toInt() and 0xFF,
                            systolic = data[1].toInt() and 0xFF,
                            map = data[2].toInt() and 0xFF,
                            hr = data[3].toInt() and 0xFF,
                            status = statusByte
                        )
                        Log.i(TAG, "Reading: ${reading.systolic}/${reading.diastolic} HR=${reading.hr} status=${reading.status}")
                        gatt.disconnect()
                        timeoutJob.cancel()
                        finish(reading)
                    }
                }
            }

            pendingData = null
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

    @Volatile
    private var pendingData: ByteArray? = null
}
