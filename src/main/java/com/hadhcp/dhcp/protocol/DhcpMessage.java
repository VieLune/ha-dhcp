package com.hadhcp.dhcp.protocol;

import com.hadhcp.dhcp.util.Ipv4Addresses;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class DhcpMessage {

    public static final int BOOTREQUEST = 1;
    public static final int BOOTREPLY = 2;
    public static final int ETHERNET = 1;

    private int op;
    private int hardwareType;
    private int hardwareAddressLength;
    private int hops;
    private int transactionId;
    private int seconds;
    private int flags;
    private byte[] clientIpAddress = Ipv4Addresses.zero();
    private byte[] yourIpAddress = Ipv4Addresses.zero();
    private byte[] serverIpAddress = Ipv4Addresses.zero();
    private byte[] gatewayIpAddress = Ipv4Addresses.zero();
    private byte[] clientHardwareAddress = new byte[16];
    private byte[] serverHostName = new byte[64];
    private byte[] bootFileName = new byte[128];
    private final Map<Integer, byte[]> options = new LinkedHashMap<>();

    public int getOp() {
        return op;
    }

    public void setOp(int op) {
        this.op = op;
    }

    public int getHardwareType() {
        return hardwareType;
    }

    public void setHardwareType(int hardwareType) {
        this.hardwareType = hardwareType;
    }

    public int getHardwareAddressLength() {
        return hardwareAddressLength;
    }

    public void setHardwareAddressLength(int hardwareAddressLength) {
        this.hardwareAddressLength = hardwareAddressLength;
    }

    public int getHops() {
        return hops;
    }

    public void setHops(int hops) {
        this.hops = hops;
    }

    public int getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(int transactionId) {
        this.transactionId = transactionId;
    }

    public int getSeconds() {
        return seconds;
    }

    public void setSeconds(int seconds) {
        this.seconds = seconds;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public byte[] getClientIpAddress() {
        return copy(clientIpAddress);
    }

    public void setClientIpAddress(byte[] clientIpAddress) {
        this.clientIpAddress = copy4(clientIpAddress);
    }

    public byte[] getYourIpAddress() {
        return copy(yourIpAddress);
    }

    public void setYourIpAddress(byte[] yourIpAddress) {
        this.yourIpAddress = copy4(yourIpAddress);
    }

    public byte[] getServerIpAddress() {
        return copy(serverIpAddress);
    }

    public void setServerIpAddress(byte[] serverIpAddress) {
        this.serverIpAddress = copy4(serverIpAddress);
    }

    public byte[] getGatewayIpAddress() {
        return copy(gatewayIpAddress);
    }

    public void setGatewayIpAddress(byte[] gatewayIpAddress) {
        this.gatewayIpAddress = copy4(gatewayIpAddress);
    }

    public byte[] getClientHardwareAddress() {
        return copy(clientHardwareAddress);
    }

    public void setClientHardwareAddress(byte[] clientHardwareAddress) {
        if (clientHardwareAddress == null || clientHardwareAddress.length > 16) {
            throw new IllegalArgumentException("Client hardware address must be <= 16 bytes");
        }
        this.clientHardwareAddress = new byte[16];
        System.arraycopy(clientHardwareAddress, 0, this.clientHardwareAddress, 0, clientHardwareAddress.length);
    }

    public byte[] getServerHostName() {
        return copy(serverHostName);
    }

    public void setServerHostName(byte[] serverHostName) {
        this.serverHostName = fixed(serverHostName, 64);
    }

    public byte[] getBootFileName() {
        return copy(bootFileName);
    }

    public void setBootFileName(byte[] bootFileName) {
        this.bootFileName = fixed(bootFileName, 128);
    }

    public Map<Integer, byte[]> getOptions() {
        Map<Integer, byte[]> copy = new LinkedHashMap<>();
        options.forEach((key, value) -> copy.put(key, copy(value)));
        return copy;
    }

    public void putOption(int code, byte[] value) {
        if (code <= 0 || code >= 255) {
            throw new IllegalArgumentException("DHCP option code must be between 1 and 254");
        }
        if (value == null || value.length > 255) {
            throw new IllegalArgumentException("DHCP option value must be 1-255 bytes");
        }
        options.put(code, copy(value));
    }

    public Optional<byte[]> option(int code) {
        return Optional.ofNullable(options.get(code)).map(DhcpMessage::copy);
    }

    public Optional<DhcpMessageType> messageType() {
        return option(DhcpOptionCode.MESSAGE_TYPE)
                .filter(value -> value.length == 1)
                .map(value -> DhcpMessageType.fromCode(value[0] & 0xff));
    }

    public boolean isBroadcastFlagSet() {
        return (flags & 0x8000) != 0;
    }

    private static byte[] copy4(byte[] source) {
        if (source == null || source.length != 4) {
            throw new IllegalArgumentException("IPv4 address field must contain exactly 4 bytes");
        }
        return copy(source);
    }

    private static byte[] fixed(byte[] source, int length) {
        if (source == null || source.length > length) {
            throw new IllegalArgumentException("Field length must be <= " + length);
        }
        byte[] target = new byte[length];
        System.arraycopy(source, 0, target, 0, source.length);
        return target;
    }

    private static byte[] copy(byte[] source) {
        return Arrays.copyOf(source, source.length);
    }
}
