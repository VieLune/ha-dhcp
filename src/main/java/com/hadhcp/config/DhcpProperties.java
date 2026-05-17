package com.hadhcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "dhcp")
public class DhcpProperties {

    private boolean enabled = true;
    @NotBlank
    private String bindAddress = "0.0.0.0";
    @Min(1)
    private int port = 1067;
    @Min(1)
    private int clientPort = 1068;
    private String interfaceName;
    @NotBlank
    private String serverIdentifier = "127.0.0.1";
    @NotBlank
    private String subnetMask = "255.255.255.0";
    @NotBlank
    private String router = "192.168.56.1";
    private List<String> dnsServers = new ArrayList<>();
    @Min(60)
    private long leaseSeconds = 3600;
    private List<String> excludedAddresses = new ArrayList<>();
    private Map<String, String> reservations = new LinkedHashMap<>();
    @Valid
    private Pool pool = new Pool();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getClientPort() {
        return clientPort;
    }

    public void setClientPort(int clientPort) {
        this.clientPort = clientPort;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public String getServerIdentifier() {
        return serverIdentifier;
    }

    public void setServerIdentifier(String serverIdentifier) {
        this.serverIdentifier = serverIdentifier;
    }

    public String getSubnetMask() {
        return subnetMask;
    }

    public void setSubnetMask(String subnetMask) {
        this.subnetMask = subnetMask;
    }

    public String getRouter() {
        return router;
    }

    public void setRouter(String router) {
        this.router = router;
    }

    public List<String> getDnsServers() {
        return dnsServers;
    }

    public void setDnsServers(List<String> dnsServers) {
        this.dnsServers = dnsServers;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public List<String> getExcludedAddresses() {
        return excludedAddresses;
    }

    public void setExcludedAddresses(List<String> excludedAddresses) {
        this.excludedAddresses = excludedAddresses;
    }

    public Map<String, String> getReservations() {
        return reservations;
    }

    public void setReservations(Map<String, String> reservations) {
        this.reservations = reservations;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    public static class Pool {
        @NotBlank
        private String start = "192.168.56.100";
        @NotBlank
        private String end = "192.168.56.200";

        public String getStart() {
            return start;
        }

        public void setStart(String start) {
            this.start = start;
        }

        public String getEnd() {
            return end;
        }

        public void setEnd(String end) {
            this.end = end;
        }
    }
}
