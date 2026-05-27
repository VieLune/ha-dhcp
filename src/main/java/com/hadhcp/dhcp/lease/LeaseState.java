package com.hadhcp.dhcp.lease;

/**
 * DHCP lease lifecycle states used by allocation, expiration, and recovery.
 */
public enum LeaseState {
    OFFERED,
    ACTIVE,
    EXPIRED,
    RELEASED,
    DECLINED,
    ABANDONED
}
