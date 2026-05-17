package com.hadhcp.dhcp.protocol;

import com.hadhcp.dhcp.util.Ipv4Addresses;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

public final class DhcpOptions {

    private DhcpOptions() {
    }

    public static byte[] messageType(DhcpMessageType type) {
        return new byte[] {(byte) type.code()};
    }

    public static byte[] ipv4(String address) {
        return Ipv4Addresses.toBytes(address);
    }

    public static byte[] ipv4List(List<String> addresses) {
        ByteBuffer buffer = ByteBuffer.allocate(addresses.size() * 4);
        addresses.forEach(address -> buffer.put(Ipv4Addresses.toBytes(address)));
        return buffer.array();
    }

    public static byte[] uint32(long value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt((int) value).array();
    }

    public static Optional<String> requestedIp(DhcpMessage message) {
        return message.option(DhcpOptionCode.REQUESTED_IP)
                .filter(value -> value.length == 4)
                .map(Ipv4Addresses::fromBytes);
    }
}
