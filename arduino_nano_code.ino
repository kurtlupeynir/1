#include <Wire.h>
#include <SoftwareSerial.h>

SoftwareSerial BT(10, 11);

#define SENSOR_PIN 2
#define TEMP_SENSOR_PIN A0

#define PULSE_PER_TURN 29
#define WHEEL_CIRCUMFERENCE 1.718

#define INTERVAL 250
#define SPEED_FILTER_ALPHA 0.6

// Sıcaklık sensörü kalibrasyonu (Palio için)
// Sensörün çıkışı 0-5V, sıcaklık -40°C ile 125°C arasında
#define TEMP_OFFSET 40.0      // Offset değeri
#define TEMP_SCALE 165.0      // Ölçek değeri (165 = 125 - (-40))

volatile unsigned long pulseCount = 0;
volatile unsigned long lastPulseTime = 0;

unsigned long lastTime = 0;
unsigned long lastBtSend = 0;

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
}

void loop()
{
    unsigned long currentTime = millis();

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

    // Sıcaklık oku (her 500ms)
    if (millis() - lastBtSend >= 500)
    {
        temperature = readTemperature();
    }

    // Android'e veri gönder (her 1000ms)
    if (millis() - lastBtSend >= 1000)
    {
        int speedToSend = (int)speed;
        
        BT.print("<");
        BT.print(speedToSend);
        BT.print(",");
        BT.print(totalKm, 1);
        BT.print(",");
        BT.print((int)temperature);
        BT.println(">");

        // Debug
        Serial.print("Hız: ");
        Serial.print(speedToSend);
        Serial.print(" km/h | Km: ");
        Serial.print(totalKm, 1);
        Serial.print(" | Sıcaklık: ");
        Serial.print(temperature, 1);
        Serial.println(" °C");

        lastBtSend = millis();
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
    
    // 0-5V'a çevir
    float voltage = rawValue * (5.0 / 1023.0);
    
    // Sıcaklığa çevir (Palio sensörü için)
    // Formül: Temp = (Voltage * 165 / 5) - 40
    // Voltage 0V = -40°C, Voltage 5V = 125°C
    float temp = (voltage * TEMP_SCALE / 5.0) - TEMP_OFFSET;
    
    // Geçerlilik kontrolü (-40°C ile 125°C arasında)
    if (temp < -40.0) temp = -40.0;
    if (temp > 125.0) temp = 125.0;
    
    return temp;
}
