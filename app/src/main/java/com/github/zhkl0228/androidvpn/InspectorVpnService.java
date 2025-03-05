package com.github.zhkl0228.androidvpn;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class InspectorVpnService extends VpnService {

    private static final String TAG = AndroidVPN.TAG;

    private static final int MSG_SERVICE_INTENT = 0;

    public static final String EXTRA_COMMAND = "Command";
    private static final String EXTRA_REASON = "Reason";

    private static final int MTU = 10000;

    public enum Command {start, stop}

    private volatile Looper commandLooper;
    private volatile CommandHandler commandHandler;
    private Thread tunnelThread;
    private ParcelFileDescriptor vpn;

    private final class CommandHandler extends Handler {
        CommandHandler(Looper looper) {
            super(looper);
        }

        public void queue(Intent intent) {
            Message msg = commandHandler.obtainMessage();
            msg.obj = intent;
            msg.what = MSG_SERVICE_INTENT;
            commandHandler.sendMessage(msg);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            try {
                if (msg.what == MSG_SERVICE_INTENT) {
                    handleIntent((Intent) msg.obj);
                } else {
                    Log.e(TAG, "Unknown command message=" + msg.what);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
            }
        }

        private void handleIntent(Intent intent) {
            Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
            String reason = intent.getStringExtra(EXTRA_REASON);
            Bundle bundle = intent.getBundleExtra(Bundle.class.getCanonicalName());
            String vpnHost = bundle == null ? null : bundle.getString(VPN_HOST_KEY);
            int vpnPort = bundle == null ? 0 : bundle.getInt(VPN_PORT_KEY);
            Log.i(TAG, "Executing intent=" + intent + " command=" + cmd + " reason=" + reason +
                    " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000) +
                    ", vpnHost=" + vpnHost + ", vpnPort=" + vpnPort);

            try {
                if (cmd == null) {
                    throw new IllegalStateException();
                }
                switch (cmd) {
                    case start:
                        if (vpnHost != null && vpnPort != 0) {
                            start(vpnHost, vpnPort);
                        }
                        break;

                    case stop:
                        stop();
                        break;

                    default:
                        Log.e(TAG, "Unknown command=" + cmd);
                }

            } catch (Throwable ex) {
                Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
            }
        }

        private void start(String vpnHost, int vpnPort) {
            if (vpn == null) {
                Builder builder = getBuilder(vpnHost);
                vpn = startVPN(builder);
                if (vpn == null) {
                    throw new IllegalStateException("start vpn failed.");
                }
                startNative(vpn, vpnHost, vpnPort);
            }
        }

        private void stop() {
            if (vpn != null) {
                stopNative();
                stopVPN(vpn);
                vpn = null;
            }
        }
    }

    private void stopNative() {
        Log.i(TAG, "Stop native");

        if (vpnServerThread != null) {
            Thread thread = vpnServerThread;
            thread.interrupt();
            vpnServerThread = null;
            Log.i(TAG, "Stopped vpn server thread");
        }
        if (tunnelThread != null) {
            Log.i(TAG, "Stopping tunnel thread: " + tunnelThread);

            Thread thread = tunnelThread;
            if (thread != null) {
                try {
                    thread.join();
                } catch (InterruptedException ignored) {
                }
            }
            tunnelThread = null;

            Log.i(TAG, "Stopped tunnel thread");
        }
    }

    private void stopVPN(ParcelFileDescriptor pfd) {
        try {
            if (pfd != null) {
                pfd.close();
            }
        } catch (IOException ex) {
            Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
        }
    }

    private static final byte VPN_MAGIC = 0xe;

    private class StreamForward implements Runnable {
        private final DataInput dataInput;
        private final OutputStream outputStream;
        private final int mtu;
        public StreamForward(DataInput dataInput, OutputStream outputStream, int mtu) {
            this.dataInput = dataInput;
            this.outputStream = outputStream;
            this.mtu = mtu;
        }
        @Override
        public void run() {
            try {
                byte[] packet = new byte[mtu];
                while (vpnServerThread != null) {
                    int length = dataInput.readUnsignedShort();
                    if (length > mtu) {
                        throw new IOException("length=" + length + ", mtu=" + mtu);
                    }
                    dataInput.readFully(packet, 0, length);
                    if (length == -1) {
                        throw new EOFException();
                    }
                    if (length > 0) {
                        for (int i = 0; i < length; i++) {
                            packet[i] ^= VPN_MAGIC;
                        }
                        outputStream.write(packet, 0, length);
                        outputStream.flush();
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "stream forward", e);
            }

            if (vpn != null) {
                try { vpn.close(); } catch(Exception ignored) {}
                vpn = null;
            }
        }
    }

    private Thread vpnServerThread;

    private class ApplicationDiscoverServer implements Runnable {
        private final SocketAddress socketAddress;
        public ApplicationDiscoverServer(SocketAddress socketAddress) {
            this.socketAddress = socketAddress;
        }
        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            PackageManager pm = getPackageManager();
            try (DatagramSocket udp = new DatagramSocket(socketAddress)) {
                protect(udp);
                udp.setSoTimeout(2000);
                Thread thread;
                while ((thread = vpnServerThread) != null && thread.isAlive()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        udp.receive(packet);
                        DataInput dataInput = new DataInputStream(new ByteArrayInputStream(buffer));
                        int type = dataInput.readUnsignedByte();
                        if (type != 0x1) {
                            throw new IllegalStateException("type=" + type);
                        }
                        int protocol = dataInput.readUnsignedByte();
                        String saddr = dataInput.readUTF();
                        int sport = dataInput.readUnsignedShort();
                        String daddr = dataInput.readUTF();
                        int dport = dataInput.readUnsignedShort();
                        if (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP) {
                            continue;
                        }
                        InetSocketAddress local = new InetSocketAddress(saddr, sport);
                        InetSocketAddress remote = new InetSocketAddress(daddr, dport);
                        int uid = cm.getConnectionOwnerUid(protocol, local, remote);
                        String[] packages = pm.getPackagesForUid(uid);
                        int hash = Objects.hash(protocol, saddr, sport, daddr, dport);
                        Log.d(TAG, "allowed protocol=" + protocol + ", uid=" + uid + ", packages=" + Arrays.toString(packages) + " " + local + " => " + remote);
                        if (packages != null) {
                            List<Package> list = new ArrayList<>(packages.length);
                            for (String packageName : packages) {
                                PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA);
                                CharSequence label = pm.getApplicationLabel(packageInfo.applicationInfo);
                                list.add(new Package(packageName, label, packageInfo.getLongVersionCode()));
                            }
                            Log.d(TAG, "allowed list=" + list);
                            byte[] data = responseForPackages(hash, list);
                            DatagramPacket forSend = new DatagramPacket(data, data.length, packet.getSocketAddress());
                            udp.send(forSend);
                        }
                    } catch(SocketTimeoutException ignored) {}
                }
            } catch (IOException e) {
                Log.d(TAG, "run udp server failed.", e);
            } catch (Exception e) {
                Log.w(TAG, "run udp server failed.", e);
            } finally {
                Log.d(TAG, "exit udp server.");
            }
        }

        @NonNull
        private byte[] responseForPackages(int hash, List<Package> packages) {
            if (packages == null) {
                throw new IllegalArgumentException();
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                DataOutput dataOutput = new DataOutputStream(baos);
                dataOutput.writeByte(0x2);
                dataOutput.writeInt(hash);
                dataOutput.writeByte(packages.size());
                for (Package pkg : packages) {
                    pkg.output(dataOutput);
                }
                return baos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void startNative(final ParcelFileDescriptor vpn, String vpnHost, int vpnPort) {
        Log.d(TAG, "startNative vpnHost=" + vpnHost + ", vpnPort=" + vpnPort + ", vpnServerThread=" + vpnServerThread);
        if (vpnServerThread != null) {
            return;
        }
        vpnServerThread = new Thread(() -> {
            try (Socket socket = new Socket()) {
                protect(socket);
                socket.connect(new InetSocketAddress(vpnHost, vpnPort), 15000);
                Log.d(TAG, "Connected to vpn server: " + socket);
                {
                    Thread udpServerThread = new Thread(new ApplicationDiscoverServer(socket.getLocalSocketAddress()));
                    udpServerThread.setDaemon(true);
                    udpServerThread.start();
                }

                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                int mtu = MTU;
                try (InputStream vpnInput = new FileInputStream(vpn.getFileDescriptor());
                     OutputStream vpnOutput = new FileOutputStream(vpn.getFileDescriptor())) {
                    DataOutput output = new DataOutputStream(outputStream);
                    int osType = 0x0;
                    File dir = getExternalFilesDir(null);
                    // /sdcard/Android/data/com.github.zhkl0228.androidvpn/files/vpn_config.txt
                    File configFile = dir == null ? null : new File(dir, "vpn_config.txt");
                    Log.d(TAG, "vpn config path: " + configFile);
                    byte[] configData = null;
                    if (configFile != null && configFile.canRead()) {
                        try (FileInputStream fileInputStream = new FileInputStream(configFile);
                             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            FileUtils.copy(fileInputStream, baos);
                            configData = baos.toByteArray();
                        } catch (Exception e) {
                            Log.w(TAG, "read vpn config failed: " + configFile, e);
                        }
                    }
                    if (configData != null) {
                        osType |= 0x80;
                    }
                    output.writeByte(osType);
                    if (configData != null) {
                        Locale locale = Locale.getDefault();
                        try {
                            JSONObject obj = new JSONObject();
                            obj.put("locale", locale.toString());
                            obj.put("language", locale.getLanguage());
                            obj.put("country", locale.getCountry());
                            obj.put("config", Base64.encodeToString(configData, Base64.NO_WRAP));
                            String json = obj.toString();
                            Log.d(TAG, "vpn config path: " + configFile + ", json=" + json);
                            output.writeUTF(json);
                        } catch (JSONException e) {
                            Log.w(TAG, "write vpn config failed: " + configFile, e);
                        }
                    }
                    Thread thread = new Thread(new StreamForward(new DataInputStream(inputStream), vpnOutput, mtu));
                    thread.start();
                    byte[] packet = new byte[mtu];
                    while (true) {
                        int length = vpnInput.read(packet);
                        if (length == -1) {
                            throw new EOFException();
                        }
                        if (length > 0) {
                            if (length > mtu) {
                                throw new IOException("Invalid mtu=" + mtu + ", length=" + length);
                            }
                            output.writeShort(length);
                            for (int i = 0; i < length; i++) {
                                packet[i] ^= VPN_MAGIC;
                            }
                            output.write(packet, 0, length);
                            outputStream.flush();
                        }
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "loop vpn server failed", e);
            }

            ParcelFileDescriptor fd = this.vpn;
            try {
                if (fd != null) {
                    fd.close();
                }
                this.vpn = null;
            } catch (IOException ignored) {
            }
            vpnServerThread = null;
        }, "Connect vpn server");
        vpnServerThread.setPriority(Thread.MAX_PRIORITY);
        vpnServerThread.start();
    }

    private ParcelFileDescriptor startVPN(Builder builder) throws SecurityException {
        try {
            return builder.establish();
        } catch (SecurityException ex) {
            throw ex;
        } catch (Throwable ex) {
            Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
            return null;
        }
    }

    private Builder getBuilder(String vpnHost) {
        // Build VPN service
        Builder builder = new Builder();
        builder.setSession("Inspector");

        // VPN address
        String vpn4 = "10.1.10.1";
        Log.i(TAG, "vpn4=" + vpn4);
        builder.addAddress(vpn4, 32);

//        String vpn6 = "fd00:1:fd00:1:fd00:1:fd00:1";
//        Log.i(TAG, "vpn6=" + vpn6);
//        builder.addAddress(vpn6, 128);

        // Exclude IP ranges
        List<IPUtil.CIDR> listExclude = new ArrayList<>();

        try {
            InetAddress address = InetAddress.getByName(vpnHost);
            IPUtil.CIDR local = new IPUtil.CIDR(address, address.getAddress().length * 8);
            Log.i(TAG, "Excluding " + vpnHost + " " + local);
            listExclude.add(local);
        } catch (IOException ex) {
            Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
        }

        // DNS address
        for (InetAddress dns : getDns()) {
            if (dns instanceof Inet4Address) {
                Log.i(TAG, "dns=" + dns + ", address=" + dns.getHostAddress());
                builder.addDnsServer(dns);
                listExclude.add(new IPUtil.CIDR(dns.getHostAddress(), 24));
            }
        }

        listExclude.add(new IPUtil.CIDR("127.0.0.0", 8)); // localhost

        // USB tethering 192.168.42.x
        // Wi-Fi tethering 192.168.43.x
        listExclude.add(new IPUtil.CIDR("192.168.42.0", 23));
        // Wi-Fi direct 192.168.49.x
        listExclude.add(new IPUtil.CIDR("192.168.49.0", 24));

        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni != null && ni.isUp() && !ni.isLoopback() &&
                        ni.getName() != null && !ni.getName().startsWith("tun"))
                    for (InterfaceAddress ia : ni.getInterfaceAddresses())
                        if (ia.getAddress() instanceof Inet4Address) {
                            IPUtil.CIDR local = new IPUtil.CIDR(ia.getAddress(), ia.getNetworkPrefixLength());
                            Log.i(TAG, "Excluding " + ni.getName() + " " + local);
                            listExclude.add(local);
                        }
            }
        } catch (SocketException ex) {
            Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
        }

        // https://en.wikipedia.org/wiki/Mobile_country_code
        Configuration config = getResources().getConfiguration();

        // T-Mobile Wi-Fi calling
        if (config.mcc == 310 && (config.mnc == 160 ||
                config.mnc == 200 ||
                config.mnc == 210 ||
                config.mnc == 220 ||
                config.mnc == 230 ||
                config.mnc == 240 ||
                config.mnc == 250 ||
                config.mnc == 260 ||
                config.mnc == 270 ||
                config.mnc == 310 ||
                config.mnc == 490 ||
                config.mnc == 660 ||
                config.mnc == 800)) {
            listExclude.add(new IPUtil.CIDR("66.94.2.0", 24));
            listExclude.add(new IPUtil.CIDR("66.94.6.0", 23));
            listExclude.add(new IPUtil.CIDR("66.94.8.0", 22));
            listExclude.add(new IPUtil.CIDR("208.54.0.0", 16));
        }

        // Verizon wireless calling
        if ((config.mcc == 310 &&
                (config.mnc == 4 ||
                        config.mnc == 5 ||
                        config.mnc == 6 ||
                        config.mnc == 10 ||
                        config.mnc == 12 ||
                        config.mnc == 13 ||
                        config.mnc == 350 ||
                        config.mnc == 590 ||
                        config.mnc == 820 ||
                        config.mnc == 890 ||
                        config.mnc == 910)) ||
                (config.mcc == 311 && (config.mnc == 12 ||
                        config.mnc == 110 ||
                        (config.mnc >= 270 && config.mnc <= 289) ||
                        config.mnc == 390 ||
                        (config.mnc >= 480 && config.mnc <= 489) ||
                        config.mnc == 590)) ||
                (config.mcc == 312 && (config.mnc == 770))) {
            listExclude.add(new IPUtil.CIDR("66.174.0.0", 16)); // 66.174.0.0 - 66.174.255.255
            listExclude.add(new IPUtil.CIDR("66.82.0.0", 15)); // 69.82.0.0 - 69.83.255.255
            listExclude.add(new IPUtil.CIDR("69.96.0.0", 13)); // 69.96.0.0 - 69.103.255.255
            listExclude.add(new IPUtil.CIDR("70.192.0.0", 11)); // 70.192.0.0 - 70.223.255.255
            listExclude.add(new IPUtil.CIDR("97.128.0.0", 9)); // 97.128.0.0 - 97.255.255.255
            listExclude.add(new IPUtil.CIDR("174.192.0.0", 9)); // 174.192.0.0 - 174.255.255.255
            listExclude.add(new IPUtil.CIDR("72.96.0.0", 9)); // 72.96.0.0 - 72.127.255.255
            listExclude.add(new IPUtil.CIDR("75.192.0.0", 9)); // 75.192.0.0 - 75.255.255.255
            listExclude.add(new IPUtil.CIDR("97.0.0.0", 10)); // 97.0.0.0 - 97.63.255.255
        }

        // Broadcast
        listExclude.add(new IPUtil.CIDR("224.0.0.0", 3));

        Collections.sort(listExclude);

        try {
            InetAddress start = InetAddress.getByName("0.0.0.0");
            for (IPUtil.CIDR exclude : listExclude) {
                Log.i(TAG, "Exclude " + exclude.getStart().getHostAddress() + "..." + exclude.getEnd().getHostAddress());
                for (IPUtil.CIDR include : IPUtil.toCIDR(start, IPUtil.minus1(exclude.getStart()))) {
                    try {
                        builder.addRoute(include.address, include.prefix);
                    } catch (Throwable ex) {
                        Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
                    }
                }
                start = IPUtil.plus1(exclude.getEnd());
            }
            for (IPUtil.CIDR include : IPUtil.toCIDR("224.0.0.0", "255.255.255.255"))
                try {
                    builder.addRoute(include.address, include.prefix);
                } catch (Throwable ex) {
                    Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
                }
        } catch (UnknownHostException ex) {
            Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
        }

//        builder.addRoute("0:0:0:0:0:0:0:0", 0);
        builder.setMtu(MTU);
        builder.setBlocking(true);

        return builder;
    }

    @SuppressWarnings("deprecation")
    private class Builder extends VpnService.Builder {
        private final NetworkInfo networkInfo;
        private int mtu;
        private final List<String> listAddress = new ArrayList<>();
        private final List<String> listRoute = new ArrayList<>();
        private final List<InetAddress> listDns = new ArrayList<>();

        private Builder() {
            super();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkInfo = cm == null ? null : cm.getActiveNetworkInfo();
        }

        @NonNull
        @Override
        public VpnService.Builder setMtu(int mtu) {
            this.mtu = mtu;
            super.setMtu(mtu);
            return this;
        }

        @NonNull
        @Override
        public Builder addAddress(@NonNull String address, int prefixLength) {
            listAddress.add(address + "/" + prefixLength);
            super.addAddress(address, prefixLength);
            return this;
        }

        @NonNull
        @Override
        public Builder addRoute(@NonNull String address, int prefixLength) {
            listRoute.add(address + "/" + prefixLength);
            super.addRoute(address, prefixLength);
            return this;
        }

        @NonNull
        @Override
        public Builder addDnsServer(@NonNull InetAddress address) {
            listDns.add(address);
            super.addDnsServer(address);
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Builder)) {
                return false;
            }

            Builder other = (Builder) obj;

            if (this.networkInfo == null || other.networkInfo == null ||
                    this.networkInfo.getType() != other.networkInfo.getType())
                return false;

            if (this.mtu != other.mtu)
                return false;

            if (this.listAddress.size() != other.listAddress.size())
                return false;

            if (this.listRoute.size() != other.listRoute.size())
                return false;

            if (this.listDns.size() != other.listDns.size())
                return false;

            for (String address : this.listAddress)
                if (!other.listAddress.contains(address))
                    return false;

            for (String route : this.listRoute)
                if (!other.listRoute.contains(route))
                    return false;

            for (InetAddress dns : this.listDns)
                if (!other.listDns.contains(dns))
                    return false;

            return true;
        }
    }

    private static List<InetAddress> getDns() {
        List<InetAddress> listDns = new ArrayList<>();
        List<String> sysDns = Arrays.asList("8.8.8.8", "8.8.4.4");

        // Get custom DNS servers
        Log.i(TAG, "DNS system=" + TextUtils.join(",", sysDns));

        // Use system DNS servers only when no two custom DNS servers specified
        for (String def_dns : sysDns) {
            try {
                InetAddress ddns = InetAddress.getByName(def_dns);
                if (!listDns.contains(ddns) &&
                        !(ddns.isLoopbackAddress() || ddns.isAnyLocalAddress()) &&
                        ddns instanceof Inet4Address)
                    listDns.add(ddns);
            } catch (Throwable ex) {
                Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
            }
        }

        return listDns;
    }

    public static void logExtras(Intent intent) {
        if (intent != null)
            logBundle(intent.getExtras());
    }

    public static void logBundle(Bundle data) {
        if (data != null) {
            Set<String> keys = data.keySet();
            StringBuilder stringBuilder = new StringBuilder();
            for (String key : keys) {
                Object value = data.get(key);
                stringBuilder.append(key)
                        .append("=")
                        .append(value)
                        .append(value == null ? "" : " (" + value.getClass().getSimpleName() + ")")
                        .append("\r\n");
            }
            Log.d(TAG, stringBuilder.toString());
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate obj=" + this);

        super.onCreate();

        HandlerThread commandThread = new HandlerThread("NetGuard command", Process.THREAD_PRIORITY_FOREGROUND);
        commandThread.start();

        commandLooper = commandThread.getLooper();
        commandHandler = new CommandHandler(commandLooper);
    }

    public static final String VPN_HOST_KEY = "vpnHost";
    public static final String VPN_PORT_KEY = "vpnPort";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received " + intent);
        logExtras(intent);

        // Handle service restart
        if (intent == null) {
            Log.i(TAG, "Restart");

            // Recreate intent
            intent = new Intent(this, InspectorVpnService.class);
            intent.putExtra(EXTRA_COMMAND, Command.start);
        }

        if (vpn == null) {
            intent.putExtra(EXTRA_COMMAND, Command.start);
        } else {
            intent.putExtra(EXTRA_COMMAND, Command.stop);
        }
        String reason = intent.getStringExtra(EXTRA_REASON);
        Log.i(TAG, "Start intent=" + intent + " reason=" + reason + " vpn=" + (vpn != null));

        commandHandler.queue(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroy");

        commandLooper.quit();

        try {
            if (vpn != null) {
                stopNative();
                stopVPN(vpn);
                vpn = null;
            }
        } catch (Throwable ex) {
            Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
        }

        super.onDestroy();
    }

}
