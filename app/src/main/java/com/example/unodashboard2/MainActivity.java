package com.example.unodashboard2;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Dashboard";

    // UI Elemanları
    private TextView txtBtStatus, txtSpeed, txtOdo, txtTripA, txtTripB;

    // Bluetooth
    private BluetoothSocket btSocket;
    private InputStream inputStream;
    private String bufferData = "";
    private long lastPacketTime = 0;

    // Durum
    private volatile boolean isConnected = false;
    private volatile boolean isConnecting = false;

    // Thread'ler
    private Thread readerThread;
    private Thread monitorThread;
    private boolean shouldRun = true;

    // Veri değerleri
    private float currentTripA = 0f;
    private float currentTripB = 0f;
    private float currentTripC = 0f;
    private float previousArduinoKm = 0f;
    private boolean firstPacket = true;
    private static final float START_ODO = 259457.0f;

    // SharedPreferences
    private SharedPreferences prefs;

    private final UUID HC05_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtBtStatus = findViewById(R.id.txtBtStatus);
        txtSpeed = findViewById(R.id.txtSpeed);
        txtOdo = findViewById(R.id.txtOdo);
        txtTripA = findViewById(R.id.txtTripA);
        txtTripB = findViewById(R.id.txtTripB);

        prefs = getSharedPreferences("TripData", MODE_PRIVATE);

        currentTripA = prefs.getFloat("TripA", 0f);
        currentTripB = prefs.getFloat("TripB", 0f);
        currentTripC = prefs.getFloat("TripC", 0f);
        previousArduinoKm = prefs.getFloat("PreviousArduinoKm", 0f);

        Log.d(TAG, "Başlangıç - TripC: " + currentTripC + ", TripA: " + currentTripA + ", TripB: " + currentTripB);
        Log.d(TAG, "Başlangıç - previousArduinoKm: " + previousArduinoKm);

        txtTripA.setText(String.format("%.1f", currentTripA));
        txtTripB.setText(String.format("%.1f", currentTripB));
        txtOdo.setText(String.format("%.1f km", START_ODO + currentTripC));
        txtSpeed.setText("0");

        txtTripA.setOnClickListener(v -> {
            currentTripA = 0f;
            txtTripA.setText("0.0");
            saveTripValue("TripA", 0f);
            Log.d(TAG, "Trip A sıfırlandı");
        });

        txtTripB.setOnClickListener(v -> {
            currentTripB = 0f;
            txtTripB.setText("0.0");
            saveTripValue("TripB", 0f);
            Log.d(TAG, "Trip B sıfırlandı");
        });

        connectBluetooth();
        startMonitorThread();
    }

    private void saveTripValue(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
    }

    // ODO artık saklanmıyor. TripC saklanıyor.

    private void savePreviousArduinoKm(float value) {
        prefs.edit().putFloat("PreviousArduinoKm", value).apply();
    }

    private void startMonitorThread() {
        monitorThread = new Thread(() -> {
            while (shouldRun) {
                try {
                    Thread.sleep(2000);

                    if (isConnected && System.currentTimeMillis() - lastPacketTime > 5000) {
                        runOnUiThread(() -> {
                            txtBtStatus.setText("🔴 Bağlantı koptu");
                            txtBtStatus.setTextColor(0xFFFF4444);
                        });
                        resetConnection();
                    }

                    if (!isConnected && !isConnecting) {
                        runOnUiThread(() -> {
                            txtBtStatus.setText("🔄 Yeniden bağlanıyor...");
                            txtBtStatus.setTextColor(0xFFFFAA44);
                        });
                        connectBluetooth();
                    }

                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
        monitorThread.start();
    }

    private void connectBluetooth() {
        if (isConnecting) return;
        isConnecting = true;

        new Thread(() -> {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    isConnecting = false;
                    runOnUiThread(() -> {
                        txtBtStatus.setText("🔴 Bluetooth kapalı");
                        txtBtStatus.setTextColor(0xFFFF4444);
                    });
                    return;
                }

                BluetoothDevice hc05 = null;
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    if (d.getName() != null && d.getName().contains("HC-05")) {
                        hc05 = d;
                        break;
                    }
                }

                if (hc05 == null) {
                    isConnecting = false;
                    runOnUiThread(() -> {
                        txtBtStatus.setText("🔴 HC-05 bulunamadı");
                        txtBtStatus.setTextColor(0xFFFF4444);
                    });
                    return;
                }

                if (btSocket != null) {
                    try { btSocket.close(); } catch (Exception ignored) {}
                }

                btSocket = hc05.createRfcommSocketToServiceRecord(HC05_UUID);
                btSocket.connect();
                inputStream = btSocket.getInputStream();

                isConnected = true;
                isConnecting = false;
                lastPacketTime = System.currentTimeMillis();
                firstPacket = true;

                runOnUiThread(() -> {
                    txtBtStatus.setText("🟢 Bağlı");
                    txtBtStatus.setTextColor(0xFF00FF00);
                });

                startReader();

            } catch (Exception e) {
                isConnected = false;
                isConnecting = false;
                runOnUiThread(() -> {
                    txtBtStatus.setText("🔴 Bağlantı hatası");
                    txtBtStatus.setTextColor(0xFFFF4444);
                });
            }
        }).start();
    }

    private void startReader() {
        if (readerThread != null) {
            readerThread.interrupt();
        }

        readerThread = new Thread(() -> {
            byte[] buffer = new byte[1024];

            try {
                while (!Thread.currentThread().isInterrupted() && isConnected) {
                    if (inputStream == null) break;

                    if (inputStream.available() > 0) {
                        int bytes = inputStream.read(buffer);
                        if (bytes > 0) {
                            String received = new String(buffer, 0, bytes);
                            bufferData += received;
                            processPackets();
                            lastPacketTime = System.currentTimeMillis();
                        }
                    } else {
                        Thread.sleep(50);
                    }
                }
            } catch (Exception e) {
                resetConnection();
            }
        });

        readerThread.start();
    }

    private void processPackets() {
        while (bufferData.contains("<") && bufferData.contains(">")) {
            int start = bufferData.indexOf("<");
            int end = bufferData.indexOf(">");

            if (end <= start) {
                bufferData = bufferData.substring(end + 1);
                continue;
            }

            String packet = bufferData.substring(start + 1, end);
            String[] values = packet.split(",");

            if (values.length == 2) {
                try {
                    String speedStr = values[0];
                    float newArduinoKm = Float.parseFloat(values[1]);

                    Log.d(TAG, "=========================================");
                    Log.d(TAG, "📦 Gelen veri: <" + speedStr + "," + newArduinoKm + ">");
                    Log.d(TAG, "📊 Mevcut durum:");
                    Log.d(TAG, "   TripA: " + currentTripA);
                    Log.d(TAG, "   TripB: " + currentTripB);
                    Log.d(TAG, "   TripC: " + currentTripC);
                    Log.d(TAG, "   previousArduinoKm: " + previousArduinoKm);
                    Log.d(TAG, "   firstPacket: " + firstPacket);

                    if (firstPacket) {
                        // İlk paket - sadece değerleri kaydet, ekleme yapma
                        previousArduinoKm = newArduinoKm;
                        savePreviousArduinoKm(previousArduinoKm);
                        firstPacket = false;
                        Log.d(TAG, "🔄 İLK PAKET - Sadece kaydedildi, ekleme yapılmadı");
                        Log.d(TAG, "   previousArduinoKm = " + previousArduinoKm);
                    } else {
                        // Normal işlem
                        float distance = newArduinoKm - previousArduinoKm;
                        Log.d(TAG, "📏 Hesaplanan mesafe: " + distance + " km");

                        if (distance > 0 && distance < 10.0f) {
                           // Trip A'ya ekle
                            float oldTripA = currentTripA;
                            currentTripA += distance;
                            Log.d(TAG, "✅ TripA: " + oldTripA + " + " + distance + " = " + currentTripA);

                            // Trip B'ye ekle
                            float oldTripB = currentTripB;
                            currentTripB += distance;
                            Log.d(TAG, "✅ TripB: " + oldTripB + " + " + distance + " = " + currentTripB);

                            // TripC'ye ekle
                            float oldTripC = currentTripC;
                            currentTripC += distance;
                            Log.d(TAG, "✅ TripC: " + oldTripC + " + " + distance + " = " + currentTripC);

                            // Kaydet
                            saveTripValue("TripA", currentTripA);
                            saveTripValue("TripB", currentTripB);
                            saveTripValue("TripC", currentTripC);

                            // UI'ı güncelle
                            runOnUiThread(() -> {
                                txtOdo.setText(String.format("%.1f km", START_ODO + currentTripC));
                                txtTripA.setText(String.format("%.1f", currentTripA));
                                txtTripB.setText(String.format("%.1f", currentTripB));
                            });
                        } else {
                            Log.d(TAG, "⚠️ Mesafe çok büyük veya negatif! distance: " + distance);
                        }

                        // Önceki değeri güncelle
                        previousArduinoKm = newArduinoKm;
                        savePreviousArduinoKm(previousArduinoKm);
                        Log.d(TAG, "📝 previousArduinoKm güncellendi: " + previousArduinoKm);
                    }

                    // Hızı güncelle
                    runOnUiThread(() -> {
                        txtSpeed.setText(speedStr);
                        txtBtStatus.setText("🟢 Veri alınıyor");
                        txtBtStatus.setTextColor(0xFF00FF00);
                    });

                    Log.d(TAG, "=========================================");

                } catch (NumberFormatException e) {
                    Log.e(TAG, "❌ Sayı formatı hatası: " + e.getMessage());
                }
            }

            bufferData = bufferData.substring(end + 1);

            if (bufferData.length() > 2000) {
                bufferData = bufferData.substring(bufferData.length() - 1000);
            }
        }
    }

    private void resetConnection() {
        isConnected = false;
        isConnecting = false;

        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }

        try {
            if (btSocket != null) {
                btSocket.close();
                btSocket = null;
            }
        } catch (Exception ignored) {}

        bufferData = "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shouldRun = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
        resetConnection();
    }
}