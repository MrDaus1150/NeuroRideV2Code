// this is for the android studio java code
package com.example.testjson;

import androidx.appcompat.app.AppCompatActivity;
import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import android.widget.*;
import org.json.JSONObject;

import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String DEVICE_NAME = "ESP32C3_JSON";
    private static final String SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private TextView txtMessage;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtMessage = findViewById(R.id.txtMessage);
        btnConnect = findViewById(R.id.btnConnect);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Request permissions (Android 12+)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }

        btnConnect.setOnClickListener(v -> startScan());
    }

    private void startScan() {
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        txtMessage.setText("Scanning for ESP32...");

        scanner.startScan(new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device.getName() != null && device.getName().equals(DEVICE_NAME)) {
                    txtMessage.setText("Found device! Connecting...");
                    scanner.stopScan(this);
                    connectToDevice(device);
                }
            }
        });
    }

    private void connectToDevice(BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(this, false, new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread(() -> txtMessage.setText("Connected. Requesting MTU..."));
                    gatt.requestMtu(247);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread(() -> txtMessage.setText("Disconnected"));
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                Log.d("BLE_JSON", "MTU changed to " + mtu);
                gatt.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                BluetoothGattService service = gatt.getService(UUID.fromString(SERVICE_UUID));
                if (service != null) {
                    BluetoothGattCharacteristic characteristic =
                            service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID));

                    // Enable notifications
                    gatt.setCharacteristicNotification(characteristic, true);
                    BluetoothGattDescriptor descriptor =
                            characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    }
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic) {
                String value = new String(characteristic.getValue());
                try {
                    JSONObject json = new JSONObject(value);
                    double gforce = json.optDouble("gforce", -1);
                    double temp = json.optDouble("temp", -999);

                    String display = String.format(Locale.US,
                            "G-Force: %.2f g\nTemp: %.2f °C", gforce, temp);

                    runOnUiThread(() -> txtMessage.setText(display));

                } catch (Exception e) {
                    runOnUiThread(() -> txtMessage.setText("Invalid JSON: " + value));
                }
            }
        });
    }
}

