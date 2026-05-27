package com.hadhcp.edge;

import com.hadhcp.dhcp.util.Ipv4Addresses;
import com.hadhcp.ha.HaService;
import com.hadhcp.persistence.EdgeDeviceEntity;
import com.hadhcp.persistence.EdgeDeviceRepository;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages edge device SN-to-IP mappings and reconciles them through Hazelcast after recovery.
 */
@Service
public class EdgeDeviceService {

    private static final Logger log = LoggerFactory.getLogger(EdgeDeviceService.class);

    private final EdgeDeviceRepository repository;
    private final HaService haService;
    private final HazelcastInstance hazelcastInstance;
    private final IMap<String, String> devices;
    private final Clock clock;

    /**
     * Creates the edge device service around the local repository and Hazelcast map.
     *
     * @param repository local H2 edge device mirror
     * @param haService HA role service used to gate writes
     * @param hazelcastInstance Hazelcast member that stores the shared view
     */
    public EdgeDeviceService(
            EdgeDeviceRepository repository,
            HaService haService,
            HazelcastInstance hazelcastInstance
    ) {
        this.repository = repository;
        this.haService = haService;
        this.hazelcastInstance = hazelcastInstance;
        this.devices = hazelcastInstance.getMap("edge:devices");
        this.clock = Clock.systemUTC();
    }

    /**
     * Publishes the local edge device mirror into Hazelcast on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadMirrorIntoHazelcast() {
        int published = 0;
        for (EdgeDeviceEntity entity : repository.findAll()) {
            if (devices.putIfAbsent(entity.getSerialNumber(), entity.getIpAddress()) == null) {
                published++;
            }
        }
        log.info("Edge device mirror loaded into Hazelcast: published={} localRows={}", published, repository.count());
    }

    /**
     * Lists all local edge device mappings.
     *
     * @return sorted edge device responses
     */
    @Transactional(readOnly = true)
    public List<EdgeDeviceResponse> findAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(EdgeDeviceEntity::getSerialNumber))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Finds a local edge device mapping by serial number.
     *
     * @param sn raw serial number
     * @return matching edge device response when present
     */
    @Transactional(readOnly = true)
    public Optional<EdgeDeviceResponse> findBySn(String sn) {
        return repository.findById(normalizeSn(sn)).map(this::toResponse);
    }

    /**
     * Creates or updates an edge device mapping on the VIP holder.
     *
     * @param sn raw serial number
     * @param request requested IP mapping
     * @return saved edge device response
     */
    @Transactional
    public EdgeDeviceResponse upsert(String sn, EdgeDeviceUpdateRequest request) {
        if (!haService.hasVip()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the VIP holder can update edge devices");
        }
        String serialNumber = normalizeSn(sn);
        String ipAddress = normalizeIp(request == null ? null : request.ip());
        rejectDuplicateIp(serialNumber, ipAddress);

        Instant now = clock.instant();
        EdgeDeviceEntity entity = repository.findById(serialNumber).orElseGet(() -> {
            EdgeDeviceEntity created = new EdgeDeviceEntity();
            created.setSerialNumber(serialNumber);
            created.setCreatedAt(now);
            return created;
        });
        entity.setIpAddress(ipAddress);
        entity.setUpdatedAt(now);
        EdgeDeviceEntity saved = repository.save(entity);
        devices.put(serialNumber, ipAddress);
        return toResponse(saved);
    }

    /**
     * Reconciles edge device mappings after a partition heals.
     */
    @Scheduled(fixedDelayString = "${edge.device-sync-ms:2000}")
    @Transactional
    public void syncRecoveredDevices() {
        if (memberCount() < 2) {
            return;
        }

        if (haService.hasVip()) {
            mergeAsVipHolder();
        } else {
            replaceLocalMirrorFromHazelcast();
        }
    }

    /**
     * Keeps VIP-holder mappings first and supplements non-conflicting remote SN/IP pairs.
     */
    private void mergeAsVipHolder() {
        Map<String, String> finalView = new LinkedHashMap<>();
        Map<String, String> usedIps = new LinkedHashMap<>();
        for (EdgeDeviceEntity local : repository.findAll().stream()
                .sorted(Comparator.comparing(EdgeDeviceEntity::getSerialNumber))
                .toList()) {
            finalView.put(local.getSerialNumber(), local.getIpAddress());
            usedIps.put(local.getIpAddress(), local.getSerialNumber());
        }

        for (Map.Entry<String, String> remote : devices.entrySet()) {
            String sn = normalizeSn(remote.getKey());
            String ip = normalizeIp(remote.getValue());
            if (finalView.containsKey(sn)) {
                continue;
            }
            if (usedIps.containsKey(ip)) {
                continue;
            }
            finalView.put(sn, ip);
            usedIps.put(ip, sn);
            saveLocal(sn, ip);
        }

        devices.clear();
        devices.putAll(finalView);
    }

    /**
     * Replaces the local mirror with the current Hazelcast view on backup nodes.
     */
    private void replaceLocalMirrorFromHazelcast() {
        Map<String, String> finalView = new LinkedHashMap<>();
        List<Map.Entry<String, String>> remoteEntries = new ArrayList<>(devices.entrySet());
        remoteEntries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, String> remote : remoteEntries) {
            String sn = normalizeSn(remote.getKey());
            String ip = normalizeIp(remote.getValue());
            if (!finalView.containsValue(ip)) {
                finalView.put(sn, ip);
            }
        }

        repository.deleteAllInBatch();
        finalView.forEach(this::saveLocal);
    }

    /**
     * Rejects an update when the requested IP is already owned by another SN.
     */
    private void rejectDuplicateIp(String serialNumber, String ipAddress) {
        repository.findByIpAddress(ipAddress)
                .filter(existing -> !existing.getSerialNumber().equals(serialNumber))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Edge device IP is already used by SN " + existing.getSerialNumber()
                    );
                });
        devices.entrySet().stream()
                .filter(entry -> entry.getValue().equals(ipAddress))
                .filter(entry -> !entry.getKey().equals(serialNumber))
                .findFirst()
                .ifPresent(entry -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Edge device IP is already used by SN " + entry.getKey()
                    );
                });
    }

    /**
     * Persists a local edge device mapping.
     */
    private EdgeDeviceEntity saveLocal(String serialNumber, String ipAddress) {
        Instant now = clock.instant();
        EdgeDeviceEntity entity = repository.findById(serialNumber).orElseGet(() -> {
            EdgeDeviceEntity created = new EdgeDeviceEntity();
            created.setSerialNumber(serialNumber);
            created.setCreatedAt(now);
            return created;
        });
        entity.setIpAddress(ipAddress);
        entity.setUpdatedAt(now);
        return repository.save(entity);
    }

    /**
     * Converts a persistence row into the API response shape.
     */
    private EdgeDeviceResponse toResponse(EdgeDeviceEntity entity) {
        return new EdgeDeviceResponse(
                entity.getSerialNumber(),
                entity.getIpAddress(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Normalizes and validates edge device serial numbers.
     */
    private String normalizeSn(String sn) {
        if (!StringUtils.hasText(sn)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Edge device SN is required");
        }
        String normalized = sn.trim();
        if (normalized.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Edge device SN must not exceed 128 characters");
        }
        return normalized;
    }

    /**
     * Normalizes and validates edge device IPv4 addresses.
     */
    private String normalizeIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Edge device IP is required");
        }
        String normalized = ip.trim();
        try {
            Ipv4Addresses.toInt(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Edge device IP must be a valid IPv4 address", ex);
        }
    }

    /**
     * Returns the currently visible Hazelcast member count.
     */
    private int memberCount() {
        try {
            return hazelcastInstance.getCluster().getMembers().size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
