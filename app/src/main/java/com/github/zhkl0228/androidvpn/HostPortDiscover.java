package com.github.zhkl0228.androidvpn;

import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

class HostPortDiscover implements Runnable {

    private static final int UDP_PORT = 20230;

    public interface Listener {
        void onDiscover(String host, int port);
    }

    private final Listener listener;
    private final WifiManager.MulticastLock lock;

    public HostPortDiscover(Listener listener, WifiManager.MulticastLock lock) {
        this.listener = listener;
        this.lock = lock;
    }

    @Override
    public void run() {
        byte[] buf = new byte[32];
        try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
            lock.acquire();
            socket.setSoTimeout(3000);
            Log.d(AndroidVPN.TAG, "start discover socket=" + socket);
            while (!canStop) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    byte[] data = packet.getData();
                    if (packet.getLength() != 7) {
                        continue;
                    }
                    DataInput dataInput = new DataInputStream(new ByteArrayInputStream(data));
                    int magicSize = dataInput.readShort() & 0xffff;
                    if (magicSize == 3) {
                        byte[] vpn = new byte[magicSize];
                        dataInput.readFully(vpn);
                        if ("vpn".equals(new String(vpn))) {
                            InetAddress address = packet.getAddress();
                            int port = dataInput.readShort() & 0xffff;
                            if (listener != null) {
                                listener.onDiscover(address.getHostAddress(), port);
                            }
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(AndroidVPN.TAG, "run exception", e);
        } finally {
            lock.release();
        }
    }

    private boolean canStop;

    final void start() {
        if (canStop) {
            throw new IllegalStateException();
        }
        Thread thread = new Thread(this, getClass().getSimpleName());
        thread.setDaemon(true);
        thread.start();
    }

    final void stop() {
        canStop = true;
    }

}
