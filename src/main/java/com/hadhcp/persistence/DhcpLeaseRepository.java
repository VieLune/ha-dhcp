package com.hadhcp.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DhcpLeaseRepository extends JpaRepository<DhcpLeaseEntity, String> {

    Optional<DhcpLeaseEntity> findByMacAddressIgnoreCase(String macAddress);
}
