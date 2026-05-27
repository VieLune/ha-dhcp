package com.hadhcp.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hadhcp.ha.HaService;
import com.hadhcp.persistence.EdgeDeviceEntity;
import com.hadhcp.persistence.EdgeDeviceRepository;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EdgeDeviceServiceTest {

    @Test
    void upsertRequiresVipHolderAndPublishesSnToIp() {
        EdgeDeviceRepository repository = mock(EdgeDeviceRepository.class);
        HaService haService = mock(HaService.class);
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, String> map = mock(IMap.class);

        when(hazelcastInstance.<String, String>getMap("edge:devices")).thenReturn(map);
        when(haService.hasVip()).thenReturn(true);
        when(repository.findById("SN-001")).thenReturn(Optional.empty());
        when(repository.findByIpAddress("192.168.56.210")).thenReturn(Optional.empty());
        when(repository.save(any(EdgeDeviceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(map.entrySet()).thenReturn(Set.of());

        EdgeDeviceService service = new EdgeDeviceService(repository, haService, hazelcastInstance);
        EdgeDeviceResponse response = service.upsert(" SN-001 ", new EdgeDeviceUpdateRequest("192.168.56.210"));

        assertThat(response.sn()).isEqualTo("SN-001");
        assertThat(response.ip()).isEqualTo("192.168.56.210");
        verify(map).put("SN-001", "192.168.56.210");
    }

    @Test
    void upsertRejectsNonVipNode() {
        EdgeDeviceRepository repository = mock(EdgeDeviceRepository.class);
        HaService haService = mock(HaService.class);
        HazelcastInstance hazelcastInstance = mock(HazelcastInstance.class);
        @SuppressWarnings("unchecked")
        IMap<String, String> map = mock(IMap.class);

        when(hazelcastInstance.<String, String>getMap("edge:devices")).thenReturn(map);
        when(haService.hasVip()).thenReturn(false);

        EdgeDeviceService service = new EdgeDeviceService(repository, haService, hazelcastInstance);

        assertThatThrownBy(() -> service.upsert("SN-001", new EdgeDeviceUpdateRequest("192.168.56.210")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only the VIP holder");
    }
}
