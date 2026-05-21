# AC/CMS 进程架构集群状态管理与仲裁方案 — 调研报告

> 调研完成时间：2026-05-21
> 调研范围：Hazelcast 集群方案、仲裁共识算法、VIP 漂移控制、整体架构风险评估

---

## 一、Hazelcast 集群方案

### 1.1 如何让 CMS 和 AC 两个独立系统自动发现并加入同一个 Hazelcast 集群？

**推荐方案：TCP/IP 静态种子节点（生产环境禁用 Multicast）**

```java
// AC 和 CMS 共用同一份集群配置
Config config = new Config();
config.setClusterName("ac-cms-cluster");  // 共享 cluster-name

// 静态种子节点列表（IP 或主机名）
config.getNetworkConfig().getJoin().getTcpIpConfig()
    .setEnabled(true)
    .addMember("192.168.1.10")   // AC-1
    .addMember("192.168.1.11")   // AC-2
    .addMember("192.168.1.20")   // CMS-1
    .addMember("192.168.1.21")   // CMS-2
    .addMember("192.168.1.22");  // CMS-3

// 禁用 Multicast（避免网络广播风暴和跨网段问题）
config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);

// 通过 MemberAttribute 区分节点角色
MemberAttributeConfig attrs = new MemberAttributeConfig();
attrs.setStringAttribute("role", "AC");   // CMS 端设为 "CMS"
config.setMemberAttributeConfig(attrs);
```

### 1.2 集群成员发现和生命周期管理最佳实践

| 场景 | 建议 |
|------|------|
| 新节点加入 | 自动发现种子节点 → 握手 → 分区重分配 |
| 节点离开 | Hazelcast 自动检测（默认 5 次心跳 × 2s = 10s 超时） |
| 网络分区 | 启用 Split-Brain Protection，配置 merge policy |
| 偶数节点 | CP Subsystem 要求奇数成员，推荐 5 成员组 |

### 1.3 Hazelcast 集群分区、数据复制、故障转移

```java
// 数据复制因子（默认 1，建议 2）
config.setProperty(ClusterProperty.PARTITION_COUNT.getName(), "271");

// Split-Brain 保护（偶数节点时强制启用）
SplitBrainProtectionConfig sbp = new SplitBrainProtectionConfig();
sbp.setName("write-protection");
sbp.setEnabled(true);
sbp.setMinimumClusterSize(3);  // 至少 3 个节点才允许写入
sbp.setProtectOn(SplitBrainProtectionConfig.ProtectOn.WRITE);
config.addSplitBrainProtectionConfig(sbp);

// Merge Policy：网络分区恢复时保留最新数据
config.getMapConfig("shared-state")
    .setMergePolicyConfig(new MergePolicyConfig(
        LatestUpdateMergePolicy.class.getName()));
```

### 1.4 Hazelcast 结合 H2 作为持久化备份

**推荐：GenericMapStore（Hazelcast 5.2+）+ HikariCP + H2**

```java
// H2 JDBC 数据源配置（关键参数防止崩溃损坏）
HikariConfig hikari = new HikariConfig();
hikari.setJdbcUrl("jdbc:h2:file:/data/hazelcast-store;" +
    "WRITE_DELAY=0;" +        // 立即写入磁盘
    "FILE_LOCK=FS;" +         // 文件系统级锁
    "DB_CLOSE_ON_EXIT=FALSE");
hikari.setUsername("sa");
hikari.setPassword("");
hikari.setMaximumPoolSize(10);
DataSource ds = new HikariDataSource(hikari);

// GenericMapStore 自动将 Map 数据持久化到 H2
MapStoreConfig mapStore = new MapStoreConfig();
mapStore.setClassName(GenericMapStore.class.getName());
mapStore.setProperties(Map.of(
    "dataConnectionRef", "h2-ds",    // 引用已注册的 DataSource
    "idColumn", "id",
    "columns", "id VARCHAR, value VARCHAR, updated_at TIMESTAMP"
));
mapStore.setInitialLoadMode(MapStoreConfig.InitialLoadMode.EAGER);

config.getMapConfig("arbitration-state").setMapStoreConfig(mapStore);

// JVM shutdown hook：安全关闭 H2 + 执行 DEFRAG
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try (Connection c = ds.getConnection();
         Statement st = c.createStatement()) {
        st.execute("SHUTDOWN DEFRAG");  // 整理碎片，加速下次启动
    } catch (Exception e) {
        log.error("H2 shutdown failed", e);
    }
}));
```

---

## 二、仲裁进程设计（CMS 侧）

### 2.1 共识算法推荐 → Hazelcast CP Subsystem（内置 Raft）

**无需引入 ZooKeeper/etcd，完全在进程内实现。**

```java
// CP Subsystem 配置：5 成员 CP 组（2AC + 3CMS）
config.getCPSubsystemConfig()
    .setCPMemberCount(5)           // 固定 5 个 CP 成员
    .setSessionTimeToLiveSeconds(10)
    .setMissingCPMemberAutoRemovalSeconds(30)
    .setFailOnIndeterminateOperationState(true);

// ★ 核心原则：2 个 AC 节点绝对不能单独构成 CP 组（偶数问题）
// 最小安全配置：2AC + 1CMS = 3 成员
// 推荐配置：2AC + 3CMS = 5 成员（容错 2 节点故障）
```

### 2.2 FencedLock 仲裁权获取（防脑裂）

```java
public class ArbitrationManager {
    private final HazelcastInstance hz;
    private final FencedLock arbiterLock;
    private volatile long currentFence = -1;
    private volatile boolean isArbiter = false;

    public ArbitrationManager(HazelcastInstance hz) {
        this.hz = hz;
        // 分布式命名锁，全局唯一
        this.arbiterLock = hz.getCPSubsystem()
            .getLock("arbiter-lock@cms-arbitration");
    }

    /**
     * 竞选仲裁节点
     */
    public void startElection(Runnable onWin, Runnable onLose) {
        new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    // 尝试获取锁 + 获取 fencing token
                    long fence = arbiterLock.lockAndGetFence();
                    if (fence > currentFence) {
                        currentFence = fence;
                        isArbiter = true;
                        log.info("当选仲裁节点，fence={}", fence);
                        onWin.run();  // 绑定 VIP
                    }
                    // 持有锁期间保持心跳
                    while (arbiterLock.isLockedByCurrentThread()) {
                        Thread.sleep(2000);
                    }
                } catch (Exception e) {
                    log.error("仲裁竞选异常", e);
                } finally {
                    isArbiter = false;
                    onLose.run();  // 释放 VIP
                }
                // 退避重试
                Thread.sleep(5000);
            }
        }, "arbitration-election").start();
    }

    public void resign() {
        if (arbiterLock.isLockedByCurrentThread()) {
            arbiterLock.unlock();
        }
    }

    public boolean isArbiter() { return isArbiter; }
    public long getCurrentFence() { return currentFence; }
}
```

### 2.3 Fencing Token 防脑裂写入

```java
public void writeToSharedResource(String data, long fenceToken) {
    // 检查 fenceToken 是否为当前最新（单调递增，旧 leader 的 token 更小）
    if (fenceToken < getCurrentFence()) {
        throw new StaleLeaderException(
            "Fencing token " + fenceToken
            + " is outdated, current=" + getCurrentFence());
    }
    // 执行写操作...
}
```

---

## 三、VIP 漂移控制

### 3.1 Linux VIP 控制

```bash
# 绑定 VIP
sudo ip address add 192.168.1.100/24 dev eth0

# 发送 gratuitous ARP 刷新全网缓存
sudo arping -U -c 5 -I eth0 192.168.1.100  # Unsolicited
sudo arping -A -c 3 -I eth0 192.168.1.100  # Advertisement

# 释放 VIP
sudo ip address del 192.168.1.100/24 dev eth0
```

### 3.2 Windows VIP 控制

```batch
REM 绑定 VIP（需要管理员权限）
netsh interface ip add address name="Ethernet" addr=192.168.1.100 mask=255.255.255.0

REM 发送 gratuitous ARP（Windows 无原生 arping，可用第三方工具）
arping.exe -U -c 5 192.168.1.100

REM 释放 VIP
netsh interface ip delete address name="Ethernet" addr=192.168.1.100

REM 清空 ARP 缓存
arp -d *

REM 验证
netsh interface ip show address name="Ethernet"
```

**Windows 说明：** 必须以 LocalSystem 或拥有"管理网络接口"权限的账户运行 JVM，runas 命令在非交互模式下不可用。推荐以 Windows Service 方式部署 AC 进程。

### 3.3 Java 进程控制 VIP 实现

```java
public class VipManager {
    private static final String INTERFACE  = "eth0";    // Linux 网卡名
    private static final String VIP        = "192.168.1.100";
    private static final String VIP_PREFIX = "/24";

    public static void assignVip() throws IOException, InterruptedException {
        runPrivileged("ip", "address", "add", VIP + VIP_PREFIX, "dev", INTERFACE);
        // 刷新全网 ARP 缓存（两种格式，双保险）
        runPrivileged("arping", "-U", "-c", "5", "-I", INTERFACE, VIP);
        runPrivileged("arping", "-A", "-c", "3", "-I", INTERFACE, VIP);
        System.out.printf("[VIP] %s 绑定成功%n", VIP);
    }

    public static void releaseVip() throws IOException, InterruptedException {
        // 允许释放失败（新 leader 的 gratuitous ARP 会覆盖 ARP 缓存）
        try {
            runPrivileged("ip", "address", "del", VIP + VIP_PREFIX, "dev", INTERFACE);
            System.out.printf("[VIP] %s 释放成功%n", VIP);
        } catch (IOException e) {
            System.err.printf("[VIP] 释放 %s 失败（可忽略）: %s%n", VIP, e.getMessage());
        }
    }

    private static void runPrivileged(String... cmd)
            throws IOException, InterruptedException {
        // sudoers 需配置：youruser ALL=(ALL) NOPASSWD: /sbin/ip, /usr/sbin/arping
        String[] fullCmd = new String[cmd.length + 1];
        fullCmd[0] = "sudo";
        System.arraycopy(cmd, 0, fullCmd, 1, cmd.length);

        Process proc = new ProcessBuilder(fullCmd)
            .redirectErrorStream(true)
            .start();

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream()))) {
            reader.lines().forEach(line -> System.out.println("[VIP-cmd] " + line));
        }

        int exit = proc.waitFor();
        if (exit != 0) throw new IOException(
            "VIP 命令失败 exit=" + exit + ": " + Arrays.toString(cmd));
    }
}
```

### 3.4 仲裁切换 → VIP 漂移时序

```java
public class CMSArbitrationNode {
    private final ArbitrationManager arbitration;
    private static final String VIP = "192.168.1.100";

    public void start() {
        arbitration.startElection(
            () -> {
                // ① 成为仲裁节点后立即绑定 VIP
                try {
                    VipManager.assignVip();
                    System.out.println("→ 我是当前仲裁节点，VIP 已绑定");
                } catch (Exception e) {
                    System.err.println("VIP 绑定失败，主动让出仲裁权");
                    arbitration.resign(); // 绑定失败则放弃仲裁权，触发重选举
                }
            },
            () -> {
                // ② 失去仲裁权后释放 VIP
                try {
                    VipManager.releaseVip();
                    System.out.println("→ 我已让出仲裁权，VIP 已释放");
                } catch (Exception e) {
                    // 新仲裁节点的 gratuitous ARP 会在 30-60s 内覆盖 ARP 缓存
                    // 此处失败不影响最终一致性
                    System.err.println("VIP 释放失败（不阻塞重选举）: " + e.getMessage());
                }
            }
        );
    }
}
```

**完整切换时序：**

| 时间点 | 事件 |
|--------|------|
| T+0s | 仲裁节点 CMS-1 失联（CP session 停止心跳） |
| T+10s | Raft 重选举触发（5次心跳 × 2s = 10s） |
| T+10s | CMS-2 赢得选举，获取 FencedLock |
| T+10s | CMS-2 调用 VipManager.assignVip() |
| T+10s | 执行 arping -U -c 5（约 5s 完成） |
| T+15s | 全网 ARP 缓存更新，客户端连接漂移至新仲裁节点 |
| T+45s | 旧 ARP 缓存自然过期（Linux 默认 30-60s） |

### 3.5 下游客户端重连建议

```java
// 客户端连接池配置（HikariCP 示例）
HikariConfig hikariConfig = new HikariConfig();
hikariConfig.setJdbcUrl("jdbc:h2:tcp://192.168.1.100/~/appdb");
hikariConfig.setConnectionTimeout(5_000);   // 5s 获取连接超时
hikariConfig.setValidationTimeout(2_000);   // 2s 连接验证超时
hikariConfig.setKeepaliveTime(15_000);      // 15s TCP keepalive 检测僵尸连接
hikariConfig.setConnectionTestQuery("SELECT 1");
// 验证失败的连接会被自动驱逐，HikariCP 自动重连到新 VIP

// TCP 层调优（JVM 启动参数）
// -Dsun.net.client.defaultConnectTimeout=3000
// -Dsun.net.client.defaultReadTimeout=10000
```

---

## 四、整体架构风险评估

### 4.1 风险清单

| 编号 | 风险描述 | 严重级别 | 缓解措施 |
|------|----------|----------|----------|
| R1 | 2 个 AC 节点无法独立构成 CP 多数 | 🔴 严重 | CP 组必须包含 ≥1 个 CMS 节点，推荐 5 成员组 |
| R2 | 偶数节点脑裂（2 AC 网络分区） | 🔴 严重 | FencedLock + fencing token 防止旧主写入 |
| R3 | H2 文件在 JVM 崩溃后损坏 | 🟠 高 | WRITE_DELAY=0，shutdown hook，定期备份 |
| R4 | CMS 扩缩容触发 Hazelcast 数据重分配 | 🟡 中 | CMS 设为 Lite Member，或限制并发迁移数 |
| R5 | AC 节点故障影响 CP 组可用性 | 🟡 中 | 5 成员组容忍 2 个节点故障 |
| R6 | VIP 漂移后 ARP 缓存未及时刷新 | 🟡 中 | 发送 5 次 gratuitous ARP；客户端配置 keepalive |
| R7 | H2 数据一致性（集群恢复时） | 🟠 高 | 使用 MapStore write-through 模式；PARTIAL_RECOVERY_MOST_RECENT |
| R8 | CP 成员动态变更（CMS 上下线） | 🟡 中 | CP 成员变更需显式调用 API，不自动跟随 AP 集群 |

### 4.2 风险详情与缓解

**R3（H2 崩溃恢复）：**

```java
// 定期备份（每 5 分钟）
scheduler.scheduleAtFixedRate(() -> {
    try (Connection c = dataSource.getConnection();
         Statement st = c.createStatement()) {
        st.execute("BACKUP TO '/data/backup/h2-" +
                   System.currentTimeMillis() + ".zip'");
    } catch (Exception e) {
        log.error("H2 备份失败", e);
    }
}, 5, 5, TimeUnit.MINUTES);

// 启动时检测 H2 文件完整性
// 如果文件损坏，使用 org.h2.tools.Recover 恢复行数据
```

**R4（CMS 扩缩容）：**

```java
// CMS 节点设为 Lite Member（最优解）
config.setLiteMember(true);
// Lite Member 不持有 partition，扩缩容不触发数据迁移，对集群影响为零

// 如果 CMS 必须持有数据，限制并发迁移
config.setProperty(ClusterProperty.PARTITION_MIGRATION_INTERVAL.getName(), "1000");
config.setProperty(ClusterProperty.PARTITION_MAX_PARALLEL_MIGRATIONS.getName(), "3");
```

**R8（CP 成员动态变更）：**

```java
// 添加新 CP 成员（需要现有 CP 多数存活）
hz.getCPSubsystem()
  .getCPSubsystemManagementService()
  .promoteToCPMember()
  .toCompletableFuture()
  .get(30, TimeUnit.SECONDS);

// 移除已下线的 CP 成员
UUID removedMemberId = /* 下线成员的 UUID */;
hz.getCPSubsystem()
  .getCPSubsystemManagementService()
  .removeCPMember(removedMemberId)
  .toCompletableFuture()
  .get(30, TimeUnit.SECONDS);
```

---

## 五、推荐架构总结

```
┌─────────────────────────────────────────────────────────────┐
│                    Hazelcast 集群（AP层）                      │
│  ┌─────────┐  ┌─────────┐  ┌────────┐  ┌────────┐  ┌──────┐│
│  │  AC-1   │  │  AC-2   │  │ CMS-1  │  │ CMS-2  │  │CMS-3 ││
│  │role=AC  │  │role=AC  │  │role=CMS│  │role=CMS│  │ ...  ││
│  └────┬────┘  └────┬────┘  └───┬────┘  └───┬────┘  └──┬───┘│
│       └────────────┴───────────┴────────────┴──────────┘    │
│                    CP Subsystem（Raft层）                    │
│              CP 组大小 = 5（2AC + 3CMS）                    │
│              FencedLock "arbiter-lock@cms-arbitration"       │
│              多数 = 3，容错 = 2                               │
└─────────────────────────────────────────────────────────────┘
                               │
                      当前仲裁节点（FencedLock 持有者）
                               │ assignVip()
                               ↓
                      VIP: 192.168.1.100
                               │
                      下游客户端（通过 VIP 访问）
```

### 核心决策摘要

1. **发现机制：** TCP/IP 静态种子节点，AC + CMS 共享 `cluster-name`
2. **持久化：** GenericMapStore + H2（WRITE_DELAY=0） + Hazelcast Persistence 双层备份
3. **仲裁共识：** Hazelcast CP Subsystem（内置 Raft），5 成员 CP 组（2AC+3CMS）
4. **VIP 控制：** 仲裁节点通过 `ProcessBuilder` 调用 `ip` + `arping`，fencing token 防脑裂
5. **CMS 扩缩容：** 建议 CMS 设为 Lite Member，与 CP 成员管理解耦

---

## 六、依赖清单

```xml
<dependencies>
    <!-- Hazelcast -->
    <dependency>
        <groupId>com.hazelcast</groupId>
        <artifactId>hazelcast</artifactId>
        <version>5.5.0</version>
    </dependency>

    <!-- H2 -->
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <version>2.3.232</version>
    </dependency>

    <!-- HikariCP -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>6.2.1</version>
    </dependency>
</dependencies>
```
