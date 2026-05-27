package com.hadhcp.ha;

import com.hadhcp.config.DhcpProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class HaService {

    private final DhcpProperties dhcpProperties;
    private final LeaseAuthorityHealth leaseAuthorityHealth;
    private final VipInspector vipInspector;
    private final VipAddressResolver vipAddressResolver;
    private final AtomicBoolean leaseMirrorLoaded = new AtomicBoolean(false);
    private final AtomicBoolean configLoaded = new AtomicBoolean(false);

    public HaService(
            DhcpProperties dhcpProperties,
            LeaseAuthorityHealth leaseAuthorityHealth,
            VipInspector vipInspector,
            VipAddressResolver vipAddressResolver
    ) {
        this.dhcpProperties = dhcpProperties;
        this.leaseAuthorityHealth = leaseAuthorityHealth;
        this.vipInspector = vipInspector;
        this.vipAddressResolver = vipAddressResolver;
    }

    public HaRole role() {
        return hasVip() ? HaRole.MASTER : HaRole.BACKUP;
    }

    public void markLeaseMirrorLoaded() {
        leaseMirrorLoaded.set(true);
    }

    public boolean isLeaseMirrorLoaded() {
        return leaseMirrorLoaded.get();
    }

    public void markConfigLoaded() {
        configLoaded.set(true);
    }

    public boolean isConfigLoaded() {
        return configLoaded.get();
    }

    public boolean hasVip() {
        return vipAddressResolver.resolveVip()
                .filter(vip -> vipInspector.hasAddress(vip, vipAddressResolver.resolveInterfaceName().orElse(null)))
                .isPresent();
    }

    public boolean canServeDhcp() {
        return dhcpProperties.isEnabled()
                && isLeaseMirrorLoaded()
                && isConfigLoaded()
                && hasVip();
    }

    public boolean isVipEligible() {
        return dhcpProperties.isEnabled()
                && isLeaseMirrorLoaded()
                && isConfigLoaded()
                && vipAddressResolver.resolveVip().isPresent();
    }

    public HaStatus status() {
        boolean hasVip = hasVip();
        boolean authorityAvailable = leaseAuthorityHealth.isAvailable();
        boolean canServe = dhcpProperties.isEnabled()
                && isLeaseMirrorLoaded()
                && isConfigLoaded()
                && hasVip;
        return new HaStatus(
                hasVip ? HaRole.MASTER : HaRole.BACKUP,
                dhcpProperties.isEnabled(),
                vipAddressResolver.resolveVip().orElse(null),
                hasVip,
                isLeaseMirrorLoaded(),
                isConfigLoaded(),
                authorityAvailable,
                leaseAuthorityHealth.memberCount(),
                canServe
        );
    }
}
