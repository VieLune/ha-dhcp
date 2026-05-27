package com.hadhcp.ac.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hadhcp.config.DhcpProperties;
import com.hadhcp.config.HaProperties;
import com.hadhcp.dhcp.util.Ipv4Addresses;
import com.hadhcp.dhcp.util.MacAddresses;
import com.hadhcp.ha.HaService;
import com.hadhcp.persistence.AcConfigEntity;
import com.hadhcp.persistence.AcConfigRepository;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores, applies, publishes, and recovers the effective AC runtime configuration.
 */
@Service
public class AcConfigService implements SmartInitializingSingleton {

    public static final String EFFECTIVE_KEY = "effective";

    private static final Logger log = LoggerFactory.getLogger(AcConfigService.class);

    private final DhcpProperties dhcpProperties;
    private final HaProperties haProperties;
    private final AcConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final HaService haService;
    private final HazelcastInstance hazelcastInstance;
    private final IMap<String, String> configs;

    /**
     * Creates the AC config service around runtime properties, H2 storage, and Hazelcast publication.
     *
     * @param dhcpProperties mutable DHCP runtime properties
     * @param haProperties mutable HA runtime properties
     * @param repository local H2 config mirror
     * @param objectMapper JSON mapper for persisted config
     * @param haService HA role service used to gate writes
     * @param hazelcastInstance Hazelcast member that stores the effective config view
     */
    public AcConfigService(
            DhcpProperties dhcpProperties,
            HaProperties haProperties,
            AcConfigRepository repository,
            ObjectMapper objectMapper,
            HaService haService,
            HazelcastInstance hazelcastInstance
    ) {
        this.dhcpProperties = dhcpProperties;
        this.haProperties = haProperties;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.haService = haService;
        this.hazelcastInstance = hazelcastInstance;
        this.configs = hazelcastInstance.getMap("ac:config");
    }

    /**
     * Loads the effective configuration once Spring has created every singleton bean.
     */
    @Override
    public void afterSingletonsInstantiated() {
        loadEffectiveConfig();
    }

    /**
     * Returns a snapshot of the current runtime configuration.
     *
     * @return current effective AC configuration
     */
    @Transactional(readOnly = true)
    public AcRuntimeConfig current() {
        return snapshot();
    }

    /**
     * Replaces the effective AC configuration on the VIP holder.
     *
     * @param request requested full replacement config
     * @return normalized and applied configuration
     */
    @Transactional
    public AcRuntimeConfig update(AcRuntimeConfig request) {
        if (!haService.hasVip()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the VIP holder can update AC config");
        }
        AcRuntimeConfig normalized = normalize(request);
        save(normalized);
        apply(normalized);
        publish(normalized);
        haService.markConfigLoaded();
        return normalized;
    }

    /**
     * Reconciles effective config after recovery, publishing from the VIP holder and applying on backups.
     */
    @Scheduled(fixedDelayString = "${ac.config-sync-ms:2000}")
    @Transactional
    public void syncRecoveredConfig() {
        if (!haService.isConfigLoaded() || memberCount() < 2) {
            return;
        }

        if (haService.hasVip()) {
            publish(snapshot());
            return;
        }

        String remote = configs.get(EFFECTIVE_KEY);
        if (!StringUtils.hasText(remote)) {
            return;
        }

        AcRuntimeConfig normalized = normalize(read(remote));
        save(normalized);
        apply(normalized);
    }

    /**
     * Loads config from H2 or application properties, applies it locally, and publishes it to Hazelcast.
     */
    private void loadEffectiveConfig() {
        AcRuntimeConfig effective = repository.findById(EFFECTIVE_KEY)
                .map(AcConfigEntity::getConfigJson)
                .map(this::read)
                .map(this::normalize)
                .orElseGet(() -> normalize(snapshot()));
        apply(effective);
        publish(effective);
        haService.markConfigLoaded();
        log.info("AC config loaded: source={}", repository.existsById(EFFECTIVE_KEY) ? "database" : "application.yml");
    }

    /**
     * Builds an immutable config snapshot from mutable runtime properties.
     */
    private AcRuntimeConfig snapshot() {
        DhcpProperties.Pool pool = dhcpProperties.getPool();
        return new AcRuntimeConfig(
                new AcRuntimeConfig.Dhcp(
                        dhcpProperties.isEnabled(),
                        dhcpProperties.getBindAddress(),
                        dhcpProperties.getPort(),
                        dhcpProperties.getClientPort(),
                        dhcpProperties.getInterfaceName(),
                        dhcpProperties.getServerIdentifier(),
                        dhcpProperties.getSubnetMask(),
                        dhcpProperties.getRouter(),
                        new ArrayList<>(dhcpProperties.getDnsServers()),
                        dhcpProperties.getLeaseSeconds(),
                        new ArrayList<>(dhcpProperties.getExcludedAddresses()),
                        new LinkedHashMap<>(dhcpProperties.getReservations()),
                        new AcRuntimeConfig.Pool(pool.getStart(), pool.getEnd())
                ),
                new AcRuntimeConfig.Ha(
                        haProperties.getIp(),
                        haProperties.getInterfaceName(),
                        haProperties.getPrefixLength(),
                        haProperties.isVipManagementEnabled(),
                        haProperties.getCommandTimeoutSeconds()
                )
        );
    }

    /**
     * Applies a normalized config to mutable runtime properties.
     */
    private void apply(AcRuntimeConfig config) {
        AcRuntimeConfig.Dhcp dhcp = config.dhcp();
        dhcpProperties.setEnabled(dhcp.enabled());
        dhcpProperties.setBindAddress(dhcp.bindAddress());
        dhcpProperties.setPort(dhcp.port());
        dhcpProperties.setClientPort(dhcp.clientPort());
        dhcpProperties.setInterfaceName(dhcp.interfaceName());
        dhcpProperties.setServerIdentifier(dhcp.serverIdentifier());
        dhcpProperties.setSubnetMask(dhcp.subnetMask());
        dhcpProperties.setRouter(dhcp.router());
        dhcpProperties.setDnsServers(new ArrayList<>(dhcp.dnsServers()));
        dhcpProperties.setLeaseSeconds(dhcp.leaseSeconds());
        dhcpProperties.setExcludedAddresses(new ArrayList<>(dhcp.excludedAddresses()));
        dhcpProperties.setReservations(new LinkedHashMap<>(dhcp.reservations()));
        dhcpProperties.getPool().setStart(dhcp.pool().start());
        dhcpProperties.getPool().setEnd(dhcp.pool().end());

        haProperties.setIp(config.ha().ip());
        haProperties.setInterfaceName(config.ha().interfaceName());
        haProperties.setPrefixLength(config.ha().prefixLength());
        haProperties.setVipManagementEnabled(config.ha().vipManagementEnabled());
        haProperties.setCommandTimeoutSeconds(config.ha().commandTimeoutSeconds());
    }

    /**
     * Validates and normalizes a full AC runtime config.
     */
    private AcRuntimeConfig normalize(AcRuntimeConfig config) {
        if (config == null || config.dhcp() == null || config.ha() == null || config.dhcp().pool() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AC config must include dhcp, dhcp.pool and ha");
        }

        AcRuntimeConfig.Dhcp dhcp = config.dhcp();
        requireIpv4(dhcp.bindAddress(), "dhcp.bindAddress");
        requireIpv4(dhcp.serverIdentifier(), "dhcp.serverIdentifier");
        requireIpv4(dhcp.subnetMask(), "dhcp.subnetMask");
        requireIpv4(dhcp.router(), "dhcp.router");
        requirePort(dhcp.port(), "dhcp.port");
        requirePort(dhcp.clientPort(), "dhcp.clientPort");
        if (dhcp.leaseSeconds() < 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dhcp.leaseSeconds must be at least 60");
        }

        String poolStart = requireIpv4(dhcp.pool().start(), "dhcp.pool.start");
        String poolEnd = requireIpv4(dhcp.pool().end(), "dhcp.pool.end");
        if (Integer.toUnsignedLong(Ipv4Addresses.toInt(poolStart))
                > Integer.toUnsignedLong(Ipv4Addresses.toInt(poolEnd))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dhcp.pool.start must be before dhcp.pool.end");
        }

        List<String> dnsServers = normalizeIpList(dhcp.dnsServers(), "dhcp.dnsServers");
        List<String> excludedAddresses = normalizeIpList(dhcp.excludedAddresses(), "dhcp.excludedAddresses");
        Map<String, String> reservations = normalizeReservations(dhcp.reservations());

        String haIp = normalizeOptionalIp(config.ha().ip(), "ha.ip");
        if (StringUtils.hasText(haIp) && isInPool(haIp, poolStart, poolEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ha.ip must not be inside the DHCP pool");
        }
        if (config.ha().prefixLength() < 1 || config.ha().prefixLength() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ha.prefixLength must be between 1 and 32");
        }
        if (config.ha().commandTimeoutSeconds() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ha.commandTimeoutSeconds must be at least 1");
        }

        return new AcRuntimeConfig(
                new AcRuntimeConfig.Dhcp(
                        dhcp.enabled(),
                        dhcp.bindAddress().trim(),
                        dhcp.port(),
                        dhcp.clientPort(),
                        trimToNull(dhcp.interfaceName()),
                        dhcp.serverIdentifier().trim(),
                        dhcp.subnetMask().trim(),
                        dhcp.router().trim(),
                        dnsServers,
                        dhcp.leaseSeconds(),
                        excludedAddresses,
                        reservations,
                        new AcRuntimeConfig.Pool(poolStart, poolEnd)
                ),
                new AcRuntimeConfig.Ha(
                        haIp,
                        trimToNull(config.ha().interfaceName()),
                        config.ha().prefixLength(),
                        config.ha().vipManagementEnabled(),
                        config.ha().commandTimeoutSeconds()
                )
        );
    }

    /**
     * Persists the effective config JSON to the local H2 mirror.
     */
    private void save(AcRuntimeConfig config) {
        Instant now = Instant.now();
        AcConfigEntity entity = repository.findById(EFFECTIVE_KEY).orElseGet(() -> {
            AcConfigEntity created = new AcConfigEntity();
            created.setConfigKey(EFFECTIVE_KEY);
            created.setCreatedAt(now);
            return created;
        });
        entity.setConfigJson(write(config));
        entity.setUpdatedAt(now);
        repository.save(entity);
    }

    /**
     * Publishes the effective config JSON to Hazelcast.
     */
    private void publish(AcRuntimeConfig config) {
        try {
            configs.put(EFFECTIVE_KEY, write(config));
        } catch (RuntimeException ex) {
            log.warn("Failed to publish AC config to Hazelcast", ex);
        }
    }

    /**
     * Parses a persisted config JSON document.
     */
    private AcRuntimeConfig read(String json) {
        try {
            return objectMapper.readValue(json, AcRuntimeConfig.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse AC config JSON", ex);
        }
    }

    /**
     * Serializes a config object into JSON.
     */
    private String write(AcRuntimeConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AC config JSON", ex);
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

    /**
     * Requires a non-empty valid IPv4 address.
     */
    private String requireIpv4(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        try {
            Ipv4Addresses.toInt(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a valid IPv4 address", ex);
        }
    }

    /**
     * Normalizes an optional IPv4 address when supplied.
     */
    private String normalizeOptionalIp(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        return requireIpv4(normalized, field);
    }

    /**
     * Requires a TCP/UDP port value in the valid range.
     */
    private void requirePort(int port, String field) {
        if (port < 1 || port > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be between 1 and 65535");
        }
    }

    /**
     * Normalizes a list of IPv4 values.
     */
    private List<String> normalizeIpList(List<String> values, String field) {
        List<String> normalized = new ArrayList<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            normalized.add(requireIpv4(value, field));
        }
        return normalized;
    }

    /**
     * Normalizes reservation MAC keys and IPv4 values.
     */
    private Map<String, String> normalizeReservations(Map<String, String> reservations) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (reservations == null) {
            return normalized;
        }
        reservations.forEach((mac, ip) -> normalized.put(MacAddresses.normalize(mac), requireIpv4(ip, "dhcp.reservations")));
        return normalized;
    }

    /**
     * Checks whether an IPv4 address falls within a pool range.
     */
    private boolean isInPool(String ip, String poolStart, String poolEnd) {
        long value = Integer.toUnsignedLong(Ipv4Addresses.toInt(ip));
        long start = Integer.toUnsignedLong(Ipv4Addresses.toInt(poolStart));
        long end = Integer.toUnsignedLong(Ipv4Addresses.toInt(poolEnd));
        return value >= start && value <= end;
    }

    /**
     * Converts blank strings to null and trims non-blank strings.
     */
    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
