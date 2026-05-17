package com.hadhcp.persistence;

import com.hadhcp.dhcp.lease.LeaseState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "dhcp_lease")
public class DhcpLeaseEntity {

    @Id
    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "mac_address", nullable = false, unique = true, length = 32)
    private String macAddress;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lease_state", nullable = false, length = 32)
    private LeaseState state;

    @Column(name = "lease_start_time")
    private Instant leaseStartTime;

    @Column(name = "lease_expire_time")
    private Instant leaseExpireTime;

    @Column(name = "last_seen_time")
    private Instant lastSeenTime;

    @Column(name = "owner_node_id", length = 128)
    private String ownerNodeId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public LeaseState getState() {
        return state;
    }

    public void setState(LeaseState state) {
        this.state = state;
    }

    public Instant getLeaseStartTime() {
        return leaseStartTime;
    }

    public void setLeaseStartTime(Instant leaseStartTime) {
        this.leaseStartTime = leaseStartTime;
    }

    public Instant getLeaseExpireTime() {
        return leaseExpireTime;
    }

    public void setLeaseExpireTime(Instant leaseExpireTime) {
        this.leaseExpireTime = leaseExpireTime;
    }

    public Instant getLastSeenTime() {
        return lastSeenTime;
    }

    public void setLastSeenTime(Instant lastSeenTime) {
        this.lastSeenTime = lastSeenTime;
    }

    public String getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(String ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
