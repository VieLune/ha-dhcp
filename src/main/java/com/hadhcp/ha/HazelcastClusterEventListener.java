package com.hadhcp.ha;

import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleListener;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Bridges Hazelcast lifecycle and member events into VIP and data recovery actions.
 */
@Component
public class HazelcastClusterEventListener implements LifecycleListener, MembershipListener, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HazelcastClusterEventListener.class);

    private final HazelcastInstance hazelcastInstance;
    private final VipReconciler vipReconciler;
    private final ClusterRecoveryCoordinator recoveryCoordinator;
    private UUID lifecycleListenerId;
    private UUID membershipListenerId;

    /**
     * Creates a listener that reacts to Hazelcast split and merge events.
     *
     * @param hazelcastInstance Hazelcast member that emits cluster events
     * @param vipReconciler component that owns VIP promotion and demotion
     * @param recoveryCoordinator component that owns recovered data conflict resolution
     */
    public HazelcastClusterEventListener(
            HazelcastInstance hazelcastInstance,
            VipReconciler vipReconciler,
            ClusterRecoveryCoordinator recoveryCoordinator
    ) {
        this.hazelcastInstance = hazelcastInstance;
        this.vipReconciler = vipReconciler;
        this.recoveryCoordinator = recoveryCoordinator;
    }

    /**
     * Registers this object as both a LifecycleEvent and MembershipEvent listener.
     */
    @Override
    public void afterPropertiesSet() {
        lifecycleListenerId = hazelcastInstance.getLifecycleService().addLifecycleListener(this);
        membershipListenerId = hazelcastInstance.getCluster().addMembershipListener(this);
        log.info("Registered Hazelcast cluster event listeners");
    }

    /**
     * Removes Hazelcast listener registrations during Spring shutdown.
     */
    @Override
    public void destroy() {
        if (lifecycleListenerId != null) {
            hazelcastInstance.getLifecycleService().removeLifecycleListener(lifecycleListenerId);
        }
        if (membershipListenerId != null) {
            hazelcastInstance.getCluster().removeMembershipListener(membershipListenerId);
        }
    }

    /**
     * Reacts to lifecycle merge states emitted by Hazelcast.
     *
     * @param event lifecycle event emitted by Hazelcast
     */
    @Override
    public void stateChanged(LifecycleEvent event) {
        runSafely("hazelcast lifecycle " + event.getState(), () -> {
            LifecycleEvent.LifecycleState state = event.getState();
            if (state == LifecycleEvent.LifecycleState.MERGING) {
                vipReconciler.demoteBeforeMerge("hazelcast lifecycle MERGING");
                return;
            }
            if (state == LifecycleEvent.LifecycleState.MERGED) {
                vipReconciler.promoteAfterSplit("hazelcast lifecycle MERGED");
                recoveryCoordinator.resolveMergeConflicts("hazelcast lifecycle MERGED");
                return;
            }
            if (state == LifecycleEvent.LifecycleState.MERGE_FAILED) {
                vipReconciler.promoteAfterSplit("hazelcast lifecycle MERGE_FAILED");
            }
        });
    }

    /**
     * Handles a member joining the visible cluster after a merge or normal startup.
     *
     * @param event membership event emitted by Hazelcast
     */
    @Override
    public void memberAdded(MembershipEvent event) {
        runSafely("hazelcast member added", () -> {
            vipReconciler.promoteAfterSplit("hazelcast member added");
            recoveryCoordinator.resolveMergeConflicts("hazelcast member added");
        });
    }

    /**
     * Promotes the local side immediately when the visible cluster shrinks during a partition.
     *
     * @param event membership event emitted by Hazelcast
     */
    @Override
    public void memberRemoved(MembershipEvent event) {
        runSafely("hazelcast member removed", () -> vipReconciler.promoteAfterSplit("hazelcast member removed"));
    }

    /**
     * Runs an event handler without letting recovery exceptions escape Hazelcast callbacks.
     *
     * @param action event label used in logs
     * @param task handler to execute
     */
    private void runSafely(String action, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            log.warn("Failed to handle {}", action, ex);
        }
    }
}
