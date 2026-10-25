"""notify_pr.py のテスト。

条件網羅・複数条件網羅の組み合わせは、各テストクラスの説明に表で書く。
"""

import io
import json
import os
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import notify_pr
from notify_pr import Action
from rule_check import DIGEST_PREFIX, MARKER


def report_of(value):
    return f"{MARKER}\n{DIGEST_PREFIX}{value} -->\n@amasato1221 違反\n"


def bot_comment(comment_id, body):
    return {"id": comment_id, "user": {"login": notify_pr.BOT_LOGIN}, "body": body}


class ExtractDigestTests(unittest.TestCase):
    """違反の内容を表す値の取り出し。"""

    def test_値がある_取り出す(self):
        self.assertEqual(notify_pr.extract_digest(report_of("abc")), "abc")

    def test_目印がない_None(self):
        self.assertIsNone(notify_pr.extract_digest("ふつうのコメント"))

    def test_終わりがない_None(self):
        self.assertIsNone(notify_pr.extract_digest(DIGEST_PREFIX + "abc"))


class FindReportCommentTests(unittest.TestCase):
    """前のコメントを探す。

    | 書き手がボット | 目印がある | 結果 |
    | 真 | 真 | 見つかる |
    | 真 | 偽 | 見つからない |
    | 偽 | 真 | 見つからない（人が目印を書き写したもの） |
    | 偽 | 偽 | 見つからない |
    """

    def test_組み合わせ(self):
        cases = [
            (notify_pr.BOT_LOGIN, MARKER, True),
            (notify_pr.BOT_LOGIN, "ほかのボットのコメント", False),
            ("amasato1221", MARKER, False),
            ("amasato1221", "コメント", False),
        ]
        for login, body, found in cases:
            with self.subTest(login=login, body=body):
                comment = {"id": 1, "user": {"login": login}, "body": body}
                self.assertEqual(notify_pr.find_report_comment([comment]) is not None, found)

    def test_書き手と本文がない_見つからない(self):
        self.assertIsNone(notify_pr.find_report_comment([{"id": 1, "user": None, "body": None}]))
        self.assertIsNone(notify_pr.find_report_comment([{"id": 1, "user": {"login": notify_pr.BOT_LOGIN}}]))

    def test_いくつかある_最初のものを返す(self):
        comments = [{"id": 1, "user": {"login": "x"}, "body": "a"}, bot_comment(2, MARKER), bot_comment(3, MARKER)]
        self.assertEqual(notify_pr.find_report_comment(comments)["id"], 2)

    def test_コメントなし_None(self):
        self.assertIsNone(notify_pr.find_report_comment([]))


class DecideTests(unittest.TestCase):
    """行うことを決める。

    | 違反がある | 前のコメントがある | 前と同じ違反 | 前が「直りました」 | 結果 |
    | 真 | 偽 | － | － | create |
    | 真 | 真 | 真 | － | update（報告） |
    | 真 | 真 | 偽 | － | recreate |
    | 偽 | 偽 | － | － | none |
    | 偽 | 真 | － | 真 | none |
    | 偽 | 真 | － | 偽 | update（直りました） |
    """

    HEAD = "abcdef1234"

    def test_違反あり_前のコメントなし_create(self):
        self.assertEqual(notify_pr.decide(report_of("a"), None, self.HEAD), Action("create", None, report_of("a")))

    def test_違反あり_前と同じ_update(self):
        existing = bot_comment(5, report_of("a"))
        self.assertEqual(notify_pr.decide(report_of("a"), existing, self.HEAD), Action("update", 5, report_of("a")))

    def test_違反あり_前と違う_recreate(self):
        existing = bot_comment(5, report_of("a"))
        self.assertEqual(notify_pr.decide(report_of("b"), existing, self.HEAD),
                         Action("recreate", 5, report_of("b")))

    def test_違反あり_前が直りました_recreate(self):
        existing = bot_comment(5, notify_pr.resolved_body("0" * 40))
        self.assertEqual(notify_pr.decide(report_of("a"), existing, self.HEAD).kind, "recreate")

    def test_違反あり_前の本文がない_recreate(self):
        self.assertEqual(notify_pr.decide(report_of("a"), {"id": 5, "body": None}, self.HEAD).kind, "recreate")

    def test_違反なし_前のコメントなし_none(self):
        self.assertEqual(notify_pr.decide("", None, self.HEAD), Action("none"))

    def test_違反なし_前が直りました_none(self):
        existing = bot_comment(5, notify_pr.resolved_body("0" * 40))
        self.assertEqual(notify_pr.decide("", existing, self.HEAD), Action("none"))

    def test_違反なし_前に違反の報告_直りましたに書き換える(self):
        action = notify_pr.decide("", bot_comment(5, report_of("a")), self.HEAD)
        self.assertEqual(action.kind, "update")
        self.assertEqual(action.comment_id, 5)
        self.assertIn("違反はすべて直りました（コミット `abcdef1`）", action.body)
        self.assertNotIn("@amasato1221", action.body)

    def test_違反なし_前の本文がない_直りましたに書き換える(self):
        self.assertEqual(notify_pr.decide("", {"id": 5, "body": None}, self.HEAD).kind, "update")


class FakeResponse:
    def __init__(self, data):
        self.data = data

    def read(self):
        return b"" if self.data is None else json.dumps(self.data).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeOpener:
    """送られた要求を覚え、決めておいた応答を返す。"""

    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def __call__(self, request):
        body = None if request.data is None else json.loads(request.data.decode("utf-8"))
        self.requests.append((request.get_method(), request.full_url, body, dict(request.header_items())))
        return FakeResponse(self.responses.pop(0))


class GitHubClientTests(unittest.TestCase):
    """GitHub REST API の呼び方。"""

    BASE = "https://api.github.com/repos/o/r"

    def test_コメントの一覧_100件ごとに続きを読む(self):
        first = [{"id": i} for i in range(notify_pr.PER_PAGE)]
        opener = FakeOpener([first, [{"id": 999}]])
        comments = notify_pr.GitHubClient("t", "o/r", opener).list_comments(7)
        self.assertEqual(len(comments), notify_pr.PER_PAGE + 1)
        self.assertEqual([r[1] for r in opener.requests], [
            f"{self.BASE}/issues/7/comments?per_page=100&page=1",
            f"{self.BASE}/issues/7/comments?per_page=100&page=2",
        ])
        method, _, body, headers = opener.requests[0]
        self.assertEqual((method, body), ("GET", None))
        self.assertEqual(headers["Authorization"], "Bearer t")

    def test_コメントの一覧_100件より少ない_1回で終わる(self):
        opener = FakeOpener([[]])
        self.assertEqual(notify_pr.GitHubClient("t", "o/r", opener).list_comments(7), [])
        self.assertEqual(len(opener.requests), 1)

    def test_書く_書き換える_消す(self):
        opener = FakeOpener([{"id": 1}, {"id": 1}, None])
        client = notify_pr.GitHubClient("t", "o/r", opener)
        client.create_comment(7, "a")
        client.update_comment(1, "b")
        client.delete_comment(1)
        self.assertEqual([(m, u, b) for m, u, b, _ in opener.requests], [
            ("POST", f"{self.BASE}/issues/7/comments", {"body": "a"}),
            ("PATCH", f"{self.BASE}/issues/comments/1", {"body": "b"}),
            ("DELETE", f"{self.BASE}/issues/comments/1", None),
        ])


class ApplyTests(unittest.TestCase):
    """決めたことの反映。kind の4通り。"""

    def calls(self, action):
        client = mock.Mock()
        notify_pr.apply(client, 7, action)
        return client.mock_calls

    def test_recreate_消してから書く(self):
        self.assertEqual(self.calls(Action("recreate", 5, "x")),
                         [mock.call.delete_comment(5), mock.call.create_comment(7, "x")])

    def test_create_書く(self):
        self.assertEqual(self.calls(Action("create", None, "x")), [mock.call.create_comment(7, "x")])

    def test_update_書き換える(self):
        self.assertEqual(self.calls(Action("update", 5, "x")), [mock.call.update_comment(5, "x")])

    def test_none_何もしない(self):
        self.assertEqual(self.calls(Action("none")), [])


class MainTests(unittest.TestCase):
    """全体の流れと、GitHub のエラーのとき。

    | HTTP エラー | 403 | 戻り値 |
    | 偽 | － | 0 |
    | 真 | 真 | 0（警告） |
    | 真 | 偽 | 1（エラー） |
    """

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        directory = Path(self.temp.name)
        self.event = directory / "event.json"
        self.event.write_text(json.dumps({"pull_request": {"number": 7, "head": {"sha": "abcdef1234"}}}),
                              encoding="utf-8")
        self.report = directory / "report.md"
        self.report.write_text(report_of("a"), encoding="utf-8")
        self.client = mock.Mock()
        self.client.list_comments.return_value = []
        environment = {"GITHUB_TOKEN": "t", "GITHUB_REPOSITORY": "o/r"}
        patcher = mock.patch.dict(os.environ, environment)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.temp.cleanup)

    def run_main(self):
        factory = mock.Mock(return_value=self.client)
        output = io.StringIO()
        with redirect_stdout(output):
            code = notify_pr.main(["--event", str(self.event), "--report", str(self.report)], factory)
        factory.assert_called_once_with("t", "o/r")
        return code, output.getvalue()

    def test_違反あり_コメントを書く(self):
        code, output = self.run_main()
        self.assertEqual(code, 0)
        self.client.list_comments.assert_called_once_with(7)
        self.client.create_comment.assert_called_once_with(7, report_of("a"))
        self.assertIn("PR #7 のコメント：create", output)

    def error(self, code):
        return urllib.error.HTTPError("https://api.github.com", code, "x", {}, None)

    def test_権限がない_警告だけで0(self):
        self.client.list_comments.side_effect = self.error(403)
        code, output = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("::warning::", output)

    def test_ほかのエラー_1(self):
        self.client.create_comment.side_effect = self.error(500)
        code, output = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("::error::PR へのコメントに失敗しました（HTTP 500）", output)


if __name__ == "__main__":
    unittest.main()
