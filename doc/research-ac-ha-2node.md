# 双节点 AC 高可用 — 脑裂问题分析与推荐方案

**项目**：ha-dhcp  
**日期**：2026-05-21  
**范围**：仅 2 台 AC 节点（无 CMS），Spring Boot 3.3.6 + Hazelcast 5.5.0 AP + Keepalived  
**状态**：研究 / 设计阶段

---

## 目录

1. [当前架构快照](#1-当前架构快照)
2. [双节点脑裂问题](#2-双节点脑裂问题)
3. [方案选项](#3-方案选项)
4. [推荐方案](#4-推荐方案)
5. [故障转移设计](#5-故障转移设计)
6. [代码片段](#6-代码片段)
7. [验证测试计划](#7-验证测试计划)
8. [风险评估](#8-风险评估)

---

## 1. 当前架构快照

### 1.1 组件

| 组件 | 角色 | 关键配置 |
|------|------|----------|
| Spring Boot 3.3.6 | DHCP 服务器 + HA 状态机 | 端口 8080，DHCP 1067 |
| Hazelcast 5.5.0（AP） | 分布式租约存储 | 端口 5701，`dhcp:leases` backupCount=1 |
| Keepalived | 通过 VRRP 管理 VIP | AC-1 priority=110，AC-2 priority=100，nopreempt，unicast |
| H2 + Flyway + JPA | 本地租约镜像 | `./data/ha-dhcp` |

### 1.2 关键代码路径

**`HazelcastDhcpLeaseStore.isAvailable()`**（`HazelcastDhcpLeaseStore.java:52-58`）：
```java
public boolean isAvailable() {
    LifecycleService lifecycleService = hazelcastInstance.getLifecycleService();
    if (!lifecycleService.isRunning()) return false;
    return !haProperties.isRequireMajority()
        || memberCount() >= haProperties.getMinimumClusterSize();
}
```

**`HaService.canServeDhcp()`**（`HaService.java:55-61`）：
```java
public boolean canServeDhcp() {
    return dhcpProperties.isEnabled()
            && role.get() == HaRole.MASTER
            && isLeaseMirrorLoaded()
            && leaseAuthorityHealth.isAvailable()
            && hasVip();
}
```

**`HaService.hasVip()`** 检查操作系统网卡上是否绑定了 VIP 地址 —— 这是一个真实的 OS 级别检查，不是缓存标志。

### 1.3 当前 `application.yml`（相关摘录）

```yaml
ha:
  vip: 127.0.0.1          # 生产环境需改为真实 VIP
  require-majority: false  # ← 当前已禁用
  minimum-cluster-size: 2

cluster:
  name: ha-dhcp
  port: 5701
  members: []              # ← 空数组 = 组播发现
```

**关键缺陷**：`require-majority: false` 意味着只要 Hazelcast 在运行，`isAvailable()` 就始终返回 `true` —— 无论集群中有多少个成员。这在生产环境的双节点部署中是不安全的。

---

## 2. 双节点脑裂问题

### 2.1 定额理论

对于 N 个节点，安全的定额需要 ⌊N/2⌋+1 票。当 N=2 时：
- 定额 = 2 个节点
- 任何分区都会产生两边各 1 个节点
- 任何一边单独都不满足定额

双节点问题在没有仲裁者的情况下是结构上无法解决的：**每次网络分区要么导致完全不可用（安全），要么导致双主（不安全）** —— 你必须选择哪种故障模式更可接受。

### 2.2 VRRP 脑裂场景（这里的具体风险）

使用当前 keepalived 配置逐步分析：

```
初始状态：
  AC-1: VRRP state=MASTER, priority=110, VIP 绑定到 eth0
  AC-2: VRRP state=BACKUP, priority=100, 无 VIP

网络分区开始（AC-1 ↔ AC-2 链路断开）：

  AC-1:
    → 继续向单播对等节点 AC-2 发送 VRRP 广告（包被丢弃）
    → AC-1 没有理由放弃 VIP
    → VIP 保留在 AC-1 的 eth0 上
    → keepalived 状态：仍然是 MASTER

  AC-2:
    → 停止收到来自 AC-1 的 VRRP 广告
    → 失效间隔 = advert_int * ((256 - priority_of_master) / 256) ≈ 1s
    → 自我提升为 MASTER
    → 将 VIP 绑定到自己的 eth0 上
    → 调用 notify.sh MASTER → Spring Boot role=MASTER

分区后的结果：
  AC-1: VIP=yes, role=MASTER, Hazelcast members=1
  AC-2: VIP=yes, role=MASTER, Hazelcast members=1

  使用当前配置（require-majority=false）的 canServeDhcp()：
    AC-1: enabled=T, role=MASTER, mirror=T, authority=T(requireMajority=F), hasVip=T → true
    AC-2: enabled=T, role=MASTER, mirror=T, authority=T(requireMajority=F), hasVip=T → true

  ⟹ 双 DHCP MASTER — 两者都响应 DISCOVER/REQUEST
  ⟹ 如果它们从重叠的地址池中分配，存在 IP 冲突风险
```

**`nopreempt` 在这里没有帮助。** `nopreempt` 只能防止高优先级节点在恢复后重新抢占 VIP —— 它不能阻止 BACKUP 节点在听不到 MASTER 时自我提升。

### 2.3 分区期间 Hazelcast 会发生什么

| 场景 | AC-1 成员数 | AC-2 成员数 | `isAvailable()`（requireMajority=false） | `isAvailable()`（requireMajority=true） |
|------|-------------|-------------|----------------------------------------|----------------------------------------|
| 正常 | 2 | 2 | true | true |
| AC-1 进程崩溃 | — | 1（超时后） | true | **false** |
| 网络分区 | 1 | 1 | true | **false** |

使用 `require-majority: true` 和 `minimum-cluster-size: 2`，`isAvailable()` 在分区两边都返回 `false` → `canServeDhcp()` 返回 `false` → 无双主。这是核心修复。

**Hazelcast 成员移除时机**（对时间线规划至关重要）：

- **进程崩溃**：TCP RST 或连接被拒绝被立即检测到；Hazelcast 在 ~5–10 秒内移除该成员。
- **网络分区**（防火墙丢弃，无 RST）：Hazelcast 必须等待心跳超时。默认 `hazelcast.max.no.heartbeat.seconds` = 60 秒。如果未设置 requireMajority，这是 60 秒的潜在双主窗口。

---

## 3. 方案选项

### 3.1 选项 A — 启用 `requireMajority`（双节点，安全但分区时不可用）

**机制**：设置 `require-majority: true`。分区期间每个节点只有 1 个成员 < `minimum-cluster-size: 2`，所以 `isAvailable()` 返回 `false`，`canServeDhcp()` 在两边都返回 `false`。

**行为矩阵**：

| 事件 | 结果 |
|------|------|
| AC-1 崩溃（进程） | AC-2：Hazelcast ~10s 内移除 AC-1，VIP 通过 keepalived 漂移 → AC-2 提供服务（正常故障转移） |
| AC-2 崩溃 | 对称 —— AC-1 继续 |
| 网络分区 | 两个节点都停止提供 DHCP。现有租约保持到分区恢复。 |
| 分区恢复 | Hazelcast 重新组成集群，成员数变为 2，`isAvailable()=true`。需要角色重新评估（见 §6.3）。 |

**对现有客户端的影响**：DHCP 租约通常是 T=3600s；客户端在 T/2=1800s 时续租。在短暂分区（秒到分钟）期间，持有有效租约的客户端继续运行。只有新加入请求失败。

**恢复**：Hazelcast 集群重新合并时自动恢复。需要生命周期监听器（见 §6.3）在合并后触发角色重新评估。

**结论**：对于纯双节点方案来说是正确且简单的。接受分区期间的不可用以换取安全性。推荐基线方案。

---

### 3.2 选项 B — 轻量见证节点（无需完整业务逻辑的定额）

**机制**：第 3 台机器运行一个最小的 Hazelcast 成员（无 DHCP、无 HTTP、无 H2）来提供决胜投票。3 个成员且 `minimum-cluster-size: 2`：

```
分区场景 → 定额结果：
  AC-1 隔离（1 个成员）：          失去定额 → 停止服务
  AC-2 + 见证节点（2 个成员）：     有定额 → AC-2 可以服务
  AC-1 + 见证节点（2 个成员）：     有定额 → AC-1 可以服务
  AC-1 + AC-2，见证节点故障（2）： 有定额 → 正常运作继续
  三个都隔离：                     无服务
```

没有任何场景会产生双主。这是最强大的双节点 AC 解决方案。

**见证节点要求**：Java 21，~256 MB RAM，~50 MB 磁盘。可以是树莓派 4、小型 VM 或 Docker 容器，放在与 AC-1/AC-2 不同的主机上。

**见证节点 Hazelcast 配置**（独立运行，无需 Spring Boot）：

```java
Config config = new Config();
config.setClusterName("ha-dhcp");
config.getNetworkConfig().setPort(5701);
JoinConfig join = config.getNetworkConfig().getJoin();
join.getMulticastConfig().setEnabled(false);
join.getTcpIpConfig().setEnabled(true)
    .addMember("AC1_IP:5701")
    .addMember("AC2_IP:5701");
// 不需要 MapConfig —— 见证节点不存储数据
Hazelcast.newHazelcastInstance(config);
```

**结论**：最优架构。如果有第 3 台机器，强烈推荐。现有 AC 代码改动最小（只需启用 `require-majority: true` 并在 `cluster.yml` 中设置 `members`）。

---

### 3.3 选项 C — STONITH / 硬件隔离

**机制**：当节点 A 检测到节点 B 故障时，A 调用隔离代理（IPMI、PDU API、管理型交换机端口禁用）来重启或网络隔离 B，然后 A 接管。

**对于本项目**：
- 需要两台机器的 IPMI/BMC 或 PDU API 访问权限
- keepalived 中的 `notify_master` 在绑定 VIP 之前调用隔离脚本
- 确保失败者无法在获胜者声明 MASTER 后保持存活进程
- 防止“僵尸主节点”场景

**keepalived 钩子示例**（`keepalived-ac1.conf`）：
```
notify_master "/etc/keepalived/fence_peer.sh MASTER AC2_IPMI_IP"
```

**`fence_peer.sh`**（伪代码）：
```bash
#!/usr/bin/env bash
ROLE="$1"; PEER_IPMI="$2"
if [ "$ROLE" = "MASTER" ]; then
    ipmitool -H "$PEER_IPMI" -U admin -P secret chassis power off
    sleep 3
    ipmitool -H "$PEER_IPMI" -U admin -P secret chassis power on
fi
```

**结论**：非常健壮，但需要专用 IPMI/BMC 硬件。设置和测试复杂。对于大多数 DHCP 部署来说过度设计。仅在硬件基础设施已有带外管理时才考虑。

---

### 3.4 选项 D — Keepalived 本身能否解决脑裂？

**简短回答**：不能。

如 §2.2 所示，使用 `nopreempt` 的 VRRP 单播无法阻止网络分区期间两个节点同时拥有 VIP。VRRP 设计用于处理节点故障，而非网络分区。

**Keepalived 擅长什么**：
- 当一个节点的应用死亡时，基于健康检查的快速故障转移（check_app.sh）
- 故障转移后的 VIP 管理和 GARP
- `nopreempt` 防止恢复时不必要的 VIP 抖动

**Keepalived 无法做到什么**：
- 在网络分区期间保证至多一个 VIP 持有者
- 协调节点间的分布式状态

**结论**：Keepalived 是必要的，但不足够。必须与选项 A 或 B 结合使用。

---

### 3.5 选项 E — 双节点 Hazelcast CP Subsystem

CP Subsystem（Raft）需要最少 3 个成员的 CP 组（容忍 1 个故障）。使用 2 个 AC 节点：

- 2 节点 CP 组：1 个故障时无法达到定额 → 不可行
- 2 AC + 1 见证节点作为 CP 成员：可行，但见证节点必须是 Raft 参与者（不是轻量成员）

使用 CP Subsystem 可以通过 `FencedLock` 确保恰好一个节点持有 MASTER 锁：
```java
FencedLock masterLock = hazelcastInstance.getCPSubsystem().getLock("dhcp:master-lock");
long fence = masterLock.tryLockAndGetFence();
if (fence != FencedLock.INVALID_FENCE) {
    // 该节点是独占主节点
}
```

**结论**：架构上正确，但需要 3 成员 CP 组（即第 3 台机器必须完全参与 Raft），并且增加了显著复杂度。除非你需要 SBP + requireMajority 之外的可线性化分布式操作，否则不推荐。对于单主 DHCP，选项 A 或 B 已足够。

---

### 3.6 选项 F — 共享存储仲裁

**机制**：两个节点竞争共享存储上的锁（PostgreSQL advisory lock、NFS 文件锁、etcd 租约）。锁持有者是 MASTER。

**示例**：
- 共享 PostgreSQL 上的 `pg_try_advisory_lock(1)`
- etcd 租约 + TTL
- Zookeeper 临时 znode

**结论**：引入共享存储组件作为新的单点故障。不推荐，除非基础设施已有高可用共享数据库。见证节点选项（3.2）更简单，且避免了新的外部依赖。

---

## 4. 推荐方案

### 4.1 决策矩阵

| 评判标准 | 选项 A（requireMajority，双节点） | 选项 B（+ 见证节点） |
|----------|----------------------------------|----------------------|
| 脑裂安全 | ✅ | ✅ |
| 网络分区期间可用 | ❌ 双方都停止 | ✅ 多数分区继续 |
| 需要第 3 台机器 | ❌ | ✅ 小型 VM/树莓派 |
| 需要代码改动 | 最小 | 最小 |
| 运维复杂度 | 低 | 低 + 见证节点维护 |
| 推荐给 | 预算受限 / 正确性优先 | 生产部署 |

### 4.2 推荐：选项 B（Keepalived + requireMajority + 见证节点）

对于生产环境，部署轻量见证节点并启用 `require-majority: true`。

**架构**：
```
┌─────────────────────────────────────────────────────────────────┐
│  二层网络                                                       │
│                                                                 │
│  ┌───────────────┐     Hazelcast     ┌───────────────┐         │
│  │     AC-1      │◄─────集群──────────►│     AC-2      │         │
│  │ Spring Boot   │     (TCP-IP)       │ Spring Boot   │         │
│  │ Hazelcast     │                    │ Hazelcast     │         │
│  │ keepalived    │                    │ keepalived    │         │
│  │ priority=110  │                    │ priority=100  │         │
│  └───────┬───────┘                    └───────┬───────┘         │
│          │                                    │                  │
│          └──────────────┬─────────────────────┘                  │
│                         │ Hazelcast 集群                       │
│                    ┌────▼────┐                                  │
│                    │ 见证节点 │  （仅 Hazelcast，无 DHCP）       │
│                    │         │  小型 VM / 树莓派                 │
│                    └─────────┘                                  │
│                                                                 │
│  VIP：通过 keepalived VRRP 在 AC-1 和 AC-2 之间漂移            │
└─────────────────────────────────────────────────────────────────┘
```

**有见证节点时的脑裂行为**：

| 场景 | Hazelcast 定额（≥2） | DHCP 服务 |
|------|----------------------|----------|
| 全部健康 | AC-1: 3，AC-2: 3 | MASTER 提供服务 |
| AC-1 崩溃 | AC-2: 2（AC-2+见证） | AC-2 通过 keepalived 接管 VIP → 提供服务 |
| AC-2 崩溃 | AC-1: 2（AC-1+见证） | AC-1 保持 VIP → 继续提供服务 |
| 网络分区 AC-1 \| AC-2+见证 | AC-1: 1 < 2 → 停止 | AC-2+见证: 2 → AC-2 提供服务 |
| 网络分区 AC-2 \| AC-1+见证 | AC-2: 1 < 2 → 停止 | AC-1+见证: 2 → AC-1 提供服务 |
| 见证节点故障 | AC-1: 2，AC-2: 2 | 正常运作 |
| 三个都隔离 | 1, 1, 1 | 无服务（安全） |

### 4.3 备选：选项 A（纯双节点，无见证节点）

如果没有第 3 台机器，仅启用 `require-majority: true`。网络分区期间两个节点都停止服务。这个权衡已被记录和接受。

**关键配置变更**：
```yaml
ha:
  require-majority: true   # 原来是 false
  minimum-cluster-size: 2  # 不变
```

选项 A 不需要代码改动。`HazelcastDhcpLeaseStore.isAvailable()` 中的逻辑已经正确处理了这种情况。

---

## 5. 故障转移设计

### 5.1 notify.sh 与“Spring Boot 宕机”竞态条件

Spring Boot 不可用时当前 `notify.sh` 的行为：
```bash
curl -fsS --max-time 2 -X POST "${HA_NOTIFY_URL}?role=${ROLE}" \
  || logger -t ac-ha-notify "failed to notify Spring Boot: role=$ROLE"
```

- curl 静默失败（已记录，非致命）✅
- 角色文件 `/run/ac-ha-role` 仍然写入 ✅
- **问题**：Spring Boot 重启时以 `role=UNKNOWN` 初始化，永远不会重新读取角色文件

**缺口**：keepalived 只在状态转换时调用 notify.sh。如果 Spring Boot 在上一次 keepalived 状态变更时宕机，它会永久丢失角色直到下一次转换。

**修复**：启动时读取 `/run/ac-ha-role` 并应用角色（见 §6.1 — `RoleRecoveryBean`）。

**额外关注**：存在一个排序窗口，keepalived 在 Spring Boot 完成启动（leaseMirrorLoaded=false）之前就将节点提升为 MASTER。在这个窗口中，即使 VIP 已绑定且角色是 MASTER，`canServeDhcp()` 也返回 `false`。这是正确且安全的行为 —— 节点在镜像加载完成之前不会提供 DHCP。

### 5.2 应用内自动故障转移（不使用外部 Keepalived）

完全应用内的高可用设计是可行的，但有一个重大限制：**VIP 管理需要 OS 级别的 `ip addr add/del` 命令，需要 `CAP_NET_ADMIN` 权限或 root**。

可行的混合方案：
- **Keepalived** 保留 VIP 责任（GARP、ARP 表更新）
- **Spring Boot** 在后台线程中监控自己的 VIP 状态和 Hazelcast 成员关系

如果 keepalived 故障（进程崩溃），Spring Boot 可以检测到 `hasVip()` 变化并自我降级到安全状态（但没有 OS 权限无法重新获取 VIP）。这是纵深防御，不是 keepalived 的替代品。

纯应用内设计需要：
1. 服务单元中的 `CAP_NET_ADMIN` 或 root
2. 在 VIP 接管时实现 GARP（或调用 `arping`）
3. 实现 VIP 失效间隔定时器（相当于 VRRP）

不推荐，因为 keepalived 已经健壮地处理了这些。

### 5.3 完整故障转移时间线：AC-1 崩溃 → 客户端恢复

```
T=0s      AC-1 JVM 崩溃（或 systemctl kill，或断电）
          - 到 AC-1:5701 的 TCP 连接收到 RST 或超时
          - 到 AC-1:8080 的 TCP 连接收到 RST

T=~2s     AC-2 上的 Hazelcast：检测到到 AC-1 的连接故障
          - 开始怀疑/心跳评估
          （进程崩溃：快；网络分区：最多到 max.no.heartbeat.seconds=60s）

T=2s      AC-2 上的 keepalived：VRRP advert_int=1s，fall=2
          - 需要两次连续健康检查失败
          - check_app.sh curl AC-1 的健康端点 → 连接被拒绝（max-time=1s）
          - 在 ~T=4s 达到 fall 阈值（2 个间隔 × 2s）

T=4s      keepalived AC-2：BACKUP → MASTER 转换
          - 将 VIP 绑定到 AC-2 的 eth0
          - 发送 GARP（garp_master_delay=1s，repeat=3，在 keepalived-ac2.conf 中配置）
          - 调用：notify.sh MASTER

T=4s      AC-2 上的 notify.sh：
          - 将 "MASTER" 写入 /run/ac-ha-role
          - POST 到 http://127.0.0.1:8080/internal/ha/role?role=MASTER
          - Spring Boot：HaService.updateRole(MASTER)

T=5-7s    网络交换机更新 VIP 的 ARP 缓存 → 现在指向 AC-2 的 MAC
          （GARP 强制所有连接设备立即更新 ARP 表）

T=5-10s   AC-2 上的 Hazelcast：移除 AC-1 成员（TCP RST 被快速检测到）
          - 如果没有见证节点，memberCount() 暂时降到 1
            → 有见证节点：memberCount=2，isAvailable()=true ✓
            → 无见证节点 + requireMajority=true：isAvailable()=false（短暂中断）

T=~10s    （无见证节点场景）AC-2 有 role=MASTER，VIP=yes，但 authority 不可用
          → canServeDhcp()=false
          → AC-2 在 Hazelcast 完成成员移除之前无法服务

T=~15s    （无见证节点场景）Hazelcast 将 AC-1 从集群成员中完全移除
          → memberCount 保持在 1（无见证节点）
          → 使用 requireMajority=true：isAvailable()=false，直到 AC-1 重新加入
          *** 这是纯双节点选项 A 的权衡 ***

T=~10s    （有见证节点）Hazelcast 集群：AC-2 + 见证节点 = 2 个成员
          → isAvailable()=true
          → canServeDhcp()=true
          → AC-2 响应 DHCP 请求

T=5-15s   DHCP 客户端：ARP 解析 VIP 到 AC-2，请求到达 AC-2
T=10-20s  DHCP 客户端完全恢复
```

**总结**：
- 有见证节点：~10–15 秒完全恢复
- 无见证节点 + requireMajority=true：AC-2 在 AC-1 重新加入之前无法服务（无限期）
- 无见证节点 + requireMajority=false：~10s 恢复但脑裂风险仍然存在

### 5.4 分区愈合后的 Hazelcast 合并策略

当网络分区愈合时，Hazelcast 检测分裂的集群并将较小的合并到较大的中。合并过程：

1. "失败"（较小）的集群成员收到 `MERGING` 生命周期事件
2. 它断开连接，重新加入较大的集群
3. Map 条目使用配置的 `MergePolicyConfig` 进行调和
4. 在重新加入的成员上触发 `MERGED` 生命周期事件

对于 DHCP 租约，使用 `LatestUpdateMergePolicy` —— 最新更新时间戳的条目获胜：
```java
MapConfig leases = new MapConfig("dhcp:leases");
leases.getMergePolicyConfig()
    .setPolicy("com.hazelcast.spi.merge.LatestUpdateMergePolicy")
    .setBatchSize(100);
```

合并后，应用必须重新评估其角色（见 §6.3 — `HazelcastPartitionListener`）。

---

## 6. 代码片段

所有片段都可在现有 Spring Boot 项目中直接使用。

### 6.1 Spring Boot 启动角色恢复

当 keepalived 在先前状态转换期间无法到达 Spring Boot 时，从 `/run/ac-ha-role` 读取角色。

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
 * 读取 keepalived 角色文件以从 Spring Boot 在 keepalived 上次变更状态时宕机的情况中恢复。
 * 在 Hazelcast 和租约镜像初始化之后运行（Order=10 假设租约镜像加载发生在更小的 order number）。
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
            // 角色在 runner 执行之前已通过 API 设置 —— 跳过文件恢复
            return;
        }
        Path roleFile = Path.of(roleFilePath);
        if (!Files.exists(roleFile)) {
            log.info("角色文件 {} 不存在；保持 role=UNKNOWN 直到 keepalived 通知", roleFilePath);
            return;
        }
        try {
            String raw = Files.readString(roleFile).strip();
            HaRole recovered = mapKeepalivedRole(raw);
            haService.updateRole(recovered);
            log.info("从 {} 恢复 HA 角色：{} -> {}", roleFilePath, raw, recovered);
        } catch (IOException e) {
            log.warn("读取角色文件 {} 失败：{}", roleFilePath, e.getMessage());
        }
    }

    /** 将 keepalived VRRP 状态名映射为内部 HaRole。 */
    private HaRole mapKeepalivedRole(String keepalivedState) {
        return switch (keepalivedState.toUpperCase()) {
            case "MASTER"  -> HaRole.MASTER;
            case "BACKUP"  -> HaRole.STANDBY;
            case "FAULT"   -> HaRole.FAULT;
            case "STOP"    -> HaRole.STOP;
            default -> {
                log.warn("未知的 keepalived 状态 '{}'，默认设为 UNKNOWN", keepalivedState);
                yield HaRole.UNKNOWN;
            }
        };
    }
}
```

---

### 6.2 带有脑裂保护和合并策略的 Hazelcast 配置

扩展 `HazelcastConfiguration.java` 以添加 SBP 和合并策略：

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

        // 脑裂保护：当集群成员少于 minimumClusterSize 时，map 操作抛出 SplitBrainProtectionException
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
        // 脑裂合并时最新写入获胜
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

### 6.3 Hazelcast 分区愈合监听器（合并后角色重新评估）

Hazelcast 脑裂合并后，重新加入的节点会重启其 Hazelcast 实例。现有角色（由 keepalived 设置）可能与合并后的集群状态不一致。此监听器触发重新检查。

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
 * 监听 Hazelcast 生命周期事件，在脑裂合并后重新评估 HA 角色。
 * MERGED 之后，该节点的租约数据已调和；角色应与当前 VIP 状态对齐。
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
            log.info("Hazelcast 脑裂合并完成 —— 重新评估 HA 角色");
            reevaluateRole();
        } else if (event.getState() == LifecycleState.MERGE_FAILED) {
            log.error("Hazelcast 脑裂合并失败 —— 强制角色设为 FAULT");
            haService.updateRole(HaRole.FAULT);
        }
    }

    /**
     * 合并后：如果该节点持有 VIP，应变为 MASTER。
     * 如果不持有，应变为 STANDBY。
     * 已在 FAULT/MAINTENANCE/STOP 状态的节点保持当前角色。
     */
    private void reevaluateRole() {
        HaRole current = haService.role();
        if (current == HaRole.FAULT
                || current == HaRole.MAINTENANCE
                || current == HaRole.STOP) {
            log.info("合并后角色重新评估跳过：节点处于 {} 状态", current);
            return;
        }
        boolean hasVip = haService.hasVip();
        HaRole newRole = hasVip ? HaRole.MASTER : HaRole.STANDBY;
        if (newRole != current) {
            log.info("合并后角色转换：{} -> {}（hasVip={}）", current, newRole, hasVip);
            haService.updateRole(newRole);
        }
    }
}
```

---

### 6.4 更新后的 `HazelcastDhcpLeaseStore` —— 处理 `SplitBrainProtectionException`

启用 SBP 且集群失去定额时，map 操作会抛出 `SplitBrainProtectionException`。当前 `isAvailable()` 检查使用成员数检查 —— 但如果 SBP 激活，`put()` 和 `putIfAbsent()` 仍可能抛出。防御性地包装它们：

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
            log.warn("脑裂保护激活 —— 读取被拒绝 ip={}", ipAddress);
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
            log.warn("脑裂保护激活 —— 读取被拒绝 mac={}", macAddress);
            return Optional.empty();
        }
    }

    @Override
    public boolean putIfAbsent(DhcpLeaseRecord lease) {
        try {
            return leases.putIfAbsent(lease.ipAddress(), lease) == null;
        } catch (SplitBrainProtectionException e) {
            log.warn("脑裂保护激活 —— putIfAbsent 被拒绝 ip={}", lease.ipAddress());
            return false;
        }
    }

    @Override
    public void put(DhcpLeaseRecord lease) {
        try {
            leases.put(lease.ipAddress(), lease);
        } catch (SplitBrainProtectionException e) {
            log.warn("脑裂保护激活 —— put 被拒绝 ip={}", lease.ipAddress());
        }
    }

    @Override
    public Collection<DhcpLeaseRecord> values() {
        try {
            return leases.values();
        } catch (SplitBrainProtectionException e) {
            log.warn("脑裂保护激活 —— values() 被拒绝");
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

### 6.5 生产双节点部署的 `application.yml` 变更

```yaml
ha:
  vip: 192.168.56.200      # 生产环境真实 VIP CIDR（原 127.0.0.1）
  interface-name: eth0      # 承载 VIP 的网卡
  require-majority: true     # 关键变更：原 false
  minimum-cluster-size: 2    # 不变

cluster:
  name: ha-dhcp
  port: 5701
  members:
    - 192.168.56.101:5701   # AC-1 物理 IP
    - 192.168.56.102:5701   # AC-2 物理 IP
    - 192.168.56.103:5701   # 见证节点（如有）；纯双节点则省略
```

如有需要，添加角色文件路径覆盖：
```yaml
ha:
  role-file: /run/ac-ha-role   # 必须与 notify.sh 中的 ROLE_FILE 一致
```

---

### 6.6 见证节点启动脚本（`witness-start.sh`）

最小独立 Hazelcast 见证节点。将 `hazelcast-5.5.0-slim.jar` 复制到见证节点机器并运行：

```bash
#!/usr/bin/env bash
# witness-start.sh —— 在见证节点上运行
# 需要：Java 21，classpath 上有 hazelcast-5.5.0-slim.jar
set -euo pipefail

AC1_IP="${1:?需要 AC1_IP}"
AC2_IP="${2:?需要 AC2_IP}"
CLUSTER_NAME="${3:-ha-dhcp}"
PORT="${4:-5701}"

HAZELCAST_JAR="/opt/hazelcast/hazelcast-5.5.0-slim.jar"

java -Xms64m -Xmx128m \
  -cp "$HAZELCAST_JAR" \
  -Dhazelcast.config=/etc/hazelcast/witness.xml \
  com.hazelcast.core.server.HazelcastMemberStarter
```

`/etc/hazelcast/witness.xml`：
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

    <!-- 见证节点不存储数据 —— 所有 map 使用最小配置 -->
    <map name="default">
        <backup-count>0</backup-count>
        <async-backup-count>0</async-backup-count>
        <eviction eviction-policy="LRU" max-size-policy="PER_NODE" size="0"/>
    </map>

    <properties>
        <!-- 进程崩溃时更快的成员移除 -->
        <property name="hazelcast.max.no.heartbeat.seconds">10</property>
        <property name="hazelcast.heartbeat.interval.seconds">1</property>
    </properties>
</hazelcast>
```

相同的心跳调优也应应用于 AC-1 和 AC-2 的 Hazelcast 配置，以实现更快的成员故障检测：

```java
// 在 HazelcastConfiguration.java 中 —— 添加到 config
config.setProperty("hazelcast.max.no.heartbeat.seconds", "10");
config.setProperty("hazelcast.heartbeat.interval.seconds", "1");
```

---

## 7. 验证测试计划

### 7.1 测试环境搭建

```
物理 / VM 拓扑：
  AC-1 (192.168.56.101)：Spring Boot + Hazelcast + keepalived
  AC-2 (192.168.56.102)：Spring Boot + Hazelcast + keepalived
  见证节点 (192.168.56.103)：仅 Hazelcast（可选）
  VIP: 192.168.56.200
  测试客户端: 192.168.56.50（发送 DHCP 请求，监控响应）
  抓包主机: 同一 L2 段的混杂模式

辅助别名：
  alias kstatus='journalctl -u keepalived -n 20 --no-pager'
  alias hzstatus='curl -s http://VIP:8080/actuator/health | python3 -m json.tool'
```

---

### 7.2 HA-01 — 正常故障转移（AC-1 进程崩溃）

**目的**：验证 AC-1 崩溃时 VIP 漂移和 DHCP 连续性。

```bash
# 步骤 1：验证初始状态
curl http://192.168.56.200:8080/actuator/health  # 应返回 UP
ip addr show eth0 | grep 192.168.56.200          # 在 AC-1 上：应显示 VIP

# 步骤 2：崩溃 AC-1
ssh root@192.168.56.101 "systemctl stop ha-dhcp"
ssh root@192.168.56.101 "systemctl stop keepalived"

# 步骤 3：在 AC-2 上监控故障转移
watch -n1 'ip addr show eth0 | grep 56.200'    # VIP 应在 15s 内出现
journalctl -u keepalived -f                     # 观察 MASTER 转换
curl http://192.168.56.200:8080/actuator/health # ~15s 后应返回 UP

# 步骤 4：验证 DHCP 仍然工作
dhclient -v -r && dhclient -v eth0             # 在测试客户端上：续租

# 步骤 5：验证无重复响应
tcpdump -i eth0 -n port 67 or port 68 &       # 在抓包主机上
# 确认每个 DISCOVER 只有 1 个 OFFER

# 预期：VIP 在 10-15s 内出现在 AC-2 上，单个 DHCP 响应者
```

**通过标准**：
- VIP 在 15 秒内出现在 AC-2 的 `ip addr` 上
- `curl http://VIP:8080/actuator/health` 在 20 秒内返回 `{"status":"UP"}`
- DHCP 客户端每个 DISCOVER 恰好收到一个 OFFER
- DHCP 响应中选项 54（服务器标识符）= VIP (192.168.56.200)

---

### 7.3 HA-02 — 网络分区模拟

**目的**：验证 AC-1 和 AC-2 之间网络断开时的脑裂防护。

```bash
# 步骤 1：验证初始状态（两个节点健康，VIP 在 AC-1 上）
curl http://192.168.56.200:8080/actuator/health
# 在两个节点上：
curl http://192.168.56.101:8080/actuator/health
curl http://192.168.56.102:8080/actuator/health

# 步骤 2：使用 iptables 模拟分区（不杀进程）
# 在 AC-1 上：丢弃所有到/来自 AC-2 的包
ssh root@192.168.56.101 "iptables -A INPUT -s 192.168.56.102 -j DROP && iptables -A OUTPUT -d 192.168.56.102 -j DROP"
# 在 AC-2 上：丢弃所有到/来自 AC-1 的包
ssh root@192.168.56.102 "iptables -A INPUT -s 192.168.56.101 -j DROP && iptables -A OUTPUT -d 192.168.56.101 -j DROP"
# 如果有见证节点，也隔离一侧与见证节点以测试非对称分区

# 步骤 3：等待分区检测（进程崩溃路径 ~5-10s，网络最多 60s）
sleep 15

# 步骤 4：独立检查每个节点
ssh root@192.168.56.101 "curl -s http://127.0.0.1:8080/actuator/health"
ssh root@192.168.56.102 "curl -s http://127.0.0.1:8080/actuator/health"

# 无见证节点 + requireMajority=true：两边都应显示 canServeDhcp=false
# 有见证节点（AC-2+见证节点有定额）：AC-2 应显示 canServeDhcp=true，AC-1=false

# 步骤 5：验证无双 DHCP 响应（在测试客户端网卡上抓包）
tcpdump -i eth0 -n '(udp port 67 or udp port 68)'
# 从测试客户端发送 DHCP DISCOVER
dhclient -v -r && dhclient -v --no-pid eth0
# 计数 OFFER 包 —— 必须恰好为 0（无见证节点，双方都停止）或 1（有见证节点）

# 步骤 6：愈合分区
ssh root@192.168.56.101 "iptables -F"
ssh root@192.168.56.102 "iptables -F"

# 步骤 7：验证恢复
sleep 15
curl http://192.168.56.200:8080/actuator/health  # 应返回 UP
```

**通过标准（选项 A —— 无见证节点）**：
- 分区后：两个节点都通过健康端点报告 `canServeDhcp=false`
- 分区期间 DHCP 客户端收到 0 个 OFFER 包
- 分区愈合后：恰好一个节点提供 DHCP

**通过标准（选项 B —— 有见证节点）**：
- AC-1（隔离）报告 `canServeDhcp=false`
- AC-2（多数侧）报告 `canServeDhcp=true`
- DHCP 客户端恰好收到 1 个 OFFER

---

### 7.4 HA-03 — VIP 漂移检测

**目的**：验证 `hasVip()` 正确反映 OS 状态，而不是缓存值。

```bash
# 检查每个节点上的 VIP 检测
ssh root@192.168.56.101 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | grep -i vip'
ssh root@192.168.56.102 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | grep -i vip'

# 在 AC-1 持有 VIP 时，手动给 AC-2 添加 VIP（模拟配置错误）
ssh root@192.168.56.102 "ip addr add 192.168.56.200/24 dev eth0"
# 在健康轮询间隔内，AC-2 应检测到 hasVip=true
# 但 role != MASTER，所以 AC-2 上 canServeDhcp() 应保持 false
ssh root@192.168.56.102 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool'

# 清理
ssh root@192.168.56.102 "ip addr del 192.168.56.200/24 dev eth0"

# 通过：AC-2 即使持有 VIP，也因 role != MASTER 而 canServeDhcp=false
```

---

### 7.5 HA-04 — Spring Boot 重启角色恢复

**目的**：验证 `RoleRecoveryBean` 在 Spring Boot 崩溃后正确恢复角色。

```bash
# 步骤 1：确保 AC-1 是 MASTER 且角色文件存在
ssh root@192.168.56.101 "cat /run/ac-ha-role"  # 应打印 MASTER

# 步骤 2：杀掉 Spring Boot（保持 keepalived 运行）
ssh root@192.168.56.101 "systemctl stop ha-dhcp"

# 步骤 3：AC-2 上的 keepalived 检测到故障 → 提升为 MASTER
# 步骤 4：keepalived 调用 notify.sh MASTER → 在 AC-2 上将 MASTER 写入 /run/ac-ha-role
# 步骤 5：在 AC-1 上重启 Spring Boot
ssh root@192.168.56.101 "systemctl start ha-dhcp"

# 步骤 6：在 AC-1 上，检查角色是否从文件恢复（应为 BACKUP/STANDBY）
# keepalived 状态 = AC-2 接管后的 BACKUP → 角色文件应说 BACKUP
ssh root@192.168.56.101 "cat /run/ac-ha-role"  # 应打印 BACKUP
ssh root@192.168.56.101 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | grep -i role'

# 通过：AC-1 重启后报告 role=STANDBY，不是 MASTER
```

---

### 7.6 HA-05 — 故障转移时间测量

**目的**：测量实际端到端故障转移时间以建立 SLA 基线。

```bash
# 在抓包主机上：监控 DHCP 响应可用性
while true; do
    result=$(echo "" | timeout 3 dhcping -s 192.168.56.200 2>&1 || true)
    echo "$(date '+%H:%M:%S.%3N') $result"
    sleep 0.5
done > /tmp/dhcp-availability.log &

# 在 AC-1 上：崩溃服务
systemctl stop ha-dhcp && systemctl stop keepalived

# 等待恢复
sleep 30

# 分析 dhcp-availability.log 中的间隙
grep -c "Got answer" /tmp/dhcp-availability.log
# 找到间隙后第一个 "Got answer" 以计算恢复时间
```

**目标**：30 秒内恢复。（Keepalived 故障转移 ~10s + Hazelcast 检测 ~10s + GARP 传播 ~2s + DHCP 客户端重试 ~5s）

---

### 7.7 HA-06 — 并发负载下重复 IP 分配

**目的**：验证两个节点短暂都认为自己是 MASTER 时不会发生 IP 冲突。

```bash
# 这是最关键的正确性测试
# 需要：能够发送突发请求的自定义 DHCP 客户端

# 设置：两个客户端，在故障转移事件期间同时发送 DISCOVER
# 客户端 A 在网段 1，客户端 B 在网段 2（同一 L2）

# 步骤 1：两个客户端同时请求新租约
# 步骤 2：在请求期间，在 AC-1 上触发 keepalived MASTER 翻转
# 步骤 3：验证没有两个客户端收到相同 IP

# 用 dhclient 简单检查（顺序执行，不理想）：
for i in $(seq 1 20); do
    ip=$(dhclient -v eth0 2>&1 | grep 'bound to' | awk '{print $3}')
    echo "客户端分配: $ip"
done

# 交叉检查：所有分配的 IP 应唯一
# 也可通过 Hazelcast 验证：curl http://VIP:8080/internal/leases（如果端点存在）
```

---

## 8. 风险评估

### 8.1 风险矩阵

| # | 风险 | 可能性 | 影响 | 缓解措施 |
|---|------|--------|------|----------|
| R01 | 网络分区导致双主（requireMajority=false） | 未修复时 **高** | **严重** —— IP 冲突 | 启用 `require-majority: true`（§4.3） |
| R02 | Hazelcast 成员检测延迟（默认最长 60s） | 中 | 高 —— 延长双主窗口 | 调优心跳：`max.no.heartbeat.seconds=10`（§6.6） |
| R03 | keepalived 状态变更时 Spring Boot 宕机 —— 角色未更新 | 中 | 中 —— 重启后角色错误 | `RoleRecoveryBean` 启动时读取角色文件（§6.1） |
| R04 | 分区期间 VIP 同时绑定到两个节点 | 中 | 严重 —— GARP 混乱，ARP 投毒 | requireMajority 检查在 VIP 操作之前限制 DHCP 响应 |
| R05 | Hazelcast 脑裂合并丢失租约记录 | 低 | 高 —— 合并后 IP 重用 | `LatestUpdateMergePolicy` + H2 本地镜像作为备份（§5.4） |
| R06 | 见证节点故障减少定额余量 | 低 | 低 —— 回退到选项 A 行为 | 监控见证节点健康；保持简单（无会失败的业务逻辑） |
| R07 | `check_app.sh` 返回假阴性（应用健康但检查失败） | 低 | 中 —— 误报 VIP 故障转移 | keepalived 中调优 `fall=3`；确保健康端点是轻量的 |
| R08 | `nopreempt` 导致 VIP 留在崩溃后恢复的 AC-2 上 | 低 | 低 —— 非最优但可工作 | 如需手动将 VIP 强制移回：在 AC-1 上 `systemctl restart keepalived` |
| R09 | 崩溃时 H2 文件损坏 | 极低 | 中 —— 租约镜像丢失；客户端获得新 IP | H2 JDBC URL 中 `WRITE_DELAY=0`；H2 有 WAL，损坏很少见 |
| R10 | GARP 未传播（交换机丢弃 gratuitous ARP） | 极低 | 高 —— 故障转移后 VIP 不可达 | 增加 `garp_master_repeat=5`；在实验室测试；在 notify.sh 中用 `arping` 作为备份 |

### 8.2 最关键：R01 —— 当前 `requireMajority=false`

当前生产配置（`require-majority: false`）意味着 AC-1 和 AC-2 之间的网络分区将导致两个节点同时提供 DHCP。这是最高优先级的修复。

**立即行动**：在生产部署之前将 `require-majority: true` 变更到 `application.yml`。这个权衡（分区期间 DHCP 不可用）是可接受的 —— 现有 DHCP 客户端保持租约，只有新加入请求在停机窗口期间失败。

### 8.3 Keepalived 单播 vs 组播

当前配置使用单播（`unicast_src_ip`、`unicast_peer`）。在组播不可靠的环境中这是正确的。然而，单播 VRRP 要求两个节点在配置时就知道对方的 IP。如果物理 IP 变化，keepalived 配置必须更新。

确保生产环境中的 `TODO_AC1_IP`、`TODO_AC2_IP`、`TODO_VIP_CIDR` 和 `TODO_INTERFACE` 占位符被替换为真实值。

### 8.4 健康检查精度

`check_app.sh` 在 `/actuator/health/ha` 不可用时回退到 `/actuator/health`。HA 专用健康检查更精确，因为它包含 `canServeDhcp` 状态。当 HA 端点实现后，设置 `ALLOW_BASIC_HEALTH_FALLBACK=false` 以避免将 JVM 健康但尚未真正准备好提供 DHCP 的节点提升为 MASTER。

### 8.5 故障转移中的租约连续性

Hazelcast map 配置中的 `backupCount=1` 意味着所有租约同步复制到备份节点。当 AC-1 故障时，AC-2 拥有所有租约的完整副本。对于干净的节点故障，不会发生租约丢失。

在脑裂期间（两个节点独立写入），`LatestUpdateMergePolicy` 将使用合并后最新更新的条目。对于 DHCP，这意味着：最后一次 RENEW/REBIND 获胜。理论上可能发生冲突（相同 IP，不同 MAC），但需要非常特定的时机。H2 本地镜像提供了额外的审计跟踪。

### 8.6 `nopreempt` 的含义

使用 `nopreempt`，AC-1 从崩溃中恢复后，AC-2 保持 VIP。AC-1 作为 STANDBY 返回。这防止了不必要的 VIP 抖动，但意味着：
- "原始"主节点（priority=110）不会自动恢复
- 多次故障转移后，你会丢失哪个节点"应该"是主节点的记录
- 如需手动重新平衡 VIP：`systemctl restart keepalived` on AC-2 会触发新的选举

这是 DHCP HA 的预期且正确行为。VIP 抖动比让 VIP 留在备份节点上更有害。

---

## 9. 总结与行动项

### 立即执行（生产上线前）

| 优先级 | 行动 | 文件 | 变更 |
|--------|------|------|------|
| P0 | 启用 `require-majority` | `application.yml` | `require-majority: false` → `true` |
| P0 | 设置真实 VIP 和网卡 | `application.yml` | `vip: 127.0.0.1` → 真实 VIP；设置 `interface-name` |
| P0 | 填写 keepalived TODO 占位符 | `keepalived-ac1.conf`、`keepalived-ac2.conf` | 替换所有 `TODO_*` 值 |
| P0 | 配置 TCP-IP 集群成员 | `application.yml` | `members: []` → 真实 AC IP |
| P1 | 添加 `RoleRecoveryBean` | 新文件 | §6.1 |
| P1 | 在 `HazelcastConfiguration` 中添加 SBP | `HazelcastConfiguration.java` | §6.2 |
| P1 | 在租约存储中包装 SBP 异常 | `HazelcastDhcpLeaseStore.java` | §6.4 |
| P1 | 添加 `HazelcastPartitionListener` | 新文件 | §6.3 |
| P1 | 调优 Hazelcast 心跳超时 | `HazelcastConfiguration.java` | `max.no.heartbeat.seconds=10` |
| P2 | 部署见证节点 | 新部署 | §6.6 |
| P2 | 运行 HA-01 到 HA-05 测试场景 | 测试环境 | §7 |

### 架构决策记录

**选定**：选项 B（keepalived VRRP + requireMajority=true + 轻量见证节点）

**理由**：见证节点消除了选项 A 的可用性权衡，同时保持严格的脑裂安全。见证节点运维简单（无状态，无应用逻辑）且托管成本低。keepalived（VIP 管理）和 Hazelcast SBP（写入保护）的组合提供了纵深防御：即使 keepalived 错误地将 VIP 绑定到两个节点，定额检查也能阻止双 DHCP 服务。

**备选**：如果没有第 3 台机器，选项 A（requireMajority=true，无见证节点）是安全的。网络分区期间的 DHCP 停机是可接受的，因为现有客户端保留租约。
