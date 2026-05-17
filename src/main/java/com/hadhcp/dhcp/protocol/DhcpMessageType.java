package com.hadhcp.dhcp.protocol;

import java.util.Arrays;

public enum DhcpMessageType {
    DISCOVER(1),
    OFFER(2),
    REQUEST(3),
    DECLINE(4),
    ACK(5),
    NAK(6),
    RELEASE(7),
    INFORM(8);

    private final int code;

    DhcpMessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DhcpMessageType fromCode(int code) {
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported DHCP message type: " + code));
    }
}
