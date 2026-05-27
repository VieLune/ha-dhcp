package com.hadhcp.ha;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import org.junit.jupiter.api.Test;

class HazelcastClusterEventListenerTest {

    @Test
    void lifecycleMergingDemotesVipBeforeMerge() {
        VipReconciler vipReconciler = mock(VipReconciler.class);
        ClusterRecoveryCoordinator recoveryCoordinator = mock(ClusterRecoveryCoordinator.class);
        HazelcastClusterEventListener listener = new HazelcastClusterEventListener(
                mock(HazelcastInstance.class),
                vipReconciler,
                recoveryCoordinator
        );

        listener.stateChanged(new LifecycleEvent(LifecycleEvent.LifecycleState.MERGING));

        verify(vipReconciler).demoteBeforeMerge("hazelcast lifecycle MERGING");
    }

    @Test
    void lifecycleMergedReconcilesVipAndResolvesConflicts() {
        VipReconciler vipReconciler = mock(VipReconciler.class);
        ClusterRecoveryCoordinator recoveryCoordinator = mock(ClusterRecoveryCoordinator.class);
        HazelcastClusterEventListener listener = new HazelcastClusterEventListener(
                mock(HazelcastInstance.class),
                vipReconciler,
                recoveryCoordinator
        );

        listener.stateChanged(new LifecycleEvent(LifecycleEvent.LifecycleState.MERGED));

        verify(vipReconciler).promoteAfterSplit("hazelcast lifecycle MERGED");
        verify(recoveryCoordinator).resolveMergeConflicts("hazelcast lifecycle MERGED");
    }

    @Test
    void memberRemovedPromotesLocalPartition() {
        VipReconciler vipReconciler = mock(VipReconciler.class);
        ClusterRecoveryCoordinator recoveryCoordinator = mock(ClusterRecoveryCoordinator.class);
        HazelcastClusterEventListener listener = new HazelcastClusterEventListener(
                mock(HazelcastInstance.class),
                vipReconciler,
                recoveryCoordinator
        );

        listener.memberRemoved(mock(MembershipEvent.class));

        verify(vipReconciler).promoteAfterSplit("hazelcast member removed");
    }

    @Test
    void memberAddedReconcilesAndRunsRecoveredMerge() {
        VipReconciler vipReconciler = mock(VipReconciler.class);
        ClusterRecoveryCoordinator recoveryCoordinator = mock(ClusterRecoveryCoordinator.class);
        HazelcastClusterEventListener listener = new HazelcastClusterEventListener(
                mock(HazelcastInstance.class),
                vipReconciler,
                recoveryCoordinator
        );

        listener.memberAdded(mock(MembershipEvent.class));

        verify(vipReconciler).promoteAfterSplit("hazelcast member added");
        verify(recoveryCoordinator).resolveMergeConflicts("hazelcast member added");
    }
}
