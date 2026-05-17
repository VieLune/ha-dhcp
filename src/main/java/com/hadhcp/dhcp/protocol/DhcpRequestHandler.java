package com.hadhcp.dhcp.protocol;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.dhcp.lease.DhcpLeaseRecord;
import com.hadhcp.dhcp.lease.DhcpLeaseService;
import com.hadhcp.dhcp.util.Ipv4Addresses;
import com.hadhcp.dhcp.util.MacAddresses;
import com.hadhcp.ha.HaService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DhcpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(DhcpRequestHandler.class);

    private final DhcpProperties properties;
    private final DhcpLeaseService leaseService;
    private final HaService haService;

    public DhcpRequestHandler(DhcpProperties properties, DhcpLeaseService leaseService, HaService haService) {
        this.properties = properties;
        this.leaseService = leaseService;
        this.haService = haService;
    }

    public Optional<DhcpMessage> handle(DhcpMessage request) {
        if (!haService.canServeDhcp()) {
            return Optional.empty();
        }
        if (request.getOp() != DhcpMessage.BOOTREQUEST) {
            return Optional.empty();
        }

        Optional<DhcpMessageType> maybeType = request.messageType();
        if (maybeType.isEmpty()) {
            return Optional.empty();
        }

        try {
            String macAddress = MacAddresses.fromClientHardwareAddress(
                    request.getClientHardwareAddress(),
                    request.getHardwareAddressLength()
            );
            return switch (maybeType.get()) {
                case DISCOVER -> handleDiscover(request, macAddress);
                case REQUEST -> handleRequest(request, macAddress);
                case RELEASE -> {
                    leaseService.releaseLease(macAddress);
                    yield Optional.empty();
                }
                case DECLINE -> {
                    leaseService.declineLease(macAddress);
                    yield Optional.empty();
                }
                default -> Optional.empty();
            };
        } catch (RuntimeException ex) {
            log.warn("Failed to handle DHCP request xid={}", request.getTransactionId(), ex);
            return Optional.empty();
        }
    }

    private Optional<DhcpMessage> handleDiscover(DhcpMessage request, String macAddress) {
        Optional<DhcpLeaseRecord> lease = leaseService.offerLease(macAddress);
        if (lease.isEmpty() || !haService.canServeDhcp()) {
            return Optional.empty();
        }
        return Optional.of(reply(request, DhcpMessageType.OFFER, lease.get().ipAddress()));
    }

    private Optional<DhcpMessage> handleRequest(DhcpMessage request, String macAddress) {
        Optional<String> requestedIp = DhcpOptions.requestedIp(request)
                .or(() -> Optional.of(request.getClientIpAddress())
                        .filter(address -> !Ipv4Addresses.isZero(address))
                        .map(Ipv4Addresses::fromBytes));

        Optional<DhcpLeaseRecord> lease = leaseService.acknowledgeLease(macAddress, requestedIp);
        if (!haService.canServeDhcp()) {
            return Optional.empty();
        }
        if (lease.isPresent()) {
            return Optional.of(reply(request, DhcpMessageType.ACK, lease.get().ipAddress()));
        }
        return Optional.of(reply(request, DhcpMessageType.NAK, "0.0.0.0"));
    }

    private DhcpMessage reply(DhcpMessage request, DhcpMessageType type, String yiaddr) {
        DhcpMessage response = new DhcpMessage();
        response.setOp(DhcpMessage.BOOTREPLY);
        response.setHardwareType(request.getHardwareType());
        response.setHardwareAddressLength(request.getHardwareAddressLength());
        response.setHops(0);
        response.setTransactionId(request.getTransactionId());
        response.setSeconds(0);
        response.setFlags(request.getFlags());
        response.setClientIpAddress(request.getClientIpAddress());
        response.setYourIpAddress(Ipv4Addresses.toBytes(yiaddr));
        response.setServerIpAddress(Ipv4Addresses.toBytes(properties.getServerIdentifier()));
        response.setGatewayIpAddress(request.getGatewayIpAddress());
        response.setClientHardwareAddress(request.getClientHardwareAddress());

        response.putOption(DhcpOptionCode.MESSAGE_TYPE, DhcpOptions.messageType(type));
        response.putOption(DhcpOptionCode.SERVER_IDENTIFIER, DhcpOptions.ipv4(properties.getServerIdentifier()));

        if (type != DhcpMessageType.NAK) {
            response.putOption(DhcpOptionCode.SUBNET_MASK, DhcpOptions.ipv4(properties.getSubnetMask()));
            response.putOption(DhcpOptionCode.ROUTER, DhcpOptions.ipv4(properties.getRouter()));
            if (!properties.getDnsServers().isEmpty()) {
                response.putOption(DhcpOptionCode.DNS, DhcpOptions.ipv4List(properties.getDnsServers()));
            }
            response.putOption(DhcpOptionCode.BROADCAST_ADDRESS, DhcpOptions.ipv4(
                    Ipv4Addresses.broadcastAddress(yiaddr, properties.getSubnetMask())
            ));
            response.putOption(DhcpOptionCode.LEASE_TIME, DhcpOptions.uint32(properties.getLeaseSeconds()));
            response.putOption(DhcpOptionCode.RENEWAL_TIME, DhcpOptions.uint32(properties.getLeaseSeconds() / 2));
            response.putOption(DhcpOptionCode.REBINDING_TIME, DhcpOptions.uint32(properties.getLeaseSeconds() * 7 / 8));
        }
        return response;
    }
}
