package com.hadhcp.persistence;

import com.hadhcp.dhcp.lease.DhcpLeaseRecord;
import org.springframework.stereotype.Component;

@Component
public class DhcpLeaseMapper {

    public DhcpLeaseRecord toRecord(DhcpLeaseEntity entity) {
        return new DhcpLeaseRecord(
                entity.getIpAddress(),
                entity.getMacAddress(),
                entity.getDeviceId(),
                entity.getState(),
                entity.getLeaseStartTime(),
                entity.getLeaseExpireTime(),
                entity.getLastSeenTime(),
                entity.getOwnerNodeId(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public DhcpLeaseEntity toEntity(DhcpLeaseRecord record) {
        DhcpLeaseEntity entity = new DhcpLeaseEntity();
        entity.setIpAddress(record.ipAddress());
        entity.setMacAddress(record.macAddress());
        entity.setDeviceId(record.deviceId());
        entity.setState(record.state());
        entity.setLeaseStartTime(record.leaseStartTime());
        entity.setLeaseExpireTime(record.leaseExpireTime());
        entity.setLastSeenTime(record.lastSeenTime());
        entity.setOwnerNodeId(record.ownerNodeId());
        entity.setVersion(record.version());
        entity.setCreatedAt(record.createdAt());
        entity.setUpdatedAt(record.updatedAt());
        return entity;
    }
}
