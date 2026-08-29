# BringTeleport

一个面向 Paper 服务器的传送插件，提供路径点（Warp）、死亡返回（/back）与玩家间传送（TPA）功能。

支持 Paper 1.21.1 及以上的所有版本。

## 功能

- **Warp 路径点** — 创建、删除、重命名、查询、传送私有或公有路径点，支持收藏夹
- **死亡返回** — 死亡后返回死亡地点
- **TPA 传送** — 向玩家发起传送请求，接受/拒绝，并支持返回传送前位置
- **安全传送** — 传送时自动寻找安全落点
- **本地化消息** — 所有提示文案可配置

## 命令

### Warp（别名 `wp`）

| 命令 | 说明 |
| --- | --- |
| `/warp help` | 查看帮助 |
| `/warp create <名称>` | 在当前位置创建路径点 |
| `/warp delete <名称>` | 删除路径点（需二次确认） |
| `/warp rename <旧名称> <新名称>` | 重命名路径点 |
| `/warp info <名称>` | 查看路径点信息 |
| `/warp tp <名称>` | 传送到路径点 |
| `/warp tp back` | 返回本次 warp 传送前的位置 |
| `/warp tp back undo` | 撤销返回，回到路径点 |
| `/warp star add/remove <名称>` | 收藏 / 取消收藏路径点 |
| `/warp star list` | 查看收藏列表 |
| `/tpwarp <名称>` | 快捷传送至路径点（等价 `/warp tp`） |
| `/setwarp <名称>` | 快捷创建路径点（等价 `/warp create`） |

### 死亡返回

| 命令 | 说明 |
| --- | --- |
| `/back` | 返回死亡地点（需二次确认） |

### TPA

| 命令 | 说明 |
| --- | --- |
| `/tpa <玩家>` | 请求传送到对方所在位置 |
| `/tpahere <玩家>` | 请求对方传送到自己所在位置 |
| `/tpaccept` | 接受传送请求 |
| `/tpadeny` | 拒绝传送请求 |
| `/tpaback` | 返回最近一次 TPA 传送前的位置 |

### 管理

| 命令 | 说明 |
| --- | --- |
| `/bringteleport reload` | 重载配置文件 |
| `/bringteleport help` | 查看插件帮助 |

## 权限

| 权限 | 说明 | 默认 |
| --- | --- | --- |
| `bringteleport.reload` | 重载插件配置 | op |
| `bringteleport.warp.create` | 创建路径点 | true |
| `bringteleport.warp.del` | 删除路径点 | true |
| `bringteleport.warp.del.other` | 删除其他玩家的公有路径点 | op |
| `bringteleport.warp.info` | 查看路径点信息 | true |
| `bringteleport.warp.rename` | 重命名路径点 | true |
| `bringteleport.warp.tp` | 传送到路径点 | true |
| `bringteleport.warp.star` | 收藏路径点 | true |
| `bringteleport.deathback` | 使用死亡返回 | true |
| `bringteleport.tpa.request` | 发送 TPA 请求 | true |
| `bringteleport.tpa.accept` | 接受/拒绝 TPA 请求 | true |
| `bringteleport.tpa.back` | 返回 TPA 前位置 | true |

通配权限：`bringteleport.warp.*`、`bringteleport.tpa.*`、`bringteleport.*`（均为 op 默认）。

## 安装

1. 将 `BringTeleport-Paper-<版本>.jar` 放入服务器的 `plugins/` 目录
2. 重启服务器（或使用支持热加载的插件管理器）

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/BringTeleport-Paper-<版本>.jar`（已包含 SQLite 驱动依赖）。

## 配置与本地化

首次启动后插件会生成配置与本地化文件，所有玩家可见文案均可修改。

## 兼容性

- 编译目标：Java 21（1.21.x 与 26.x 服务器均可加载）
- API 版本：1.21（向后兼容，支持 1.21.1 ~ 26.2 的 Paper 服务器）
- 数据存储：SQLite（路径点、收藏等数据持久化）

## 许可

[Apache-2.0](LICENSE)
