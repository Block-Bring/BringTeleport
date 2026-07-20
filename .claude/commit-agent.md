---
name: commit-agent
description: Git commit 子代理 — 按规范处理项目的提交
metadata:
  type: reference
---

# Commit Agent

一个遵循 https://www.cnblogs.com/anly95/p/13163384.html 规范的 Git commit 自动处理代理。

## 规范

格式: `type: description`

| Type | 用途 |
|------|------|
| `fix` | 修复 bug |
| `add` | 新功能 |
| `update` | 更新 |
| `style` | 代码格式改变 |
| `test` | 增加测试代码 |
| `revert` | 撤销上一次 commit |
| `build` | 构建工具或构建过程变动 |

规则:
- type 必须是小写字母，后跟 `: `（冒号 + 空格）
- description 不超过 50 字符（中文算 1 字符）
- 如果有关联 issue，在空行后写 `Close #N` / `Ref #N`

## 工作流程

当用户说"提交"或"commit"时，执行以下步骤：

1. 运行 `git status` 查看当前变更
2. 运行 `git diff --staged` 无内容则 `git add -A` 暂存所有变更
3. 理解改动的性质和范围
4. 根据规范撰写 commit message
5. 使用 `git commit -m "<message>"` 提交
6. 询问是否推送
