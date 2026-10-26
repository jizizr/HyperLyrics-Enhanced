# 贡献指南 / Contributing Guide

中文在前，[English below](#contributing-guide)。

## 贡献指南

欢迎直接提 Pull Request。

### 先选对仓库

- **Apple Music 功能与适配、各音乐 App 的 Provider 逻辑**：去 [HLE-Providers](https://github.com/juren233/HLE-Providers) 提 PR，规则见那边的 `CONTRIBUTING.md`。
- **App 本体**（界面、设置、歌词显示、SystemUI 相关等）：在本仓库提 PR。
- **同时涉及两边的协议改动**：两个仓库各提一个 PR，见下一节。

### 与 HLE-Providers 共享的原件

App 与 Provider Pack 单独编译，Pack 拿不到 App 的类，所以接口约定在 HLE-Providers 里有一份副本，必须与本仓库的原件逐字节一致。哪些文件算原件，以 `scripts/sync_shared_to_providers.py` 为唯一来源。判断某个改动是否触及原件：

```bash
git diff --name-only origin/main...HEAD | python3 scripts/sync_shared_to_providers.py --filter sources
```

有输出就说明触及了原件，这时需要配对 PR：

1. 在本仓库的 PR 描述里写一行 `Providers-PR: <HLE-Providers 的 PR 编号>`。
2. 在 HLE-Providers 提对应 PR，包含同步后的副本和 Provider 侧改动，描述里写 `Core-PR: <本仓库 PR 编号>`。生成副本的命令：

   ```bash
   python3 scripts/sync_shared_to_providers.py --providers-repo /path/to/HLE-Providers
   ```

3. 两边的检查会互相拉取对方的 PR，运行 `--check`；副本与原件一致才通过。
4. 两个 PR 的检查都通过后，等待维护者审核即可。

两个 PR 不可能同时创建，先建一个、再建另一个，最后互相补上链接即可。PR 里缺了什么（没填 `Providers-PR`、编号有误、对方还没补回链）会由机器人在 PR 里评论，补好后评论自动更新或消失；任意一边有新提交或描述被编辑，会自动重跑对方 PR 的配对检查，不需要手动触发。检查跑完后，结果（通过、失败或运行中，失败时附修复提示）也会更新到同一条评论里。

只有本仓库一侧的原件改动被合并、HLE-Providers 没有对应 PR 时，合并到 `main` 后会由工作流自动在 HLE-Providers 开一个同步 PR。

### 提交前

- 不要修改 `app/build.gradle.kts` 里的 `versionName`、`versionCode`：`main` 上这两个值相对上一个提交发生变化时，会自动构建并发布正式版。版本号由维护者管理。
- 不要修改 `.github/` 下的工作流与脚本，除非 PR 的目的就是改它们。
- 复杂、高频或针对个别用户的诊断日志只能在 Debug 构建启用（`BuildConfig.DEBUG` 或等效的 Debug 源集），Release 构建只保留简洁的错误日志。
- 新增或修改界面动效时，沿用应用里已有的动效风格；展开/收起使用 `MainPage.kt` 中已有的写法，不要引入新的动画风格。
- 本地检查与 CI 中的一致：

  ```bash
  ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:lintRelease
  ```

### 关于 PR 的处理

- PR 打开后会自动运行构建、单测与 lint，并上传 debug 安装包供测试。
- PR 超过 30 天没有回复、也没有新提交时会被自动关闭，需要时请重新提交。

---

## Contributing Guide

Pull requests are welcome.

### Pick the right repository

- **Apple Music features and adaptation, provider logic for each music app**: open a PR in [HLE-Providers](https://github.com/juren233/HLE-Providers); see the `CONTRIBUTING.md` there.
- **The app itself** (UI, settings, lyric display, SystemUI-related code, etc.): open a PR in this repository.
- **Protocol changes that touch both sides**: open one PR in each repository; see the next section.

### Sources shared with HLE-Providers

The app and the provider packs are compiled separately, and a pack cannot see the app's classes, so HLE-Providers keeps copies of the interface contract that must be byte-identical to the sources in this repository. `scripts/sync_shared_to_providers.py` is the single source of truth for which files count as shared sources. To check whether your change touches them:

```bash
git diff --name-only origin/main...HEAD | python3 scripts/sync_shared_to_providers.py --filter sources
```

If this prints anything, your change touches shared sources and needs a paired PR:

1. Put a line `Providers-PR: <HLE-Providers PR number>` in this PR's description.
2. Open the matching PR in HLE-Providers with the synced copies and the provider-side changes, and put `Core-PR: <this PR's number>` in its description. To generate the copies:

   ```bash
   python3 scripts/sync_shared_to_providers.py --providers-repo /path/to/HLE-Providers
   ```

3. The checks on both sides fetch the other PR and run `--check`; they pass only when the copies match the sources.
4. Once the checks on both PRs pass, just wait for the maintainer's review.

The two PRs cannot be created at the same moment: create one, then the other, and finally add the links to each other. A bot comments on the PR about whatever is missing (no `Providers-PR`, a wrong number, the other side has not linked back) and updates or removes the comment once it is fixed. New commits or a description edit on either side automatically re-run the paired PR's check, so there is no need to trigger anything by hand. When the checks finish, their results (passed, failed or running, with a fix hint on failure) are written into the same comment.

If only this repository's sources are changed and merged without a PR in HLE-Providers, a workflow opens a sync PR in HLE-Providers automatically after the merge to `main`.

### Before you submit

- Do not change `versionName` or `versionCode` in `app/build.gradle.kts`: when these values change on `main` compared with the previous commit, a stable release is built and published automatically. Versions are managed by the maintainer.
- Do not change the workflows and scripts under `.github/` unless that is the purpose of the PR.
- Complex, high-frequency or user-specific diagnostic logging may only be enabled in debug builds (`BuildConfig.DEBUG` or an equivalent debug source set); release builds keep only concise error logs.
- When adding or changing UI motion, reuse the app's existing animation style; use the existing expand/collapse approach in `MainPage.kt` and do not introduce a new animation style.
- Local checks match the CI:

  ```bash
  ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:lintRelease
  ```

### How PRs are handled

- Opening a PR automatically runs the build, unit tests and lint, and uploads a debug APK for testing.
- A PR with no reply and no new commits for 30 days is closed automatically; please resubmit if you still need it.
