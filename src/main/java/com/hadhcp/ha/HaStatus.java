package com.hadhcp.ha;

public record HaStatus(
        HaRole role,
        boolean dhcpEnabled,
        String vipAddress,
        boolean hasVip,
        boolean leaseMirrorLoaded,
        boolean configLoaded,
        boolean leaseAuthorityAvailable,
        int leaseAuthorityMembers,
        boolean canServeDhcp
) {
}
