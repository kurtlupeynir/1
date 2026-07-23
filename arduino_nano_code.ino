#include <Wire.h>
#include <SoftwareSerial.h>

SoftwareSerial BT(10, 11);

#define SENSOR_PIN 2
#define TEMP_SENSOR_PIN A0

#define PULSE_PER_TURN 29
#define WHEEL_CIRCUMFERENCE 1.718

#define INTERVAL 250
#define SPEED_FILTER_ALPHA 0.6

// Sıcaklık sensörü kalibrasyonu (Fiat Uno 70S için)
// 20°C = 2-4k Ω, 50°C = 600-900 Ω, 90°C = 100-300 Ω
// Kalibrasyon değerleri (seri monitörden ölçülecek)
#define TEMP_RAW_COLD 850      // Soğuk (20°C) analog değeri
#define TEMP_RAW_HOT 150       // Sıcak (90°C) analog değeri
#define TEMP_COLD 20.0         // Soğuk sıcaklık
#define TEMP_HOT 90.0          // Sıcak sıcaklık

volatile unsigned long pulseCount = 0;
volatile unsigned long lastPulseTime = 0;

unsigned long lastTime = 0;
unsigned long lastBtSend = 0;
unsigned long lastTempRead = 0;

float speed = 0;
float filteredSpeed = 0;
float temperature = 0;

float totalKm = 0.0;

void countPulse();
float readTemperature();

void setup()
{
    Serial.begin(9600);
    BT.begin(9600);

    pinMode(SENSOR_PIN, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(SENSOR_PIN),
                    countPulse,
                    RISING);

    pinMode(TEMP_SENSOR_PIN, INPUT);

    totalKm = 0.0;
    
    Serial.println("=== UNO Dashboard Arduino Started ===");
}

void loop()
{
    unsigned long currentTime = millis();

    // Hız hesaplama (her 250ms)
    if (currentTime - lastTime >= INTERVAL)
    {
        noInterrupts();

        unsigned long pulses = pulseCount;
        pulseCount = 0;

        interrupts();

        // Hız hesaplama
        float rawSpeed = (pulses * WHEEL_CIRCUMFERENCE / PULSE_PER_TURN) * 3.6;

        if (rawSpeed < 1.5)
        {
            filteredSpeed = 0;
        }
        else
        {
            filteredSpeed = (SPEED_FILTER_ALPHA * rawSpeed) +
                           ((1 - SPEED_FILTER_ALPHA) * filteredSpeed);
        }

        speed = filteredSpeed;

        // KM hesaplama
        float distanceIncrement = (pulses * WHEEL_CIRCUMFERENCE) / (PULSE_PER_TURN * 1000.0);

        if (!isnan(distanceIncrement) && distanceIncrement > 0)
        {
            totalKm += distanceIncrement;
        }

        lastTime = currentTime;
    }

    // Sıcaklık oku (her 200ms - daha sık güncelle)
    if (currentTime - lastTempRead >= 200)
    {
        temperature = readTemperature();
        lastTempRead = currentTime;
    }

    // Android'e veri gönder (her 1000ms)
    if (currentTime - lastBtSend >= 1000)
    {
        int speedToSend = (int)speed;
        int tempToSend = (int)temperature;
        
        // Bluetooth'a gönder
        BT.print("<");
        BT.print(speedToSend);
        BT.print(",");
        BT.print(totalKm, 1);
        BT.print(",");
        BT.print(tempToSend);
        BT.println(">");

        // Debug - Serial Monitor'e yazdır
        Serial.print(">>> Hız: ");
        Serial.print(speedToSend);
        Serial.print(" km/h | Km: ");
        Serial.print(totalKm, 1);
        Serial.print(" | Sıcaklık: ");
        Serial.print(temperature, 1);
        Serial.println(" °C");

        lastBtSend = currentTime;
    }
}

void countPulse()
{
    // Basit debounce
    unsigned long currentMicros = micros();
    if (currentMicros - lastPulseTime > 200)  // 200 mikrosaniye
    {
        pulseCount++;
        lastPulseTime = currentMicros;
    }
}

float readTemperature()
{
    // Analog değer oku (10 bit: 0-1023)
    int rawValue = analogRead(TEMP_SENSOR_PIN);
    
    // Kalibrasyon formülü (doğrusal interpolasyon)
    // rawValue TEMP_RAW_COLD'da TEMP_COLD°C
    // rawValue TEMP_RAW_HOT'da TEMP_HOT°C
    
    float temp = TEMP_COLD + (TEMP_HOT - TEMP_COLD) * 
                 (TEMP_RAW_COLD - rawValue) / 
                 (TEMP_RAW_COLD - TEMP_RAW_HOT);
    
    // Geçerlilik kontrolü (0°C ile 120°C arasında)
    if (temp < 0.0) temp = 0.0;
    if (temp > 120.0) temp = 120.0;
    
    return temp;
}
