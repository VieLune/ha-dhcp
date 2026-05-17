package com.hadhcp.ha;

public record HaStatus(
        HaRole role,
        boolean dhcpEnabled,
        boolean hasVip,
        boolean leaseMirrorLoaded,
        boolean leaseAuthorityAvailable,
        int leaseAuthorityMembers,
        boolean canServeDhcp
) {
}
