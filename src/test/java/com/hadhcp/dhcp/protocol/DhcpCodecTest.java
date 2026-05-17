package com.hadhcp.dhcp.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.hadhcp.dhcp.util.Ipv4Addresses;
import com.hadhcp.dhcp.util.MacAddresses;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

class DhcpCodecTest {

    private final DhcpCodec codec = new DhcpCodec();

    @Test
    void encodesAndDecodesDhcpOptionsWithPaddingAndEnd() {
        DhcpMessage message = new DhcpMessage();
        message.setOp(DhcpMessage.BOOTREQUEST);
        message.setHardwareType(DhcpMessage.ETHERNET);
        message.setHardwareAddressLength(6);
        message.setTransactionId(0x12345678);
        message.setFlags(0x8000);
        message.setClientHardwareAddress(MacAddresses.toBytes("aa:bb:cc:dd:ee:ff"));
        message.putOption(DhcpOptionCode.MESSAGE_TYPE, DhcpOptions.messageType(DhcpMessageType.DISCOVER));
        message.putOption(DhcpOptionCode.REQUESTED_IP, Ipv4Addresses.toBytes("192.168.56.120"));

        ByteBuf encoded = codec.encode(message);
        DhcpMessage decoded = codec.decode(encoded.copy());

        assertThat(decoded.getTransactionId()).isEqualTo(0x12345678);
        assertThat(decoded.messageType()).contains(DhcpMessageType.DISCOVER);
        assertThat(DhcpOptions.requestedIp(decoded)).contains("192.168.56.120");
        assertThat(MacAddresses.fromClientHardwareAddress(
                decoded.getClientHardwareAddress(),
                decoded.getHardwareAddressLength()
        )).isEqualTo("aa:bb:cc:dd:ee:ff");
    }
}
