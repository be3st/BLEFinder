# Bluetooth Finder 1.2

Android app for finding nearby Bluetooth devices by RSSI signal strength.

## What's new in 1.2

- Scans **Bluetooth Low Energy (BLE)** advertisements.
- Also runs **Bluetooth Classic discovery** and reads RSSI from discovery results.
- Shows paired devices even when they are currently not discoverable; these entries have no live RSSI until a signal is observed.
- Adds **Finder-Signal** mode: a second Android phone running this app can advertise a private BLE service UUID so it is easy to identify as `BLE Finder-Handy`.
- Adds a button to request **Bluetooth Classic discoverability for 5 minutes** on the current phone.
- Keeps the green proximity gauge plus metal-detector-style sound and vibration.

## Important limitation

A phone is not automatically detectable just because Bluetooth is enabled. Android phones are not discoverable by default. A target device must either:

1. advertise over BLE,
2. be temporarily discoverable over Bluetooth Classic, or
3. already expose some other Bluetooth signal/service that Android can scan.

For reliable phone-to-phone finding, install this app on both Android phones. On the target phone, open the app and tap **Finder-Signal von diesem Handy senden**. On the searching phone, tap **Suche starten**, then select **BLE Finder-Handy**.

## Permissions

Android 12+:
- Nearby devices / `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE` when Finder-Signal or Classic discoverability is used
- Precise location, because this app intentionally interprets RSSI as a physical-proximity signal

Android 11 and older:
- Fine location for Bluetooth scanning

## Install from Android Studio

1. Open the project in Android Studio.
2. Wait for Gradle Sync to finish.
3. Enable Developer options and USB debugging on the Android phone.
4. Connect the phone by USB and allow the debugging prompt.
5. Select the phone in Android Studio.
6. Click **Run** for the `app` configuration.

The application id is unchanged from versions 1.0/1.1, and versionCode is higher, so Android Studio can update the existing installation.

## Test with two Android phones

Target phone:
1. Install/open Bluetooth Finder 1.2.
2. Allow requested Bluetooth permissions.
3. Tap **Finder-Signal von diesem Handy senden**.
4. Leave Bluetooth enabled.

Searching phone:
1. Install/open Bluetooth Finder 1.2.
2. Allow Nearby devices and precise location.
3. Tap **Suche starten**.
4. Select **BLE Finder-Handy**.
5. Move around; stronger RSSI means the signal is generally closer. Sound/vibration pulses speed up as RSSI rises.

RSSI is not a precise distance measurement. Walls, people, reflections, antenna orientation and device transmit power affect readings.
