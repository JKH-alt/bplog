# BPLog — Open Source Client for the Hilo (TM) (formerly Aktiia) Blood Pressure Monitor

Open source tools for the Hilo (TM) (formerly Aktiia) BLE blood pressure cuff. Includes a Python desktop client and an Android holter app for unattended periodic measurements.

## BLE Protocol

The cuff uses a **proprietary BLE protocol** — there is no standard Blood Pressure Service (0x1810).

### GATT Services

| Service | UUID | Purpose |
|---------|------|---------|
| Battery | 0x180F | Standard battery level |
| Device Information | 0x180A | Serial, firmware, model |
| Aktiia Custom | b1e71568-047b-47c4-88c9-0f90e397acf7 | Measurement data and status |
| Vendor Specific | 0xFF00 | OTA firmware updates only |

### Measurement Characteristics

| UUID | Properties | Purpose |
|------|-----------|---------|
| a6b40002-003d-4e65-9208-08f4db958863 | Notify, Read | Measurement data (subscribe for notifications) |
| a6b40003-003d-4e65-9208-08f4db958863 | Read | Measurement status byte |

### Measurement Flow

1. Scan for devices advertising with name prefix `"AKTIIA C"`
2. Connect (the cuff automatically starts inflating upon BLE connection)
3. Subscribe to notifications on `a6b40002`
4. Wait for notification (the cuff takes ~45 seconds to measure)
5. Parse the 5-byte notification payload
6. Read the status byte from `a6b40003`
7. Disconnect

**No authentication** is required beyond standard BLE pairing.

### Data Format

The measurement notification is 5 bytes (only the first 4 are used):

| Byte | Field |
|------|-------|
| 0 | Diastolic BP (mmHg) |
| 1 | Systolic BP (mmHg) |
| 2 | Mean Arterial Pressure (mmHg) |
| 3 | Heart Rate (bpm) |
| 4 | Unknown (always 0x00) |

### Status Codes

The status byte read from `a6b40003` after measurement:

| Status | Meaning |
|--------|---------|
| 2 | Success — measurement is valid |
| Other | Error — diastolic byte encodes the error type |

Error types (when status != 2, value is in the diastolic byte):

| Diastolic | Error |
|-----------|-------|
| 1 | Range/validity error |
| 2 | Movement error |
| 4, 5 | Measurement error |
| Other | Generic measurement error |

### Advertising Prefixes

| Prefix | Mode |
|--------|------|
| `AKTIIA C` | Normal cuff mode |
| `OTA_` | Firmware update (DFU) mode |
| `AKTIIA P` | Pod/wristband (different protocol) |

### Unattended Operation

The cuff has a physical power switch. As long as the switch is on, each BLE connection + notification subscription automatically triggers a new measurement without any user interaction. This enables holter-style periodic monitoring.

## Python Client

Desktop client using [bleak](https://github.com/hbldh/bleak) for BLE.

### Setup

```bash
cd python
python3 -m venv venv
source venv/bin/activate
pip install bleak
```

### Usage

```bash
# Single measurement (verbose output)
python3 bplog.py

# Holter mode: repeated measurements every 300 seconds
python3 bplog.py --holter 300
```

Single mode prints full details (battery, raw bytes, parsed values). Holter mode prints one line per measurement:

```
Mar 5, 12:34:22 135/85 BPM 58
Mar 5, 12:39:48 130/82 BPM 55
Mar 5, 12:39:48 ERROR (status: 1)
```

## Android App

Foreground service app that performs periodic BLE measurements in the background, stores results in a local database, and provides a share function.

### Building

Requires Android SDK command-line tools with platform 34 and build-tools, and JDK 17.

```bash
cd android
echo "sdk.dir=/path/to/android/sdk" > local.properties
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

### Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Features

- Set measurement interval in minutes
- Start/Stop holter monitoring via foreground service
- Persistent notification showing last reading and next measurement time
- Local Room database storage of all measurements
- Share measurements as text (format: `Mar 5, 09:02:01 134/86 BPM 57`)
- Supports Android 11+ (API 30+)

## Legal Notice

This project is an independent, open-source effort to interoperate with the Hilo (TM) blood pressure cuff. It does not infringe on Hilo's intellectual property:

- **Reverse engineering for interoperability is a protected right.** The EU Software Directive (2009/24/EC, Article 6) explicitly permits reversing to achieve interoperability. In the United States, courts have consistently upheld reverse engineering for interoperability under fair use (see *Sega v. Accolade*, 977 F.2d 1510; *Sony v. Connectix*, 203 F.3d 596; *Oracle v. Google*, 593 U.S. 1).
- **No access controls are circumvented.** The cuff requires no authentication — any BLE client can connect and receive measurements. There is no DRM, encryption, or technological protection measure involved, so the DMCA anti-circumvention provisions (17 U.S.C. § 1201) do not apply.
- **No copyrighted code is redistributed.** This project contains only original code.
- **Communication protocols are not copyrightable expression.** The BLE protocol is a functional specification (a 5-byte data format), not creative expression eligible for copyright protection.
- **Users operate their own hardware.** This software enables device owners to use their purchased hardware with the software of their choice.
- **No trademarks are misused.** "Hilo" and "Aktiia" are used solely for identification and compatibility purposes (nominative fair use). This project is not affiliated with, endorsed by, or sponsored by Hilo or Aktiia.

## License

MIT
