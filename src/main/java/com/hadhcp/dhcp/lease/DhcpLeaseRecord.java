package com.hadhcp.dhcp.lease;

import java.io.Serializable;
import java.time.Instant;

/**
 * Immutable DHCP lease state stored in Hazelcast and mirrored to H2.
 */
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

    /**
     * Checks whether this record can still be used by the supplied MAC address.
     */
    public boolean isUsableFor(String macAddress, Instant now) {
        return this.macAddress.equalsIgnoreCase(macAddress)
                && (state == LeaseState.ACTIVE || state == LeaseState.OFFERED)
                && leaseExpireTime != null
                && leaseExpireTime.isAfter(now);
    }

    /**
     * Checks whether the leased IP can be reused by a different client.
     */
    public boolean isAvailableForReuse(Instant now) {
        return state == LeaseState.RELEASED
                || state == LeaseState.DECLINED
                || state == LeaseState.EXPIRED
                || leaseExpireTime == null
                || !leaseExpireTime.isAfter(now);
    }

    /**
     * Returns a copy of this lease with an updated state and timestamps.
     */
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
