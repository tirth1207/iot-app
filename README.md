# AquaWatch — Smart Water Tank Monitoring System

Native Android IoT app built with Java and Groovy Gradle. Receives water level telemetry over Bluetooth Classic (RFCOMM SPP) from an Arduino Uno, updates a live animated UI, and syncs readings to ThingSpeak.

## Hardware Wiring
- **HC-SR04 Ultrasonic**:
  - `VCC` -> `5V`
  - `GND` -> `GND`
  - `TRIG` -> Pin `9`
  - `ECHO` -> Pin `10`
- **HC-05 Bluetooth**:
  - `VCC` -> `5V`
  - `GND` -> `GND`
  - `TXD` -> Pin `2` (Arduino RX)
  - `RXD` -> Voltage divider (Pin `3` -> 1kΩ -> HC-05 RX -> 2kΩ -> GND)

## ThingSpeak Setup
1. Create a channel on https://thingspeak.com.
2. Configure Field 1: Level (%), Field 2: Distance (cm), Field 3: Status Code.
3. Open the app, tap **Config**, and enter your 16-character Write API Key.

## Build Commands
```bash
./gradlew clean test
./gradlew assembleDebug
