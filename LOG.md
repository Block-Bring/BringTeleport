# BringTeleport 更新日志

## 0.5.0 (2026-07-25)

### 新增
- **路径点删除确认**：执行 `/waypoint delete` 后需输入 `/waypoint confirm` 确认才真正删除
  - 可配置 `waypoint.delete-confirmation.enabled` 开关
  - 可配置 `waypoint.delete-confirmation.timeout` 超时时间（秒，支持浮点数）
- **传送倒计时**：`/waypoint tp` 执行时先倒计时再传送
  - 可配置 `waypoint.teleport.countdown.enabled` 开关
  - 可配置 `waypoint.teleport.countdown.delay` 延迟时间（秒）
  - 可配置 `waypoint.teleport.countdown.interval` 更新频率（秒）
  - 支持 `subtitle` / `title` / `both` / `chat` 四种显示方式
- **传送倒计时提示音**：倒计时过程中播放提示音
  - 可配置 `waypoint.teleport.countdown.sound.enabled` 开关
  - 可配置 `waypoint.teleport.countdown.sound.name` 声音名称
  - 可配置 `waypoint.teleport.countdown.sound.interval` 播放间隔
  - 可配置 `waypoint.teleport.countdown.sound.volume` 和 `pitch`
- **消息前缀系统**：所有消息前自动添加 `[BringTeleport]` 前缀
  - 可配置 `prefix-enabled` 开关
  - 可自定义 `locales.yml` 中的 `bringteleport.prefix`

### 修复
- 修复 `/bringteleport reload` 覆盖用户对 `locales.yml` 修改的问题
- 替换低辨识度的 `<gray>` / `<dark_gray>` 为 `<white>`

### 配置变更
- `config.yml` 新增 `waypoint.delete-confirmation` 和 `waypoint.teleport.countdown` 段落
- `config.yml` 新增 `prefix-enabled` 选项
- `locales.yml` 新增 `bringteleport.prefix`、`waypoint.delete.confirm-*`、`waypoint.tp.countdown.*` 等消息

---

## 0.4.1

- 公有路径点删除增加所有者验证
- del → delete 重命名
- 新增 `playerwaypoints.del.other` 权限
