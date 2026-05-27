package com.hadhcp.ha;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.config.HaProperties;
import com.hadhcp.dhcp.util.Ipv4Addresses;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class VipAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(VipAddressResolver.class);

    private final HaProperties haProperties;
    private final DhcpProperties dhcpProperties;

    public VipAddressResolver(HaProperties haProperties, DhcpProperties dhcpProperties) {
        this.haProperties = haProperties;
        this.dhcpProperties = dhcpProperties;
    }

    public Optional<String> resolveVip() {
        if (StringUtils.hasText(haProperties.getIp())) {
            String configured = haProperties.getIp().trim();
            try {
                Ipv4Addresses.toInt(configured);
                return Optional.of(configured);
            } catch (IllegalArgumentException ex) {
                log.warn("Configured HA IP is not a valid IPv4 address: {}", configured);
                return Optional.empty();
            }
        }

        return resolveFirstPhysicalInterface()
                .flatMap(this::firstIpv4Address);
    }

    public Optional<String> resolveInterfaceName() {
        if (StringUtils.hasText(haProperties.getInterfaceName())) {
            return Optional.of(haProperties.getInterfaceName().trim());
        }
        if (StringUtils.hasText(dhcpProperties.getInterfaceName())) {
            return Optional.of(dhcpProperties.getInterfaceName().trim());
        }
        return resolveFirstPhysicalInterface().map(NetworkInterface::getName);
    }

    private Optional<NetworkInterface> resolveFirstPhysicalInterface() {
        try {
            if (StringUtils.hasText(haProperties.getInterfaceName())) {
                NetworkInterface networkInterface = NetworkInterface.getByName(haProperties.getInterfaceName().trim());
                return networkInterface != null && isUsablePhysicalInterface(networkInterface)
                        ? Optional.of(networkInterface)
                        : Optional.empty();
            }
            if (StringUtils.hasText(dhcpProperties.getInterfaceName())) {
                NetworkInterface networkInterface = NetworkInterface.getByName(dhcpProperties.getInterfaceName().trim());
                return networkInterface != null && isUsablePhysicalInterface(networkInterface)
                        ? Optional.of(networkInterface)
                        : Optional.empty();
            }

            return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .filter(this::isUsablePhysicalInterface)
                    .min(Comparator.comparingInt(NetworkInterface::getIndex));
        } catch (SocketException ex) {
            log.warn("Failed to resolve default VIP from network interfaces", ex);
            return Optional.empty();
        }
    }

    private boolean isUsablePhysicalInterface(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp()
                    && !networkInterface.isLoopback()
                    && !networkInterface.isVirtual()
                    && firstIpv4Address(networkInterface).isPresent();
        } catch (SocketException ex) {
            log.warn("Failed to inspect network interface {}", networkInterface.getName(), ex);
            return false;
        }
    }

    private Optional<String> firstIpv4Address(NetworkInterface networkInterface) {
        return Collections.list(networkInterface.getInetAddresses()).stream()
                .filter(Inet4Address.class::isInstance)
                .map(address -> address.getHostAddress())
                .findFirst();
    }
}
