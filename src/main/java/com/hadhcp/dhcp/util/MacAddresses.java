package com.hadhcp.dhcp.util;

import java.util.HexFormat;
import java.util.Locale;

public final class MacAddresses {

    private static final HexFormat HEX = HexFormat.of();

    private MacAddresses() {
    }

    public static String normalize(String macAddress) {
        String compact = macAddress.replace("-", "").replace(":", "").replace(".", "")
                .toLowerCase(Locale.ROOT);
        if (compact.length() != 12) {
            throw new IllegalArgumentException("Invalid MAC address: " + macAddress);
        }
        StringBuilder builder = new StringBuilder(17);
        for (int i = 0; i < compact.length(); i += 2) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(compact, i, i + 2);
        }
        return builder.toString();
    }

    public static String fromClientHardwareAddress(byte[] chaddr, int hardwareLength) {
        if (chaddr == null || hardwareLength < 6 || chaddr.length < 6) {
            throw new IllegalArgumentException("DHCP client hardware address is missing");
        }
        byte[] mac = new byte[6];
        System.arraycopy(chaddr, 0, mac, 0, 6);
        return normalize(HEX.formatHex(mac));
    }

    public static byte[] toBytes(String macAddress) {
        String compact = normalize(macAddress).replace(":", "");
        return HEX.parseHex(compact);
    }
}
