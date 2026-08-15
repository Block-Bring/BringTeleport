# BringTeleport

一个支持 Paper 1.21.1 ~ 26.2 的传送插件，目前包含路径点功能，后续将支持更多传送方式。

## 功能概览

- SQLite 存储数据，重启不丢
- MiniMessage 彩色文本，所有消息在 locales.yml 里都能改
- 权限对接 LuckPerms

---

## 插件管理

插件自身的管理和维护命令。

### 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/bringteleport reload` | 重载配置和语言文件 | bringteleport.reload |
| `/bringteleport help` | 显示帮助 | - |

### 权限

```
bringteleport.reload  默认 op  重载插件配置文件
```

---

## 路径点

创建路径点、查看信息和传送。

### 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/warp create public <name>` | 创建公有路径点 | bringteleport.warp.create |
| `/warp create private <name>` | 创建私有路径点 | bringteleport.warp.create |
| `/warp delete public <name>` | 删除公有路径点 | bringteleport.warp.del |
| `/warp delete private <name>` | 删除私有路径点 | bringteleport.warp.del |
| `/warp rename public <name>` | 重命名公有路径点（执行后按提示在聊天框输入新名字） | bringteleport.warp.rename |
| `/warp rename private <name>` | 重命名私有路径点（执行后按提示在聊天框输入新名字） | bringteleport.warp.rename |
| `/warp info public <name>` | 查看公有路径点信息 | bringteleport.warp.info |
| `/warp info private <name>` | 查看私有路径点信息 | bringteleport.warp.info |
| `/warp tp public <name>` | 传送到公有路径点 | bringteleport.warp.tp |
| `/warp tp private <name>` | 传送到私有路径点 | bringteleport.warp.tp |
| `/warp tp back [index]` | 返回传送前的位置（index 默认为 1） | bringteleport.warp.tp |
| `/warp tp back undo` | 撤销上次 back，回到 back 前的位置 | bringteleport.warp.tp |
| `/warp star add public\|private <name>` | 收藏路径点 | bringteleport.warp.star |
| `/warp star remove public\|private <name>` | 取消收藏路径点 | bringteleport.warp.star |
| `/warp star list` | 查看我的收藏 | bringteleport.warp.star |
| `/warp help` | 显示帮助 | - |

别名：`/wp`

收藏的路径点在 Tab 补全中会排在任何未收藏的路径点前面（收藏之间按名称排序）；`/tpwarp` 重名时优先传送到收藏的路径点。

> 服务器控制台（后台）也可执行 `info` / `delete` / `rename` 命令；操作私有路径点时需指定玩家名，如 `/warp info private <玩家名> <name>`，控制台删除直接生效、不经过确认；控制台重命名直接给两个参数：`/warp rename public <name> <新名字>`、`/warp rename private <玩家名> <name> <新名字>`。

### 权限

```
bringteleport.warp.*          默认 op    所有路径点权限
bringteleport.warp.create     默认 true  创建路径点
bringteleport.warp.del        默认 true  删除路径点
bringteleport.warp.del.other  默认 op    删除其他玩家的公有路径点
bringteleport.warp.info       默认 true  查看路径点信息
bringteleport.warp.rename     默认 true  重命名路径点
bringteleport.warp.tp         默认 true  传送至路径点
bringteleport.warp.star       默认 true  收藏路径点
```

### 配置

插件运行后会生成 `plugins/BringTeleport/` 目录，里面的路径点相关文件：

- `config.yml` — 后续会加入路径点相关配置项
- `locales.yml` — 路径点相关的消息文本都可以在这里改

改完 locales.yml 记得 `/bringteleport reload` 生效。

---

## 权限一览

所有权限汇总，方便 LuckPerms 配置。

```
bringteleport.*  默认 op  所有 BringTeleport 权限
  bringteleport.reload  默认 op  重载插件配置文件
  bringteleport.warp.*  默认 op  所有路径点权限
    bringteleport.warp.create     默认 true  创建路径点
    bringteleport.warp.del        默认 true  删除路径点
    bringteleport.warp.del.other  默认 op    删除其他玩家的公有路径点
    bringteleport.warp.info       默认 true  查看路径点信息
    bringteleport.warp.rename     默认 true  重命名路径点
    bringteleport.warp.tp         默认 true  传送至路径点
    bringteleport.warp.star       默认 true  收藏路径点
```

一键给权：`lp user <玩家> permission set bringteleport.* true`

---

## 构建

依赖 JDK 25 和 Gradle（自带 wrapper）。

```bash
./gradlew build
```

产物在 `build/libs/BringTeleport-Paper-<version>.jar`。

## 安装

把 jar 文件放到 `plugins/` 目录下，重启服务器即可。
