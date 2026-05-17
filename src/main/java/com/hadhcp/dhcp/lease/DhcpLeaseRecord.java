package com.hadhcp.dhcp.lease;

import java.io.Serializable;
import java.time.Instant;

public record DhcpLeaseRecord(
        String ipAddress,
        String macAddress,
        String deviceId,
        LeaseState state,
        Instant leaseStartTime,
        Instant leaseExpireTime,
        Instant lastSeenTime,
        String ownerNodeId,
        long version,
        Instant createdAt,
        Instant updatedAt
) implements Serializable {

    public boolean isUsableFor(String macAddress, Instant now) {
        return this.macAddress.equalsIgnoreCase(macAddress)
                && (state == LeaseState.ACTIVE || state == LeaseState.OFFERED)
                && leaseExpireTime != null
                && leaseExpireTime.isAfter(now);
    }

    public boolean isAvailableForReuse(Instant now) {
        return state == LeaseState.RELEASED
                || state == LeaseState.DECLINED
                || state == LeaseState.EXPIRED
                || leaseExpireTime == null
                || !leaseExpireTime.isAfter(now);
    }

    public DhcpLeaseRecord withState(LeaseState nextState, Instant now, long leaseSeconds, String ownerNodeId) {
        Instant expireTime = nextState == LeaseState.ACTIVE || nextState == LeaseState.OFFERED
                ? now.plusSeconds(leaseSeconds)
                : leaseExpireTime;
        return new DhcpLeaseRecord(
                ipAddress,
                macAddress,
                deviceId,
                nextState,
                now,
                expireTime,
                now,
                ownerNodeId,
                version + 1,
                createdAt == null ? now : createdAt,
                now
        );
    }
}
