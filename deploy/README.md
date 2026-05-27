# AC 双机 HA 部署辅助文件说明

本目录保留 Ubuntu 环境下的 OpenResty/Nginx、Keepalived 和抓包辅助文件。当前正式方案不再依赖 Keepalived；VIP 由应用在 Ubuntu 上通过 `ip` 和 `arping` 控制。

Keepalived 文件仅作为历史验证、迁移对比或问题复现材料，不作为正式运行依赖。

## 文件清单

```text
deploy/
  README.md
  openresty/
    nginx.conf
    test_config.sh
    reload.sh
  keepalived/
    keepalived-ac1.conf
    keepalived-ac2.conf
    check_app.sh
    notify.sh
  validation/
    capture_dhcp.sh
    capture_broadcast.sh
    capture_multicast.sh
```

## 正式方案关注点

- 两台 AC 均运行 Spring Boot、DHCP、Hazelcast 和本地 H2。
- `ha.ip` 配置后作为 VIP；未配置时应用默认解析 Ubuntu 第一块物理网卡 IPv4。
- 运行时主备只看本机是否实际持有 VIP。
- Hazelcast 同步 `dhcp:leases`、`edge:devices`、`ac:config`。
- H2 保存 `dhcp_lease`、`edge_device`、`ac_config`。
- 网络分区期间允许双主，恢复后按 VIP 持有侧优先合并。

## 需要替换的占位参数

| 占位值 | 含义 | 示例 |
|---|---|---|
| `TODO_INTERFACE` | 业务网卡名 | `eth0` |
| `TODO_AC1_IP` | AC-1 物理 IP | `192.168.1.10` |
| `TODO_AC2_IP` | AC-2 物理 IP | `192.168.1.11` |
| `TODO_VIP_CIDR` | VIP 与掩码 | `192.168.1.100/24` |
| `TODO_APP_PORT` | Spring Boot 端口 | `8080` |
| `TODO_HTTP_PORT` | OpenResty/Nginx 对外端口 | `80` |

## OpenResty/Nginx 验证

```bash
openresty -t
systemctl restart openresty
curl -i http://<VIP>:TODO_HTTP_PORT/
```

如果现场使用系统 Nginx：

```bash
nginx -t
systemctl restart nginx
```

## 抓包辅助

```bash
chmod +x deploy/validation/*.sh
deploy/validation/capture_dhcp.sh TODO_INTERFACE
deploy/validation/capture_broadcast.sh TODO_INTERFACE
deploy/validation/capture_multicast.sh TODO_INTERFACE
```

## 注意事项

- AC-1 物理 IP、AC-2 物理 IP、VIP、验证 PC IP 必须从 DHCP 地址池排除。
- 生产环境建议显式配置 `ha.ip` 和 `ha.interface-name`。
- 当前 `deploy/keepalived/*` 仅用于历史验证，不要写入正式部署步骤。
- 正式部署文档以主线 `doc/01-05` 的两节点 AP 优先方案为准。
