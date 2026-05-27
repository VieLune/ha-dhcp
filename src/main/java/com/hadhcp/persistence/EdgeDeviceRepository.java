package com.hadhcp.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EdgeDeviceRepository extends JpaRepository<EdgeDeviceEntity, String> {

    Optional<EdgeDeviceEntity> findByIpAddress(String ipAddress);
}
