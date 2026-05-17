package com.hadhcp.dhcp.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.ha.HaService;
import com.hadhcp.persistence.DhcpLeaseEntity;
import com.hadhcp.persistence.DhcpLeaseMapper;
import com.hadhcp.persistence.DhcpLeaseRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DhcpLeaseServiceTest {

    @Test
    void offerLeaseUsesReservationBeforeDynamicPool() {
        DhcpProperties properties = new DhcpProperties();
        properties.getReservations().put("aa:bb:cc:dd:ee:ff", "192.168.56.150");
        FakeStore store = new FakeStore();
        DhcpLeaseService service = service(properties, store);

        Optional<DhcpLeaseRecord> lease = service.offerLease("aa:bb:cc:dd:ee:ff");

        assertThat(lease).isPresent();
        assertThat(lease.get().ipAddress()).isEqualTo("192.168.56.150");
        assertThat(lease.get().state()).isEqualTo(LeaseState.OFFERED);
    }

    @Test
    void offerLeaseReusesExistingLeaseForSameMac() {
        DhcpProperties properties = new DhcpProperties();
        FakeStore store = new FakeStore();
        DhcpLeaseService service = service(properties, store);

        DhcpLeaseRecord first = service.offerLease("aa:bb:cc:dd:ee:ff").orElseThrow();
        DhcpLeaseRecord second = service.offerLease("aa:bb:cc:dd:ee:ff").orElseThrow();

        assertThat(second.ipAddress()).isEqualTo(first.ipAddress());
    }

    @Test
    void dynamicOfferSkipsExcludedAndReservedAddresses() {
        DhcpProperties properties = new DhcpProperties();
        properties.getPool().setStart("192.168.56.100");
        properties.getPool().setEnd("192.168.56.102");
        properties.getExcludedAddresses().add("192.168.56.100");
        properties.getReservations().put("00:11:22:33:44:55", "192.168.56.101");
        FakeStore store = new FakeStore();
        DhcpLeaseService service = service(properties, store);

        DhcpLeaseRecord lease = service.offerLease("aa:bb:cc:dd:ee:ff").orElseThrow();

        assertThat(lease.ipAddress()).isEqualTo("192.168.56.102");
    }

    private DhcpLeaseService service(DhcpProperties properties, FakeStore store) {
        DhcpLeaseRepository repository = mock(DhcpLeaseRepository.class);
        when(repository.findByMacAddressIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(DhcpLeaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new DhcpLeaseService(
                properties,
                store,
                repository,
                new DhcpLeaseMapper(),
                mock(HaService.class)
        );
    }

    private static class FakeStore implements DhcpLeaseStore {
        private final Map<String, DhcpLeaseRecord> leases = new LinkedHashMap<>();

        @Override
        public Optional<DhcpLeaseRecord> findByIp(String ipAddress) {
            return Optional.ofNullable(leases.get(ipAddress));
        }

        @Override
        public Optional<DhcpLeaseRecord> findByMac(String macAddress) {
            return leases.values().stream()
                    .filter(lease -> lease.macAddress().equalsIgnoreCase(macAddress))
                    .findFirst();
        }

        @Override
        public boolean putIfAbsent(DhcpLeaseRecord lease) {
            return leases.putIfAbsent(lease.ipAddress(), lease) == null;
        }

        @Override
        public void put(DhcpLeaseRecord lease) {
            leases.put(lease.ipAddress(), lease);
        }

        @Override
        public Collection<DhcpLeaseRecord> values() {
            return leases.values();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int memberCount() {
            return 1;
        }
    }
}
