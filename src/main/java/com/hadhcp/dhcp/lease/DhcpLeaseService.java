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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * Allocates, persists, expires, and reconciles DHCP leases across the local mirror and Hazelcast authority.
 */
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

    /**
     * Creates the DHCP lease service around the configured pool and shared lease store.
     *
     * @param properties DHCP runtime properties
     * @param store Hazelcast-backed lease authority
     * @param repository local H2 lease mirror
     * @param mapper mapper between database rows and distributed records
     * @param haService HA gate and readiness state
     */
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

    /**
     * Loads the local lease mirror into Hazelcast when the application starts.
     */
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

    /**
     * Creates or reuses an OFFER lease for the supplied client MAC address.
     *
     * @param macAddress raw client MAC address
     * @return offered lease, or empty when no address can be allocated
     */
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

    /**
     * Converts an offered or requested address into an ACTIVE lease.
     *
     * @param macAddress raw client MAC address
     * @param requestedIp optional address requested by the client
     * @return active lease, or empty when the request conflicts with authority state
     */
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

    /**
     * Marks the client's current lease as released.
     *
     * @param macAddress raw client MAC address
     */
    @Transactional
    public void releaseLease(String macAddress) {
        transitionMacLease(macAddress, LeaseState.RELEASED);
    }

    /**
     * Marks the client's current lease as declined.
     *
     * @param macAddress raw client MAC address
     */
    @Transactional
    public void declineLease(String macAddress) {
        transitionMacLease(macAddress, LeaseState.DECLINED);
    }

    /**
     * Expires active and offered leases whose lease time has elapsed.
     */
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

    /**
     * Reconciles lease state after a partition heals, either as VIP holder or follower.
     */
    @Scheduled(fixedDelayString = "${dhcp.recovery-sync-ms:2000}")
    @Transactional
    public void syncRecoveredLeases() {
        if (store.memberCount() < 2) {
            return;
        }

        if (haService.hasVip()) {
            mergeAsVipHolder();
        } else {
            replaceLocalMirrorFromAuthority();
        }
    }

    /**
     * Builds the authoritative lease view from the VIP holder's H2 mirror and non-conflicting remote records.
     */
    private void mergeAsVipHolder() {
        Map<String, DhcpLeaseRecord> finalByIp = new LinkedHashMap<>();
        Set<String> usedMacs = new HashSet<>();

        for (DhcpLeaseEntity entity : repository.findAll().stream()
                .sorted(Comparator.comparing(DhcpLeaseEntity::getIpAddress))
                .toList()) {
            DhcpLeaseRecord local = mapper.toRecord(entity);
            finalByIp.put(local.ipAddress(), local);
            usedMacs.add(local.macAddress().toLowerCase());
        }

        for (DhcpLeaseRecord remote : sortedStoreValues()) {
            if (finalByIp.containsKey(remote.ipAddress())) {
                continue;
            }
            if (usedMacs.contains(remote.macAddress().toLowerCase())) {
                continue;
            }
            finalByIp.put(remote.ipAddress(), remote);
            usedMacs.add(remote.macAddress().toLowerCase());
            saveMirror(remote);
        }

        store.replaceAll(finalByIp.values());
    }

    /**
     * Replaces this node's local mirror with the authoritative Hazelcast lease view.
     */
    private void replaceLocalMirrorFromAuthority() {
        Map<String, DhcpLeaseRecord> finalByIp = new LinkedHashMap<>();
        Set<String> usedMacs = new HashSet<>();
        for (DhcpLeaseRecord remote : sortedStoreValues()) {
            if (finalByIp.containsKey(remote.ipAddress()) || usedMacs.contains(remote.macAddress().toLowerCase())) {
                continue;
            }
            finalByIp.put(remote.ipAddress(), remote);
            usedMacs.add(remote.macAddress().toLowerCase());
        }

        repository.deleteAllInBatch();
        finalByIp.values().forEach(record -> repository.save(mapper.toEntity(record)));
    }

    /**
     * Returns stable, IP-sorted values from the distributed lease store.
     *
     * @return sorted lease records
     */
    private List<DhcpLeaseRecord> sortedStoreValues() {
        List<DhcpLeaseRecord> values = new ArrayList<>(store.values());
        values.sort(Comparator.comparing(DhcpLeaseRecord::ipAddress));
        return values;
    }

    /**
     * Finds an unexpired ACTIVE or OFFERED lease for the client MAC.
     *
     * @param mac normalized MAC address
     * @param now current instant
     * @return reusable lease when present
     */
    private Optional<DhcpLeaseRecord> findReusableLeaseByMac(String mac, Instant now) {
        return store.findByMac(mac).filter(lease -> lease.isUsableFor(mac, now));
    }

    /**
     * Reserves a candidate address if the authority does not contain a conflicting live lease.
     *
     * @param mac normalized MAC address
     * @param ipAddress candidate IPv4 address
     * @param state requested lease state
     * @param now current instant
     * @return created or updated lease when the candidate is available
     */
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

    /**
     * Creates a new immutable lease record for the current node.
     */
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

    /**
     * Transitions the current lease for a client MAC into a terminal state.
     *
     * @param macAddress raw client MAC address
     * @param nextState target lease state
     */
    private void transitionMacLease(String macAddress, LeaseState nextState) {
        String mac = MacAddresses.normalize(macAddress);
        Instant now = clock.instant();
        store.findByMac(mac).ifPresent(lease -> {
            DhcpLeaseRecord updated = lease.withState(nextState, now, properties.getLeaseSeconds(), ownerNodeId);
            store.put(updated);
            saveMirror(updated);
        });
    }

    /**
     * Checks whether a dynamic address can be allocated to the MAC.
     */
    private boolean isDynamicCandidateAllowed(String ipAddress, String mac) {
        return !excludedAddresses().contains(ipAddress)
                && !reservedIpsForOtherMacs(mac).contains(ipAddress);
    }

    /**
     * Checks whether the requested address is valid for the MAC and current pool rules.
     */
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

    /**
     * Finds the reserved IP for the normalized MAC, if configured.
     */
    private Optional<String> reservedIp(String mac) {
        return normalizedReservations().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(mac))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * Returns reservations with normalized MAC keys.
     */
    private Map<String, String> normalizedReservations() {
        Map<String, String> reservations = new HashMap<>();
        properties.getReservations().forEach((mac, ip) -> reservations.put(MacAddresses.normalize(mac), ip));
        return reservations;
    }

    /**
     * Returns reserved IPs that belong to other MAC addresses.
     */
    private Set<String> reservedIpsForOtherMacs(String mac) {
        Set<String> reserved = new HashSet<>();
        normalizedReservations().forEach((reservedMac, ip) -> {
            if (!reservedMac.equalsIgnoreCase(mac)) {
                reserved.add(ip);
            }
        });
        return reserved;
    }

    /**
     * Returns the configured excluded address set.
     */
    private Set<String> excludedAddresses() {
        return new HashSet<>(properties.getExcludedAddresses());
    }

    /**
     * Compares lease update timestamps.
     */
    private boolean isNewer(DhcpLeaseRecord left, DhcpLeaseRecord right) {
        if (left.updatedAt() == null) {
            return false;
        }
        return right.updatedAt() == null || left.updatedAt().isAfter(right.updatedAt());
    }

    /**
     * Writes a lease record to the local mirror while preserving MAC uniqueness.
     */
    private void saveMirror(DhcpLeaseRecord record) {
        repository.findByMacAddressIgnoreCase(record.macAddress())
                .filter(existing -> !existing.getIpAddress().equals(record.ipAddress()))
                .ifPresent(existing -> {
                    repository.delete(existing);
                    repository.flush();
                });
        repository.save(mapper.toEntity(record));
    }

    /**
     * Resolves the local node identifier used in distributed lease records.
     */
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
