# PlayerWaypoints

一个 Paper 26.1.2 的玩家路径点插件，使用了 AI 工具辅助编写。

## 功能

- 创建路径点，记录当前坐标和所在世界
- 路径点分公有和私有两种，公有全服可见，私有只有自己能看
- 传送到已保存的路径点
- 路径点数据存 SQLite，重启不丢
- 支持 MiniMessage 彩色文本，所有消息在 locales.yml 里都能改
- 权限对接 LuckPerms

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/waypoint create public <name>` | 创建公有路径点 | playerwaypoints.create |
| `/waypoint create private <name>` | 创建私有路径点 | playerwaypoints.create |
| `/waypoint delete public <name>` | 删除公有路径点 | playerwaypoints.del |
| `/waypoint delete private <name>` | 删除私有路径点 | playerwaypoints.del |
| `/waypoint info public <name>` | 查看公有路径点信息 | playerwaypoints.info |
| `/waypoint info private <name>` | 查看私有路径点信息 | playerwaypoints.info |
| `/waypoint tp public <name>` | 传送到公有路径点 | playerwaypoints.tp |
| `/waypoint tp private <name>` | 传送到私有路径点 | playerwaypoints.tp |
| `/waypoint tp back [index]` | 返回传送前的位置（index 默认为 1） | playerwaypoints.tp |
| `/waypoint tp back undo` | 撤销上次 back，回到 back 前的位置 | playerwaypoints.tp |
| `/waypoint reload` | 重载配置和语言文件 | playerwaypoints.reload |
| `/waypoint help` | 显示帮助 | - |

别名：`/wp`

## 权限

```
playerwaypoints.create    默认 true   创建路径点
playerwaypoints.del        默认 true   删除路径点
playerwaypoints.del.other  默认 op     删除其他玩家的公有路径点
playerwaypoints.info   默认 true   查看路径点信息
playerwaypoints.tp     默认 true   传送至路径点
playerwaypoints.reload 默认 op     重载插件
playerwaypoints.*      默认 op     以上所有
```

用 LuckPerms 的话直接 `lp user <玩家> permission set playerwaypoints.* true` 就行。

## 配置

插件装好后跑一遍，会生成 `plugins/PlayerWaypoints/` 目录，里面有：

- `config.yml` — 当前没什么好配的，后续会加
- `locales.yml` — 所有消息文本，随你改

改完 locales.yml 记得 `/waypoint reload` 生效。

## 构建

依赖 JDK 25 和 Gradle（自带 wrapper）。

```bash
./gradlew build
```

产物在 `build/libs/PlayerWaypoints-<version>.jar`。

## 安装

把 jar 文件放到 `plugins/` 目录下，然后重启服务器即可。
