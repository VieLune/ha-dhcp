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

/**
 * Implements the DHCP lease authority on top of Hazelcast's distributed map.
 */
@Component
public class HazelcastDhcpLeaseStore implements DhcpLeaseStore {

    private final HazelcastInstance hazelcastInstance;
    private final IMap<String, DhcpLeaseRecord> leases;

    /**
     * Creates the store from an embedded Hazelcast member.
     *
     * @param hazelcastInstance Hazelcast member that owns the dhcp:leases map
     */
    public HazelcastDhcpLeaseStore(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
        this.leases = hazelcastInstance.getMap("dhcp:leases");
    }

    /**
     * Finds a lease record by IP address.
     */
    @Override
    public Optional<DhcpLeaseRecord> findByIp(String ipAddress) {
        return Optional.ofNullable(leases.get(ipAddress));
    }

    /**
     * Finds a lease record by client MAC address.
     */
    @Override
    public Optional<DhcpLeaseRecord> findByMac(String macAddress) {
        return leases.values().stream()
                .filter(lease -> lease.macAddress().equalsIgnoreCase(macAddress))
                .findFirst();
    }

    /**
     * Inserts a lease only when its IP is not already present.
     */
    @Override
    public boolean putIfAbsent(DhcpLeaseRecord lease) {
        return leases.putIfAbsent(lease.ipAddress(), lease) == null;
    }

    /**
     * Upserts a lease record by IP address.
     */
    @Override
    public void put(DhcpLeaseRecord lease) {
        leases.put(lease.ipAddress(), lease);
    }

    /**
     * Replaces the whole distributed lease view with the supplied records.
     */
    @Override
    public void replaceAll(Collection<DhcpLeaseRecord> records) {
        Map<String, DhcpLeaseRecord> next = records.stream()
                .collect(Collectors.toMap(DhcpLeaseRecord::ipAddress, Function.identity(), (left, right) -> left));
        leases.clear();
        leases.putAll(next);
    }

    /**
     * Returns all distributed lease records.
     */
    @Override
    public Collection<DhcpLeaseRecord> values() {
        return leases.values();
    }

    /**
     * Returns whether the Hazelcast lifecycle is running.
     */
    @Override
    public boolean isAvailable() {
        LifecycleService lifecycleService = hazelcastInstance.getLifecycleService();
        if (!lifecycleService.isRunning()) {
            return false;
        }
        return true;
    }

    /**
     * Returns the currently visible Hazelcast member count.
     */
    @Override
    public int memberCount() {
        return hazelcastInstance.getCluster().getMembers().size();
    }
}
