package com.hadhcp.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HazelcastConfiguration {

    @Bean(destroyMethod = "shutdown")
    public HazelcastInstance hazelcastInstance(ClusterProperties properties) {
        Config config = new Config();
        config.setClusterName(properties.getName());

        NetworkConfig network = config.getNetworkConfig();
        network.setPort(properties.getPort());
        network.setPortAutoIncrement(true);

        JoinConfig join = network.getJoin();
        boolean useTcpIp = !properties.getMembers().isEmpty();
        join.getMulticastConfig().setEnabled(!useTcpIp);
        join.getTcpIpConfig().setEnabled(useTcpIp);
        properties.getMembers().forEach(join.getTcpIpConfig()::addMember);

        MapConfig leases = new MapConfig("dhcp:leases");
        leases.setBackupCount(1);
        leases.setAsyncBackupCount(0);
        config.addMapConfig(leases);

        MapConfig edgeDevices = new MapConfig("edge:devices");
        edgeDevices.setBackupCount(1);
        edgeDevices.setAsyncBackupCount(0);
        config.addMapConfig(edgeDevices);

        MapConfig acConfig = new MapConfig("ac:config");
        acConfig.setBackupCount(1);
        acConfig.setAsyncBackupCount(0);
        config.addMapConfig(acConfig);

        return Hazelcast.newHazelcastInstance(config);
    }
}
