#!/usr/bin/env python3
"""在配对 PR 上维护一条机器人评论（配对提示 + 检查结果），并重跑对方 PR 的配对检查。
Maintain one bot comment on paired PRs (pairing hints + check results) and re-run the paired PR's check.

由两个仓库的 pairing-notify.yml（pull_request_target）和 pairing-result.yml（workflow_run）
调用；两边共用本脚本，评论内容与重跑逻辑只在这里定义。脚本绝不检出或执行 PR 里的代码：
只读取 PR 的元数据（编号、描述、改动文件名）和 GitHub 返回的检查状态。评论里不会出现来自
PR 代码或检查日志的文本——PR 描述里的编号只接受纯数字，文件名只输出命中同步范围白名单的
路径，失败提示全部是本脚本里的固定文案。所有面向贡献者的文字都是中英双语（先中文后英文）。

环境变量 / Environment:
  GH_TOKEN          本仓库的 github.token，用于读 PR、写评论
  CROSS_REPO_TOKEN  对方仓库 Actions 写权限的 token，用于重跑对方检查；缺失时跳过重跑
  REPO              当前仓库，例如 juren233/HyperLyrics-Enhanced
  PR_NUMBER         pull_request_target 事件：当前 PR 编号
  HEAD_SHA / HEAD_OWNER / HEAD_BRANCH
                    workflow_run 事件：触发运行的提交与分支，用来反查对应的 PR
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import sync_shared_to_providers as sync  # noqa: E402

MARKER = "<!-- hle-pairing -->"
BOT_LOGIN = "github-actions[bot]"
OWNER = "juren233"
CORE_REPO = "juren233/HyperLyrics-Enhanced"
PROVIDERS_REPO = "juren233/HLE-Providers"
MAX_LISTED_FILES = 20
LANGUAGE_SEPARATOR = "\n\n---\n\n"

PAIRING_HINT = (
    "配对信息有误或副本与原件不一致：按上面的提示补齐；补齐后仍失败，请重新运行同步脚本生成副本并推送。",
    "The pairing info is wrong or the copies differ from the sources: follow the hints above; "
    "if it still fails, re-run the sync script to regenerate the copies and push them.",
)

SIDES = {
    "core": {
        "repo": CORE_REPO,
        "partner_repo": PROVIDERS_REPO,
        "link_key": "Providers-PR",
        "back_key": "Core-PR",
        "partner_workflow": "pr-check.yml",
        "partner_job": "Shared copies guard",
        # (工作流名称, 作业名称, 展示名称(中, 英), 失败提示(中, 英))；名称需与对应工作流文件保持一致
        "checks": [
            (
                "Shared Sources Pairing Check",
                "Shared sources match HLE-Providers",
                ("共享原件配对检查", "Shared sources pairing"),
                PAIRING_HINT,
            ),
            (
                "Android Build & Release",
                "build",
                ("构建、单测与 lint", "Build, unit tests and lint"),
                (
                    "构建、单测或 lint 失败，请打开详情查看日志并修复。",
                    "The build, unit tests or lint failed; open the details to read the log and fix it.",
                ),
            ),
        ],
    },
    "providers": {
        "repo": PROVIDERS_REPO,
        "partner_repo": CORE_REPO,
        "link_key": "Core-PR",
        "back_key": "Providers-PR",
        "partner_workflow": "pr-shared-check.yml",
        "partner_job": "Shared sources match HLE-Providers",
        "checks": [
            (
                "PR Check",
                "Shared copies guard",
                ("共享副本配对检查", "Shared copies pairing"),
                PAIRING_HINT,
            ),
            (
                "PR Check",
                "Apple Music build and tests",
                ("Apple Music 单测", "Apple Music unit tests"),
                (
                    "单测失败，请打开详情查看日志并修复。",
                    "The unit tests failed; open the details to read the log and fix it.",
                ),
            ),
        ],
    },
}

FAILED_CONCLUSIONS = {"failure", "timed_out", "startup_failure"}


def parse_link(body: str | None, key: str) -> int | None:
    match = re.search(rf"^[ \t]*{re.escape(key)}:[ \t]*#?(\d+)", body or "", re.MULTILINE)
    return int(match.group(1)) if match else None


def classify(touched: list[str], link: int | None, partner: dict | None, back_linked: bool) -> str:
    if not touched:
        return "none"
    if link is None:
        return "missing-link"
    if partner is None:
        return "bad-link"
    if not back_linked:
        return "missing-backlink"
    return "ok"


def file_block(touched: list[str]) -> str:
    listed = touched[:MAX_LISTED_FILES]
    lines = "\n".join(listed)
    extra = len(touched) - len(listed)
    if extra > 0:
        lines += f"\n… 以及另外 {extra} 个文件 / and {extra} more"
    return f"```\n{lines}\n```"


def build_message(side: str, state: str, number: int, link: int | None, touched: list[str], partner: dict | None) -> str | None:
    """配对提示部分（先中文后英文）；没有触及共享路径时返回 None。"""
    cfg = SIDES[side]
    partner_repo = cfg["partner_repo"]
    partner_url = f"https://github.com/{partner_repo}"
    link_key, back_key = cfg["link_key"], cfg["back_key"]
    if state == "none":
        return None

    if state == "missing-link" and side == "core":
        sync_command = (
            "```bash\n"
            "python3 scripts/sync_shared_to_providers.py --providers-repo /path/to/HLE-Providers\n"
            "```"
        )
        zh = (
            "### 需要配对的 HLE-Providers PR\n\n"
            f"这个 PR 修改了与 [HLE-Providers]({partner_url}) 共享的原件：\n\n"
            f"{file_block(touched)}\n\n"
            "Provider 插件单独编译，需要拿到这些原件的副本。请补齐：\n\n"
            "1. 在本地运行下面的命令，把原件复制到 HLE-Providers 的副本里（换成你的本地路径）：\n\n"
            f"{sync_command}\n\n"
            f"2. 在 HLE-Providers 提 PR，包含同步后的副本和插件侧改动，描述里写一行 `{back_key}: {number}`。\n"
            f"3. 回到本 PR，在描述里加一行 `{link_key}: <那个 PR 的编号>`。\n\n"
            "补好后检查会自动重跑，不需要再 @ 维护者。"
        )
        en = (
            "### Paired HLE-Providers PR needed\n\n"
            f"This PR changes sources shared with [HLE-Providers]({partner_url}) (files listed above). "
            "Provider packs are compiled separately and need copies of these files. Please:\n\n"
            "1. Run this locally to copy the sources into HLE-Providers (use your local path):\n\n"
            f"{sync_command}\n\n"
            "2. Open a PR in HLE-Providers with the synced copies and the provider-side changes, "
            f"and put `{back_key}: {number}` in its description.\n"
            f"3. Come back and add `{link_key}: <that PR number>` to this PR's description.\n\n"
            "Once done, the checks re-run automatically; there is no need to ping a maintainer."
        )
    elif state == "missing-link":
        zh = (
            "### 这个 PR 修改了共享副本\n\n"
            f"下列文件是由 [核心仓库](https://github.com/{partner_repo}) 的脚本生成的副本，不要手改：\n\n"
            f"{file_block(touched)}\n\n"
            f"- **协议变更**：先在核心仓库提 PR 修改原件，在那个 PR 的描述里写 `{back_key}: {number}`，"
            f"然后回到本 PR，在描述里加一行 `{link_key}: <核心仓库 PR 编号>`。\n"
            "- **不是协议变更**：请撤销对这些文件的修改。\n\n"
            "补好描述后检查会自动重跑，不需要再 @ 维护者。"
        )
        en = (
            "### This PR changes shared copies\n\n"
            f"The files listed above are copies generated by a script from the [core repository](https://github.com/{partner_repo}); "
            "please do not edit them by hand.\n\n"
            f"- **Protocol change**: open a PR in the core repository that changes the sources, put `{back_key}: {number}` "
            f"in its description, then add `{link_key}: <core PR number>` to this PR's description.\n"
            "- **Not a protocol change**: please revert your changes to these files.\n\n"
            "Once the descriptions are updated, the checks re-run automatically; there is no need to ping a maintainer."
        )
    elif state == "bad-link":
        zh = (
            "### 配对 PR 编号有误\n\n"
            f"描述里的 `{link_key}: {link}` 在 [{partner_repo}]({partner_url}) 中不存在，请改成正确的 PR 编号。"
        )
        en = (
            "### The paired PR number is wrong\n\n"
            f"`{link_key}: {link}` in the description does not exist in [{partner_repo}]({partner_url}). "
            "Please fix the PR number."
        )
    elif state == "missing-backlink":
        zh = (
            "### 请在对方 PR 里补上回链\n\n"
            f"已找到 [{partner_repo}#{link}]({partner_url}/pull/{link})，但它的描述里还没有 `{back_key}: {number}`。"
            "请在那个 PR 的描述里补上这一行，检查才会通过。"
        )
        en = (
            "### Please add the back-link in the other PR\n\n"
            f"Found [{partner_repo}#{link}]({partner_url}/pull/{link}), but its description does not contain "
            f"`{back_key}: {number}` yet. Please add that line there; the check will not pass until then."
        )
    else:
        closed = (partner or {}).get("state") != "open"
        zh = (
            "### 已关联配对 PR\n\n"
            f"已关联 [{partner_repo}#{link}]({partner_url}/pull/{link}){'（该 PR 已不是打开状态）' if closed else ''}。"
            "任意一边有新提交或描述被编辑，都会自动重跑对方的检查，结果以 Checks 为准。\n\n"
            "检查通过后，请等待维护者审核。"
        )
        en = (
            "### Paired PR linked\n\n"
            f"Linked to [{partner_repo}#{link}]({partner_url}/pull/{link}){' (that PR is no longer open)' if closed else ''}. "
            "Whenever either side gets new commits or has its description edited, the other side's check is re-run "
            "automatically; see Checks for the results.\n\n"
            "Once the checks pass, please wait for the maintainer's review."
        )
    return zh + LANGUAGE_SEPARATOR + en


def status_text(status: str | None, conclusion: str | None) -> str:
    if status != "completed":
        return "⏳ 运行中 / Running"
    if conclusion == "success":
        return "✅ 通过 / Passed"
    if conclusion in FAILED_CONCLUSIONS:
        return "❌ 失败 / Failed"
    if conclusion == "cancelled":
        return "⚪ 已取消 / Cancelled"
    if conclusion == "skipped":
        return "⏭️ 已跳过 / Skipped"
    if conclusion == "action_required":
        return "⏸️ 等待维护者批准后运行 / Waiting for a maintainer to approve the run"
    return f"⚪ {conclusion or '未知 / unknown'}"


def build_results(results: list[dict]) -> str | None:
    """检查结果部分；只含状态与固定文案，不转载检查日志或注释里的文本。"""
    if not results:
        return None
    lines = ["#### 检查结果 / Check results", "", "| 检查 / Check | 结果 / Result |", "| --- | --- |"]
    hints = []
    for result in results:
        cell = result["text"] + (f" · [详情 / Details]({result['url']})" if result.get("url") else "")
        lines.append(f"| {result['label']} | {cell} |")
        if result.get("failed"):
            hints.append(f"> **{result['label']}**：{result['hint']}")
    if hints:
        lines += ["", *hints]
    return "\n".join(lines)


def compose_comment(pairing: str | None, results: str | None) -> str | None:
    parts = [part for part in (pairing, results) if part]
    if not parts:
        return None
    return f"{MARKER}\n" + "\n\n".join(parts) + "\n"


def gh(args: list[str], token: str | None = None, check: bool = False) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    if token:
        env["GH_TOKEN"] = token
    return subprocess.run(["gh", "api", *args], capture_output=True, text=True, env=env, check=check)


def get_json(args: list[str]):
    result = gh(args)
    if result.returncode != 0 or not result.stdout.strip():
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def get_pr(repo: str, number: int) -> dict | None:
    return get_json([f"repos/{repo}/pulls/{number}"])


def pr_files(repo: str, number: int) -> list[str]:
    result = gh(["--paginate", f"repos/{repo}/pulls/{number}/files", "--jq", ".[].filename"], check=True)
    return [line for line in result.stdout.splitlines() if line]


def find_pr_number(repo: str, owner: str, branch: str, sha: str) -> int | None:
    """由触发运行的提交反查打开的 PR；先按 head 分支查，查不到再按提交号查（fork 的 PR 可能走后者）。"""
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        return None
    select = f'.[] | select(.state == "open" and .head.sha == "{sha}") | .number'
    for args in (
        ["-X", "GET", f"repos/{repo}/pulls", "-f", f"head={owner}:{branch}", "-f", "state=open", "-f", "per_page=100", "--jq", select],
        [f"repos/{repo}/commits/{sha}/pulls", "--jq", select],
    ):
        result = gh(args)
        first = result.stdout.split() if result.returncode == 0 else []
        if first and first[0].isdigit():
            return int(first[0])
    return None


def collect_results(repo: str, sha: str, checks: list[tuple]) -> list[dict]:
    runs = get_json([f"repos/{repo}/actions/runs?head_sha={sha}&event=pull_request&per_page=100", "--jq", ".workflow_runs | map({id, name})"]) or []
    latest: dict[str, int] = {}
    for run in runs:
        latest[run["name"]] = max(latest.get(run["name"], 0), run["id"])
    jobs_cache: dict[int, list[dict]] = {}
    results = []
    for workflow, job_name, (label_zh, label_en), (hint_zh, hint_en) in checks:
        run_id = latest.get(workflow)
        if run_id is None:
            continue
        if run_id not in jobs_cache:
            jobs_cache[run_id] = get_json([f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100", "--jq", ".jobs | map({name, status, conclusion, html_url})"]) or []
        job = next((item for item in jobs_cache[run_id] if item.get("name") == job_name), None)
        if job is None:
            continue
        url = job.get("html_url") or ""
        results.append(
            {
                "label": f"{label_zh} / {label_en}",
                "text": status_text(job.get("status"), job.get("conclusion")),
                "url": url if url.startswith(f"https://github.com/{repo}/") else "",
                "failed": job.get("status") == "completed" and job.get("conclusion") in FAILED_CONCLUSIONS,
                "hint": f"{hint_zh}\n> {hint_en}",
            }
        )
    return results


def upsert_comment(repo: str, number: int, message: str | None, dry_run: bool) -> None:
    result = gh(
        [
            "--paginate",
            f"repos/{repo}/issues/{number}/comments",
            "--jq",
            f'.[] | select(.user.login == "{BOT_LOGIN}" and (.body | startswith("{MARKER}"))) | {{id, body}}',
        ],
        check=True,
    )
    existing = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    if dry_run and message:
        print("---- 评论内容 / comment ----")
        print(message)
        print("---- 结束 / end ----")
    if message is None:
        for comment in existing:
            print(f"删除评论 / deleting comment {comment['id']}")
            if not dry_run:
                gh(["-X", "DELETE", f"repos/{repo}/issues/comments/{comment['id']}"], check=True)
        return
    if existing:
        if existing[0]["body"] == message:
            print("评论内容未变，跳过更新。 / Comment unchanged, skipping update.")
            return
        print(f"更新评论 / updating comment {existing[0]['id']}")
        if not dry_run:
            gh(["-X", "PATCH", f"repos/{repo}/issues/comments/{existing[0]['id']}", "-f", f"body={message}"], check=True)
        return
    print("新建评论 / creating comment")
    if not dry_run:
        gh(["-X", "POST", f"repos/{repo}/issues/{number}/comments", "-f", f"body={message}"], check=True)


def rerun_partner_check(cfg: dict, partner: dict, token: str | None, dry_run: bool) -> None:
    if not token:
        print("::notice::未配置 CROSS_REPO_TOKEN，跳过对方检查的联动重跑。 / CROSS_REPO_TOKEN is not set; skipping the linked re-run of the paired check.")
        return
    if partner.get("state") != "open":
        print("对方 PR 不是打开状态，跳过重跑。 / The paired PR is not open; skipping the re-run.")
        return
    partner_repo, sha = cfg["partner_repo"], partner["head"]["sha"]
    runs = gh(
        [
            f"repos/{partner_repo}/actions/workflows/{cfg['partner_workflow']}/runs?head_sha={sha}&per_page=1",
            "--jq",
            ".workflow_runs[0].id // empty",
        ],
        token,
    )
    run_id = runs.stdout.strip()
    if runs.returncode != 0 or not run_id:
        print("::notice::对方 PR 还没有可重跑的检查记录，跳过。 / The paired PR has no check run to re-run yet; skipping.")
        return
    jobs = gh(
        [
            f"repos/{partner_repo}/actions/runs/{run_id}/jobs",
            "--jq",
            f'.jobs[] | select(.name == "{cfg["partner_job"]}") | .id',
        ],
        token,
    )
    job_id = jobs.stdout.strip().splitlines()[0] if jobs.stdout.strip() else ""
    if jobs.returncode != 0 or not job_id:
        print("::notice::没有找到对方的配对检查作业，跳过。 / The paired check job was not found; skipping.")
        return
    print(f"重跑 / re-running {partner_repo} run {run_id} job {job_id}")
    if dry_run:
        return
    rerun = gh(["-X", "POST", f"repos/{partner_repo}/actions/jobs/{job_id}/rerun"], token)
    if rerun.returncode != 0:
        print(f"::notice::重跑被拒绝（可能正在运行或需要审批）。 / Re-run was refused (it may be running or awaiting approval): {rerun.stderr.strip()}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--side", choices=sorted(SIDES), required=True)
    parser.add_argument("--event", choices=("pull_request_target", "workflow_run"), default="pull_request_target")
    parser.add_argument("--dry-run", action="store_true", help="只打印要做的事，不写评论、不重跑 / print only, do not comment or re-run")
    args = parser.parse_args()

    cfg = SIDES[args.side]
    repo = os.environ.get("REPO", cfg["repo"])
    cross_token = os.environ.get("CROSS_REPO_TOKEN") or None

    if args.event == "workflow_run":
        number = find_pr_number(repo, os.environ.get("HEAD_OWNER", ""), os.environ.get("HEAD_BRANCH", ""), os.environ.get("HEAD_SHA", ""))
        if number is None:
            print("::notice::没有找到这次检查对应的打开状态的 PR，跳过。 / No open PR found for this check run; skipping.")
            return 0
    else:
        number = int(os.environ["PR_NUMBER"])

    pr = get_pr(repo, number)
    if pr is None:
        print(f"读取 {repo}#{number} 失败。 / Failed to read {repo}#{number}.", file=sys.stderr)
        return 1
    # 维护者自己的同仓库分支（包括自动同步 PR）不需要提醒。
    if pr["user"]["login"] == OWNER and pr["head"]["repo"] and pr["head"]["repo"]["full_name"] == repo:
        print("维护者的同仓库分支，跳过提醒。 / Maintainer's same-repo branch; skipping the reminder.")
        return 0

    entries = sync.source_entries() if args.side == "core" else sync.target_entries()
    touched = [path for path in pr_files(repo, number) if sync.is_shared(path, entries)]
    link = parse_link(pr.get("body"), cfg["link_key"])
    partner = get_pr(cfg["partner_repo"], link) if link is not None else None
    back_linked = bool(partner) and parse_link(partner.get("body"), cfg["back_key"]) == number
    state = classify(touched, link, partner, back_linked)
    print(f"状态 / state: {state}，命中的共享路径 / shared paths matched: {len(touched)}")

    results = collect_results(repo, pr["head"]["sha"], cfg["checks"])
    message = compose_comment(build_message(args.side, state, number, link, touched, partner), build_results(results))
    upsert_comment(repo, number, message, args.dry_run)
    if args.event == "pull_request_target" and partner is not None:
        rerun_partner_check(cfg, partner, cross_token, args.dry_run)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
