package com.hadhcp.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AcConfigRepository extends JpaRepository<AcConfigEntity, String> {
}
