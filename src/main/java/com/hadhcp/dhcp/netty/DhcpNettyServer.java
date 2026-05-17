package com.hadhcp.dhcp.netty;

import com.hadhcp.config.DhcpProperties;
import com.hadhcp.dhcp.protocol.DhcpRequestHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class DhcpNettyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DhcpNettyServer.class);

    private final DhcpProperties properties;
    private final DhcpRequestHandler requestHandler;
    private volatile EventLoopGroup group;
    private volatile Channel channel;
    private volatile boolean running;

    public DhcpNettyServer(DhcpProperties properties, DhcpRequestHandler requestHandler) {
        this.properties = properties;
        this.requestHandler = requestHandler;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("DHCP Netty server is disabled");
            return;
        }
        if (running) {
            return;
        }

        try {
            group = new NioEventLoopGroup(1);
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel channel) {
                            channel.pipeline().addLast(new DhcpDatagramHandler(requestHandler, properties));
                        }
                    });

            InetAddress bindAddress = InetAddress.getByName(properties.getBindAddress());
            ChannelFuture future = bootstrap.bind(new InetSocketAddress(bindAddress, properties.getPort())).sync();
            channel = future.channel();
            running = true;
            log.info("DHCP Netty server listening on {}:{}", properties.getBindAddress(), properties.getPort());
        } catch (Exception ex) {
            stop();
            throw new IllegalStateException("Failed to start DHCP Netty server", ex);
        }
    }

    @Override
    public void stop() {
        running = false;
        Channel currentChannel = channel;
        channel = null;
        if (currentChannel != null) {
            currentChannel.close().awaitUninterruptibly();
        }

        EventLoopGroup currentGroup = group;
        group = null;
        if (currentGroup != null) {
            currentGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
