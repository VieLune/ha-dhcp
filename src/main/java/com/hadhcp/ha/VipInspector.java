package com.hadhcp.ha;

public interface VipInspector {

    boolean hasAddress(String vip, String interfaceName);
}
