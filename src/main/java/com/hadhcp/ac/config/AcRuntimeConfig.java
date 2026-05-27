package com.hadhcp.ac.config;

import java.util.List;
import java.util.Map;

public record AcRuntimeConfig(
        Dhcp dhcp,
        Ha ha
) {

    public record Dhcp(
            boolean enabled,
            String bindAddress,
            int port,
            int clientPort,
            String interfaceName,
            String serverIdentifier,
            String subnetMask,
            String router,
            List<String> dnsServers,
            long leaseSeconds,
            List<String> excludedAddresses,
            Map<String, String> reservations,
            Pool pool
    ) {
    }

    public record Pool(
            String start,
            String end
    ) {
    }

    public record Ha(
            String ip,
            String interfaceName,
            int prefixLength,
            boolean vipManagementEnabled,
            long commandTimeoutSeconds
    ) {
    }
}
