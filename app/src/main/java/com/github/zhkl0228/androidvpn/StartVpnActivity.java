package com.github.zhkl0228.androidvpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.github.zhkl0228.androidvpn.databinding.ActivityStartVpnBinding;

public class StartVpnActivity extends AppCompatActivity implements HostPortDiscover.Listener {

    private ActivityStartVpnBinding binding;
    private HostPortDiscover hostPortDiscover;
    private SharedPreferences preference;

    @Override
    public void onDiscover(String host, int port) {
        Log.d(AndroidVPN.TAG, "onDiscover host=" + host + ", port=" + port);

        runOnUiThread(() -> {
            String ps = String.valueOf(port);
            EditText hostEditText = binding.host;
            EditText portEditText = binding.port;
            if (ps.equals(portEditText.getText().toString())) {
                return;
            }
            Button startVpnButton = binding.startVpn;
            hostEditText.setText(host);
            portEditText.setText(ps);
            startVpnButton.setEnabled(true);
        });
    }

    public static final int VPN_REQUEST_CODE = 0x7b;

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preference = getPreferences(Context.MODE_PRIVATE);

        binding = ActivityStartVpnBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.ca.setMovementMethod(LinkMovementMethod.getInstance());

        final Button startVpnButton = binding.startVpn;

        startVpnButton.setOnClickListener(v -> {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
            } else {
                onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
            }
        });

        EditText hostEditText = binding.host;
        EditText portEditText = binding.port;
        hostEditText.setEnabled(true);
        portEditText.setEnabled(true);
        TextView.OnEditorActionListener listener = (v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    event != null &&
                            event.getAction() == KeyEvent.ACTION_DOWN &&
                            event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event == null || !event.isShiftPressed()) {
                    if (!hostEditText.getText().toString().isEmpty() &&
                            !portEditText.getText().toString().isEmpty()) {
                        startVpnButton.setEnabled(true);
                    }
                    return true;
                }
            }
            return false;
        };
        hostEditText.setOnEditorActionListener(listener);
        portEditText.setOnEditorActionListener(listener);

        String host = preference.getString("host", null);
        int port = preference.getInt("port", 0);
        if (host != null && port != 0) {
            hostEditText.setText(host);
            portEditText.setText(String.valueOf(port));
            startVpnButton.setEnabled(true);
        }

        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifiManager.createMulticastLock(AndroidVPN.TAG);
        hostPortDiscover = new HostPortDiscover(this, lock);
        hostPortDiscover.start();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            EditText hostEditText = binding.host;
            EditText portEditText = binding.port;
            String host = hostEditText.getText().toString().trim();
            int port = Integer.parseInt(portEditText.getText().toString().trim());
            Log.d(AndroidVPN.TAG, "onActivityResult host=" + host + ", port=" + port);
            preference.edit().putString("host", host).putInt("port", port).apply();

            Intent intent = new Intent(this, InspectorVpnService.class);
            Bundle bundle = new Bundle();
            bundle.putString(InspectorVpnService.VPN_HOST_KEY, host);
            bundle.putInt(InspectorVpnService.VPN_PORT_KEY, port);
            intent.putExtra(Bundle.class.getCanonicalName(), bundle);
            startService(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hostPortDiscover.stop();
    }

}