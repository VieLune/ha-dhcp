package com.hadhcp.dhcp.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class Ipv4Addresses {

    private Ipv4Addresses() {
    }

    public static int toInt(String address) {
        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (bytes.length != 4) {
                throw new IllegalArgumentException("Not an IPv4 address: " + address);
            }
            return ((bytes[0] & 0xff) << 24)
                    | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8)
                    | (bytes[3] & 0xff);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + address, ex);
        }
    }

    public static String fromInt(int value) {
        return ((value >>> 24) & 0xff)
                + "." + ((value >>> 16) & 0xff)
                + "." + ((value >>> 8) & 0xff)
                + "." + (value & 0xff);
    }

    public static byte[] toBytes(String address) {
        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (bytes.length != 4) {
                throw new IllegalArgumentException("Not an IPv4 address: " + address);
            }
            return bytes;
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + address, ex);
        }
    }

    public static String fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 4) {
            throw new IllegalArgumentException("IPv4 address must contain exactly 4 bytes");
        }
        return (bytes[0] & 0xff)
                + "." + (bytes[1] & 0xff)
                + "." + (bytes[2] & 0xff)
                + "." + (bytes[3] & 0xff);
    }

    public static boolean isZero(byte[] bytes) {
        return bytes == null || bytes.length != 4
                || ((bytes[0] | bytes[1] | bytes[2] | bytes[3]) == 0);
    }

    public static byte[] zero() {
        return new byte[] {0, 0, 0, 0};
    }

    public static String broadcastAddress(String ipAddress, String subnetMask) {
        int ip = toInt(ipAddress);
        int mask = toInt(subnetMask);
        return fromInt(ip | ~mask);
    }
}
