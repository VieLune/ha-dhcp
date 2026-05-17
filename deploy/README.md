# AC 双机 HA 最小部署文件说明

本目录提供用于 HA 验证的最小 OpenResty/Nginx、Keepalived 和抓包辅助文件。

这些文件不修改业务代码，只用于搭建正常双机主备验证环境，并辅助证明 DHCP、广播、多播在 HA 下是否存在问题。

## 1. 文件清单

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

## 2. 使用前必须替换的参数

按现场环境替换以下占位值：

| 占位值 | 含义 | 示例 |
|---|---|---|
| `TODO_INTERFACE` | 业务网卡名 | `eth0` |
| `TODO_AC1_IP` | AC-1 物理 IP | `192.168.1.10` |
| `TODO_AC2_IP` | AC-2 物理 IP | `192.168.1.11` |
| `TODO_VIP_CIDR` | VIP 与掩码 | `192.168.1.100/24` |
| `TODO_APP_PORT` | Spring Boot 端口 | `8080` |
| `TODO_HTTP_PORT` | OpenResty 对外端口 | `80` |

## 3. 推荐复制路径

OpenResty：

```bash
cp deploy/openresty/nginx.conf /usr/local/openresty/nginx/conf/nginx.conf
```

如果现场使用系统 Nginx，也可以复制到：

```bash
cp deploy/openresty/nginx.conf /etc/nginx/nginx.conf
```

Keepalived：

```bash
cp deploy/keepalived/keepalived-ac1.conf /etc/keepalived/keepalived.conf
cp deploy/keepalived/check_app.sh /etc/keepalived/check_app.sh
cp deploy/keepalived/notify.sh /etc/keepalived/notify.sh
chmod +x /etc/keepalived/check_app.sh /etc/keepalived/notify.sh
```

AC-2 使用 `keepalived-ac2.conf`。

辅助脚本授权：

```bash
chmod +x deploy/openresty/*.sh
chmod +x deploy/validation/*.sh
```

## 4. 启动前检查

OpenResty/Nginx：

```bash
openresty -t
systemctl restart openresty
```

如果使用 Nginx：

```bash
nginx -t
systemctl restart nginx
```

Keepalived：

```bash
keepalived -t -f /etc/keepalived/keepalived.conf
systemctl restart keepalived
```

## 5. 验证命令

查看 VIP 是否在本机：

```bash
ip addr show dev TODO_INTERFACE
```

访问 VIP：

```bash
curl -i http://<VIP>:TODO_HTTP_PORT/
curl -i http://<VIP>:TODO_HTTP_PORT/nginx-health
```

查看 Keepalived 日志：

```bash
journalctl -u keepalived -f
```

抓 DHCP：

```bash
deploy/validation/capture_dhcp.sh TODO_INTERFACE
```

抓广播：

```bash
deploy/validation/capture_broadcast.sh TODO_INTERFACE
```

抓多播：

```bash
deploy/validation/capture_multicast.sh TODO_INTERFACE
```

## 6. 注意事项

- AC-1 物理 IP、AC-2 物理 IP、VIP、验证 PC IP 必须从 DHCP 地址池排除。
- 第一版验证重点是证明问题，不要求业务代码具备 HA 角色感知。
- 当前 `notify.sh` 默认只记录角色变化。如果后续 Spring Boot 增加 HA 角色接口，再配置 `HA_NOTIFY_URL` 调用业务接口。
- `check_app.sh` 默认优先检查 `/actuator/health/ha`，如果不存在，会回退检查 `/actuator/health`。
