package com.hadhcp.dhcp.lease;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.dhcp.util.Ipv4Addresses;
import com.hadhcp.dhcp.util.MacAddresses;
import com.hadhcp.ha.HaService;
import com.hadhcp.persistence.DhcpLeaseEntity;
import com.hadhcp.persistence.DhcpLeaseMapper;
import com.hadhcp.persistence.DhcpLeaseRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DhcpLeaseService {

    private static final Logger log = LoggerFactory.getLogger(DhcpLeaseService.class);

    private final DhcpProperties properties;
    private final DhcpLeaseStore store;
    private final DhcpLeaseRepository repository;
    private final DhcpLeaseMapper mapper;
    private final HaService haService;
    private final Clock clock;
    private final String ownerNodeId;

    public DhcpLeaseService(
            DhcpProperties properties,
            DhcpLeaseStore store,
            DhcpLeaseRepository repository,
            DhcpLeaseMapper mapper,
            HaService haService
    ) {
        this.properties = properties;
        this.store = store;
        this.repository = repository;
        this.mapper = mapper;
        this.haService = haService;
        this.clock = Clock.systemUTC();
        this.ownerNodeId = resolveOwnerNodeId();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadMirrorIntoAuthority() {
        int merged = 0;
        for (DhcpLeaseEntity entity : repository.findAll()) {
            DhcpLeaseRecord local = mapper.toRecord(entity);
            Optional<DhcpLeaseRecord> remote = store.findByIp(local.ipAddress());
            if (remote.isEmpty() || isNewer(local, remote.get())) {
                store.put(local);
                merged++;
            } else if (isNewer(remote.get(), local)) {
                saveMirror(remote.get());
            }
        }
        haService.markLeaseMirrorLoaded();
        log.info("DHCP lease mirror loaded into authority: merged={} localRows={}", merged, repository.count());
    }

    @Transactional
    public Optional<DhcpLeaseRecord> offerLease(String macAddress) {
        String mac = MacAddresses.normalize(macAddress);
        Instant now = clock.instant();

        Optional<DhcpLeaseRecord> existing = findReusableLeaseByMac(mac, now);
        if (existing.isPresent()) {
            DhcpLeaseRecord offered = existing.get().withState(LeaseState.OFFERED, now, properties.getLeaseSeconds(), ownerNodeId);
            store.put(offered);
            saveMirror(offered);
            return Optional.of(offered);
        }

        Optional<String> reserved = reservedIp(mac);
        if (reserved.isPresent()) {
            return occupyCandidate(mac, reserved.get(), LeaseState.OFFERED, now);
        }

        long start = Integer.toUnsignedLong(Ipv4Addresses.toInt(properties.getPool().getStart()));
        long end = Integer.toUnsignedLong(Ipv4Addresses.toInt(properties.getPool().getEnd()));
        if (start > end) {
            throw new IllegalStateException("DHCP pool start must be before or equal to end");
        }

        for (long candidate = start; candidate <= end; candidate++) {
            String ipAddress = Ipv4Addresses.fromInt((int) candidate);
            if (!isDynamicCandidateAllowed(ipAddress, mac)) {
                continue;
            }
            Optional<DhcpLeaseRecord> lease = occupyCandidate(mac, ipAddress, LeaseState.OFFERED, now);
            if (lease.isPresent()) {
                return lease;
            }
        }
        return Optional.empty();
    }

    @Transactional
    public Optional<DhcpLeaseRecord> acknowledgeLease(String macAddress, Optional<String> requestedIp) {
        String mac = MacAddresses.normalize(macAddress);
        Instant now = clock.instant();

        Optional<DhcpLeaseRecord> existing = findReusableLeaseByMac(mac, now);
        if (existing.isPresent()) {
            DhcpLeaseRecord lease = existing.get();
            if (requestedIp.isPresent() && !lease.ipAddress().equals(requestedIp.get())) {
                return Optional.empty();
            }
            DhcpLeaseRecord active = lease.withState(LeaseState.ACTIVE, now, properties.getLeaseSeconds(), ownerNodeId);
            store.put(active);
            saveMirror(active);
            return Optional.of(active);
        }

        if (requestedIp.isEmpty()) {
            return Optional.empty();
        }

        String requested = requestedIp.get();
        if (!isAddressAllowedForMac(requested, mac)) {
            return Optional.empty();
        }
        return occupyCandidate(mac, requested, LeaseState.ACTIVE, now);
    }

    @Transactional
    public void releaseLease(String macAddress) {
        transitionMacLease(macAddress, LeaseState.RELEASED);
    }

    @Transactional
    public void declineLease(String macAddress) {
        transitionMacLease(macAddress, LeaseState.DECLINED);
    }

    @Scheduled(fixedDelayString = "${dhcp.expiration-scan-ms:60000}")
    @Transactional
    public void expireLeases() {
        if (!haService.canServeDhcp()) {
            return;
        }
        Instant now = clock.instant();
        for (DhcpLeaseRecord lease : store.values()) {
            if ((lease.state() == LeaseState.ACTIVE || lease.state() == LeaseState.OFFERED)
                    && lease.leaseExpireTime() != null
                    && !lease.leaseExpireTime().isAfter(now)) {
                DhcpLeaseRecord expired = lease.withState(LeaseState.EXPIRED, now, properties.getLeaseSeconds(), ownerNodeId);
                store.put(expired);
                saveMirror(expired);
            }
        }
    }

    private Optional<DhcpLeaseRecord> findReusableLeaseByMac(String mac, Instant now) {
        return store.findByMac(mac).filter(lease -> lease.isUsableFor(mac, now));
    }

    private Optional<DhcpLeaseRecord> occupyCandidate(String mac, String ipAddress, LeaseState state, Instant now) {
        Optional<DhcpLeaseRecord> existing = store.findByIp(ipAddress);
        if (existing.isPresent()) {
            DhcpLeaseRecord current = existing.get();
            if (!current.macAddress().equalsIgnoreCase(mac) && !current.isAvailableForReuse(now)) {
                return Optional.empty();
            }
            DhcpLeaseRecord updated = newRecord(ipAddress, mac, state, now, current.version() + 1, current.createdAt());
            store.put(updated);
            saveMirror(updated);
            return Optional.of(updated);
        }

        DhcpLeaseRecord created = newRecord(ipAddress, mac, state, now, 0, now);
        if (!store.putIfAbsent(created)) {
            return Optional.empty();
        }
        saveMirror(created);
        return Optional.of(created);
    }

    private DhcpLeaseRecord newRecord(
            String ipAddress,
            String mac,
            LeaseState state,
            Instant now,
            long version,
            Instant createdAt
    ) {
        return new DhcpLeaseRecord(
                ipAddress,
                mac,
                null,
                state,
                now,
                now.plusSeconds(properties.getLeaseSeconds()),
                now,
                ownerNodeId,
                version,
                createdAt,
                now
        );
    }

    private void transitionMacLease(String macAddress, LeaseState nextState) {
        String mac = MacAddresses.normalize(macAddress);
        Instant now = clock.instant();
        store.findByMac(mac).ifPresent(lease -> {
            DhcpLeaseRecord updated = lease.withState(nextState, now, properties.getLeaseSeconds(), ownerNodeId);
            store.put(updated);
            saveMirror(updated);
        });
    }

    private boolean isDynamicCandidateAllowed(String ipAddress, String mac) {
        return !excludedAddresses().contains(ipAddress)
                && !reservedIpsForOtherMacs(mac).contains(ipAddress);
    }

    private boolean isAddressAllowedForMac(String ipAddress, String mac) {
        Optional<String> reservation = reservedIp(mac);
        if (reservation.isPresent()) {
            return reservation.get().equals(ipAddress);
        }
        long candidate = Integer.toUnsignedLong(Ipv4Addresses.toInt(ipAddress));
        long start = Integer.toUnsignedLong(Ipv4Addresses.toInt(properties.getPool().getStart()));
        long end = Integer.toUnsignedLong(Ipv4Addresses.toInt(properties.getPool().getEnd()));
        return candidate >= start && candidate <= end && isDynamicCandidateAllowed(ipAddress, mac);
    }

    private Optional<String> reservedIp(String mac) {
        return normalizedReservations().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(mac))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Map<String, String> normalizedReservations() {
        Map<String, String> reservations = new HashMap<>();
        properties.getReservations().forEach((mac, ip) -> reservations.put(MacAddresses.normalize(mac), ip));
        return reservations;
    }

    private Set<String> reservedIpsForOtherMacs(String mac) {
        Set<String> reserved = new HashSet<>();
        normalizedReservations().forEach((reservedMac, ip) -> {
            if (!reservedMac.equalsIgnoreCase(mac)) {
                reserved.add(ip);
            }
        });
        return reserved;
    }

    private Set<String> excludedAddresses() {
        return new HashSet<>(properties.getExcludedAddresses());
    }

    private boolean isNewer(DhcpLeaseRecord left, DhcpLeaseRecord right) {
        if (left.updatedAt() == null) {
            return false;
        }
        return right.updatedAt() == null || left.updatedAt().isAfter(right.updatedAt());
    }

    private void saveMirror(DhcpLeaseRecord record) {
        repository.findByMacAddressIgnoreCase(record.macAddress())
                .filter(existing -> !existing.getIpAddress().equals(record.ipAddress()))
                .ifPresent(existing -> {
                    repository.delete(existing);
                    repository.flush();
                });
        repository.save(mapper.toEntity(record));
    }

    private String resolveOwnerNodeId() {
        String configured = System.getenv("AC_NODE_ID");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "unknown-node";
        }
    }
}
