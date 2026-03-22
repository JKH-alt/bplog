#!/usr/bin/env python3
"""
BPLog — Desktop Client for Hilo (formerly Aktiia) Blood Pressure Cuff
Connects via BLE, triggers a measurement, and prints the results.

Usage:
    python3 bplog.py                  # single measurement, verbose
    python3 bplog.py --holter 300     # repeated measurements every 300s
    python3 bplog.py --battery        # just print battery level
"""

import argparse
import asyncio
import sys
from datetime import datetime
from bleak import BleakClient, BleakScanner

# UUIDs from decompiled APK
SERVICE_UUID        = "b1e71568-047b-47c4-88c9-0f90e397acf7"
MEASUREMENT_UUID    = "a6b40002-003d-4e65-9208-08f4db958863"
STATUS_UUID         = "a6b40003-003d-4e65-9208-08f4db958863"
BATTERY_UUID        = "00002a19-0000-1000-8000-00805f9b34fb"

ADVERTISING_PREFIX  = "AKTIIA C"
MEASUREMENT_TIMEOUT = 120  # seconds, same as the app

async def find_cuff(quiet=False):
    """Scan for an Aktiia cuff device."""
    if not quiet:
        print("Scanning for Aktiia/Hilo cuff...")
    devices = await BleakScanner.discover(timeout=10, return_adv=True)
    for addr, (device, adv) in devices.items():
        name = device.name or adv.local_name or ""
        if name.startswith(ADVERTISING_PREFIX):
            if not quiet:
                print(f"  Found: {name} ({addr}) RSSI={adv.rssi}")
            return addr
    return None

async def take_one_measurement(address, quiet=False):
    """Connect, wait for one measurement, return dict or None."""
    result = {}
    event = asyncio.Event()

    def on_measurement(sender, data: bytearray):
        if len(data) >= 4:
            result["diastolic"] = data[0]
            result["systolic"]  = data[1]
            result["map"]       = data[2]
            result["hr"]        = data[3]
        if not quiet:
            print(f"  Raw notification: {data.hex()} ({len(data)} bytes)")
        event.set()

    if not quiet:
        print(f"Connecting to {address}...")
    async with BleakClient(address, timeout=15) as client:
        if not quiet:
            battery = await client.read_gatt_char(BATTERY_UUID)
            print(f"  Connected. Battery: {battery[0]}%")
            print("  Subscribing to measurement notifications...")

        await client.start_notify(MEASUREMENT_UUID, on_measurement)

        if not quiet:
            print()
            print("  *** Start the measurement on the cuff now ***")
            print("  *** (press the button / wrap the cuff and wait) ***")
            print(f"  Waiting up to {MEASUREMENT_TIMEOUT}s for data...")
            print()

        try:
            await asyncio.wait_for(event.wait(), timeout=MEASUREMENT_TIMEOUT)
        except asyncio.TimeoutError:
            if not quiet:
                print("  TIMEOUT: No measurement received.")
            return None

        status_data = await client.read_gatt_char(STATUS_UUID)
        result["status"] = status_data[0] if status_data else -1
        await client.stop_notify(MEASUREMENT_UUID)

    return result if "systolic" in result else None

async def single_mode(address):
    """One-shot measurement with verbose output."""
    result = await take_one_measurement(address, quiet=False)
    print("=" * 40)
    if result:
        print(f"  Systolic:   {result['systolic']} mmHg")
        print(f"  Diastolic:  {result['diastolic']} mmHg")
        print(f"  MAP:        {result['map']} mmHg")
        print(f"  Heart Rate: {result['hr']} bpm")
        # Status 2 = success (from decompiled readMeasurementStatus$lambda$73)
        status_str = "OK" if result["status"] == 2 else f"Error (code {result['status']})"
        print(f"  Status:     {status_str}")
    else:
        print("  No valid measurement received.")
    print("=" * 40)

async def holter_mode(address, interval):
    """Repeated measurements at fixed intervals. Minimal output."""
    while True:
        result = await take_one_measurement(address, quiet=True)
        now = datetime.now().strftime("%b %-d, %H:%M:%S")
        if result and result["status"] == 2:
            print(f"{now} {result['systolic']}/{result['diastolic']} BPM {result['hr']}")
        else:
            code = result["status"] if result else "timeout"
            print(f"{now} ERROR (status: {code})")
        sys.stdout.flush()
        await asyncio.sleep(interval)

async def battery_mode(address):
    """Connect and print battery level."""
    async with BleakClient(address, timeout=15) as client:
        battery = await client.read_gatt_char(BATTERY_UUID)
        print(f"{battery[0]}%")

async def main():
    parser = argparse.ArgumentParser(description="BPLog — Hilo (formerly Aktiia) BP cuff client")
    parser.add_argument("--holter", type=int, metavar="SECONDS",
                        help="Holter mode: repeat measurement every SECONDS seconds")
    parser.add_argument("--battery", action="store_true",
                        help="Just print battery level and exit")
    args = parser.parse_args()

    quiet = args.holter is not None or args.battery
    address = await find_cuff(quiet=quiet)
    if not address:
        print("No Aktiia/Hilo cuff found. Make sure it's powered on.")
        sys.exit(1)

    if args.battery:
        await battery_mode(address)
    elif args.holter:
        await holter_mode(address, args.holter)
    else:
        await single_mode(address)

if __name__ == "__main__":
    asyncio.run(main())
