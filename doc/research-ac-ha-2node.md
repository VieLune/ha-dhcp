# 2-Node AC High Availability — Split-Brain Analysis and Recommended Solution

**Project**: ha-dhcp  
**Date**: 2026-05-21  
**Scope**: 2 AC nodes only (no CMS), Spring Boot 3.3.6 + Hazelcast 5.5.0 AP + Keepalived  
**Status**: Research / Design

---

## Table of Contents

1. [Current Architecture Snapshot](#1-current-architecture-snapshot)
2. [The 2-Node Split-Brain Problem](#2-the-2-node-split-brain-problem)
3. [Solution Options](#3-solution-options)
4. [Recommended Solution](#4-recommended-solution)
5. [Failover Design](#5-failover-design)
6. [Code Snippets](#6-code-snippets)
7. [Verification Test Plan](#7-verification-test-plan)
8. [Risk Assessment](#8-risk-assessment)

---

## 1. Current Architecture Snapshot

### 1.1 Components

| Component | Role | Key Config |
|-----------|------|------------|
| Spring Boot 3.3.6 | DHCP server + HA state machine | port 8080, DHCP 1067 |
| Hazelcast 5.5.0 (AP) | Distributed lease store | port 5701, `dhcp:leases` backupCount=1 |
| Keepalived | VIP management via VRRP | AC-1 priority=110, AC-2 priority=100, nopreempt, unicast |
| H2 + Flyway + JPA | Local lease mirror | `./data/ha-dhcp` |

### 1.2 Key Code Paths

**`HazelcastDhcpLeaseStore.isAvailable()`** (`HazelcastDhcpLeaseStore.java:52-58`):
```java
public boolean isAvailable() {
    LifecycleService lifecycleService = hazelcastInstance.getLifecycleService();
    if (!lifecycleService.isRunning()) return false;
    return !haProperties.isRequireMajority()
        || memberCount() >= haProperties.getMinimumClusterSize();
}
```

**`HaService.canServeDhcp()`** (`HaService.java:55-61`):
```java
public boolean canServeDhcp() {
    return dhcpProperties.isEnabled()
            && role.get() == HaRole.MASTER
            && isLeaseMirrorLoaded()
            && leaseAuthorityHealth.isAvailable()
            && hasVip();
}
```

**`HaService.hasVip()`** checks the OS network interface for the VIP address — it is a real OS-level check, not a cached flag.

### 1.3 Current `application.yml` (relevant excerpt)

```yaml
ha:
  vip: 127.0.0.1          # change to real VIP in production
  require-majority: false  # ← currently disabled
  minimum-cluster-size: 2

cluster:
  name: ha-dhcp
  port: 5701
  members: []              # ← empty = multicast discovery
```

**Critical gap**: `require-majority: false` means `isAvailable()` always returns `true` as long as Hazelcast is running — regardless of how many cluster members exist. This is unsafe in a production 2-node deployment.

---

## 2. The 2-Node Split-Brain Problem

### 2.1 Quorum Theory

For N nodes, a safe quorum requires ⌊N/2⌋+1 votes. With N=2:
- Quorum = 2 nodes
- Any partition produces two sides of size 1
- Neither side alone has quorum

The 2-node problem is structurally irresolvable without a tiebreaker: **every network partition produces either total unavailability (safe) or dual-master (unsafe)** — you must choose which failure mode you prefer.

### 2.2 VRRP Split-Brain Scenario (the Specific Risk Here)

Step-by-step with current keepalived config:

```
Initial state:
  AC-1: VRRP state=MASTER, priority=110, VIP bound to eth0
  AC-2: VRRP state=BACKUP, priority=100, no VIP

Network partition begins (AC-1 ↔ AC-2 link severed):

  AC-1:
    → Continues sending VRRP adverts to unicast peer AC-2 (packets dropped)
    → AC-1 has no reason to yield the VIP
    → VIP remains on AC-1's eth0
    → keepalived state: still MASTER

  AC-2:
    → Stops receiving VRRP adverts from AC-1
    → Dead interval = advert_int * ((256 - priority_of_master) / 256) ≈ 1s
    → Promotes itself to MASTER
    → Binds VIP to its own eth0
    → Calls notify.sh MASTER → Spring Boot role=MASTER

Result after partition:
  AC-1: VIP=yes, role=MASTER, Hazelcast members=1
  AC-2: VIP=yes, role=MASTER, Hazelcast members=1

  canServeDhcp() with current config (require-majority=false):
    AC-1: enabled=T, role=MASTER, mirror=T, authority=T(requireMajority=F), hasVip=T → true
    AC-2: enabled=T, role=MASTER, mirror=T, authority=T(requireMajority=F), hasVip=T → true

  ⟹ DUAL DHCP MASTER — both respond to DISCOVER/REQUEST
  ⟹ IP conflict risk if they allocate from overlapping address pools
```

**`nopreempt` does not help here.** `nopreempt` only prevents the higher-priority node from reclaiming VIP after it recovers — it does not prevent a BACKUP node from self-promoting when it cannot hear the MASTER.

### 2.3 What Happens to Hazelcast During Partition

| Scenario | AC-1 members | AC-2 members | `isAvailable()` (requireMajority=false) | `isAvailable()` (requireMajority=true) |
|----------|-------------|-------------|----------------------------------------|----------------------------------------|
| Normal | 2 | 2 | true | true |
| AC-1 process crash | — | 1 (after timeout) | true | **false** |
| Network partition | 1 | 1 | true | **false** |

With `require-majority: true` and `minimum-cluster-size: 2`, `isAvailable()` returns `false` on both sides of a partition → `canServeDhcp()` returns `false` → no dual-master. This is the core fix.

**Hazelcast member removal timing** (critical for timeline planning):

- **Process crash**: TCP RST or connection refused detected almost immediately; Hazelcast removes the member within ~5–10 seconds.
- **Network partition** (firewall drop, no RST): Hazelcast must wait for heartbeat timeout. Default `hazelcast.max.no.heartbeat.seconds` = 60 seconds. This is a 60-second window of potential dual-master if requireMajority is not set.

---

## 3. Solution Options

### 3.1 Option A — Enable `requireMajority` (2-Node, Safe but Unavailable on Partition)

**Mechanism**: Set `require-majority: true`. During partition each node has 1 member < `minimum-cluster-size: 2`, so `isAvailable()` returns `false`, `canServeDhcp()` returns `false` on both nodes.

**Behavior matrix**:

| Event | Result |
|-------|--------|
| AC-1 crashes (process) | AC-2: Hazelcast drops AC-1 in ~10s, VIP drifts via keepalived → AC-2 serves (normal failover) |
| AC-2 crashes | Symmetric — AC-1 continues |
| Network partition | Both nodes stop serving DHCP. Existing leases hold until partition heals. |
| Partition heals | Hazelcast re-forms cluster, member count goes to 2, `isAvailable()=true`. Role re-evaluation required (see §6.3). |

**Impact on existing clients**: DHCP leases are typically T=3600s; clients renew at T/2=1800s. During a short partition (seconds to minutes), clients with valid leases continue operating. Only new join attempts fail.

**Recovery**: Automatic when Hazelcast cluster re-merges. Requires a lifecycle listener (see §6.3) to trigger role re-evaluation after merge.

**Verdict**: Correct and simple for pure 2-node. Accepts unavailability during partition in exchange for safety. Recommended baseline.

---

### 3.2 Option B — Lightweight Witness Node (Quorum Without Full Business Logic)

**Mechanism**: A 3rd machine runs a minimal Hazelcast member (no DHCP, no HTTP, no H2) to provide the tiebreaker vote. With 3 members and `minimum-cluster-size: 2`:

```
Partition scenario → quorum result:
  AC-1 isolated (1 member):          loses quorum → stops serving
  AC-2 + Witness (2 members):        has quorum → AC-2 can serve
  AC-1 + Witness (2 members):        has quorum → AC-1 can serve
  AC-1 + AC-2, Witness fails (2):    has quorum → normal operation continues
  All three isolated:                 none serve
```

No scenario results in dual-master. This is the strongest 2-node-AC solution.

**Witness node requirements**: Java 21, ~256 MB RAM, ~50 MB disk. Can be a Raspberry Pi 4, a small VM, or a Docker container on a separate host from AC-1/AC-2.

**Witness Hazelcast config** (standalone, no Spring Boot required):

```java
Config config = new Config();
config.setClusterName("ha-dhcp");
config.getNetworkConfig().setPort(5701);
JoinConfig join = config.getNetworkConfig().getJoin();
join.getMulticastConfig().setEnabled(false);
join.getTcpIpConfig().setEnabled(true)
    .addMember("AC1_IP:5701")
    .addMember("AC2_IP:5701");
// No MapConfig needed — witness does not store data
Hazelcast.newHazelcastInstance(config);
```

**Verdict**: Optimal architecture. Highly recommended if a 3rd machine is available. Existing AC code changes are minimal (just enable `require-majority: true` and set `members` in `cluster.yml`).

---

### 3.3 Option C — STONITH / Hardware Fencing

**Mechanism**: When node A detects node B has failed, A calls a fencing agent (IPMI, PDU API, managed switch port disable) to power-cycle or network-isolate B before A takes over.

**For this project**:
- Requires IPMI/BMC or PDU API access on both machines
- `notify_master` in keepalived calls a fence script before binding VIP
- Ensures the loser cannot have a live process after the winner claims MASTER
- Prevents "zombie master" scenarios

**Sample keepalived hook addition** (`keepalived-ac1.conf`):
```
notify_master "/etc/keepalived/fence_peer.sh MASTER AC2_IPMI_IP"
```

**`fence_peer.sh`** (pseudocode):
```bash
#!/usr/bin/env bash
ROLE="$1"; PEER_IPMI="$2"
if [ "$ROLE" = "MASTER" ]; then
    ipmitool -H "$PEER_IPMI" -U admin -P secret chassis power off
    sleep 3
    ipmitool -H "$PEER_IPMI" -U admin -P secret chassis power on
fi
```

**Verdict**: Very robust but requires dedicated IPMI/BMC hardware. Complex to set up and test. Overkill for most DHCP deployments. Useful only when hardware infrastructure already has out-of-band management.

---

### 3.4 Option D — Can Keepalived Alone Solve Split-Brain?

**Short answer**: No.

As shown in §2.2, VRRP unicast with `nopreempt` does not prevent both nodes from having VIP during a network partition. VRRP is designed to handle node failure, not network partition.

**What keepalived does well**:
- Fast health-check-based failover when one node's app dies (check_app.sh)
- VIP management and GARP after failover
- `nopreempt` prevents unnecessary VIP flapping on recovery

**What keepalived cannot do**:
- Guarantee at-most-one VIP holder during a network partition
- Coordinate distributed state between nodes

**Conclusion**: Keepalived is necessary but not sufficient. It must be combined with Option A or B.

---

### 3.5 Option E — Hazelcast CP Subsystem with 2 Nodes

CP Subsystem (Raft) requires a minimum CP group of 3 members (tolerates 1 failure). With 2 AC nodes:

- 2-node CP group: cannot reach quorum if 1 fails → not viable
- 2 AC + 1 witness as CP members: viable, but the witness must be a Raft participant (not a lightweight member)

With CP Subsystem you could use `FencedLock` to ensure exactly one node holds the MASTER lock:
```java
FencedLock masterLock = hazelcastInstance.getCPSubsystem().getLock("dhcp:master-lock");
long fence = masterLock.tryLockAndGetFence();
if (fence != FencedLock.INVALID_FENCE) {
    // This node is the exclusive master
}
```

**Verdict**: Architecturally correct but requires 3-member CP group (i.e., a 3rd machine that participates fully in Raft), and adds significant complexity. Not recommended unless you need linearizable distributed operations beyond what SBP + requireMajority provides. For single-master DHCP, Option A or B is sufficient.

---

### 3.6 Option F — Shared Storage Arbitration

**Mechanism**: Both nodes compete for a lock on shared storage (PostgreSQL advisory lock, NFS file lock, etcd lease). The lock holder is MASTER.

**Examples**:
- `pg_try_advisory_lock(1)` on a shared PostgreSQL
- etcd lease + TTL
- Zookeeper ephemeral znode

**Verdict**: Introduces a shared storage component as a new single point of failure. Not recommended unless the infrastructure already has a highly-available shared database. The witness node option (3.2) is simpler and avoids a new external dependency.

---

## 4. Recommended Solution

### 4.1 Decision Matrix

| Criterion | Option A (requireMajority, 2-node) | Option B (+ Witness) |
|-----------|------------------------------------|----------------------|
| Split-brain safe | ✅ | ✅ |
| Available during network partition | ❌ both stop | ✅ majority partition continues |
| Requires 3rd machine | ❌ | ✅ small VM/RPi |
| Code changes needed | Minimal | Minimal |
| Operational complexity | Low | Low + witness maintenance |
| Recommended for | budget-constrained / correctness-first | production deployments |

### 4.2 Recommended: Option B (Keepalived + requireMajority + Witness)

For production, deploy a lightweight witness node and enable `require-majority: true`.

**Architecture**:
```
┌─────────────────────────────────────────────────────────────────┐
│  Layer-2 Network                                                │
│                                                                 │
│  ┌───────────────┐     Hazelcast     ┌───────────────┐         │
│  │     AC-1      │◄─────cluster─────►│     AC-2      │         │
│  │ Spring Boot   │     (TCP-IP)      │ Spring Boot   │         │
│  │ Hazelcast     │                   │ Hazelcast     │         │
│  │ keepalived    │                   │ keepalived    │         │
│  │ priority=110  │                   │ priority=100  │         │
│  └───────┬───────┘                   └───────┬───────┘         │
│          │                                   │                  │
│          └──────────────┬────────────────────┘                  │
│                         │ Hazelcast cluster                     │
│                    ┌────▼────┐                                  │
│                    │ Witness │  (Hazelcast only, no DHCP)       │
│                    │  Node   │  small VM / RPi                  │
│                    └─────────┘                                  │
│                                                                 │
│  VIP: floats between AC-1 and AC-2 via keepalived VRRP         │
└─────────────────────────────────────────────────────────────────┘
```

**Split-brain behavior with witness**:

| Scenario | Hazelcast quorum (≥2) | DHCP service |
|----------|-----------------------|--------------|
| All healthy | AC-1: 3, AC-2: 3 | MASTER serves |
| AC-1 crashes | AC-2: 2 (AC-2+W) | AC-2 takes VIP via keepalived → serves |
| AC-2 crashes | AC-1: 2 (AC-1+W) | AC-1 keeps VIP → continues serving |
| Net partition AC-1 \| AC-2+W | AC-1: 1 < 2 → stops | AC-2+W: 2 → AC-2 serves |
| Net partition AC-2 \| AC-1+W | AC-2: 1 < 2 → stops | AC-1+W: 2 → AC-1 serves |
| Witness fails | AC-1: 2, AC-2: 2 | Normal operation |
| All three isolated | 1, 1, 1 | No service (safe) |

### 4.3 Fallback: Option A (Pure 2-Node, No Witness)

If a 3rd machine is unavailable, enable `require-majority: true` only. During network partition both nodes stop serving. The tradeoff is documented and accepted.

**Key configuration change**:
```yaml
ha:
  require-majority: true   # was false
  minimum-cluster-size: 2  # unchanged
```

No code changes required for Option A. The logic in `HazelcastDhcpLeaseStore.isAvailable()` already handles this correctly.

---

## 5. Failover Design

### 5.1 notify.sh and the "Spring Boot Down" Race Condition

Current `notify.sh` behavior when Spring Boot is unavailable:
```bash
curl -fsS --max-time 2 -X POST "${HA_NOTIFY_URL}?role=${ROLE}" \
  || logger -t ac-ha-notify "failed to notify Spring Boot: role=$ROLE"
```

- curl fails silently (logged, non-fatal) ✅
- Role file `/run/ac-ha-role` is still written ✅
- **Problem**: When Spring Boot restarts, it initializes with `role=UNKNOWN` and never re-reads the role file

**The gap**: keepalived calls notify.sh only on state transitions. If Spring Boot was down during the last keepalived state change, it misses its role permanently until the next transition.

**Fix**: On startup, read `/run/ac-ha-role` and apply role (see §6.1 — `RoleRecoveryBean`).

**Additional concern**: There is an ordering window where keepalived promotes a node to MASTER before Spring Boot finishes startup (leaseMirrorLoaded=false). In this window, `canServeDhcp()` returns `false` even though the VIP is bound and the role is MASTER. This is correct and safe behavior — the node will not serve DHCP until the mirror is loaded.

### 5.2 In-Application Automatic Failover (Without External Keepalived)

A fully in-application HA design is feasible but has a significant constraint: **VIP management requires OS-level `ip addr add/del` commands, which need `CAP_NET_ADMIN` capability or root**.

Viable hybrid approach:
- **Keepalived** retains responsibility for VIP (GARP, ARP table update)
- **Spring Boot** watches its own VIP state and Hazelcast membership in a background thread

If keepalived fails (process crash), Spring Boot can detect `hasVip()` changing and self-demote to a safe state (but cannot reclaim VIP without OS capability). This is defense-in-depth, not a replacement for keepalived.

A pure in-application design would require:
1. `CAP_NET_ADMIN` or root in the service unit
2. Implementing GARP (or calling `arping`) on VIP takeover
3. Implementing the VIP dead-interval timer (equivalent to VRRP)

Not recommended given that keepalived already handles this robustly.

### 5.3 Complete Failover Timeline: AC-1 Crash → Client Recovery

```
T=0s      AC-1 JVM crashes (or systemctl kill, or power failure)
          - TCP connections to AC-1:5701 receive RST or timeout
          - TCP connections to AC-1:8080 receive RST

T=~2s     Hazelcast on AC-2: detects connection failure to AC-1
          - Begins suspect/heartbeat evaluation
          (process crash: fast; network partition: up to max.no.heartbeat.seconds=60s)

T=2s      keepalived on AC-2: VRRP advert_int=1s, fall=2
          - Two consecutive health check failures required
          - check_app.sh curls AC-1's health endpoint → connection refused (max-time=1s)
          - Fall threshold reached at ~T=4s (2 intervals × 2s)

T=4s      keepalived AC-2: transitions BACKUP → MASTER
          - Binds VIP to AC-2's eth0
          - Sends GARP (garp_master_delay=1s, repeat=3, configured in keepalived-ac2.conf)
          - Calls: notify.sh MASTER

T=4s      notify.sh on AC-2:
          - Writes "MASTER" to /run/ac-ha-role
          - POSTs to http://127.0.0.1:8080/internal/ha/role?role=MASTER
          - Spring Boot: HaService.updateRole(MASTER)

T=5-7s    Network switches update ARP cache for VIP → now points to AC-2's MAC
          (GARP forces immediate ARP table update on all connected devices)

T=5-10s   Hazelcast on AC-2: removes AC-1 member (TCP RST detected fast)
          - memberCount() drops to 1 temporarily IF witness not present
            → With witness: memberCount=2, isAvailable()=true ✓
            → Without witness + requireMajority=true: isAvailable()=false (BRIEF OUTAGE)

T=~10s    (No witness scenario) AC-2 has role=MASTER, VIP=yes, but authority=unavailable
          → canServeDhcp()=false
          → AC-2 cannot serve until Hazelcast finalizes member removal

T=~15s    (No witness scenario) Hazelcast fully removes AC-1 from cluster membership
          → memberCount stays at 1 (no witness)
          → With requireMajority=true: isAvailable()=false permanently until AC-1 rejoins
          *** THIS IS THE 2-NODE PURE OPTION A TRADE-OFF ***

T=~10s    (With witness) Hazelcast cluster: AC-2 + Witness = 2 members
          → isAvailable()=true
          → canServeDhcp()=true
          → AC-2 responds to DHCP requests

T=5-15s   DHCP clients: ARP resolves VIP to AC-2, requests reach AC-2
T=10-20s  Full recovery for DHCP clients
```

**Summary**:
- With witness: ~10–15 seconds to full recovery
- Without witness + requireMajority=true: AC-2 cannot serve until AC-1 rejoins (indefinite)
- Without witness + requireMajority=false: ~10s recovery but split-brain risk remains

### 5.4 Hazelcast Merge Policy After Partition Heal

When a network partition heals, Hazelcast detects the split cluster and merges the smaller into the larger. The merge process:

1. The "losing" (smaller) cluster member receives a `MERGING` lifecycle event
2. It disconnects, re-joins the larger cluster
3. Map entries are reconciled using the configured `MergePolicyConfig`
4. A `MERGED` lifecycle event is fired on the rejoining member

For DHCP leases, use `LatestUpdateMergePolicy` — the entry with the most recent update timestamp wins:
```java
MapConfig leases = new MapConfig("dhcp:leases");
leases.getMergePolicyConfig()
    .setPolicy("com.hazelcast.spi.merge.LatestUpdateMergePolicy")
    .setBatchSize(100);
```

After merge, the application must re-evaluate its role (see §6.3 — `HazelcastPartitionListener`).

---

## 6. Code Snippets

All snippets are ready for direct use in the existing Spring Boot project.

### 6.1 Spring Boot Startup Role Recovery

Reads role from `/run/ac-ha-role` when keepalived couldn't reach Spring Boot during a prior state transition.

```java
package com.hadhcp.ha;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reads the keepalived role file on startup to recover from the case where
 * Spring Boot was down when keepalived last changed state.
 * Runs after Hazelcast and the lease mirror are initialized (Order=10 assumes
 * lease mirror loading happens at a lower order number).
 */
@Component
@Order(10)
public class RoleRecoveryBean implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoleRecoveryBean.class);

    @Value("${ha.role-file:/run/ac-ha-role}")
    private String roleFilePath;

    private final HaService haService;

    public RoleRecoveryBean(HaService haService) {
        this.haService = haService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (haService.role() != HaRole.UNKNOWN) {
            // Role was set via API before runner executed — skip file recovery
            return;
        }
        Path roleFile = Path.of(roleFilePath);
        if (!Files.exists(roleFile)) {
            log.info("No role file at {}; keeping role=UNKNOWN until keepalived notifies", roleFilePath);
            return;
        }
        try {
            String raw = Files.readString(roleFile).strip();
            HaRole recovered = mapKeepalivedRole(raw);
            haService.updateRole(recovered);
            log.info("Recovered HA role from {}: {} -> {}", roleFilePath, raw, recovered);
        } catch (IOException e) {
            log.warn("Failed to read role file {}: {}", roleFilePath, e.getMessage());
        }
    }

    /** Maps keepalived VRRP state names to internal HaRole. */
    private HaRole mapKeepalivedRole(String keepalivedState) {
        return switch (keepalivedState.toUpperCase()) {
            case "MASTER"  -> HaRole.MASTER;
            case "BACKUP"  -> HaRole.STANDBY;
            case "FAULT"   -> HaRole.FAULT;
            case "STOP"    -> HaRole.STOP;
            default -> {
                log.warn("Unknown keepalived state '{}', defaulting to UNKNOWN", keepalivedState);
                yield HaRole.UNKNOWN;
            }
        };
    }
}
```

---

### 6.2 Hazelcast Configuration with Split-Brain Protection and Merge Policy

Extends `HazelcastConfiguration.java` to add SBP and merge policy:

```java
package com.hadhcp.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MergePolicyConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.SplitBrainProtectionConfig;
import com.hazelcast.config.SplitBrainProtectionOn;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HazelcastConfiguration {

    private static final String SBP_NAME = "dhcp-sbp";

    @Bean(destroyMethod = "shutdown")
    public HazelcastInstance hazelcastInstance(ClusterProperties properties,
                                               HaProperties haProperties) {
        Config config = new Config();
        config.setClusterName(properties.getName());

        NetworkConfig network = config.getNetworkConfig();
        network.setPort(properties.getPort());
        network.setPortAutoIncrement(true);

        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(properties.getMembers().isEmpty());
        join.getTcpIpConfig().setEnabled(!properties.getMembers().isEmpty());
        properties.getMembers().forEach(join.getTcpIpConfig()::addMember);

        // Split-brain protection: map operations throw SplitBrainProtectionException
        // when the cluster has fewer than minimumClusterSize members.
        if (haProperties.isRequireMajority()) {
            SplitBrainProtectionConfig sbp = new SplitBrainProtectionConfig()
                    .setName(SBP_NAME)
                    .setEnabled(true)
                    .setMinimumClusterSize(haProperties.getMinimumClusterSize())
                    .setProtectOn(SplitBrainProtectionOn.READ_WRITE);
            config.addSplitBrainProtectionConfig(sbp);
        }

        MapConfig leases = new MapConfig("dhcp:leases");
        leases.setBackupCount(1);
        leases.setAsyncBackupCount(0);
        // Most-recent write wins on split-brain merge
        leases.setMergePolicyConfig(
            new MergePolicyConfig("com.hazelcast.spi.merge.LatestUpdateMergePolicy", 100));
        if (haProperties.isRequireMajority()) {
            leases.setSplitBrainProtectionName(SBP_NAME);
        }
        config.addMapConfig(leases);

        return Hazelcast.newHazelcastInstance(config);
    }
}
```

---

### 6.3 Hazelcast Partition-Heal Listener (Role Re-evaluation after Merge)

When Hazelcast merges after a split-brain, the rejoining node restarts its Hazelcast instance. The existing role (set by keepalived) may be inconsistent with the post-merge cluster state. This listener triggers a re-check.

```java
package com.hadhcp.ha;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleEvent.LifecycleState;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Listens for Hazelcast lifecycle events and re-evaluates HA role after
 * a split-brain merge. After MERGED, this node's lease data has been
 * reconciled; the role should align with the current VIP state.
 */
@Component
public class HazelcastPartitionListener {

    private static final Logger log = LoggerFactory.getLogger(HazelcastPartitionListener.class);

    private final HazelcastInstance hazelcastInstance;
    private final HaService haService;
    private final VipInspector vipInspector;

    public HazelcastPartitionListener(HazelcastInstance hazelcastInstance,
                                      HaService haService,
                                      VipInspector vipInspector) {
        this.hazelcastInstance = hazelcastInstance;
        this.haService = haService;
        this.vipInspector = vipInspector;
    }

    @PostConstruct
    public void register() {
        hazelcastInstance.getLifecycleService().addLifecycleListener(this::onLifecycleEvent);
    }

    private void onLifecycleEvent(LifecycleEvent event) {
        if (event.getState() == LifecycleState.MERGED) {
            log.info("Hazelcast split-brain merge complete — re-evaluating HA role");
            reevaluateRole();
        } else if (event.getState() == LifecycleState.MERGE_FAILED) {
            log.error("Hazelcast split-brain merge FAILED — forcing role to FAULT");
            haService.updateRole(HaRole.FAULT);
        }
    }

    /**
     * After merge: if this node holds the VIP, it should become MASTER.
     * If it does not, it should become STANDBY.
     * Nodes already in FAULT/MAINTENANCE/STOP keep their current role.
     */
    private void reevaluateRole() {
        HaRole current = haService.role();
        if (current == HaRole.FAULT
                || current == HaRole.MAINTENANCE
                || current == HaRole.STOP) {
            log.info("Post-merge role re-evaluation skipped: node is in {} state", current);
            return;
        }
        boolean hasVip = haService.hasVip();
        HaRole newRole = hasVip ? HaRole.MASTER : HaRole.STANDBY;
        if (newRole != current) {
            log.info("Post-merge role transition: {} -> {} (hasVip={})", current, newRole, hasVip);
            haService.updateRole(newRole);
        }
    }
}
```

---

### 6.4 Updated `HazelcastDhcpLeaseStore` — Handle `SplitBrainProtectionException`

When SBP is enabled and the cluster loses quorum, map operations throw `SplitBrainProtectionException`. The current `isAvailable()` check uses the member count check — but `put()` and `putIfAbsent()` may still throw if SBP is active. Wrap them defensively:

```java
package com.hadhcp.dhcp.lease;

import com.hadhcp.config.HaProperties;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import com.hazelcast.splitbrainprotection.SplitBrainProtectionException;
import java.util.Collection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HazelcastDhcpLeaseStore implements DhcpLeaseStore {

    private static final Logger log = LoggerFactory.getLogger(HazelcastDhcpLeaseStore.class);

    private final HazelcastInstance hazelcastInstance;
    private final HaProperties haProperties;
    private final IMap<String, DhcpLeaseRecord> leases;

    public HazelcastDhcpLeaseStore(HazelcastInstance hazelcastInstance, HaProperties haProperties) {
        this.hazelcastInstance = hazelcastInstance;
        this.haProperties = haProperties;
        this.leases = hazelcastInstance.getMap("dhcp:leases");
    }

    @Override
    public Optional<DhcpLeaseRecord> findByIp(String ipAddress) {
        try {
            return Optional.ofNullable(leases.get(ipAddress));
        } catch (SplitBrainProtectionException e) {
            log.warn("Split-brain protection active — read rejected for ip={}", ipAddress);
            return Optional.empty();
        }
    }

    @Override
    public Optional<DhcpLeaseRecord> findByMac(String macAddress) {
        try {
            return leases.values().stream()
                    .filter(l -> l.macAddress().equalsIgnoreCase(macAddress))
                    .findFirst();
        } catch (SplitBrainProtectionException e) {
            log.warn("Split-brain protection active — read rejected for mac={}", macAddress);
            return Optional.empty();
        }
    }

    @Override
    public boolean putIfAbsent(DhcpLeaseRecord lease) {
        try {
            return leases.putIfAbsent(lease.ipAddress(), lease) == null;
        } catch (SplitBrainProtectionException e) {
            log.warn("Split-brain protection active — putIfAbsent rejected for ip={}", lease.ipAddress());
            return false;
        }
    }

    @Override
    public void put(DhcpLeaseRecord lease) {
        try {
            leases.put(lease.ipAddress(), lease);
        } catch (SplitBrainProtectionException e) {
            log.warn("Split-brain protection active — put rejected for ip={}", lease.ipAddress());
        }
    }

    @Override
    public Collection<DhcpLeaseRecord> values() {
        try {
            return leases.values();
        } catch (SplitBrainProtectionException e) {
            log.warn("Split-brain protection active — values() rejected");
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public boolean isAvailable() {
        LifecycleService lifecycleService = hazelcastInstance.getLifecycleService();
        if (!lifecycleService.isRunning()) return false;
        return !haProperties.isRequireMajority()
                || memberCount() >= haProperties.getMinimumClusterSize();
    }

    @Override
    public int memberCount() {
        return hazelcastInstance.getCluster().getMembers().size();
    }
}
```

---

### 6.5 `application.yml` Changes for Production 2-Node Deployment

```yaml
ha:
  vip: 192.168.56.200      # real VIP CIDR for production (was 127.0.0.1)
  interface-name: eth0      # the NIC that carries the VIP
  require-majority: true    # KEY CHANGE: was false
  minimum-cluster-size: 2   # unchanged

cluster:
  name: ha-dhcp
  port: 5701
  members:
    - 192.168.56.101:5701   # AC-1 physical IP
    - 192.168.56.102:5701   # AC-2 physical IP
    - 192.168.56.103:5701   # Witness node (if present); omit for pure 2-node
```

And add the role file path override if needed:
```yaml
ha:
  role-file: /run/ac-ha-role   # must match ROLE_FILE in notify.sh
```

---

### 6.6 Witness Node Startup Script (`witness-start.sh`)

Minimal standalone Hazelcast witness. Copy `hazelcast-5.5.0-slim.jar` to the witness machine and run:

```bash
#!/usr/bin/env bash
# witness-start.sh — run on the witness node
# Requires: Java 21, hazelcast-5.5.0-slim.jar on classpath
set -euo pipefail

AC1_IP="${1:?AC1_IP required}"
AC2_IP="${2:?AC2_IP required}"
CLUSTER_NAME="${3:-ha-dhcp}"
PORT="${4:-5701}"

HAZELCAST_JAR="/opt/hazelcast/hazelcast-5.5.0-slim.jar"

java -Xms64m -Xmx128m \
  -cp "$HAZELCAST_JAR" \
  -Dhazelcast.config=/etc/hazelcast/witness.xml \
  com.hazelcast.core.server.HazelcastMemberStarter
```

`/etc/hazelcast/witness.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<hazelcast xmlns="http://www.hazelcast.com/schema/config"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://www.hazelcast.com/schema/config
           https://hazelcast.com/schema/config/hazelcast-config-5.5.xsd">

    <cluster-name>ha-dhcp</cluster-name>

    <network>
        <port auto-increment="false">5701</port>
        <join>
            <multicast enabled="false"/>
            <tcp-ip enabled="true">
                <member>AC1_IP:5701</member>
                <member>AC2_IP:5701</member>
            </tcp-ip>
        </join>
    </network>

    <!-- Witness stores no data — all maps use minimal config -->
    <map name="default">
        <backup-count>0</backup-count>
        <async-backup-count>0</async-backup-count>
        <eviction eviction-policy="LRU" max-size-policy="PER_NODE" size="0"/>
    </map>

    <properties>
        <!-- Faster member removal for process crashes -->
        <property name="hazelcast.max.no.heartbeat.seconds">10</property>
        <property name="hazelcast.heartbeat.interval.seconds">1</property>
    </properties>
</hazelcast>
```

The same heartbeat tuning should be applied to AC-1 and AC-2's Hazelcast config for faster member failure detection:

```java
// In HazelcastConfiguration.java — add to config
config.setProperty("hazelcast.max.no.heartbeat.seconds", "10");
config.setProperty("hazelcast.heartbeat.interval.seconds", "1");
```

---

## 7. Verification Test Plan

### 7.1 Test Environment Setup

```
Physical / VM topology:
  AC-1 (192.168.56.101): Spring Boot + Hazelcast + keepalived
  AC-2 (192.168.56.102): Spring Boot + Hazelcast + keepalived
  Witness (192.168.56.103): Hazelcast only (optional)
  VIP: 192.168.56.200
  Test client: 192.168.56.50 (sends DHCP requests, monitors responses)
  Capture host: promiscuous mode on the same L2 segment

Helper aliases:
  alias kstatus='journalctl -u keepalived -n 20 --no-pager'
  alias hzstatus='curl -s http://VIP:8080/actuator/health | python3 -m json.tool'
```

---

### 7.2 HA-01 — Normal Failover (AC-1 Process Crash)

**Purpose**: Verify VIP drift and DHCP continuity when AC-1 crashes.

```bash
# Step 1: Verify initial state
curl http://192.168.56.200:8080/actuator/health  # should return UP
ip addr show eth0 | grep 192.168.56.200          # on AC-1: should show VIP

# Step 2: Crash AC-1
ssh root@192.168.56.101 "systemctl stop ha-dhcp"
ssh root@192.168.56.101 "systemctl stop keepalived"

# Step 3: Monitor failover on AC-2
watch -n1 'ip addr show eth0 | grep 56.200'    # VIP should appear within 15s
journalctl -u keepalived -f                     # watch for MASTER transition
curl http://192.168.56.200:8080/actuator/health # should return UP after ~15s

# Step 4: Verify DHCP still works
dhclient -v -r && dhclient -v eth0             # on test client: renew lease

# Step 5: Verify no duplicate response
tcpdump -i eth0 -n port 67 or port 68 &       # on capture host
# Confirm only 1 OFFER per DISCOVER

# Expected: VIP on AC-2 within 10-15s, single DHCP responder
```

**Pass criteria**:
- VIP visible on AC-2 `ip addr` within 15 seconds
- `curl http://VIP:8080/actuator/health` returns `{"status":"UP"}` within 20 seconds
- DHCP client receives exactly one OFFER for each DISCOVER
- Option 54 (Server Identifier) in DHCP response = VIP (192.168.56.200)

---

### 7.3 HA-02 — Network Partition Simulation

**Purpose**: Verify split-brain protection when network between AC-1 and AC-2 is severed.

```bash
# Step 1: Verify initial state (both nodes healthy, VIP on AC-1)
curl http://192.168.56.200:8080/actuator/health
# On both nodes:
curl http://192.168.56.101:8080/actuator/health
curl http://192.168.56.102:8080/actuator/health

# Step 2: Simulate partition using iptables (does NOT kill processes)
# On AC-1: drop all packets to/from AC-2
ssh root@192.168.56.101 "iptables -A INPUT -s 192.168.56.102 -j DROP && iptables -A OUTPUT -d 192.168.56.102 -j DROP"
# On AC-2: drop all packets to/from AC-1
ssh root@192.168.56.102 "iptables -A INPUT -s 192.168.56.101 -j DROP && iptables -A OUTPUT -d 192.168.56.101 -j DROP"
# If witness exists, also isolate one side from witness to test asymmetric partition

# Step 3: Wait for partition detection (~5-10s for process crash path, up to 60s for network)
sleep 15

# Step 4: Check each node independently
ssh root@192.168.56.101 "curl -s http://127.0.0.1:8080/actuator/health"
ssh root@192.168.56.102 "curl -s http://127.0.0.1:8080/actuator/health"

# WITHOUT WITNESS + requireMajority=true: BOTH should show canServeDhcp=false
# WITH WITNESS (AC-2+Witness have quorum): AC-2 should show canServeDhcp=true, AC-1=false

# Step 5: Verify no dual DHCP response (capture on test client NIC)
tcpdump -i eth0 -n '(udp port 67 or udp port 68)'
# Send a DHCP DISCOVER from test client
dhclient -v -r && dhclient -v --no-pid eth0
# Count OFFER packets — must be exactly 0 (no witness, both stopped) or 1 (with witness)

# Step 6: Heal partition
ssh root@192.168.56.101 "iptables -F"
ssh root@192.168.56.102 "iptables -F"

# Step 7: Verify recovery
sleep 15
curl http://192.168.56.200:8080/actuator/health  # should return UP
```

**Pass criteria (Option A — no witness)**:
- After partition: both nodes report `canServeDhcp=false` via health endpoint
- DHCP clients receive 0 OFFER packets during partition
- After partition heals: exactly one node serves DHCP

**Pass criteria (Option B — with witness)**:
- AC-1 (isolated) reports `canServeDhcp=false`
- AC-2 (majority side) reports `canServeDhcp=true`
- DHCP clients receive exactly 1 OFFER

---

### 7.4 HA-03 — VIP Drift Detection

**Purpose**: Verify `hasVip()` correctly reflects the OS state, not a stale cache.

```bash
# Check VIP detection on each node
ssh root@192.168.56.101 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | grep -i vip'
ssh root@192.168.56.102 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | grep -i vip'

# Manually add VIP to AC-2 (simulating misconfiguration) while AC-1 has it
ssh root@192.168.56.102 "ip addr add 192.168.56.200/24 dev eth0"
# Within the health poll interval, AC-2 should detect hasVip=true
# But role != MASTER, so canServeDhcp() should remain false on AC-2
ssh root@192.168.56.102 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool'

# Cleanup
ssh root@192.168.56.102 "ip addr del 192.168.56.200/24 dev eth0"

# Pass: canServeDhcp=false on AC-2 even with VIP present, because role != MASTER
```

---

### 7.5 HA-04 — Spring Boot Restart Role Recovery

**Purpose**: Verify `RoleRecoveryBean` correctly restores role after a Spring Boot crash.

```bash
# Step 1: Ensure AC-1 is MASTER and role file exists
ssh root@192.168.56.101 "cat /run/ac-ha-role"  # should print MASTER

# Step 2: Kill Spring Boot (keep keepalived running)
ssh root@192.168.56.101 "systemctl stop ha-dhcp"

# Step 3: keepalived on AC-2 detects failure → promotes to MASTER
# Step 4: keepalived calls notify.sh MASTER → writes MASTER to /run/ac-ha-role on AC-2
# Step 5: Restart Spring Boot on AC-1
ssh root@192.168.56.101 "systemctl start ha-dhcp"

# Step 6: On AC-1, check that role was recovered from file (should be BACKUP/STANDBY)
# keepalived state = BACKUP after AC-2 took over → role file should say BACKUP
ssh root@192.168.56.101 "cat /run/ac-ha-role"  # should print BACKUP
ssh root@192.168.56.101 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | grep -i role'

# Pass: AC-1 reports role=STANDBY after restart, not MASTER
```

---

### 7.6 HA-05 — Failover Timing Measurement

**Purpose**: Measure actual end-to-end failover time to establish SLA baseline.

```bash
# On capture host: monitor DHCP response availability
while true; do
    result=$(echo "" | timeout 3 dhcping -s 192.168.56.200 2>&1 || true)
    echo "$(date '+%H:%M:%S.%3N') $result"
    sleep 0.5
done > /tmp/dhcp-availability.log &

# On AC-1: crash the service
systemctl stop ha-dhcp && systemctl stop keepalived

# Wait for recovery
sleep 30

# Analyze gap in dhcp-availability.log
grep -c "Got answer" /tmp/dhcp-availability.log
# Find first "Got answer" after gap to calculate recovery time
```

**Target**: Recovery within 30 seconds. (Keepalived failover ~10s + Hazelcast detection ~10s + GARP propagation ~2s + DHCP client retry ~5s)

---

### 7.7 HA-06 — Duplicate IP Allocation Under Concurrent Load

**Purpose**: Verify no IP conflicts when both nodes briefly believe they are MASTER.

```bash
# This is the most critical correctness test
# Requires: a custom DHCP client that can send bursts

# Setup: two clients, send simultaneous DISCOVERs during a failover event
# Client A on segment 1, Client B on segment 2 (same L2)

# Step 1: Both clients request new leases simultaneously
# Step 2: During the request, trigger a keepalived MASTER flip on AC-1
# Step 3: Verify no two clients receive the same IP

# Simple check with dhclient (sequential, not ideal):
for i in $(seq 1 20); do
    ip=$(dhclient -v eth0 2>&1 | grep 'bound to' | awk '{print $3}')
    echo "Client allocation: $ip"
done

# Cross-check: all allocated IPs should be unique
# Also verify via Hazelcast: curl http://VIP:8080/internal/leases (if endpoint exists)
```

---

## 8. Risk Assessment

### 8.1 Risk Matrix

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R01 | Network partition causes dual-master (requireMajority=false) | **High** without fix | **Critical** — IP conflicts | Enable `require-majority: true` (§4.3) |
| R02 | Hazelcast member detection delay (up to 60s default) | Medium | High — extended dual-master window | Tune heartbeat: `max.no.heartbeat.seconds=10` (§6.6) |
| R03 | Spring Boot down during keepalived state change — role not updated | Medium | Medium — wrong role on restart | `RoleRecoveryBean` reads role file on startup (§6.1) |
| R04 | VIP bound to both nodes simultaneously during partition | Medium | Critical — GARP confusion, ARP poisoning | requireMajority check gates DHCP response before VIP action |
| R05 | Hazelcast split-brain merge loses lease records | Low | High — IP reuse after merge | `LatestUpdateMergePolicy` + H2 local mirror as backup (§5.4) |
| R06 | Witness node failure reduces quorum headroom | Low | Low — falls back to Option A behavior | Monitor witness health; keep it simple (no business logic to fail) |
| R07 | `check_app.sh` returns false negative (app healthy but check fails) | Low | Medium — spurious VIP failover | Tune `fall=3` in keepalived; ensure health endpoint is lightweight |
| R08 | `nopreempt` causes VIP to stay on failed-then-recovered AC-2 | Low | Low — suboptimal but functional | Manually force VIP back via `systemctl restart keepalived` on AC-1 |
| R09 | H2 file corruption during crash | Very Low | Medium — lease mirror lost; clients get new IPs | `WRITE_DELAY=0` in H2 JDBC URL; H2 has WAL, corruption is rare |
| R10 | GARP not propagated (switch drops gratuitous ARP) | Very Low | High — VIP unreachable after failover | Increase `garp_master_repeat=5`; test in lab; use `arping` in notify.sh as backup |

### 8.2 Most Critical: R01 — Current `requireMajority=false`

The current production configuration (`require-majority: false`) means a network partition between AC-1 and AC-2 will result in both nodes serving DHCP simultaneously. This is the highest-priority fix.

**Immediate action**: Change `require-majority: true` in `application.yml` before production deployment. The tradeoff (DHCP unavailable during partition) is acceptable — existing DHCP clients keep their leases and only new join attempts fail during the outage window.

### 8.3 Keepalived Unicast vs. Multicast

The current config uses unicast (`unicast_src_ip`, `unicast_peer`). This is correct for environments where multicast is unreliable. However, unicast VRRP requires that both nodes know each other's IPs at config time. If physical IP changes, keepalived configs must be updated.

Ensure `TODO_AC1_IP`, `TODO_AC2_IP`, `TODO_VIP_CIDR`, and `TODO_INTERFACE` placeholders are replaced with real values in production.

### 8.4 Health Check Precision

`check_app.sh` falls back to `/actuator/health` if `/actuator/health/ha` is unavailable. The HA-specific health check is more precise because it includes `canServeDhcp` state. When the HA endpoint is implemented, set `ALLOW_BASIC_HEALTH_FALLBACK=false` to avoid promoting a node to MASTER that has a healthy JVM but is not actually ready to serve DHCP.

### 8.5 Lease Continuity Through Failover

With `backupCount=1` in the Hazelcast map config, all leases are replicated to the backup node synchronously. When AC-1 fails, AC-2 has a full copy of all leases. No lease loss occurs for a clean node failure.

During a split-brain (both nodes writing independently), the `LatestUpdateMergePolicy` will use the most recently updated entry post-merge. For DHCP, this means: the last RENEW/REBIND wins. Conflicts (same IP, different MAC) are possible in theory but require very specific timing. The H2 local mirror provides an additional audit trail.

### 8.6 `nopreempt` Implications

With `nopreempt`, after AC-1 recovers from a crash, AC-2 keeps the VIP. AC-1 returns as STANDBY. This prevents unnecessary VIP churn but means:
- The "original" primary (priority=110) is not automatically restored
- Over time, after multiple failovers, you lose track of which node "should" be primary
- Manual VIP re-balancing is needed if desired: `systemctl restart keepalived` on AC-2 will trigger a new election

This is intentional and correct behavior for DHCP HA. VIP flapping is more harmful than leaving VIP on the backup.

---

## 9. Summary and Action Items

### Immediate (before production go-live)

| Priority | Action | File | Change |
|----------|--------|------|--------|
| P0 | Enable `require-majority` | `application.yml` | `require-majority: false` → `true` |
| P0 | Set real VIP and interface | `application.yml` | `vip: 127.0.0.1` → real VIP; set `interface-name` |
| P0 | Fill keepalived TODO placeholders | `keepalived-ac1.conf`, `keepalived-ac2.conf` | Replace all `TODO_*` values |
| P0 | Configure TCP-IP cluster members | `application.yml` | `members: []` → real AC IPs |
| P1 | Add `RoleRecoveryBean` | New file | §6.1 |
| P1 | Add SBP to `HazelcastConfiguration` | `HazelcastConfiguration.java` | §6.2 |
| P1 | Wrap SBP exceptions in lease store | `HazelcastDhcpLeaseStore.java` | §6.4 |
| P1 | Add `HazelcastPartitionListener` | New file | §6.3 |
| P1 | Tune Hazelcast heartbeat timeouts | `HazelcastConfiguration.java` | `max.no.heartbeat.seconds=10` |
| P2 | Deploy witness node | New deployment | §6.6 |
| P2 | Run HA-01 through HA-05 test scenarios | Test environment | §7 |

### Architecture Decision Record

**Chosen**: Option B (keepalived VRRP + requireMajority=true + lightweight witness)

**Rationale**: The witness node eliminates the availability tradeoff of Option A while maintaining strict split-brain safety. The witness is operationally simple (stateless, no application logic) and inexpensive to host. The combination of keepalived (VIP management) and Hazelcast SBP (write protection) provides defense-in-depth: even if keepalived incorrectly binds VIP to both nodes, the majority check prevents dual DHCP service.

**Fallback**: If a 3rd machine is unavailable, Option A (requireMajority=true, no witness) is safe. DHCP outage during network partition is acceptable given that existing clients retain leases.
