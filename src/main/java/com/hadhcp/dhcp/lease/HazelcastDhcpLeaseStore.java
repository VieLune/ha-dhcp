package com.hadhcp.dhcp.lease;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class HazelcastDhcpLeaseStore implements DhcpLeaseStore {

    private final HazelcastInstance hazelcastInstance;
    private final IMap<String, DhcpLeaseRecord> leases;

    public HazelcastDhcpLeaseStore(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
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
    public void replaceAll(Collection<DhcpLeaseRecord> records) {
        Map<String, DhcpLeaseRecord> next = records.stream()
                .collect(Collectors.toMap(DhcpLeaseRecord::ipAddress, Function.identity(), (left, right) -> left));
        leases.clear();
        leases.putAll(next);
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
        return true;
    }

    @Override
    public int memberCount() {
        return hazelcastInstance.getCluster().getMembers().size();
    }
}
