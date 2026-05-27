package com.hadhcp.dhcp.lease;

import com.hadhcp.ha.LeaseAuthorityHealth;
import java.util.Collection;
import java.util.Optional;

public interface DhcpLeaseStore extends LeaseAuthorityHealth {

    Optional<DhcpLeaseRecord> findByIp(String ipAddress);

    Optional<DhcpLeaseRecord> findByMac(String macAddress);

    boolean putIfAbsent(DhcpLeaseRecord lease);

    void put(DhcpLeaseRecord lease);

    void replaceAll(Collection<DhcpLeaseRecord> records);

    Collection<DhcpLeaseRecord> values();
}
