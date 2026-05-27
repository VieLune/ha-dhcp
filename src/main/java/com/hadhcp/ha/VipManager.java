package com.hadhcp.ha;

public interface VipManager {

    boolean ensureVip(String vip, String interfaceName, int prefixLength);

    boolean releaseVip(String vip, String interfaceName);
}
