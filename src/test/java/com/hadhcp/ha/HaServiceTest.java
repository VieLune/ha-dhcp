package com.hadhcp.ha;

import static org.assertj.core.api.Assertions.assertThat;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.config.HaProperties;
import org.junit.jupiter.api.Test;

class HaServiceTest {

    @Test
    void canServeDhcpOnlyWhenMasterVipAuthorityAndMirrorAreReady() {
        DhcpProperties dhcpProperties = new DhcpProperties();
        HaProperties haProperties = new HaProperties();
        FakeAuthority authority = new FakeAuthority();
        FakeVipInspector vipInspector = new FakeVipInspector();
        HaService service = new HaService(dhcpProperties, haProperties, authority, vipInspector);

        assertThat(service.canServeDhcp()).isFalse();

        service.updateRole(HaRole.MASTER);
        service.markLeaseMirrorLoaded();

        assertThat(service.canServeDhcp()).isTrue();

        service.updateRole(HaRole.BACKUP);
        assertThat(service.canServeDhcp()).isFalse();

        service.updateRole(HaRole.MASTER);
        authority.available = false;
        assertThat(service.canServeDhcp()).isFalse();

        authority.available = true;
        vipInspector.hasVip = false;
        assertThat(service.canServeDhcp()).isFalse();
    }

    private static class FakeAuthority implements LeaseAuthorityHealth {
        private boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int memberCount() {
            return available ? 2 : 0;
        }
    }

    private static class FakeVipInspector implements VipInspector {
        private boolean hasVip = true;

        @Override
        public boolean hasAddress(String vip, String interfaceName) {
            return hasVip;
        }
    }
}
