package com.hadhcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "dhcp.enabled=false",
        "ha.vip-management-enabled=false",
        "cluster.members[0]=127.0.0.1:5701",
        "spring.datasource.url=jdbc:h2:mem:ha-dhcp-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class HaDhcpApplicationContextTest {

    @Test
    void contextLoads() {
    }
}
