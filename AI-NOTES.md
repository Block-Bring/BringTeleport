# AI 注意事项

给所有在这个仓库里写代码的 AI 助手（以及用 AI 辅助的人）的固定约定。
校验规则以 [.githooks/scopes](.githooks/scopes) 和 [.githooks/commit-msg](.githooks/commit-msg) 为准，
本文件只回答一个问题：**什么时候、怎么动这两个文件。**

> `AGENTS.md`、`CLAUDE.md`、`.claude/` 是本地文件（在 `.gitignore` 里，不入库）。
> 任何需要长期生效、别人也要遵守的约定，请写进入库文件，别只写在那里。

## 新增模块时，先登记 scope，再写代码

本仓库的 commit scope 是**封闭枚举**：`type(scope): description` 里的 scope 只能取自
`.githooks/scopes`，自己造词会被 hook 和 CI 直接拒绝。

所以新增一个功能域或基础设施层时，顺序是：

1. 在 `.githooks/scopes` 里加一行，格式与邻居保持一致：
   ```
   my-scope     # 模块名：负责什么 / 对应源码路径
   ```
2. 注释里写清**源码位置**（包路径或主要类），后来的人才知道该选哪个 scope。
3. 这一行必须和模块代码出现在**同一个提交**里——让"造新词"在 diff 里可见。
4. 如果 scope 变更是独立的一步，单独提交更清楚，例如 `chore(ci): 新增 xxx scope`
   （改 `.githooks/` 下的校验规则属于 `ci`，校验脚本与枚举文件都由它管）。

## 命名规则

- 小写 ASCII，只用 `a-z0-9` 和连字符 `-`（hook 的正则不接受大写、下划线、空格）。
- 一个模块只能有一个名字。选定 `db` 之后就不要出现 `database` / `sql` 之类的近义词。
- 优先复用已有 scope：先看 `.githooks/scopes` 里的分组注释，能归入现有模块就不要新造。
- 拿不准归属时用 `repo`，但 `repo` 只用于仓库级改动（README、LICENSE 等），
  **不要用它掩盖"这里其实是个新模块"**。

## 分类先想清楚

| 分组 | 什么时候用 |
| --- | --- |
| 功能域 | 玩家可见的功能（`back`、`warp`、`tpa`、`safe-point`、`history`、`update`） |
| 基础设施 | 被多个功能域共用的一层（`core`、`db`、`command`、`config`、`locale`、`perm`） |
| 工程 | 构建与仓库配置（`build`、`ci`） |
| 兜底 | 跨模块或仓库级（`repo`） |

判断标准：**改动落在哪个模块，就选哪个 scope**。同一提交动了多个模块时，拆成多个提交，
而不是挑一个能"概括"的 scope。

## 提交前自测

hook 的位置是 `.githooks/commit-msg`，首次 clone 后需要启用一次：

```sh
git config core.hooksPath .githooks
```

先把 scope 加进 `.githooks/scopes`，再用一条临时消息确认它真的生效
（把 `my-scope` 换成你刚加的名字）：

```sh
printf 'feat(my-scope): 示例说明\n' > /tmp/msg
sh .githooks/commit-msg /tmp/msg; echo "exit=$?"   # 期望 exit=0
```

再确认近义词会被拒绝（`warps` 不在枚举里，应与 `warp` 区分开，期望退出 1）：

```sh
printf 'feat(warps): 示例说明\n' > /tmp/msg
sh .githooks/commit-msg /tmp/msg; echo "exit=$?"
```

## 检查清单

新增模块 / 新功能域时逐条过一遍：

- [ ] scope 已加进 `.githooks/scopes`，并写清对应的源码路径/类
- [ ] scope 已和模块代码放在同一个提交里
- [ ] 名字符合小写 + 连字符规则，且没有和现有 scope 近义
- [ ] 选对了分组（功能域 / 基础设施 / 工程 / 兜底）
- [ ] 提交信息用了新 scope，且 description 是祈使句、不超过 100 字符
- [ ] 用上面的命令自测过：新 scope 通过，近义词被拒
- [ ] 若这是破坏性变更，首行加 `!`，正文写明 `BREAKING CHANGE:`
