package com.example.neuroridev2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public class DevicesFragment extends Fragment {

    // ===== Match your friend's working setup =====
    private static final String TARGET_NAME = "ESP32C3_JSON"; // change if your ESP name differs
    private static final UUID SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CHAR_UUID    = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCC_UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String TAG = "NeuroRideBLE";

    // ===== Emergency contacts store keys =====
    private static final String SP = "emergency_contacts";
    private static final String KEY_LIST = "list";

    // ===== UI =====
    private TextView tvConnStatus;
    private Button btnSync;

    // ===== Shared telemetry VM =====
    private TelemetryViewModel vm;

    // ===== BLE state =====
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic jsonChar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanning = false;

    // ===== Location helper for SOS =====
    private LocationHelper loc;

    // ===== Runtime permission helpers =====
    private boolean hasBtScanPerm() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasBtConnectPerm() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private final ActivityResultLauncher<String[]> btPermsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {});

    private void requestBtPermsIfNeeded(boolean needScan, boolean needConnect) {
        ArrayList<String> wanted = new ArrayList<>();
        if (needScan && !hasBtScanPerm()) wanted.add(Manifest.permission.BLUETOOTH_SCAN);
        if (needConnect && !hasBtConnectPerm()) wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!wanted.isEmpty()) btPermsLauncher.launch(wanted.toArray(new String[0]));
    }
    private boolean isBtEnabledSafe() {
        try { return btAdapter != null && btAdapter.isEnabled(); }
        catch (SecurityException se) {
            requestBtPermsIfNeeded(false, true);
            return false;
        }
    }

    @Nullable
    @Override public View onCreateView(@NonNull LayoutInflater inflater,
                                       @Nullable ViewGroup container,
                                       @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_devices, container, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        loc = new LocationHelper(this);

        // SOS button
        Button sos = v.findViewById(R.id.button);
        sos.setOnClickListener(view -> onSosPressed());

        vm = new ViewModelProvider(requireActivity()).get(TelemetryViewModel.class);

        tvConnStatus = v.findViewById(R.id.tvConnStatus);
        btnSync = v.findViewById(R.id.btnSync);

        vm.connected().observe(getViewLifecycleOwner(), connected -> {
            Integer bat = vm.battery().getValue();
            String batStr = (bat == null) ? "--" : String.valueOf(bat);
            tvConnStatus.setText((connected ? "Connected" : "Disconnected") + " | Battery: " + batStr + "%");
        });
        vm.battery().observe(getViewLifecycleOwner(), bat -> {
            Boolean connected = vm.connected().getValue();
            String batStr = (bat == null) ? "--" : String.valueOf(bat);
            tvConnStatus.setText(((connected != null && connected) ? "Connected" : "Disconnected") + " | Battery: " + batStr + "%");
        });

        btnSync.setOnClickListener(vw -> doSync());

        BluetoothManager mgr = requireContext().getSystemService(BluetoothManager.class);
        btAdapter = (mgr != null) ? mgr.getAdapter() : null;
        scanner = (btAdapter != null) ? btAdapter.getBluetoothLeScanner() : null;
    }

    // ===== Entry: Sync/Connect =====
    private void doSync() {
        if (!isBtEnabledSafe()) {
            Toast.makeText(requireContext(), "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasBtScanPerm()) { requestBtPermsIfNeeded(true, false); return; }
        startScan();
    }

    // ===== Scan (match by NAME like your friend) =====
    private void startScan() {
        if (scanning) return;
        if (scanner == null) {
            Toast.makeText(requireContext(),"No BLE scanner",Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasBtScanPerm()) { requestBtPermsIfNeeded(true, false); return; }

        try {
            scanner.startScan(scanCallback); // scan all, filter in callback
            scanning = true;
            handler.postDelayed(this::stopScan, 10_000);
            Toast.makeText(requireContext(), "Scanning for " + TARGET_NAME + "...", Toast.LENGTH_SHORT).show();
        } catch (SecurityException se) {
            requestBtPermsIfNeeded(true, false);
        }
    }

    private void stopScan() {
        if (!scanning) return;
        if (!hasBtScanPerm()) { requestBtPermsIfNeeded(true, false); return; }
        try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = (result.getScanRecord() != null) ? result.getScanRecord().getDeviceName() : null;
            if (name == null && hasBtConnectPerm()) {
                try { name = device.getName(); } catch (SecurityException ignored) {}
            }
            String addr = device.getAddress();
            Log.d(TAG, "Found: " + (name == null ? "(no name)" : name) + " [" + addr + "]");

            if (name != null && name.equals(TARGET_NAME)) {
                stopScan();
                connect(device);
            }
        }
    };

    // ===== Connect → request MTU → discover services =====
    private void connect(BluetoothDevice dev) {
        closeGatt();
        if (!hasBtConnectPerm()) { requestBtPermsIfNeeded(false, true); return; }
        try {
            Toast.makeText(requireContext(), "Connecting...", Toast.LENGTH_SHORT).show();
            gatt = dev.connectGatt(requireContext(), false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException se) {
            requestBtPermsIfNeeded(false, true);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                vm.setConnected(true);
                if (hasBtConnectPerm()) {
                    try {
                        g.requestMtu(247); // friend’s flow
                        runOnUi("Connected. Requesting MTU...");
                    } catch (SecurityException ignored) {}
                } else {
                    requestBtPermsIfNeeded(false, true);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                vm.setConnected(false);
                if (status == 133 || status == 8 || status == 62) {
                    toast("Device busy or connected elsewhere. Disconnect nRF Connect and retry.");
                }
                closeGatt();
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.d(TAG, "MTU changed to " + mtu + " (status=" + status + ")");
            if (hasBtConnectPerm()) {
                try { g.discoverServices(); } catch (SecurityException ignored) {}
            } else {
                requestBtPermsIfNeeded(false, true);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc == null) {
                toast("Service not found: " + SERVICE_UUID);
                if (hasBtConnectPerm()) try { g.disconnect(); } catch (SecurityException ignored) {}
                return;
            }

            jsonChar = svc.getCharacteristic(CHAR_UUID);
            if (jsonChar == null) {
                toast("Characteristic not found: " + CHAR_UUID);
                if (hasBtConnectPerm()) try { g.disconnect(); } catch (SecurityException ignored) {}
                return;
            }

            if (!hasBtConnectPerm()) { requestBtPermsIfNeeded(false, true); return; }

            try {
                g.setCharacteristicNotification(jsonChar, true);
                BluetoothGattDescriptor ccc = jsonChar.getDescriptor(CCC_UUID);
                if (ccc != null) {
                    ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(ccc);
                }
                toast("Subscribed to ESP32 characteristic");
            } catch (SecurityException ignored) {}
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (!CHAR_UUID.equals(ch.getUuid())) return;
            String jsonStr = new String(ch.getValue(), StandardCharsets.UTF_8).trim();
            Log.d(TAG, "Received: " + jsonStr);

            try {
                JSONObject json = new JSONObject(jsonStr);
                double tempC   = json.optDouble("temp",  Double.NaN);
                double speed   = json.optDouble("speed", Double.NaN);
                double gforce  = json.optDouble("gforce",Double.NaN);
                double battery = json.optDouble("bat",   Double.NaN);

                if (!Double.isNaN(tempC))   vm.setTemp(tempC);
                if (!Double.isNaN(speed))   vm.setSpeed(speed);
                if (!Double.isNaN(gforce))  vm.setGforce(gforce);
                if (!Double.isNaN(battery)) vm.setBattery((int) Math.round(battery));
            } catch (Exception e) {
                Log.w(TAG, "Bad JSON: " + jsonStr, e);
            }
        }
    };

    private void runOnUi(String msg) {
        handler.post(() -> Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show());
    }
    private void toast(String s) {
        handler.post(() -> Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show());
    }

    private void closeGatt() {
        try { if (gatt != null) gatt.disconnect(); } catch (SecurityException ignored) {}
        try { if (gatt != null) gatt.close(); } catch (SecurityException ignored) {}
        gatt = null; jsonChar = null;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        closeGatt();
    }

    // ===== SOS flow (unchanged) =====

    private void onSosPressed() {
        JSONArray list = loadContacts();
        if (list.length() == 0) {
            Toast.makeText(requireContext(), "No emergency contacts yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String number = getPrimaryNumber(list);

        loc.getCurrent(new LocationHelper.Callback() {
            @Override public void onOk(double lat, double lng) {
                String msg = buildSosMessageWithLocation(lat, lng);
                sendWhatsAppOrSms(number, msg);
            }
            @Override public void onFail(String reason) {
                String msg = buildSosMessageNoLocation();
                sendWhatsAppOrSms(number, msg);
            }
        });
    }

    private String buildSosMessageWithLocation(double lat, double lng) {
        String map = "https://maps.google.com/?q=" + lat + "," + lng;
        return "🚨 SOS! I need help.\n"
                + "• App: NeuroRide V2\n"
                + "• Location: " + map + "\n"
                + "Please call me ASAP.";
    }
    private String buildSosMessageNoLocation() {
        return "🚨 SOS! I need help.\n"
                + "• App: NeuroRide V2\n"
                + "• Location: unavailable\n"
                + "Please call me ASAP.";
    }

    private JSONArray loadContacts() {
        Context ctx = requireContext();
        String s = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_LIST, "[]");
        try { return new JSONArray(s); } catch (JSONException e) { return new JSONArray(); }
    }
    private String getPrimaryNumber(JSONArray list) {
        JSONObject o = list.optJSONObject(0);
        return o != null ? o.optString("phone", "") : "";
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        return digits;
    }
    private boolean appInstalled(String pkg) {
        try { requireContext().getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }
    private void sendWhatsAppOrSms(String rawPhone, String message) {
        String phone = normalizePhone(rawPhone);
        if (phone.isEmpty()) {
            Toast.makeText(requireContext(), "Invalid phone number", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri waUri = Uri.parse("https://wa.me/" + phone + "?text=" + Uri.encode(message));
        Intent i = new Intent(Intent.ACTION_VIEW, waUri);
        if (appInstalled("com.whatsapp.w4b")) i.setPackage("com.whatsapp.w4b");
        else if (appInstalled("com.whatsapp")) i.setPackage("com.whatsapp");

        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
                sms.putExtra("sms_body", message);
                startActivity(sms);
            } catch (Exception ex) {
                Toast.makeText(requireContext(), "No app to send message", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
