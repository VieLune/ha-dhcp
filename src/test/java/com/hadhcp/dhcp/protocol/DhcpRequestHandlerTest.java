package com.hadhcp.dhcp.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.dhcp.lease.DhcpLeaseRecord;
import com.hadhcp.dhcp.lease.DhcpLeaseService;
import com.hadhcp.dhcp.lease.LeaseState;
import com.hadhcp.dhcp.util.Ipv4Addresses;
import com.hadhcp.dhcp.util.MacAddresses;
import com.hadhcp.ha.HaService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DhcpRequestHandlerTest {

    @Test
    void doesNotRespondWhenHaGateIsClosed() {
        DhcpProperties properties = new DhcpProperties();
        DhcpLeaseService leaseService = mock(DhcpLeaseService.class);
        HaService haService = mock(HaService.class);
        DhcpRequestHandler handler = new DhcpRequestHandler(properties, leaseService, haService);

        assertThat(handler.handle(discover())).isEmpty();
    }

    @Test
    void discoverReturnsOfferWithServerIdentifierVip() {
        DhcpProperties properties = new DhcpProperties();
        properties.setServerIdentifier("192.168.56.10");
        DhcpLeaseService leaseService = mock(DhcpLeaseService.class);
        HaService haService = mock(HaService.class);
        DhcpRequestHandler handler = new DhcpRequestHandler(properties, leaseService, haService);
        DhcpLeaseRecord lease = new DhcpLeaseRecord(
                "192.168.56.120",
                "aa:bb:cc:dd:ee:ff",
                null,
                LeaseState.OFFERED,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Instant.now(),
                "test",
                0,
                Instant.now(),
                Instant.now()
        );
        when(haService.canServeDhcp()).thenReturn(true);
        when(leaseService.offerLease("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.of(lease));

        DhcpMessage response = handler.handle(discover()).orElseThrow();

        assertThat(response.messageType()).contains(DhcpMessageType.OFFER);
        assertThat(response.option(DhcpOptionCode.SERVER_IDENTIFIER).orElseThrow())
                .containsExactly(Ipv4Addresses.toBytes("192.168.56.10"));
        assertThat(Ipv4Addresses.fromBytes(response.getYourIpAddress())).isEqualTo("192.168.56.120");
    }

    private DhcpMessage discover() {
        DhcpMessage message = new DhcpMessage();
        message.setOp(DhcpMessage.BOOTREQUEST);
        message.setHardwareType(DhcpMessage.ETHERNET);
        message.setHardwareAddressLength(6);
        message.setTransactionId(7);
        message.setFlags(0x8000);
        message.setClientHardwareAddress(MacAddresses.toBytes("aa:bb:cc:dd:ee:ff"));
        message.putOption(DhcpOptionCode.MESSAGE_TYPE, DhcpOptions.messageType(DhcpMessageType.DISCOVER));
        return message;
    }
}
