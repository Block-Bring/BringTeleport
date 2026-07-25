# BringTeleport 更新日志

## 0.5.0 (2026-07-25)

### 新增
- **配置文件自动迁移**：插件启动和重载时自动将 jar 中的默认 config.yml/locales.yml 新增 key 合并到用户现有文件，无需手动删除旧文件即可获得新功能的配置项和本地化文本
- **路径点删除确认**：执行 `/waypoint delete` 后需输入 `/waypoint confirm` 确认才真正删除
  - 可配置 `waypoint.delete-confirmation.enabled` 开关
  - 可配置 `waypoint.delete-confirmation.timeout` 超时时间（秒，支持浮点数）
- **传送倒计时**：`/waypoint tp` 执行时先倒计时再传送
  - 可配置 `waypoint.teleport.countdown.enabled` 开关
  - 可配置 `waypoint.teleport.countdown.delay` 延迟时间（秒）
  - 可配置 `waypoint.teleport.countdown.interval` 更新频率（秒）
  - 支持 `subtitle` / `title` / `both` / `chat` 四种显示方式
- **传送倒计时提示音**：倒计时过程中播放提示音
  - 可配置 `waypoint.teleport.countdown.sound` 相关选项
- **传送倒计时移动取消**：倒计时期间玩家移动则取消传送
  - 可配置 `waypoint.teleport.countdown.cancel-on-move.enabled` 开关
  - 可配置 `waypoint.teleport.countdown.cancel-on-move.display` 取消提示显示方式（subtitle/title/both/chat）
  - 可配置 `waypoint.teleport.countdown.cancel-on-move.sound` 取消提示音
- **传送成功提示**：传送完成时可配置显示方式和提示音
  - 可配置 `waypoint.teleport.success.display` 显示方式（subtitle/title/both/chat）
  - 可配置 `waypoint.teleport.success.sound` 相关选项
- **消息前缀系统**：所有消息前自动添加 `[BringTeleport]` 前缀
  - 可配置 `prefix-enabled` 开关
  - 可自定义 `locales.yml` 中的 `bringteleport.prefix`

### 修复
- 修复 `/bringteleport reload` 覆盖用户对 `locales.yml` 修改的问题
- 替换低辨识度的 `<gray>` / `<dark_gray>` 为 `<white>`
- 修复传送成功提示音有时听不到或声音不完整的问题（音效改为在传送异步完成后播放）
- 修复传送倒计时移动取消响应延迟 ~1 秒的问题（改用 PlayerMoveEvent 事件驱动，即时响应）
- 修复 `pm unload` 后 `pm load` 数据库连接已关闭的问题（WaypointManager 增加自动重连机制）

### 配置变更
- `config.yml` 新增 `waypoint.delete-confirmation` 和 `waypoint.teleport.countdown` 段落
- `config.yml` 新增 `prefix-enabled` 选项
- `config.yml` 新增 `waypoint.teleport.countdown.cancel-on-move.display` 选项
- `locales.yml` 新增 `bringteleport.prefix`、`waypoint.delete.confirm-*`、`waypoint.tp.countdown.*` 等消息
- 新增 `ConfigManager` 自动迁移系统

---

## 0.4.1

- 公有路径点删除增加所有者验证
- del → delete 重命名
- 新增 `playerwaypoints.del.other` 权限
