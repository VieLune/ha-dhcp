package com.hadhcp.dhcp.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Map;

public class DhcpCodec {

    private static final int DHCP_FIXED_HEADER_LENGTH = 236;
    private static final int DHCP_MAGIC_COOKIE = 0x63825363;
    private static final int MIN_PACKET_LENGTH = 300;

    public DhcpMessage decode(ByteBuf input) {
        if (input.readableBytes() < DHCP_FIXED_HEADER_LENGTH + Integer.BYTES) {
            throw new IllegalArgumentException("DHCP packet is too short");
        }

        DhcpMessage message = new DhcpMessage();
        message.setOp(input.readUnsignedByte());
        message.setHardwareType(input.readUnsignedByte());
        message.setHardwareAddressLength(input.readUnsignedByte());
        message.setHops(input.readUnsignedByte());
        message.setTransactionId(input.readInt());
        message.setSeconds(input.readUnsignedShort());
        message.setFlags(input.readUnsignedShort());
        message.setClientIpAddress(readBytes(input, 4));
        message.setYourIpAddress(readBytes(input, 4));
        message.setServerIpAddress(readBytes(input, 4));
        message.setGatewayIpAddress(readBytes(input, 4));
        message.setClientHardwareAddress(readBytes(input, 16));
        message.setServerHostName(readBytes(input, 64));
        message.setBootFileName(readBytes(input, 128));

        int cookie = input.readInt();
        if (cookie != DHCP_MAGIC_COOKIE) {
            throw new IllegalArgumentException("Invalid DHCP magic cookie");
        }

        while (input.isReadable()) {
            int code = input.readUnsignedByte();
            if (code == DhcpOptionCode.PAD) {
                continue;
            }
            if (code == DhcpOptionCode.END) {
                break;
            }
            if (!input.isReadable()) {
                throw new IllegalArgumentException("DHCP option is missing length: " + code);
            }
            int length = input.readUnsignedByte();
            if (input.readableBytes() < length) {
                throw new IllegalArgumentException("DHCP option length exceeds packet: " + code);
            }
            message.putOption(code, readBytes(input, length));
        }
        return message;
    }

    public ByteBuf encode(DhcpMessage message) {
        ByteBuf output = Unpooled.buffer(MIN_PACKET_LENGTH);
        output.writeByte(message.getOp());
        output.writeByte(message.getHardwareType());
        output.writeByte(message.getHardwareAddressLength());
        output.writeByte(message.getHops());
        output.writeInt(message.getTransactionId());
        output.writeShort(message.getSeconds());
        output.writeShort(message.getFlags());
        output.writeBytes(message.getClientIpAddress());
        output.writeBytes(message.getYourIpAddress());
        output.writeBytes(message.getServerIpAddress());
        output.writeBytes(message.getGatewayIpAddress());
        output.writeBytes(message.getClientHardwareAddress());
        output.writeBytes(message.getServerHostName());
        output.writeBytes(message.getBootFileName());
        output.writeInt(DHCP_MAGIC_COOKIE);

        for (Map.Entry<Integer, byte[]> option : message.getOptions().entrySet()) {
            output.writeByte(option.getKey());
            output.writeByte(option.getValue().length);
            output.writeBytes(option.getValue());
        }
        output.writeByte(DhcpOptionCode.END);
        while (output.readableBytes() < MIN_PACKET_LENGTH) {
            output.writeByte(DhcpOptionCode.PAD);
        }
        return output;
    }

    private static byte[] readBytes(ByteBuf input, int length) {
        byte[] bytes = new byte[length];
        input.readBytes(bytes);
        return bytes;
    }
}
