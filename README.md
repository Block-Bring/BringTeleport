# BringTeleport

[![Release](https://img.shields.io/github/v/release/Block-Bring/BringTeleport?style=flat-square&label=release&color=2f7d32)](https://github.com/Block-Bring/BringTeleport/releases/latest)
[![License](https://img.shields.io/github/license/Block-Bring/BringTeleport?style=flat-square&color=2f7d32)](LICENSE)
[![Stars](https://img.shields.io/github/stars/Block-Bring/BringTeleport?style=flat-square&color=f5a623)](https://github.com/Block-Bring/BringTeleport/stargazers)
[![Issues](https://img.shields.io/github/issues/Block-Bring/BringTeleport?style=flat-square&color=0e8a16)](https://github.com/Block-Bring/BringTeleport/issues)
![Paper](https://img.shields.io/badge/Paper-1.21.1%2B-2196F3?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-E76F00?style=flat-square&logo=openjdk&logoColor=white)

一个面向 Paper 服务器的传送插件，提供路径点（Warp）、死亡返回（/back）与玩家间传送（TPA）功能。

支持 Paper 1.21.1 及以上的所有版本。

## 功能

- **Warp 路径点** — 创建、删除、重命名、查询、传送公有或私有路径点，支持收藏夹与创建广播
- **死亡返回** — 死亡后返回死亡地点，落点危险时自动寻找附近的安全落脚点
- **TPA 传送** — 向玩家发起传送请求，接受/拒绝/取消，并支持返回传送前位置
- **传送倒计时** — warp 与 TPA 传送前先倒计时，移动可取消，提示方式与音效均可配置
- **更新检查** — 启动与定期检查 GitHub Releases 新版本并通知管理员
- **本地化消息** — 所有提示文案可配置

## 命令

### Warp（别名 `wp`）

路径点分**公有**（全服可见）与**私有**（仅自己可见）两种类型，`public` / `private` 为必填类型参数；控制台操作私有路径点还需带上玩家名。

| 命令 | 说明 |
| --- | --- |
| `/warp`、`/warp help` | 查看帮助 |
| `/warp create <public\|private> <名称>` | 在当前位置创建路径点 |
| `/setwarp <public\|private> <名称>` | 快捷创建（等价 `/warp create`） |
| `/warp delete <public\|private> <名称>` | 删除自己的路径点（需 `/warp confirm` 二次确认） |
| `/warp delete public <名称>` | 删除他人创建的公有路径点（需 `bringteleport.admin.warp.del.other`，同样需确认） |
| `/warp delete private <玩家名> <名称>` | 控制台删除指定玩家的私有路径点（控制台直接生效，无需确认） |
| `/warp rename <public\|private> <名称>` | 重命名路径点（随后在聊天框输入新名字，30 秒超时） |
| `/warp info <public\|private> <名称>` | 查看路径点信息 |
| `/warp tp <public\|private> <名称>` | 传送到路径点（带倒计时） |
| `/tpwarp <名称>` | 快捷传送至路径点 |
| `/warp tp back [步数]` | 返回本次传送前的位置，可指定回溯步数 |
| `/warp tp back undo` | 撤销返回，回到路径点 |
| `/warp star add <public\|private> <名称>` | 收藏路径点 |
| `/warp star remove <public\|private> <名称>` | 取消收藏 |
| `/warp star list` | 查看收藏列表 |

> 控制台示例：`/warp info public <名称>`、`/warp info private <玩家名> <名称>`、`/warp delete private <玩家名> <名称>`。

### 死亡返回

| 命令 | 说明 |
| --- | --- |
| `/back` | 返回死亡地点 |
| `/back confirm` | 死亡点附近找不到安全落脚点时，确认后仍然返回 |

### TPA

| 命令 | 说明 |
| --- | --- |
| `/tpa <玩家>` | 请求传送到对方所在位置 |
| `/tpahere <玩家>` | 请求对方传送到自己所在位置 |
| `/tpaccept [玩家名]` | 接受传送请求（有多个待处理请求时需指定玩家） |
| `/tpadeny [玩家名]` | 拒绝传送请求（有多个待处理请求时需指定玩家） |
| `/tpacancel [玩家名]` | 取消自己发出的请求，不带参数则取消全部 |
| `/tpaback` | 返回最近一次 TPA 传送前的位置 |

### 管理

| 命令 | 说明 |
| --- | --- |
| `/bringteleport reload` | 重载配置文件 |
| `/bringteleport help` | 查看插件帮助 |
| `/bringteleport update` | 立即检查插件更新 |

## 权限

涉及管理或其他玩家数据的敏感操作统一收在 `bringteleport.admin` 命名空间下，默认仅 op 拥有。

| 权限 | 说明 | 默认 |
| --- | --- | --- |
| `bringteleport.admin.reload` | 重载插件配置 | op |
| `bringteleport.admin.update` | 检查插件更新（含加入服务器时的更新提示） | op |
| `bringteleport.admin.warp.del.other` | 删除其他玩家创建的公有路径点（重命名他人公有路径点同样需要） | op |
| `bringteleport.warp.create` | 创建路径点 | true |
| `bringteleport.warp.del` | 删除路径点 | true |
| `bringteleport.warp.info` | 查看路径点信息 | true |
| `bringteleport.warp.rename` | 重命名路径点 | true |
| `bringteleport.warp.tp` | 传送到路径点 | true |
| `bringteleport.warp.star` | 收藏路径点 | true |
| `bringteleport.deathback` | 使用死亡返回 | true |
| `bringteleport.tpa.request` | 发送或取消 TPA 请求 | true |
| `bringteleport.tpa.accept` | 接受或拒绝 TPA 请求 | true |
| `bringteleport.tpa.back` | 返回 TPA 前位置 | true |

通配权限：`bringteleport.admin.*`、`bringteleport.warp.*`、`bringteleport.tpa.*`、`bringteleport.*`（均为 op 默认）。

## 安装

1. 将 `BringTeleport-Paper-<版本>.jar` 放入服务器的 `plugins/` 目录
2. 重启服务器（或使用支持热加载的插件管理器）

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/BringTeleport-Paper-<版本>.jar`（已包含 SQLite 驱动依赖）。

## 配置与本地化

首次启动后插件会在 `plugins/BringTeleport/` 生成 `config.yml`、`locales.yml` 与 `data.db`，所有玩家可见文案均在 `locales.yml` 中可改。插件启动与 `/bringteleport reload` 时会把新版本新增的配置键与消息键自动合并进现有文件，无需手动删除旧配置。

`config.yml` 主要段落：

| 段落 | 作用 |
| --- | --- |
| `timezone` | 路径点信息中时间的显示时区 |
| `warp.delete-confirmation` | 路径点删除的 `/warp confirm` 开关与超时 |
| `warp.teleport.countdown` | 传送倒计时：延迟、更新频率、显示方式、提示音、移动取消 |
| `warp.teleport.success` | 传送成功提示的显示方式与提示音 |
| `death-back` | 死亡返回开关、复活提示、安全落脚点搜索半径与努力程度（1–3） |
| `tpa.timeout` | TPA 请求过期时间 |
| `tpa.request.sound` | 收到 TPA 请求时的提示音（默认经验球拾取音，可关） |
| `tpa.teleport.*` | TPA 传送的倒计时与成功提示（选项同 warp） |
| `update-checker` | 启动检查开关、定期检查间隔与 Releases API 地址 |

## 兼容性

- 编译目标：Java 21（1.21.x 与 26.x 服务器均可加载）
- API 版本：1.21（向后兼容，支持 1.21.1 ~ 26.2 的 Paper 服务器）
- 数据存储：SQLite（路径点、收藏等数据持久化）

## 许可

[Apache-2.0](LICENSE)
