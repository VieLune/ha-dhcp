package com.hadhcp.dhcp.lease;

import com.hadhcp.config.HaProperties;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import java.util.Collection;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class HazelcastDhcpLeaseStore implements DhcpLeaseStore {

    private final HazelcastInstance hazelcastInstance;
    private final HaProperties haProperties;
    private final IMap<String, DhcpLeaseRecord> leases;

    public HazelcastDhcpLeaseStore(HazelcastInstance hazelcastInstance, HaProperties haProperties) {
        this.hazelcastInstance = hazelcastInstance;
        this.haProperties = haProperties;
        this.leases = hazelcastInstance.getMap("dhcp:leases");
    }

    @Override
    public Optional<DhcpLeaseRecord> findByIp(String ipAddress) {
        return Optional.ofNullable(leases.get(ipAddress));
    }

    @Override
    public Optional<DhcpLeaseRecord> findByMac(String macAddress) {
        return leases.values().stream()
                .filter(lease -> lease.macAddress().equalsIgnoreCase(macAddress))
                .findFirst();
    }

    @Override
    public boolean putIfAbsent(DhcpLeaseRecord lease) {
        return leases.putIfAbsent(lease.ipAddress(), lease) == null;
    }

    @Override
    public void put(DhcpLeaseRecord lease) {
        leases.put(lease.ipAddress(), lease);
    }

    @Override
    public Collection<DhcpLeaseRecord> values() {
        return leases.values();
    }

    @Override
    public boolean isAvailable() {
        LifecycleService lifecycleService = hazelcastInstance.getLifecycleService();
        if (!lifecycleService.isRunning()) {
            return false;
        }
        return !haProperties.isRequireMajority() || memberCount() >= haProperties.getMinimumClusterSize();
    }

    @Override
    public int memberCount() {
        return hazelcastInstance.getCluster().getMembers().size();
    }
}
