package com.hadhcp.ha;

import com.hadhcp.ac.config.AcConfigService;
import com.hadhcp.dhcp.lease.DhcpLeaseService;
import com.hadhcp.edge.EdgeDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Coordinates cross-domain recovery work after a split-brain partition rejoins.
 */
@Component
public class ClusterRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ClusterRecoveryCoordinator.class);

    private final AcConfigService acConfigService;
    private final DhcpLeaseService dhcpLeaseService;
    private final EdgeDeviceService edgeDeviceService;
    private final HaService haService;

    /**
     * Creates the coordinator for config, DHCP lease, and edge device recovery.
     *
     * @param acConfigService service that reconciles AC runtime config
     * @param dhcpLeaseService service that reconciles DHCP leases
     * @param edgeDeviceService service that reconciles edge device mappings
     * @param haService service that exposes local mirror readiness
     */
    public ClusterRecoveryCoordinator(
            AcConfigService acConfigService,
            DhcpLeaseService dhcpLeaseService,
            EdgeDeviceService edgeDeviceService,
            HaService haService
    ) {
        this.acConfigService = acConfigService;
        this.dhcpLeaseService = dhcpLeaseService;
        this.edgeDeviceService = edgeDeviceService;
        this.haService = haService;
    }

    /**
     * Runs all recovery merge routines for the current node role.
     *
     * @param reason human-readable event source used in logs
     */
    public void resolveMergeConflicts(String reason) {
        if (!haService.isLeaseMirrorLoaded() || !haService.isConfigLoaded()) {
            log.info("Skipping recovered cluster conflict resolution until local mirrors are loaded: reason={}", reason);
            return;
        }

        log.info("Resolving recovered cluster conflicts: reason={}", reason);
        acConfigService.syncRecoveredConfig();
        dhcpLeaseService.syncRecoveredLeases();
        edgeDeviceService.syncRecoveredDevices();
    }
}
