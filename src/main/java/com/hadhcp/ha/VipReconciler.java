package com.hadhcp.ha;

import com.hadhcp.config.ClusterProperties;
import com.hadhcp.config.HaProperties;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VipReconciler {

    private static final Logger log = LoggerFactory.getLogger(VipReconciler.class);

    private final HaProperties haProperties;
    private final ClusterProperties clusterProperties;
    private final HazelcastInstance hazelcastInstance;
    private final VipAddressResolver vipAddressResolver;
    private final VipManager vipManager;

    public VipReconciler(
            HaProperties haProperties,
            ClusterProperties clusterProperties,
            HazelcastInstance hazelcastInstance,
            VipAddressResolver vipAddressResolver,
            VipManager vipManager
    ) {
        this.haProperties = haProperties;
        this.clusterProperties = clusterProperties;
        this.hazelcastInstance = hazelcastInstance;
        this.vipAddressResolver = vipAddressResolver;
        this.vipManager = vipManager;
    }

    @Scheduled(fixedDelayString = "${ha.reconcile-ms:2000}")
    public void reconcile() {
        if (!haProperties.isVipManagementEnabled()) {
            return;
        }

        Optional<String> vip = vipAddressResolver.resolveVip();
        Optional<String> interfaceName = vipAddressResolver.resolveInterfaceName();
        if (vip.isEmpty() || interfaceName.isEmpty()) {
            return;
        }

        if (shouldHoldVip()) {
            if (!vipManager.ensureVip(vip.get(), interfaceName.get(), haProperties.getPrefixLength())) {
                log.warn("Failed to ensure VIP {} on {}", vip.get(), interfaceName.get());
            }
        } else if (!vipManager.releaseVip(vip.get(), interfaceName.get())) {
            log.warn("Failed to release VIP {} on {}", vip.get(), interfaceName.get());
        }
    }

    private boolean shouldHoldVip() {
        int memberCount = hazelcastInstance.getCluster().getMembers().size();
        if (memberCount <= 1) {
            return true;
        }

        Member local = hazelcastInstance.getCluster().getLocalMember();
        String localAddress = memberAddress(local);
        if (!clusterProperties.getMembers().isEmpty()) {
            List<String> configuredMembers = clusterProperties.getMembers().stream()
                    .map(this::normalizeConfiguredMember)
                    .toList();
            if (configuredMembers.contains(localAddress)) {
                return localAddress.equals(configuredMembers.get(0));
            }
        }

        String firstVisible = hazelcastInstance.getCluster().getMembers().stream()
                .map(this::memberAddress)
                .min(Comparator.naturalOrder())
                .orElse(localAddress);
        return localAddress.equals(firstVisible);
    }

    private String memberAddress(Member member) {
        return member.getAddress().getHost() + ":" + member.getAddress().getPort();
    }

    private String normalizeConfiguredMember(String member) {
        return member == null ? "" : member.trim();
    }
}
