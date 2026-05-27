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
import org.springframework.util.StringUtils;

/**
 * Keeps the VIP bound to the node that should currently act as the master.
 */
@Component
public class VipReconciler {

    private static final Logger log = LoggerFactory.getLogger(VipReconciler.class);

    private final HaProperties haProperties;
    private final ClusterProperties clusterProperties;
    private final HazelcastInstance hazelcastInstance;
    private final VipAddressResolver vipAddressResolver;
    private final VipManager vipManager;

    /**
     * Creates the reconciler from HA settings, cluster membership, and VIP operations.
     *
     * @param haProperties HA runtime properties
     * @param clusterProperties configured cluster member ordering
     * @param hazelcastInstance Hazelcast member used to inspect visible cluster members
     * @param vipAddressResolver resolver for VIP and interface names
     * @param vipManager operating-system VIP manager
     */
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

    /**
     * Runs the default VIP reconciliation loop on the scheduler.
     */
    @Scheduled(fixedDelayString = "${ha.reconcile-ms:2000}")
    public void reconcile() {
        promoteAfterSplit("scheduled reconcile");
    }

    /**
     * Re-evaluates whether this node should hold the VIP after a split-brain or membership change.
     *
     * @param trigger human-readable reason used in logs
     */
    public void promoteAfterSplit(String trigger) {
        applyVipState(true, trigger);
    }

    /**
     * Releases the VIP before a merge so the node does not keep a stale master address.
     *
     * @param trigger human-readable reason used in logs
     */
    public void demoteBeforeMerge(String trigger) {
        applyVipState(false, trigger);
    }

    /**
     * Applies either the hold or release path for the currently resolved VIP.
     *
     * @param shouldHoldVip whether the node should keep the VIP
     * @param trigger human-readable reason used in logs
     */
    private void applyVipState(boolean shouldHoldVip, String trigger) {
        if (!haProperties.isVipManagementEnabled()) {
            return;
        }

        Optional<String> vip = vipAddressResolver.resolveVip();
        Optional<String> interfaceName = vipAddressResolver.resolveInterfaceName();
        if (vip.isEmpty() || interfaceName.isEmpty()) {
            return;
        }

        String reason = StringUtils.hasText(trigger) ? trigger : "unspecified";
        boolean holdVip = shouldHoldVip && shouldHoldVip();
        if (holdVip) {
            if (!vipManager.ensureVip(vip.get(), interfaceName.get(), haProperties.getPrefixLength())) {
                log.warn("Failed to ensure VIP {} on {} during {}", vip.get(), interfaceName.get(), reason);
            }
            return;
        }

        if (!vipManager.releaseVip(vip.get(), interfaceName.get())) {
            log.warn("Failed to release VIP {} on {} during {}", vip.get(), interfaceName.get(), reason);
        }
    }

    /**
     * Decides whether the current node should own the VIP for the visible cluster.
     *
     * @return true when the local node should hold the VIP
     */
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

    /**
     * Formats the Hazelcast member address for deterministic comparisons.
     *
     * @param member Hazelcast cluster member
     * @return host:port string used for ordering
     */
    private String memberAddress(Member member) {
        return member.getAddress().getHost() + ":" + member.getAddress().getPort();
    }

    /**
     * Normalizes configured member entries before comparison.
     *
     * @param member raw configuration value
     * @return trimmed member entry or an empty string
     */
    private String normalizeConfiguredMember(String member) {
        return member == null ? "" : member.trim();
    }
}
