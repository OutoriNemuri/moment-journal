# 时刻日记 / Moment Journal / 瞬間ジャーナル

一个流畅的日记软件，记录每一天的日常。

## 状态

**0.1 (2) Beta — Build 2 已可安装。**

CI 全绿，产出 `moment-journal-debug.apk`。Build 1 完成「录、存、备」三个主干，
Build 2 补齐应用图标。构建在 GitHub Actions 完成（本机无 JDK / Android SDK）。

## 概览

| 名称 | 值 |
|---|---|
| 应用名 | 时刻日记 / Moment Journal / 瞬間ジャーナル |
| 包名 | `com.nemuri.journal` |
| minSdk / targetSdk | 28 (Android 9) / 35 (Android 15) |
| 当前版本 | `0.1 (2)` Beta |

## 0.1 Build 1 范围

**只做三件事：录、存、备。**

- **录** — 标题 + 正文编辑；日记列表；按年份/地区分类的日记本（侧栏抽屉）；MD3 浅色/深色主题。
- **存** — SQLite (Room) + WAL；正文以**隐式扩展 Markdown** 源文本为唯一真相（时间 / 地点 / 图片三类内联标记）；输入防抖即刻保存。
- **备** — 本地 `.mjb`（ZIP）备份：一致性快照的 `journal.db` + `images/` + `manifest.json`；冷启动与退后台触发；**本版含恢复逻辑**。

完整功能列表与验收标准见 [docs/BETA-0.1-BUILD-1.md](docs/BETA-0.1-BUILD-1.md)。

### 明确不做（外推）

地点插入 → 0.2；云备份（WebDAV / FTP / SMB）→ 0.2+；编辑器背景、全局字体、呼吸式光标、MD3 Expressive → 0.3 及以后。

## 文档

- [docs/CHANGELOG.md](docs/CHANGELOG.md) — 更新日志（每个 Build 一条）
- [docs/BETA-0.1-BUILD-1.md](docs/BETA-0.1-BUILD-1.md) — 0.1 Build 1 范围定义、验收标准、已知风险
- [docs/spec-v0.1.md](docs/spec-v0.1.md) — 原始企划存档

## 许可证

未定。
