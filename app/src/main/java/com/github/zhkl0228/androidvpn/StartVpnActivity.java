package com.github.zhkl0228.androidvpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.github.zhkl0228.androidvpn.databinding.ActivityStartVpnBinding;

public class StartVpnActivity extends AppCompatActivity implements HostPortDiscover.Listener {

    private ActivityStartVpnBinding binding;
    private HostPortDiscover hostPortDiscover;

    @Override
    public void onDiscover(String host, int port) {
        Log.d(AndroidVPN.TAG, "onDiscover host=" + host + ", port=" + port);

        runOnUiThread(() -> {
            EditText hostEditText = binding.host;
            EditText portEditText = binding.port;
            Button startVpnButton = binding.startVpn;
            hostEditText.setText(host);
            portEditText.setText(String.valueOf(port));
            startVpnButton.setEnabled(true);
        });
    }

    public static final int VPN_REQUEST_CODE = 0x7b;

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityStartVpnBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        final Button startVpnButton = binding.startVpn;

        startVpnButton.setOnClickListener(v -> {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
            } else {
                onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
            }
        });

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
            String host = hostEditText.getText().toString();
            int port = Integer.parseInt(portEditText.getText().toString());
            Log.d(AndroidVPN.TAG, "onActivityResult host=" + host + ", port=" + port);

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