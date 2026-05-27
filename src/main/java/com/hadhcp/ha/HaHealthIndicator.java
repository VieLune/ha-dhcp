package com.hadhcp.ha;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ha")
public class HaHealthIndicator implements HealthIndicator {

    private final HaService haService;

    public HaHealthIndicator(HaService haService) {
        this.haService = haService;
    }

    @Override
    public Health health() {
        HaStatus status = haService.status();
        Health.Builder builder = haService.isVipEligible() ? Health.up() : Health.down();
        return builder
                .withDetail("role", status.role())
                .withDetail("dhcpEnabled", status.dhcpEnabled())
                .withDetail("vipAddress", status.vipAddress())
                .withDetail("hasVip", status.hasVip())
                .withDetail("leaseMirrorLoaded", status.leaseMirrorLoaded())
                .withDetail("configLoaded", status.configLoaded())
                .withDetail("leaseAuthorityAvailable", status.leaseAuthorityAvailable())
                .withDetail("leaseAuthorityMembers", status.leaseAuthorityMembers())
                .withDetail("canServeDhcp", status.canServeDhcp())
                .build();
    }
}
