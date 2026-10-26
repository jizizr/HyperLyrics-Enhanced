import re
import unittest

import pairing_notify as notify


class ParseLinkTest(unittest.TestCase):
    def test_reads_number_with_or_without_hash(self):
        self.assertEqual(notify.parse_link("说明\nProviders-PR: 55\n", "Providers-PR"), 55)
        self.assertEqual(notify.parse_link("  Core-PR:#101 备注", "Core-PR"), 101)

    def test_ignores_non_numeric_and_inline_text(self):
        self.assertIsNone(notify.parse_link("Providers-PR: abc", "Providers-PR"))
        self.assertIsNone(notify.parse_link("见 Providers-PR: 5", "Providers-PR"))
        self.assertIsNone(notify.parse_link(None, "Core-PR"))

    def test_other_key_is_not_matched(self):
        self.assertIsNone(notify.parse_link("Core-PR: 7", "Providers-PR"))


class ClassifyTest(unittest.TestCase):
    def test_states(self):
        files = ["a"]
        self.assertEqual(notify.classify([], None, None, False), "none")
        self.assertEqual(notify.classify(files, None, None, False), "missing-link")
        self.assertEqual(notify.classify(files, 3, None, False), "bad-link")
        self.assertEqual(notify.classify(files, 3, {"state": "open"}, False), "missing-backlink")
        self.assertEqual(notify.classify(files, 3, {"state": "open"}, True), "ok")


class MessageTest(unittest.TestCase):
    def test_no_message_when_nothing_touched(self):
        self.assertIsNone(notify.build_message("core", "none", 1, None, [], None))

    def test_every_message_carries_the_marker(self):
        for side in notify.SIDES:
            for state in ("missing-link", "bad-link", "missing-backlink", "ok"):
                message = notify.compose_comment(
                    notify.build_message(side, state, 9, 4, ["x/y.kt"], {"state": "open"}), None
                )
                self.assertTrue(message.startswith(notify.MARKER), (side, state))

    def test_core_missing_link_tells_contributor_the_exact_lines(self):
        message = notify.build_message("core", "missing-link", 101, None, ["a.kt"], None)
        self.assertIn("Core-PR: 101", message)
        self.assertIn("Providers-PR:", message)
        self.assertIn("sync_shared_to_providers.py", message)

    def test_providers_missing_link_offers_both_paths(self):
        message = notify.build_message("providers", "missing-link", 55, None, ["a.kt"], None)
        self.assertIn("Providers-PR: 55", message)
        self.assertIn("Core-PR:", message)
        self.assertIn("撤销", message)

    def test_file_list_is_truncated(self):
        files = [f"f{i}.kt" for i in range(notify.MAX_LISTED_FILES + 5)]
        block = notify.file_block(files)
        self.assertIn("另外 5 个文件", block)
        self.assertNotIn(f"f{notify.MAX_LISTED_FILES}.kt", block)

    def test_closed_partner_is_called_out(self):
        message = notify.build_message("core", "ok", 1, 2, ["a"], {"state": "closed"})
        self.assertIn("不是打开状态", message)


class CoreWorkflowNamesTest(unittest.TestCase):
    """脚本按名称在 GitHub 上查找本仓库的检查，名称与工作流文件漂移就会静默失效。"""

    def test_core_check_names_exist_in_workflow_files(self):
        import pathlib

        workflows = pathlib.Path(__file__).resolve().parent.parent / ".github" / "workflows"
        texts = {path.name: path.read_text(encoding="utf-8") for path in workflows.glob("*.yml")}
        for workflow, job_name, _label, _hint in notify.SIDES["core"]["checks"]:
            owner = [text for text in texts.values() if f"name: {workflow}\n" in text]
            self.assertTrue(owner, f"找不到名称为 {workflow} 的工作流")
            if job_name != "build":
                self.assertIn(f"name: {job_name}\n", owner[0])
        # 对方（HLE-Providers）要重跑的作业名也由本脚本定义，但那份工作流不在本仓库，只检查本仓库自己的
        self.assertIn(f"name: {notify.SIDES['providers']['partner_job']}\n", texts["pr-shared-check.yml"])
        self.assertIn("workflows: [\"Shared Sources Pairing Check\", \"Android Build & Release\"]", texts["pairing-result.yml"])


class ResultsTest(unittest.TestCase):
    def test_status_text(self):
        self.assertEqual(notify.status_text("in_progress", None), "⏳ 运行中 / Running")
        self.assertEqual(notify.status_text("completed", "success"), "✅ 通过 / Passed")
        self.assertEqual(notify.status_text("completed", "failure"), "❌ 失败 / Failed")
        self.assertEqual(notify.status_text("completed", "timed_out"), "❌ 失败 / Failed")
        self.assertEqual(notify.status_text("completed", "action_required"), "⏸️ 等待维护者批准后运行 / Waiting for a maintainer to approve the run")

    def test_failure_hint_is_fixed_text_and_only_for_failures(self):
        results = [
            {"label": "甲", "text": "✅ 通过", "url": "", "failed": False, "hint": "不该出现"},
            {"label": "乙", "text": "❌ 失败", "url": "https://github.com/o/r/actions/runs/1/job/2", "failed": True, "hint": "固定提示"},
        ]
        text = notify.build_results(results)
        self.assertIn("> **乙**：固定提示", text)
        self.assertNotIn("不该出现", text)
        self.assertIn("[详情 / Details](https://github.com/o/r/actions/runs/1/job/2)", text)

    def test_no_results_no_section(self):
        self.assertIsNone(notify.build_results([]))

    def test_compose_comment(self):
        self.assertIsNone(notify.compose_comment(None, None))
        both = notify.compose_comment("### 提示", "#### 检查结果")
        self.assertTrue(both.startswith(notify.MARKER))
        self.assertLess(both.index("### 提示"), both.index("#### 检查结果"))

    def test_find_pr_rejects_non_sha_without_calling_gh(self):
        self.assertIsNone(notify.find_pr_number("o/r", "x", "b", "not-a-sha"))

    def test_ok_message_does_not_mention_merge_order(self):
        message = notify.build_message("core", "ok", 1, 2, ["a"], {"state": "open"})
        self.assertNotIn("先合", message)
        self.assertIn("审核", message)
        self.assertIn("review", message)

    def test_every_message_is_bilingual(self):
        cjk = re.compile(r"[\u4e00-\u9fff]")
        for side in notify.SIDES:
            for state in ("missing-link", "bad-link", "missing-backlink", "ok"):
                message = notify.build_message(side, state, 9, 4, ["x/y.kt"], {"state": "open"})
                zh, separator, en = message.partition(notify.LANGUAGE_SEPARATOR)
                self.assertEqual(separator, notify.LANGUAGE_SEPARATOR, (side, state))
                self.assertTrue(cjk.search(zh), (side, state))
                self.assertFalse(cjk.search(en), (side, state, "英文部分不应出现中文"))
                self.assertTrue(re.search(r"[A-Za-z]{4,}", en), (side, state))


if __name__ == "__main__":
    unittest.main()
