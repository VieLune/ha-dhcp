package com.hadhcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ha")
public class HaProperties {

    private String ip;
    private String interfaceName;
    private int prefixLength = 24;
    private boolean vipManagementEnabled = true;
    private long commandTimeoutSeconds = 3;

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public void setPrefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
    }

    public boolean isVipManagementEnabled() {
        return vipManagementEnabled;
    }

    public void setVipManagementEnabled(boolean vipManagementEnabled) {
        this.vipManagementEnabled = vipManagementEnabled;
    }

    public long getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(long commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    @Deprecated
    public String getVip() {
        return ip;
    }

    @Deprecated
    public void setVip(String vip) {
        this.ip = vip;
    }
}
