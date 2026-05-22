# 双节点 AC 高可用 — 脑裂问题分析与推荐方案

**项目**：ha-dhcp  
**日期**：2026-05-21  
**范围**：2 台 AC + n 台 CMS，共 n+2 台设备。Spring Boot 3.3.6 + Hazelcast 5.5.0 AP + Keepalived。
**拓扑说明**：
- 首选纯分离拓扑：AC-1、AC-2 与每个 CMS 均运行在独立服务器上。
- 兼容过渡拓扑：AC 与 CMS 部署在同一台服务器的情况最多只能出现一次，不能让两台 AC 都各自绑定一个本地 CMS。
- CMS 进程自动加入 Hazelcast 集群，并作为普通数据成员参与 `dhcp:leases` 数据分区和备份存储。
- AC 的主备切换始终只涉及 2 台 AC 设备；CMS 只提供集群成员数和数据副本能力，不承载 VIP。  **状态**：研究 / 设计阶段

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

## 2.4 部署拓扑与脑裂风险分析

### 2.4.1 分离模式（n≥1）：CMS 独立部署

```
设备-1: AC进程          → Hazelcast 数据成员（AC-1）
设备-2: AC进程          → Hazelcast 数据成员（AC-2）
设备-3..n+2: CMS进程    → Hazelcast 数据成员（CMS-1..CMS-n）
总计: n+2 个 Hazelcast 数据成员
```

推荐拓扑是 AC 与 CMS 全部分离部署。若迁移阶段确实需要 AC 与 CMS 同机部署，只允许出现一次；不能让 AC-1 与 AC-2 两侧都各自拥有本地 CMS，否则网络分区时两侧都可能看到足够成员数并继续服务。

当 n≥1 时，总成员数 ≥3。启用 `require-majority: true` 和 `minimum-cluster-size: 2` 后，网络分区期间：
- 包含 AC 与至少 1 台 CMS 的一侧满足服务条件，可以继续提供 DHCP。
- 只有单个 AC 的一侧不满足 `minimum-cluster-size=2`，停止提供 DHCP。
- CMS 作为普通数据成员保存 Hazelcast 数据分区和备份，因此故障转移后仍能提供租约数据副本。

**结论**：分离拓扑下，CMS 作为普通 Hazelcast 数据成员即可提供定额和数据副本能力；不要把 CMS 当作只投票不存储数据的成员。

---
## 3. 方案选项

### 3.2 选项 B — 分离部署 + CMS 普通数据成员（推荐）

**机制**：AC 与 CMS 分离部署，所有 AC 和 CMS 都以 Hazelcast 普通数据成员加入同一个集群。AC 继续由 keepalived 管理 VIP，Hazelcast 通过 `require-majority: true` 和 `minimum-cluster-size: 2` 控制租约读写权限。

```
分离部署：
  AC-1               → Spring Boot + Hazelcast 数据成员 + keepalived
  AC-2               → Spring Boot + Hazelcast 数据成员 + keepalived
  CMS-1..CMS-n       → CMS 进程 + Hazelcast 数据成员

关键配置：
  ha.require-majority=true
  ha.minimum-cluster-size=2
  cluster.members = AC-1, AC-2, CMS-1..CMS-n 的固定地址列表
```

网络分区期间的行为：
- AC-1 单独隔离时，`memberCount()=1`，`isAvailable()` 返回 `false`，AC-1 停止 DHCP 服务。
- AC-2 与至少 1 台 CMS 保持连通时，`memberCount()>=2`，且 AC-2 拿到 VIP 后可以继续服务。
- 对称场景下，AC-1 与 CMS 保持连通时同样可以继续服务。
- 如果所有成员都互相隔离，所有 AC 都停止 DHCP 服务，优先保证不会双主写入。

**CMS 配置要求**：
1. CMS 必须作为 Hazelcast 普通数据成员运行，参与 `dhcp:leases` 数据分区和备份存储。
2. AC 保持 `require-majority: true`，`minimum-cluster-size: 2`。
3. 所有节点（AC+CMS）的 `cluster.members` 使用同一份固定成员列表。
4. 生产拓扑首选所有 AC 与 CMS 分离部署；迁移期 AC 与 CMS 同机最多只能出现一次。

**结论**：这是推荐方案。它保留 keepalived 的快速 VIP 漂移能力，同时由 Hazelcast 成员数保护租约读写，CMS 还承担数据副本职责。

---

### 3.5 选项 E — 双节点 Hazelcast CP Subsystem

CP Subsystem（Raft）需要最少 3 个成员的 CP 组（容忍 1 个故障）。仅使用 2 个 AC 节点时，任何一个节点故障都会导致 CP 组无法达到定额，因此不可行。

若采用 CP Subsystem，第三个 CP 成员应由独立 CMS 等普通 Hazelcast 成员承担，并完整参与 Raft。这样可以通过 `FencedLock` 确保恰好一个节点持有 MASTER 锁：

```java
FencedLock masterLock = hazelcastInstance.getCPSubsystem().getLock("dhcp:master-lock");
long fence = masterLock.tryLockAndGetFence();
if (fence != FencedLock.INVALID_FENCE) {
    // 该节点是独占主节点
}
```

**结论**：架构上正确，但复杂度高于方案 B。除非后续需要线性化锁、信号量或其他 CP 语义，否则不建议为 DHCP 单主场景引入 CP Subsystem。

---
## 4. 推荐方案

### 4.1 决策矩阵

| 评判标准 | 选项 B（分离部署 + CMS 普通数据成员） | 选项 E（Hazelcast CP Subsystem） |
|----------|--------------------------------------|----------------------------------|
| 脑裂安全 | ✅ 通过 `requireMajority` 和成员数保护租约读写 | ✅ 通过 Raft 定额和 `FencedLock` 保护主锁 |
| 网络分区期间可用 | ✅ 多数侧包含 AC+CMS 时继续服务 | ✅ 多数 CP 成员侧可继续服务 |
| CMS 数据职责 | ✅ CMS 存储 Hazelcast 数据分区和备份 | ✅ 作为普通成员时可同时参与数据和 CP 组 |
| 需要代码改动 | 最小，沿用现有 `isAvailable()` 判断 | 较大，需要引入 CP 锁、锁续期和 fencing 处理 |
| 运维复杂度 | 低，重点是固定成员列表和拓扑隔离 | 中，需要维护 CP 成员、会话和锁状态 |
| 推荐给 | 当前生产目标 | 未来确实需要 CP 语义的场景 |

### 4.2 推荐：方案 B（Keepalived + requireMajority + CMS 普通数据成员）

生产部署采用方案 B：keepalived 只负责 VIP 漂移，Hazelcast 负责租约读写保护，CMS 作为普通数据成员保存数据分区和备份。

关键要求：
- `ha.require-majority=true`
- `ha.minimum-cluster-size=2`
- `cluster.members` 固定列出 AC-1、AC-2 和全部 CMS 地址
- CMS 独立部署为首选；AC 与 CMS 同机最多只能出现一次
- DHCP 服务只在 `role=MASTER`、持有 VIP、租约镜像已加载、Hazelcast 成员数满足要求时响应

分区行为：
- 孤立 AC：停止 DHCP 服务。
- AC + 至少 1 台 CMS：满足成员数要求，若该 AC 持有 VIP 则继续服务。
- 全部成员互相隔离：停止服务，避免双主写入。

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

以下时间线假设采用分离部署：AC-1、AC-2 与至少 1 台 CMS 均为 Hazelcast 普通数据成员，配置 `require-majority=true`、`minimum-cluster-size=2`。

```
T=0s      AC-1 JVM 崩溃（或 systemctl kill，或断电）
          - 到 AC-1:5701 的 TCP 连接收到 RST 或超时
          - 到 AC-1:8080 的 TCP 连接收到 RST

T=~2s     AC-2 上的 Hazelcast 检测到到 AC-1 的连接故障
          - 开始成员故障评估
          - CMS 成员仍与 AC-2 连通，集群多数侧保持可用

T=2s      AC-2 上的 keepalived：VRRP advert_int=1s，fall=2
          - 需要两次连续健康检查失败
          - check_app.sh curl AC-1 的健康端点 → 连接被拒绝（max-time=1s）
          - 在 ~T=4s 达到 fall 阈值

T=4s      keepalived AC-2：BACKUP → MASTER 转换
          - 将 VIP 绑定到 AC-2 的 eth0
          - 发送 GARP
          - 调用 notify.sh MASTER

T=4s      AC-2 上的 notify.sh：
          - 将 "MASTER" 写入 /run/ac-ha-role
          - POST 到 http://127.0.0.1:8080/internal/ha/role?role=MASTER
          - Spring Boot：HaService.updateRole(MASTER)

T=5-7s    网络交换机更新 VIP 的 ARP 缓存 → 现在指向 AC-2 的 MAC

T=5-10s   AC-2 上的 Hazelcast 移除 AC-1 成员
          - AC-2 + CMS 至少 2 个成员仍然存活
          - memberCount() >= minimum-cluster-size
          - isAvailable()=true

T=~10s    AC-2 同时满足 role=MASTER、VIP=yes、leaseMirrorLoaded=true、authority=true
          → canServeDhcp()=true
          → AC-2 响应 DHCP 请求

T=10-20s  DHCP 客户端完成 ARP 更新和重试，服务恢复
```

**总结**：分离部署下，正常节点故障的目标恢复时间为 10–20 秒，取决于 keepalived 健康检查、Hazelcast 成员检测、GARP 传播和客户端重试间隔。
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
  vip: 192.168.56.200       # 生产环境真实 VIP CIDR（原 127.0.0.1）
  interface-name: eth0       # 承载 VIP 的网卡
  require-majority: true     # 关键变更：原 false
  minimum-cluster-size: 2    # 分离部署下 AC + CMS 多数侧可继续服务

cluster:
  name: ha-dhcp
  port: 5701
  members:
    - 192.168.56.101:5701   # AC-1 物理 IP
    - 192.168.56.102:5701   # AC-2 物理 IP
    - 192.168.56.103:5701   # CMS-1 物理 IP，普通 Hazelcast 数据成员
```

如有多台 CMS，继续追加到同一份固定成员列表中。所有 AC 和 CMS 必须使用一致的 `cluster.members` 配置。

如有需要，添加角色文件路径覆盖：
```yaml
ha:
  role-file: /run/ac-ha-role   # 必须与 notify.sh 中的 ROLE_FILE 一致
```

---
## 7. 验证测试计划

### 7.1 测试环境搭建

```
物理 / VM 拓扑：
  AC-1  (192.168.56.101)：Spring Boot + Hazelcast + keepalived
  AC-2  (192.168.56.102)：Spring Boot + Hazelcast + keepalived
  CMS-1 (192.168.56.103)：CMS 进程 + Hazelcast 普通数据成员
  VIP: 192.168.56.200
  测试客户端: 192.168.56.50（发送 DHCP 请求，监控响应）
  抓包主机: 同一 L2 段的混杂模式

辅助别名：
  alias kstatus='journalctl -u keepalived -n 20 --no-pager'
  alias hzstatus='curl -s http://VIP:8080/actuator/health | python3 -m json.tool'
```

---

### 7.2 HA-01 — 正常故障转移（AC-1 进程崩溃）

**目的**：验证 AC-1 崩溃时，AC-2 在 CMS 成员仍在线的情况下完成 VIP 漂移并继续提供 DHCP。

```bash
# 步骤 1：验证初始状态
curl http://192.168.56.200:8080/actuator/health  # 应返回 UP
ip addr show eth0 | grep 192.168.56.200          # 在 AC-1 上：应显示 VIP
curl http://192.168.56.103:8080/actuator/health  # 如 CMS 暴露健康端点，应返回 UP

# 步骤 2：崩溃 AC-1
ssh root@192.168.56.101 "systemctl stop ha-dhcp"
ssh root@192.168.56.101 "systemctl stop keepalived"

# 步骤 3：在 AC-2 上监控故障转移
watch -n1 'ip addr show eth0 | grep 56.200'    # VIP 应在 15s 内出现
journalctl -u keepalived -f                     # 观察 MASTER 转换
curl http://192.168.56.200:8080/actuator/health # 10-20s 后应返回 UP

# 步骤 4：验证 DHCP 仍然工作
dhclient -v -r && dhclient -v eth0

# 步骤 5：验证无重复响应
tcpdump -i eth0 -n port 67 or port 68 &
# 确认每个 DISCOVER 只有 1 个 OFFER
```

**通过标准**：
- VIP 在 15 秒内出现在 AC-2 的 `ip addr` 中。
- AC-2 与 CMS 组成至少 2 个 Hazelcast 成员，`canServeDhcp=true`。
- DHCP 客户端每个 DISCOVER 恰好收到一个 OFFER。
- DHCP 响应中选项 54（服务器标识符）= VIP (192.168.56.200)。

---

### 7.3 HA-02 — 网络分区模拟

**目的**：验证 AC-1 被隔离时，AC-1 停止 DHCP，AC-2 与 CMS 组成多数侧继续服务。

```bash
# 步骤 1：验证初始状态
curl http://192.168.56.200:8080/actuator/health
curl http://192.168.56.101:8080/actuator/health
curl http://192.168.56.102:8080/actuator/health

# 步骤 2：隔离 AC-1，使 AC-2 与 CMS 保持连通
ssh root@192.168.56.101 "iptables -A INPUT -s 192.168.56.102 -j DROP && iptables -A OUTPUT -d 192.168.56.102 -j DROP"
ssh root@192.168.56.101 "iptables -A INPUT -s 192.168.56.103 -j DROP && iptables -A OUTPUT -d 192.168.56.103 -j DROP"
ssh root@192.168.56.102 "iptables -A INPUT -s 192.168.56.101 -j DROP && iptables -A OUTPUT -d 192.168.56.101 -j DROP"
ssh root@192.168.56.103 "iptables -A INPUT -s 192.168.56.101 -j DROP && iptables -A OUTPUT -d 192.168.56.101 -j DROP"

# 步骤 3：等待分区检测
sleep 15

# 步骤 4：独立检查每个 AC
ssh root@192.168.56.101 "curl -s http://127.0.0.1:8080/actuator/health"
ssh root@192.168.56.102 "curl -s http://127.0.0.1:8080/actuator/health"

# 步骤 5：验证只有多数侧响应 DHCP
tcpdump -i eth0 -n '(udp port 67 or udp port 68)'
dhclient -v -r && dhclient -v --no-pid eth0
# OFFER 包必须恰好为 1

# 步骤 6：愈合分区
ssh root@192.168.56.101 "iptables -F"
ssh root@192.168.56.102 "iptables -F"
ssh root@192.168.56.103 "iptables -F"

# 步骤 7：验证恢复
sleep 15
curl http://192.168.56.200:8080/actuator/health  # 应返回 UP
```

**通过标准**：
- AC-1 报告 `canServeDhcp=false`。
- AC-2 报告 `canServeDhcp=true`，且成员数包含 AC-2 + CMS。
- DHCP 客户端恰好收到 1 个 OFFER。
- 分区愈合后，仍只有持有 VIP 的 MASTER 响应 DHCP。

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
```

**通过标准**：AC-2 即使持有 VIP，也因 role != MASTER 而 `canServeDhcp=false`。

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
ssh root@192.168.56.101 "cat /run/ac-ha-role"  # 应打印 BACKUP
ssh root@192.168.56.101 'curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | grep -i role'
```

**通过标准**：AC-1 重启后报告 role=STANDBY，不是 MASTER。

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

**目标**：20 秒内恢复。（Keepalived 故障转移 + Hazelcast 成员检测 + GARP 传播 + DHCP 客户端重试）

---

### 7.7 HA-06 — 并发负载下重复 IP 分配

**目的**：验证故障转移和网络分区期间不会发生重复 IP 分配。

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
    echo "客户端分配 $ip"
done

# 交叉检查：所有分配的 IP 应唯一
# 也可通过 Hazelcast 验证：curl http://VIP:8080/internal/leases（如果端点存在）
```

---
## 8. 风险评估

### 8.1 风险矩阵

| # | 风险 | 可能性 | 影响 | 缓解措施 |
|---|------|--------|------|----------|
| R01 | 网络分区导致双主（requireMajority=false） | 未修复时 **高** | **严重** —— IP 冲突 | 启用 `require-majority: true`（§4.2、§6.5） |
| R02 | Hazelcast 成员检测延迟（默认最长 60s） | 中 | 高 —— 延长双主窗口 | 调优心跳：`max.no.heartbeat.seconds=10`（§6.2） |
| R03 | keepalived 状态变更时 Spring Boot 宕机 —— 角色未更新 | 中 | 中 —— 重启后角色错误 | `RoleRecoveryBean` 启动时读取角色文件（§6.1） |
| R04 | 分区期间 VIP 同时绑定到两个 AC | 中 | 严重 —— GARP 混乱，ARP 投毒 | `requireMajority` 检查限制 DHCP 响应；只有多数侧可服务 |
| R05 | Hazelcast 脑裂合并丢失租约记录 | 低 | 高 —— 合并后 IP 重用 | `LatestUpdateMergePolicy` + H2 本地镜像作为备份（§5.4） |
| R07 | `check_app.sh` 返回假阴性（应用健康但检查失败） | 低 | 中 —— 误报 VIP 故障转移 | keepalived 中调优 `fall=3`；确保健康端点是轻量的 |
| R08 | `nopreempt` 导致 VIP 留在崩溃后恢复的 AC-2 上 | 低 | 低 —— 非最优但可工作 | 如需手动将 VIP 强制移回：在 AC-1 上 `systemctl restart keepalived` |
| R09 | 崩溃时 H2 文件损坏 | 极低 | 中 —— 租约镜像丢失；客户端获得新 IP | H2 JDBC URL 中 `WRITE_DELAY=0`；H2 有 WAL，损坏很少见 |
| R10 | GARP 未传播（交换机丢弃 gratuitous ARP） | 极低 | 高 —— 故障转移后 VIP 不可达 | 增加 `garp_master_repeat=5`；在实验室测试；在 notify.sh 中用 `arping` 作为备份 |

### 8.2 最关键：R01 —— 当前 `requireMajority=false`

当前生产配置（`require-majority: false`）意味着 AC-1 和 AC-2 之间的网络分区可能导致两个节点同时提供 DHCP。这是最高优先级的修复。

**立即行动**：在生产部署之前将 `require-majority: true` 变更到 `application.yml`，并确保至少 1 台 CMS 作为独立普通数据成员加入 Hazelcast 集群。这样多数侧可以继续服务，孤立 AC 会停止响应。

### 8.3 Keepalived 单播 vs 组播

当前配置使用单播（`unicast_src_ip`、`unicast_peer`）。在组播不可靠的环境中这是正确的。然而，单播 VRRP 要求两个节点在配置时就知道对方的 IP。如果物理 IP 变化，keepalived 配置必须更新。

确保生产环境中的 `TODO_AC1_IP`、`TODO_AC2_IP`、`TODO_VIP_CIDR` 和 `TODO_INTERFACE` 占位符被替换为真实值。

### 8.4 健康检查精度

`check_app.sh` 在 `/actuator/health/ha` 不可用时回退到 `/actuator/health`。HA 专用健康检查更精确，因为它包含 `canServeDhcp` 状态。当 HA 端点实现后，设置 `ALLOW_BASIC_HEALTH_FALLBACK=false` 以避免将 JVM 健康但尚未真正准备好提供 DHCP 的节点提升为 MASTER。

### 8.5 故障转移中的租约连续性

Hazelcast map 配置中的 `backupCount=1` 意味着租约同步复制到其他普通数据成员。当 AC-1 故障时，AC-2 与 CMS 保持集群可用，租约数据仍有副本可读写。对于干净的节点故障，不会发生租约丢失。

在脑裂愈合时，`LatestUpdateMergePolicy` 将使用合并后最新更新的条目。对于 DHCP，这意味着最后一次 RENEW/REBIND 获胜。理论上可能发生冲突（相同 IP，不同 MAC），但需要非常特定的时机。H2 本地镜像提供额外的审计跟踪。

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
| P0 | 配置 TCP-IP 集群成员 | `application.yml` | `members: []` → AC-1、AC-2、CMS-1..CMS-n 的固定地址列表 |
| P1 | 添加 `RoleRecoveryBean` | 新文件 | §6.1 |
| P1 | 在 `HazelcastConfiguration` 中添加 SBP | `HazelcastConfiguration.java` | §6.2 |
| P1 | 在租约存储中包装 SBP 异常 | `HazelcastDhcpLeaseStore.java` | §6.4 |
| P1 | 添加 `HazelcastPartitionListener` | 新文件 | §6.3 |
| P1 | 调优 Hazelcast 心跳超时 | `HazelcastConfiguration.java` | `max.no.heartbeat.seconds=10` |
| P1 | 运行 HA-01 到 HA-06 测试场景 | 测试环境 | §7 |

### 架构决策记录

**选定**：选项 B（Keepalived + requireMajority + CMS 普通数据成员）。

**理由**：方案 B 让 keepalived 继续负责 VIP 漂移，同时让 Hazelcast 根据成员数保护租约读写。CMS 作为普通数据成员保存数据分区和备份，既提供定额能力，也提供租约数据副本。该方案代码改动小，运维模型清晰，适合当前 DHCP 单主 HA 目标。

**拓扑约束**：首选 AC 与 CMS 全部分离部署。迁移期若必须同机部署，AC 与 CMS 同服务器最多只能出现一次，避免两侧都拥有本地 CMS 而削弱分区保护。