# BringTeleport

一个支持 Paper 1.21.1 ~ 26.2 的传送插件，主打路径点（Warp）系统，后续将支持更多传送方式。

## 特性

- **公有 / 私有路径点**：公有路径点全服共享，私有路径点仅自己可见
- **收藏（Star）系统**：收藏的路径点在 Tab 补全中置顶，告别翻菜单
- **传送倒计时**：默认 3 秒延迟，期间移动自动取消，防误触
- **返回上一站**：`/warp tp back`，传错了随时回去
- **死亡返回**：`/back` 一键回到死亡地点，捡回掉落物
- **玩家传送（TPA）**：`/tpa` 请求传送到好友身边，对方点一下即可同意
- **SQLite 存储**：重启不丢数据
- **MiniMessage 彩色文本**：所有提示消息都能在 locales.yml 里自定义

## 快速上手

装好后先建一个路径点试试：

```text
/warp create public home   # 在当前位置创建公有路径点（全服可见）
/warp create private base  # 创建只有自己能看到的私有路径点
/tpwarp home               # 直接传送到路径点（Tab 可补全）
/warp tp back              # 传错了？返回上一个位置
```

更多命令在游戏里用 `/warp help` 查看。

## 安装

1. 把 `BringTeleport-Paper-*.jar` 放进 `plugins/` 目录
2. 重启服务器
3. 进服执行 `/warp help` 确认插件生效

支持 Paper 1.21.1 ~ 26.2 及其衍生端（如 Leaf）。

## 命令

### 创建与管理

| 命令 | 说明 |
|---|---|
| `/warp create public <名字>` | 在当前位置创建公有路径点 |
| `/warp create private <名字>` | 在当前位置创建私有路径点 |
| `/setwarp public\|private <名字>` | 创建路径点的快捷命令，效果同上 |
| `/warp rename public <名字>` | 重命名公有路径点（按提示在聊天框输入新名字） |
| `/warp rename private <名字>` | 重命名私有路径点 |
| `/warp delete public <名字>` | 删除公有路径点（需二次确认） |
| `/warp delete private <名字>` | 删除私有路径点（需二次确认） |
| `/warp info public <名字>` | 查看公有路径点信息（含创建者、坐标、收藏数） |
| `/warp info private <名字>` | 查看私有路径点信息 |
| `/warp help` | 显示帮助 |

路径点名称最长 32 个字符，支持中文和空格（空格用引号包裹）。

### 传送

| 命令 | 说明 |
|---|---|
| `/warp tp public <名字>` | 传送到公有路径点 |
| `/warp tp private <名字>` | 传送到自己的私有路径点 |
| `/tpwarp <名字>` | 直接传送，不用指定类型；Tab 补全列出全部可传送的路径点 |
| `/warp tp back [index]` | 返回上一次传送前的位置，index 可多回退几步 |
| `/warp tp back undo` | 撤销刚才的 back |

传送默认有 3 秒倒计时，期间移动会取消传送；这些行为都可以在 config.yml 里调整。

`/tpwarp` 遇到同名路径点时，优先传送到：**收藏的 > 自己的私有 > 公有**。

### 死亡返回

死亡后想回去捡掉落？死亡位置会自动记录（SQLite 持久化，重启不丢），复活后直接：

| 命令 | 说明 |
|---|---|
| `/back` | 立即返回最近一次死亡地点（无倒计时） |

每次死亡只保留最近一次记录，`/back` 使用一次后自动清除。返回后可以用 `/warp tp back undo` 撤销（回到返回前的位置）。

> 仅记录**有 `bringteleport.deathback` 权限**的玩家的死亡位置；没有死亡记录时使用 `/back` 会提示"没有可返回的死亡记录"。

### 玩家传送（TPA）

找朋友汇合不用报坐标了，发个请求等对方同意即可传送：

| 命令 | 说明 |
|---|---|
| `/tpa <玩家名>` | 请求传送到该玩家身边 |
| `/tpahere <玩家名>` | 请求该玩家传送到你身边 |
| `/tpaccept [玩家名]` | 接受传送请求 |
| `/tpadeny [玩家名]` | 拒绝传送请求 |
| `/tpaback` | 返回 TPA 传送前的位置 |

收到请求的玩家会看到一条带 **[接受]** / **[拒绝]** 按钮的消息，点击即可回应，也可以直接输入命令。

收到多个请求时，`/tpaccept` 不带参数会提示你指定玩家，也可以直接 `/tpaccept <玩家名>` 精确回应某一个人。请求默认 60 秒过期（可在 config.yml 调整），同一人重复请求会覆盖旧请求。

接受请求后，**传送的一方**（`/tpa` 是请求者，`/tpahere` 是被请求者）会进入 3 秒传送倒计时，期间移动会自动取消——与路径点传送同款，可在 config.yml 的 `tpa.teleport.countdown` 段调整。每次传送后可以用 `/tpaback` 回到传送前的位置（每次传送后只能用一次）。

### 收藏（Star）

路径点多了之后 Tab 补全要翻很久？把常用路径点收藏起来，它们会排在所有路径点最前面。

| 命令 | 说明 |
|---|---|
| `/warp star add public\|private <名字>` | 收藏路径点 |
| `/warp star remove public\|private <名字>` | 取消收藏 |
| `/warp star list` | 查看我的收藏 |

收藏按时间倒序排列（最近收藏的在上）。公有路径点的 info 会显示它被多少人收藏过。

### 别名

`/warp` 的别名是 `/wp`，效果完全一样。

## 公有 vs 私有

| | 公有路径点 | 私有路径点 |
|---|---|---|
| 谁能看见 | 全服玩家 | 只有创建者 |
| 谁能传送 | 全服玩家 | 只有创建者 |
| 谁能删除/重命名 | 创建者；有 `del.other` 权限者可管理任意 | 只有创建者 |
| 典型用途 | 商店、出生点等公共地标 | 个人基地、矿洞入口 |

创建公有路径点时，全服在线玩家会收到一条分享通知，可以点击直接传送。

## 服务器控制台

后台（控制台）也可以执行部分命令，但没有"自己"的概念，操作私有路径点必须显式指定玩家名：

```text
/warp info private <玩家名> <名字>
/warp delete public <名字>
/warp rename public <旧名字> <新名字>
/warp rename private <玩家名> <旧名字> <新名字>
```

控制台删除路径点不需要二次确认，直接生效。

## 配置

插件目录 `plugins/BringTeleport/` 下有两个配置文件，改完执行 `/bringteleport reload` 生效。

### config.yml

| 配置项 | 默认值 | 说明 |
|---|---|---|
| prefix-enabled | `true` | 是否在消息前显示插件前缀 |
| timezone | `+8` | 路径点创建时间的显示时区；支持 UTC 偏移（`+8`、`-5`、`+08:30`）或 IANA 名（`Asia/Shanghai`、`UTC`） |
| warp.delete-confirmation.enabled | `true` | 是否启用删除二次确认 |
| warp.delete-confirmation.timeout | `10.0` | 确认超时时间（秒） |
| warp.teleport.countdown.enabled | `true` | 是否启用传送倒计时 |
| warp.teleport.countdown.delay | `3.0` | 倒计时时长（秒） |
| warp.teleport.countdown.interval | `1` | 倒计时刷新频率（秒） |
| warp.teleport.countdown.display | `both` | 倒计时显示方式：`subtitle` / `title` / `both` / `chat` |
| warp.teleport.countdown.sound.* | — | 倒计时提示音（enabled / name / interval / volume / pitch） |
| warp.teleport.countdown.cancel-on-move.* | — | 移动取消传送（enabled / display / sound.name / volume / pitch） |
| warp.teleport.success.display | `title` | 传送成功提示方式：`subtitle` / `title` / `both` / `chat` |
| warp.teleport.success.sound.* | — | 传送成功音效（enabled / name / volume / pitch） |
| death-back.enabled | `true` | 是否启用死亡返回功能（`/back`） |
| death-back.notify-on-respawn | `true` | 玩家复活后是否提示可以用 `/back` 返回死亡地点 |
| death-back.safe-point.enabled | `true` | 死亡点无法安全站立（如掉进岩浆）时，自动寻找最近的安全落脚点 |
| death-back.safe-point.radius-horizontal | `32` | 安全点搜索的水平半径（格） |
| death-back.safe-point.radius-vertical | `16` | 安全点搜索的垂直半径（格，上下各多少） |
| tpa.timeout | `60` | TPA 传送请求过期时间（秒） |
| tpa.teleport.countdown.enabled | `true` | 是否启用 TPA 传送倒计时 |
| tpa.teleport.countdown.delay | `3.0` | 倒计时时长（秒） |
| tpa.teleport.countdown.interval | `1` | 倒计时刷新频率（秒） |
| tpa.teleport.countdown.display | `both` | 倒计时显示方式：`subtitle` / `title` / `both` / `chat` |
| tpa.teleport.countdown.sound.* | — | 倒计时提示音（enabled / name / interval / volume / pitch） |
| tpa.teleport.countdown.cancel-on-move.* | — | 移动取消传送（enabled / display / sound.name / volume / pitch） |
| tpa.teleport.success.display | `title` | 传送成功提示方式：`subtitle` / `title` / `both` / `chat` |
| tpa.teleport.success.sound.* | — | 传送成功音效（enabled / name / volume / pitch） |

### locales.yml

所有玩家看到的提示消息都在这里。使用 [MiniMessage](https://docs.advntr.dev/minimessage/format.html) 语法，支持颜色、加粗、点击等富文本。

`world-names` 段可以为每个世界自定义显示名（兼容多世界插件，新世界直接加一行）：

```yaml
world-names:
  world: <green>主世界</green>
  world_nether: <red>下界</red>
  world_the_end: <yellow>末地</yellow>
```

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| bringteleport.reload | op | 重载插件配置 |
| bringteleport.warp.create | 玩家 | 创建路径点 |
| bringteleport.warp.del | 玩家 | 删除路径点 |
| bringteleport.warp.del.other | op | 删除/重命名其他玩家的公有路径点 |
| bringteleport.warp.info | 玩家 | 查看路径点信息 |
| bringteleport.warp.rename | 玩家 | 重命名路径点 |
| bringteleport.warp.tp | 玩家 | 传送到路径点 |
| bringteleport.warp.star | 玩家 | 收藏路径点 |
| bringteleport.deathback | 玩家 | 死亡后返回死亡地点（`/back`） |
| bringteleport.tpa.request | 玩家 | 发送 TPA 传送请求（`/tpa`、`/tpahere`） |
| bringteleport.tpa.accept | 玩家 | 接受或拒绝传送请求（`/tpaccept`、`/tpadeny`） |
| bringteleport.tpa.back | 玩家 | 返回 TPA 传送前的位置（`/tpaback`） |
| bringteleport.warp.* | op | 以上所有路径点权限 |
| bringteleport.tpa.* | op | 以上所有 TPA 权限 |
| bringteleport.* | op | 插件全部权限 |

LuckPerms 一键给全权限：

```text
lp user <玩家> permission set bringteleport.* true
```

## 构建

依赖 JDK 25 和 Gradle（自带 wrapper）：

```bash
./gradlew build
```

产物在 `build/libs/BringTeleport-Paper-<version>.jar`。
