package com.hadhcp.dhcp.netty;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.dhcp.protocol.DhcpCodec;
import com.hadhcp.dhcp.protocol.DhcpMessage;
import com.hadhcp.dhcp.protocol.DhcpRequestHandler;
import com.hadhcp.dhcp.util.Ipv4Addresses;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DhcpDatagramHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final Logger log = LoggerFactory.getLogger(DhcpDatagramHandler.class);

    private final DhcpCodec codec = new DhcpCodec();
    private final DhcpRequestHandler requestHandler;
    private final DhcpProperties properties;

    public DhcpDatagramHandler(DhcpRequestHandler requestHandler, DhcpProperties properties) {
        this.requestHandler = requestHandler;
        this.properties = properties;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
        try {
            DhcpMessage request = codec.decode(packet.content());
            requestHandler.handle(request).ifPresent(response -> {
                ByteBuf responseBytes = codec.encode(response);
                InetSocketAddress destination = destination(request);
                context.writeAndFlush(new DatagramPacket(responseBytes, destination));
            });
        } catch (RuntimeException ex) {
            log.warn("Dropping malformed DHCP packet from {}", packet.sender(), ex);
        }
    }

    private InetSocketAddress destination(DhcpMessage request) {
        try {
            if (!request.isBroadcastFlagSet() && !Ipv4Addresses.isZero(request.getClientIpAddress())) {
                InetAddress clientAddress = InetAddress.getByAddress(request.getClientIpAddress());
                return new InetSocketAddress(clientAddress, properties.getClientPort());
            }
            return new InetSocketAddress(InetAddress.getByName("255.255.255.255"), properties.getClientPort());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve DHCP response destination", ex);
        }
    }
}
