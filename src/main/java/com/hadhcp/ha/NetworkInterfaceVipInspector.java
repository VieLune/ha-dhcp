package com.hadhcp.ha;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NetworkInterfaceVipInspector implements VipInspector {

    private static final Logger log = LoggerFactory.getLogger(NetworkInterfaceVipInspector.class);

    @Override
    public boolean hasAddress(String vip, String interfaceName) {
        try {
            InetAddress expected = InetAddress.getByName(vip);
            if (StringUtils.hasText(interfaceName)) {
                NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
                return networkInterface != null && hasAddress(networkInterface, expected);
            }

            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (hasAddress(networkInterface, expected)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log.warn("Failed to inspect VIP address {}", vip, ex);
            return false;
        }
    }

    private boolean hasAddress(NetworkInterface networkInterface, InetAddress expected) throws SocketException {
        if (!networkInterface.isUp()) {
            return false;
        }
        return Collections.list(networkInterface.getInetAddresses()).stream().anyMatch(expected::equals);
    }
}
