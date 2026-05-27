package com.hadhcp.ac.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hadhcp.config.DhcpProperties;
import com.hadhcp.config.HaProperties;
import com.hadhcp.ha.HaService;
import com.hadhcp.persistence.AcConfigEntity;
import com.hadhcp.persistence.AcConfigRepository;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AcConfigServiceTest {

    @Test
    void updatePersistsAppliesAndPublishesEffectiveConfig() {
        DhcpProperties dhcpProperties = new DhcpProperties();
        HaProperties haProperties = new HaProperties();
        AcConfigRepository repository = mock(AcConfigRepository.class);
        HaService haService = mock(HaService.class);
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, String> map = mock(IMap.class);

        when(hazelcastInstance.<String, String>getMap("ac:config")).thenReturn(map);
        when(haService.hasVip()).thenReturn(true);
        when(repository.findById(AcConfigService.EFFECTIVE_KEY)).thenReturn(Optional.empty());
        when(repository.save(any(AcConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AcConfigService service = new AcConfigService(
                dhcpProperties,
                haProperties,
                repository,
                new ObjectMapper(),
                haService,
                hazelcastInstance
        );

        AcRuntimeConfig updated = service.update(config("192.168.56.250"));

        assertThat(updated.ha().ip()).isEqualTo("192.168.56.250");
        assertThat(haProperties.getIp()).isEqualTo("192.168.56.250");
        assertThat(dhcpProperties.getLeaseSeconds()).isEqualTo(7200);
        verify(repository).save(any(AcConfigEntity.class));
        verify(map).put(eq(AcConfigService.EFFECTIVE_KEY), any(String.class));
    }

    @Test
    void updateRejectsVipInsideDhcpPool() {
        AcConfigService service = serviceWithVip();

        assertThatThrownBy(() -> service.update(config("192.168.56.150")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ha.ip must not be inside the DHCP pool");
    }

    private AcConfigService serviceWithVip() {
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, String> map = mock(IMap.class);
        when(hazelcastInstance.<String, String>getMap("ac:config")).thenReturn(map);
        HaService haService = mock(HaService.class);
        when(haService.hasVip()).thenReturn(true);
        AcConfigRepository repository = mock(AcConfigRepository.class);
        when(repository.findById(AcConfigService.EFFECTIVE_KEY)).thenReturn(Optional.empty());
        when(repository.save(any(AcConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new AcConfigService(
                new DhcpProperties(),
                new HaProperties(),
                repository,
                new ObjectMapper(),
                haService,
                hazelcastInstance
        );
    }

    private AcRuntimeConfig config(String haIp) {
        return new AcRuntimeConfig(
                new AcRuntimeConfig.Dhcp(
                        true,
                        "0.0.0.0",
                        1067,
                        1068,
                        null,
                        "192.168.56.10",
                        "255.255.255.0",
                        "192.168.56.1",
                        List.of("192.168.56.1"),
                        7200,
                        List.of("192.168.56.10"),
                        Map.of(),
                        new AcRuntimeConfig.Pool("192.168.56.100", "192.168.56.200")
                ),
                new AcRuntimeConfig.Ha(haIp, null, 24, true, 3)
        );
    }
}
