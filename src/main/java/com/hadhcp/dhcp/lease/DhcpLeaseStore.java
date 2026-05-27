package com.hadhcp.dhcp.lease;

import com.hadhcp.ha.LeaseAuthorityHealth;
import java.util.Collection;
import java.util.Optional;

/**
 * Defines the distributed DHCP lease authority contract.
 */
public interface DhcpLeaseStore extends LeaseAuthorityHealth {

    /**
     * Finds a lease by IP address.
     */
    Optional<DhcpLeaseRecord> findByIp(String ipAddress);

    /**
     * Finds a lease by MAC address.
     */
    Optional<DhcpLeaseRecord> findByMac(String macAddress);

    /**
     * Inserts a lease only when the IP is not already present.
     */
    boolean putIfAbsent(DhcpLeaseRecord lease);

    /**
     * Upserts a lease by IP address.
     */
    void put(DhcpLeaseRecord lease);

    /**
     * Replaces the entire authority view.
     */
    void replaceAll(Collection<DhcpLeaseRecord> records);

    /**
     * Returns all lease records in the authority.
     */
    Collection<DhcpLeaseRecord> values();
}
