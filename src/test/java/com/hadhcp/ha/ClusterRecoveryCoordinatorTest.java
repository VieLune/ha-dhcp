package com.hadhcp.ha;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hadhcp.ac.config.AcConfigService;
import com.hadhcp.dhcp.lease.DhcpLeaseService;
import com.hadhcp.edge.EdgeDeviceService;
import org.junit.jupiter.api.Test;

class ClusterRecoveryCoordinatorTest {

    @Test
    void resolvesAllRecoveredViewsWhenMirrorsAreReady() {
        AcConfigService acConfigService = mock(AcConfigService.class);
        DhcpLeaseService dhcpLeaseService = mock(DhcpLeaseService.class);
        EdgeDeviceService edgeDeviceService = mock(EdgeDeviceService.class);
        HaService haService = mock(HaService.class);
        when(haService.isLeaseMirrorLoaded()).thenReturn(true);
        when(haService.isConfigLoaded()).thenReturn(true);
        ClusterRecoveryCoordinator coordinator = new ClusterRecoveryCoordinator(
                acConfigService,
                dhcpLeaseService,
                edgeDeviceService,
                haService
        );

        coordinator.resolveMergeConflicts("test");

        verify(acConfigService).syncRecoveredConfig();
        verify(dhcpLeaseService).syncRecoveredLeases();
        verify(edgeDeviceService).syncRecoveredDevices();
    }

    @Test
    void skipsRecoveryUntilLocalMirrorsAreReady() {
        AcConfigService acConfigService = mock(AcConfigService.class);
        DhcpLeaseService dhcpLeaseService = mock(DhcpLeaseService.class);
        EdgeDeviceService edgeDeviceService = mock(EdgeDeviceService.class);
        HaService haService = mock(HaService.class);
        ClusterRecoveryCoordinator coordinator = new ClusterRecoveryCoordinator(
                acConfigService,
                dhcpLeaseService,
                edgeDeviceService,
                haService
        );

        coordinator.resolveMergeConflicts("test");

        verifyNoInteractions(acConfigService, dhcpLeaseService, edgeDeviceService);
    }
}
