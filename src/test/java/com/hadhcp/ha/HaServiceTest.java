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
        haProperties.setIp("127.0.0.1");
        FakeAuthority authority = new FakeAuthority();
        FakeVipInspector vipInspector = new FakeVipInspector();
        VipAddressResolver vipAddressResolver = new VipAddressResolver(haProperties, dhcpProperties);
        HaService service = new HaService(dhcpProperties, authority, vipInspector, vipAddressResolver);

        assertThat(service.canServeDhcp()).isFalse();

        service.markLeaseMirrorLoaded();
        service.markConfigLoaded();

        assertThat(service.canServeDhcp()).isTrue();
        assertThat(service.role()).isEqualTo(HaRole.MASTER);
        authority.available = false;
        assertThat(service.canServeDhcp()).isTrue();

        authority.available = true;
        vipInspector.hasVip = false;
        assertThat(service.canServeDhcp()).isFalse();
        assertThat(service.role()).isEqualTo(HaRole.BACKUP);
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
