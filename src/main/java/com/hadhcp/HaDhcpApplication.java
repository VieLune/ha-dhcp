package com.hadhcp;

import com.hadhcp.config.ClusterProperties;
import com.hadhcp.config.DhcpProperties;
import com.hadhcp.config.HaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.hazelcast.HazelcastAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = HazelcastAutoConfiguration.class)
@EnableConfigurationProperties({DhcpProperties.class, HaProperties.class, ClusterProperties.class})
@EnableScheduling
public class HaDhcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(HaDhcpApplication.class, args);
    }
}
