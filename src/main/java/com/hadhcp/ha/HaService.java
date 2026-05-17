package com.hadhcp.ha;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.config.HaProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class HaService {

    private final DhcpProperties dhcpProperties;
    private final HaProperties haProperties;
    private final LeaseAuthorityHealth leaseAuthorityHealth;
    private final VipInspector vipInspector;
    private final AtomicReference<HaRole> role = new AtomicReference<>(HaRole.UNKNOWN);
    private final AtomicBoolean leaseMirrorLoaded = new AtomicBoolean(false);

    public HaService(
            DhcpProperties dhcpProperties,
            HaProperties haProperties,
            LeaseAuthorityHealth leaseAuthorityHealth,
            VipInspector vipInspector
    ) {
        this.dhcpProperties = dhcpProperties;
        this.haProperties = haProperties;
        this.leaseAuthorityHealth = leaseAuthorityHealth;
        this.vipInspector = vipInspector;
    }

    public HaRole role() {
        return role.get();
    }

    public HaRole updateRole(HaRole nextRole) {
        if (nextRole == null) {
            nextRole = HaRole.UNKNOWN;
        }
        role.set(nextRole);
        return nextRole;
    }

    public void markLeaseMirrorLoaded() {
        leaseMirrorLoaded.set(true);
    }

    public boolean isLeaseMirrorLoaded() {
        return leaseMirrorLoaded.get();
    }

    public boolean hasVip() {
        return vipInspector.hasAddress(haProperties.getVip(), effectiveInterfaceName());
    }

    public boolean canServeDhcp() {
        return dhcpProperties.isEnabled()
                && role.get() == HaRole.MASTER
                && isLeaseMirrorLoaded()
                && leaseAuthorityHealth.isAvailable()
                && hasVip();
    }

    public boolean isVipEligible() {
        HaRole current = role.get();
        return dhcpProperties.isEnabled()
                && current != HaRole.FAULT
                && current != HaRole.STOP
                && current != HaRole.MAINTENANCE
                && isLeaseMirrorLoaded()
                && leaseAuthorityHealth.isAvailable();
    }

    public HaStatus status() {
        boolean hasVip = hasVip();
        boolean authorityAvailable = leaseAuthorityHealth.isAvailable();
        boolean canServe = dhcpProperties.isEnabled()
                && role.get() == HaRole.MASTER
                && isLeaseMirrorLoaded()
                && authorityAvailable
                && hasVip;
        return new HaStatus(
                role.get(),
                dhcpProperties.isEnabled(),
                hasVip,
                isLeaseMirrorLoaded(),
                authorityAvailable,
                leaseAuthorityHealth.memberCount(),
                canServe
        );
    }

    private String effectiveInterfaceName() {
        if (haProperties.getInterfaceName() != null && !haProperties.getInterfaceName().isBlank()) {
            return haProperties.getInterfaceName();
        }
        return dhcpProperties.getInterfaceName();
    }
}
