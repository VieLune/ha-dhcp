package com.hadhcp.dhcp.protocol;

public final class DhcpOptionCode {

    private DhcpOptionCode() {
    }

    public static final int PAD = 0;
    public static final int SUBNET_MASK = 1;
    public static final int ROUTER = 3;
    public static final int DNS = 6;
    public static final int BROADCAST_ADDRESS = 28;
    public static final int REQUESTED_IP = 50;
    public static final int LEASE_TIME = 51;
    public static final int MESSAGE_TYPE = 53;
    public static final int SERVER_IDENTIFIER = 54;
    public static final int RENEWAL_TIME = 58;
    public static final int REBINDING_TIME = 59;
    public static final int END = 255;
}
