#include <SoftwareSerial.h>

const int PIN_RX = 2; // Connects to HC-05 TX
const int PIN_TX = 3; // Connects to HC-05 RX (via voltage divider)
const int PIN_TRIG = 9;
const int PIN_ECHO = 10;

const float TANK_TOTAL_HEIGHT_CM = 100.0;
const float SENSOR_OFFSET_CM = 10.0;

SoftwareSerial btSerial(PIN_RX, PIN_TX);

void setup() {
  Serial.begin(9600);
  btSerial.begin(9600);
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  digitalWrite(PIN_TRIG, LOW);
  delay(500);
}

void loop() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);

  long duration = pulseIn(PIN_ECHO, HIGH, 30000);

  if (duration == 0) {
    btSerial.print("ERROR,NO_ECHO\r\n");
    Serial.println("ERROR,NO_ECHO");
    delay(2000);
    return;
  }

  float distanceCm = (duration * 0.0343) / 2.0;
  float usableDepth = TANK_TOTAL_HEIGHT_CM - SENSOR_OFFSET_CM;
  float waterDepth = TANK_TOTAL_HEIGHT_CM - distanceCm;
  float levelPercent = (waterDepth / usableDepth) * 100.0;

  if (levelPercent < 0.0) levelPercent = 0.0;
  if (levelPercent > 100.0) levelPercent = 100.0;

  String status = "NORMAL";
  if (levelPercent <= 10.0) {
    status = "EMPTY";
  } else if (levelPercent >= 95.0) {
    status = "OVERFLOW";
  }

  String payload = String(levelPercent, 1) + "," + String(distanceCm, 1) + "," + status + "\r\n";
  btSerial.print(payload);
  Serial.print(payload);

  delay(2000);
}
