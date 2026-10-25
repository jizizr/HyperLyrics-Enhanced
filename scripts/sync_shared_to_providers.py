#!/usr/bin/env python3
"""Copy shared Provider sources from the core repo into HLE-Providers.

The Apple Music Pack is compiled independently, so the Plugin API, the contract
package and the shared ABI sources must be byte-identical on both sides.
Run without arguments after changing shared files. Before publishing a Pack, run
--check manually against the core branch the Pack targets (not core main: Packs ship
before the app, so comparing with main would report false mismatches).
"""

from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import sys


CORE_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_PROVIDERS_ROOT = CORE_ROOT.parent / "HLE-Providers"

PLUGIN_IDS = (
    "netease",
    "qqmusic",
    "kugou",
    "kuwo",
    "spotify",
    "salt-player",
    "qishui",
    "applemusic",
)

SHARED_API = "app/src/main/java/com/juren233/hyperlyricsenhanced/provider/OfficialProviderPluginApi.kt"
API_TARGET = "src/main/kotlin/com/juren233/hyperlyricsenhanced/provider/OfficialProviderPluginApi.kt"

SHARED_FILES = {
    "app/src/main/java/com/juren233/hyperlyricsenhanced/contract/AppleMusicPreferenceKeys.kt":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/contract/AppleMusicPreferenceKeys.kt",
    "app/src/main/java/com/juren233/hyperlyricsenhanced/contract/AppleDirectBridgeContract.kt":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/contract/AppleDirectBridgeContract.kt",
    "app/src/main/java/com/juren233/hyperlyricsenhanced/contract/AppleSourceSwitchPerformanceDiagnostics.kt":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/contract/AppleSourceSwitchPerformanceDiagnostics.kt",
    "app/src/main/java/com/juren233/hyperlyricsenhanced/contract/PlaybackStateActivityPolicy.kt":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/contract/PlaybackStateActivityPolicy.kt",
    "app/src/main/java/com/juren233/hyperlyricsenhanced/lyric/LyricModels.kt":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/lyric/LyricModels.kt",
    "app/src/main/java/com/juren233/hyperlyricsenhanced/online/OnlineTranslationSourcePreferences.kt":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/online/OnlineTranslationSourcePreferences.kt",
    "app/src/main/java/com/juren233/hyperlyricsenhanced/online/model/Source.kt":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/online/model/Source.kt",
    "app/src/main/aidl/com/juren233/hyperlyricsenhanced/IAppleMusicLyricBridge.aidl":
        "src/main/aidl/com/juren233/hyperlyricsenhanced/IAppleMusicLyricBridge.aidl",
    "app/src/main/aidl/com/juren233/hyperlyricsenhanced/IAppleMusicTranslationReceiver.aidl":
        "src/main/aidl/com/juren233/hyperlyricsenhanced/IAppleMusicTranslationReceiver.aidl",
}

SHARED_TREES = {
    # Shared lyric policies resolved from the core APK by the Pack.
    "app/src/main/java/com/juren233/hyperlyricsenhanced/common/lyric":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/common/lyric",
    # Lyric models are part of the contract ABI (kept by the core).
    "app/src/main/java/com/juren233/hyperlyricsenhanced/lyric/model":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/lyric/model",
    # Apple direct-bridge contract (broadcast identity + binder helper).
    "app/src/main/java/com/juren233/hyperlyricsenhanced/common/bridge":
        "src/main/kotlin/com/juren233/hyperlyricsenhanced/common/bridge",
}

# Only the policy files used by the Pack; common/lyric contains unrelated core code.
POLICY_FILES = (
    "AppleLyricsBlurPolicy.kt",
    "AppleMissingLyricsSourceMetadata.kt",
    "AppleOriginalMetadataPolicy.kt",
    "ApplePronunciationVisibilityPolicy.kt",
    "AppleSystemFontWeightPolicy.kt",
    "ChineseLyricsPolicy.kt",
    "LyricMetadataKeys.kt",
    "OnlineTranslationContentPolicy.kt",
    "OnlineTranslationMatchStats.kt",
    "RomanizationPolicy.kt",
)


def providers_root(explicit: str | None) -> pathlib.Path:
    if explicit:
        return pathlib.Path(explicit).expanduser().resolve()
    env = os.environ.get("HLE_PROVIDERS_DIR")
    if env:
        return pathlib.Path(env).expanduser().resolve()
    return DEFAULT_PROVIDERS_ROOT.resolve()


def relative_files(root: pathlib.Path) -> set[pathlib.Path]:
    return {path.relative_to(root) for path in root.rglob("*.kt") if path.is_file()}


def copy_file(source: pathlib.Path, target: pathlib.Path, check: bool, problems: list[str]) -> None:
    if not source.is_file():
        problems.append(f"缺少源文件: {source}")
        return
    if check:
        if not target.is_file() or target.read_bytes() != source.read_bytes():
            problems.append(f"内容不一致: {target}")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def sync_tree(
    source_root: pathlib.Path,
    target_root: pathlib.Path,
    check: bool,
    problems: list[str],
    *,
    only: tuple[str, ...] | None = None,
) -> None:
    source_files = relative_files(source_root)
    if only is not None:
        source_files = {path for path in source_files if path.name in only}
    if check:
        target_files = relative_files(target_root) if target_root.is_dir() else set()
        for relative in sorted(source_files):
            target = target_root / relative
            if not target.is_file() or target.read_bytes() != (source_root / relative).read_bytes():
                problems.append(f"内容不一致: {target}")
        for extra in sorted(target_files - source_files):
            problems.append(f"目标侧多出文件: {target_root / extra}")
        return

    target_root.mkdir(parents=True, exist_ok=True)
    for relative in sorted(source_files):
        target = target_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_root / relative, target)
    for extra in sorted(relative_files(target_root) - source_files):
        (target_root / extra).unlink()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--providers-repo", help="HLE-Providers 仓库路径（默认同级目录）")
    parser.add_argument("--check", action="store_true", help="只比较，不写入")
    args = parser.parse_args()

    providers = providers_root(args.providers_repo)
    if not (providers / "settings.gradle.kts").is_file():
        raise SystemExit(f"HLE-Providers 路径无效: {providers}")

    problems: list[str] = []
    for plugin_id in PLUGIN_IDS:
        module = providers / "providers" / plugin_id
        if not module.is_dir():
            problems.append(f"缺少 Provider 模块: {module}")
            continue
        copy_file(
            CORE_ROOT / SHARED_API,
            module / API_TARGET,
            args.check,
            problems,
        )

    apple_music = providers / "providers" / "applemusic"
    for source_relative, target_relative in SHARED_FILES.items():
        copy_file(
            CORE_ROOT / source_relative,
            apple_music / target_relative,
            args.check,
            problems,
        )

    for source_relative, target_relative in SHARED_TREES.items():
        only = POLICY_FILES if source_relative.endswith("/common/lyric") else None
        sync_tree(
            CORE_ROOT / source_relative,
            apple_music / target_relative,
            args.check,
            problems,
            only=only,
        )

    if problems:
        print("共享源码不同步：", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1
    print("共享源码已同步" if not args.check else "共享源码一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
