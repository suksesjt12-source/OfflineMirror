package com.offlinemirror.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class NetworkAddress {
    private static final String UNAVAILABLE = "IP belum tersedia";
    private NetworkAddress() {}

    public static String getLocalIpv4(Context context) {
        String activeIp = getActiveWifiIpv4(context);
        if (isValidIpv4(activeIp)) return activeIp;
        try {
            List<NetworkInterface> interfaces =
                    new ArrayList<>(Collections.list(NetworkInterface.getNetworkInterfaces()));
            interfaces.sort(Comparator.comparingInt(n -> interfacePriority(n.getName())));
            String fallback = null;
            for (NetworkInterface network : interfaces) {
                if (!network.isUp() || network.isLoopback() || isCellularInterface(network.getName())) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!isUsableLocalIpv4(address)) continue;
                    String ip = address.getHostAddress();
                    if (interfacePriority(network.getName()) <= 1) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback == null ? UNAVAILABLE : fallback;
        } catch (Exception ignored) {
            return UNAVAILABLE;
        }
    }

    private static String getActiveWifiIpv4(Context context) {
        // ConnectivityManager.getActiveNetwork/getNetworkCapabilities were added in API 23.
        // Fall back to NetworkInterface enumeration on Android 5.x (minSdk 21).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            Network active = manager.getActiveNetwork();
            if (active == null) return null;
            NetworkCapabilities caps = manager.getNetworkCapabilities(active);
            if (caps == null || (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null;
            LinkProperties properties = manager.getLinkProperties(active);
            if (properties == null) return null;
            for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (isUsableLocalIpv4(address)) return address.getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isUsableLocalIpv4(InetAddress address) {
        return address instanceof Inet4Address && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress() && address.isSiteLocalAddress();
    }

    private static int interfacePriority(String raw) {
        String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (name.startsWith("wlan") || name.startsWith("swlan")
                || name.startsWith("softap") || name.startsWith("ap")) return 0;
        if (name.startsWith("p2p")) return 1;
        if (name.startsWith("eth") || name.startsWith("usb")) return 2;
        return 3;
    }

    private static boolean isCellularInterface(String raw) {
        String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return name.startsWith("rmnet") || name.startsWith("ccmni")
                || name.startsWith("pdp") || name.startsWith("wwan")
                || name.startsWith("tun") || name.startsWith("lo");
    }

    public static boolean isValidIpv4(String value) {
        if (value == null || value.isEmpty()) return false;
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException error) {
            return false;
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }
}
