package com.github.zhkl0228.androidvpn;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.github.zhkl0228.androidvpn.AndroidVPN;
import com.github.zhkl0228.androidvpn.HostPortDiscover;
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityStartVpnBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        final Button startVpnButton = binding.startVpn;

        startVpnButton.setOnClickListener(v -> {
            EditText hostEditText = binding.host;
            EditText portEditText = binding.port;
            String host = hostEditText.getText().toString();
            int port = Integer.parseInt(portEditText.getText().toString());
            Log.d(AndroidVPN.TAG, "startVpn host=" + host + ", port=" + port);
        });

        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifiManager.createMulticastLock(AndroidVPN.TAG);
        hostPortDiscover = new HostPortDiscover(this, lock);
        hostPortDiscover.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hostPortDiscover.stop();
    }

}