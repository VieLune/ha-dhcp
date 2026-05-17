package com.hadhcp.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ha")
public class HaProperties {

    @NotBlank
    private String vip = "127.0.0.1";
    private String interfaceName;
    private boolean requireMajority = false;
    @Min(1)
    private int minimumClusterSize = 2;

    public String getVip() {
        return vip;
    }

    public void setVip(String vip) {
        this.vip = vip;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public boolean isRequireMajority() {
        return requireMajority;
    }

    public void setRequireMajority(boolean requireMajority) {
        this.requireMajority = requireMajority;
    }

    public int getMinimumClusterSize() {
        return minimumClusterSize;
    }

    public void setMinimumClusterSize(int minimumClusterSize) {
        this.minimumClusterSize = minimumClusterSize;
    }
}
