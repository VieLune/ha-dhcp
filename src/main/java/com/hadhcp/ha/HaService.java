package com.hadhcp.ha;

import com.hadhcp.config.DhcpProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * Exposes the local HA role, readiness gates, and DHCP serving decision.
 */
@Service
public class HaService {

    private final DhcpProperties dhcpProperties;
    private final LeaseAuthorityHealth leaseAuthorityHealth;
    private final VipInspector vipInspector;
    private final VipAddressResolver vipAddressResolver;
    private final AtomicBoolean leaseMirrorLoaded = new AtomicBoolean(false);
    private final AtomicBoolean configLoaded = new AtomicBoolean(false);

    /**
     * Creates the HA service from runtime config, lease authority health, and VIP inspection.
     */
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

    /**
     * Returns MASTER when this node currently owns the VIP, otherwise BACKUP.
     */
    public HaRole role() {
        return hasVip() ? HaRole.MASTER : HaRole.BACKUP;
    }

    /**
     * Marks the local DHCP lease mirror as loaded.
     */
    public void markLeaseMirrorLoaded() {
        leaseMirrorLoaded.set(true);
    }

    /**
     * Returns whether the local DHCP lease mirror has been loaded.
     */
    public boolean isLeaseMirrorLoaded() {
        return leaseMirrorLoaded.get();
    }

    /**
     * Marks the local effective config as loaded.
     */
    public void markConfigLoaded() {
        configLoaded.set(true);
    }

    /**
     * Returns whether the effective config has been loaded.
     */
    public boolean isConfigLoaded() {
        return configLoaded.get();
    }

    /**
     * Checks whether the configured VIP is currently bound to this node.
     */
    public boolean hasVip() {
        return vipAddressResolver.resolveVip()
                .filter(vip -> vipInspector.hasAddress(vip, vipAddressResolver.resolveInterfaceName().orElse(null)))
                .isPresent();
    }

    /**
     * Returns whether this node may answer DHCP traffic right now.
     */
    public boolean canServeDhcp() {
        return dhcpProperties.isEnabled()
                && isLeaseMirrorLoaded()
                && isConfigLoaded()
                && hasVip();
    }

    /**
     * Returns whether this node has enough local readiness to become VIP holder.
     */
    public boolean isVipEligible() {
        return dhcpProperties.isEnabled()
                && isLeaseMirrorLoaded()
                && isConfigLoaded()
                && vipAddressResolver.resolveVip().isPresent();
    }

    /**
     * Builds a current HA status snapshot for health and status APIs.
     */
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
