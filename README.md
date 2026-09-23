# RikkaHub III

> **这是一个修改版（fork）。**
>
> 本作品基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 修改而成，按
> **GNU Affero General Public License v3.0 (AGPL-3.0)** 许可发布。
>
> - 原项目：https://github.com/rikkahub/rikkahub
> - 原项目版权：归 RikkaHub 及其贡献者所有
> - 修改方：RikkaHub III
> - **修改日期：2026-09-23 起**

---

## 与原版的差异

本版本相对上游 RikkaHub 做了以下修改：

| 类别 | 改动 |
|---|---|
| 应用标识 | 应用名改为 `RikkaHub III`（含 6 种语言）；包名改为 `com.rikkahubiii.app` |
| 图标 | 更换为全新的三级罗马数字图标 |
| 版本号 | 重新从 `1.0.0` (versionCode 1) 起算，与原版版本序列分离 |
| **移除统计分析** | **完整移除了 Firebase Analytics 与 Crashlytics**，不再向任何第三方上报使用数据 |
| 更新检查 | 更新源从原项目的 `updates.rikka-ai.com` 改为查询本仓库的 GitHub Releases，避免把用户引导去下载官方版 |
| URL Scheme | `rikkahub://` 改为 `rikkahubiii://`，避免与原版应用冲突 |
| 导出水印 | 导出图片的水印由 `rikka-ai.com` 改为 `RikkaHub III` |
| 关于页 | 增加「修改版声明」条目，按 AGPL-3.0 §5 要求标注修改事实与日期 |

**保留了原项目的 Java 包路径**（`me.rerere.rikkahub`）**与原始 LICENSE 文件未作改动**。

## 许可

本项目按 **AGPL-3.0** 发布，许可证全文见 [LICENSE](LICENSE)。

你需要知道：

- 你可以自由使用、修改、分发本软件，包括商用。
- 但如果你分发本软件（或它的修改版），**必须**：
  - 以 AGPL-3.0 公开完整源代码；
  - 保留原有的版权与许可声明；
  - 显著标注你做过修改，并注明日期。
- 如果你通过网络向用户提供服务，用户有权获得源代码（AGPL §13）。

## 源码

https://github.com/ygpking/rikkahub-iii

## 构建

需要 JDK 17、Android SDK、Node.js 22 与 pnpm 11。

```bash
# 1. 准备签名配置（local.properties）
#    storeFile=app.key
#    storePassword=***
#    keyAlias=***
#    keyPassword=***

# 2. 构建
./gradlew assembleRelease
```

本仓库配置了 GitHub Actions 工作流（`.github/workflows/release-build.yml`），
可在 GitHub 上直接构建并产出已签名的 APK。

## 免责声明

本软件按「现状」提供，不附带任何明示或暗示的担保。使用本软件产生的任何后果由使用者自行承担。

本版本是第三方修改版，**与 RikkaHub 原项目作者无关**。原项目作者不对本版本提供支持或背书。

## 致谢

非常感谢 [RikkaHub](https://github.com/rikkahub/rikkahub) 原项目及其贡献者。
