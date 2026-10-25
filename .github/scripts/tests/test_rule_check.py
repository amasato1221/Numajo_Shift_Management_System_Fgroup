"""rule_check.py のテスト。

条件網羅・複数条件網羅の組み合わせは、各テストクラスの説明に表で書く。
"""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import rule_check
from rule_check import Violation


class CheckBranchTests(unittest.TestCase):
    """ブランチ名（92 2.1）。判定は fullmatch の真・偽の2通り。"""

    def test_決まった形の名前_違反なし(self):
        for name in ("feature/SB-03-request-submit", "docs/fix-SM-06-warning", "fix/SM-07-recipients",
                     "feature/common-entity", "feature/SB-03sp-layout", "feature/common"):
            with self.subTest(name=name):
                self.assertEqual(rule_check.check_branch(name), [])

    def test_決まりと違う名前_違反1件(self):
        for name in ("claude/project-thread-iqkz5f", "main", "feature/Request-submit", "feature/",
                     "feature/sb_03", "feature/SB-03-日本語", "feature/SB-03--x", "feat/SB-03-x"):
            with self.subTest(name=name):
                violations = rule_check.check_branch(name)
                self.assertEqual(len(violations), 1)
                self.assertEqual(violations[0].rule, "92 2.1")
                self.assertIn(name, violations[0].message)


class CheckSubjectTests(unittest.TestCase):
    """コミット・PRタイトルの1行目（92 3.1・4.2）。

    | 形に合う | 50文字を超える | 結果 |
    | 偽 | － | 形の違反 |
    | 真 | 真 | 長さの違反 |
    | 真 | 偽 | 違反なし |
    """

    def test_種類が全部使える(self):
        for kind in ("feat", "fix", "docs", "test", "refactor", "chore"):
            with self.subTest(kind=kind):
                self.assertEqual(rule_check.check_subject(f"{kind}: 何かをした", "X"), [])

    def test_形が違う_形の違反(self):
        for subject in ("feature: 追加", "feat:追加", "feat 追加", "Feat: 追加", "feat:  追加", "", "feat: "):
            with self.subTest(subject=subject):
                messages = rule_check.check_subject(subject, "PRタイトル")
                self.assertEqual(len(messages), 1)
                self.assertIn("PRタイトルが「種類: 何をしたか」の形ではありません", messages[0])

    def test_ちょうど50文字_違反なし(self):
        self.assertEqual(rule_check.check_subject("feat: " + "あ" * 50, "X"), [])

    def test_51文字_長さの違反(self):
        messages = rule_check.check_subject("feat: " + "あ" * 51, "X")
        self.assertEqual(messages, ["Xの「何をしたか」が51文字です（50文字まで）"])

    def test_PRタイトル_違反は92の4_2(self):
        self.assertEqual(rule_check.check_pr_title("feat: 追加"), [])
        violations = rule_check.check_pr_title("追加しました")
        self.assertEqual([v.rule for v in violations], ["92 4.2"])


class CheckCommitsTests(unittest.TestCase):
    """コミットメッセージ（92 3.1）。

    2行目の判定（複数条件）：
    | 2行以上 | 2行目が空でない | 結果 |
    | 偽 | － | 違反なし（1行だけ） |
    | 真 | 真 | 2行目の違反 |
    | 真 | 偽 | 違反なし |
    """

    def test_コミットなし_違反なし(self):
        self.assertEqual(rule_check.check_commits([]), [])

    def test_1行だけ_違反なし(self):
        self.assertEqual(rule_check.check_commits([("a" * 40, "feat: 追加")]), [])

    def test_2行目が空いている_違反なし(self):
        message = "feat: 追加\n\n理由\n\n関係する設計書：20-SB-03"
        self.assertEqual(rule_check.check_commits([("a" * 40, message)]), [])

    def test_2行目が空いていない_2行目の違反(self):
        violations = rule_check.check_commits([("abcdef1234", "feat: 追加\n理由")])
        self.assertEqual(len(violations), 1)
        self.assertIn("`abcdef1` の2行目が空いていません", violations[0].message)

    def test_2行目が空白だけ_違反なし(self):
        self.assertEqual(rule_check.check_commits([("a" * 40, "feat: 追加\n   \n理由")]), [])

    def test_1行目と2行目の両方が違う_違反2件(self):
        violations = rule_check.check_commits([("a" * 40, "追加\n理由")])
        self.assertEqual(len(violations), 2)

    def test_メッセージが空_形の違反(self):
        violations = rule_check.check_commits([("a" * 40, "")])
        self.assertEqual(len(violations), 1)

    def test_マージコミット_対象外(self):
        commits = [("a" * 40, "Merge branch 'main' into feature/x"),
                   ("b" * 40, "Merge remote-tracking branch 'origin/main' into feature/x"),
                   ("c" * 40, "Merge pull request #3 from x/y")]
        self.assertEqual(rule_check.check_commits(commits), [])


class CheckPrBodyTests(unittest.TestCase):
    """PR本文の見出し（92 4.2）。"""

    FULL = "## 概要\nx\n## 関係する設計書\n- 20\n## 確認したこと\n- [ ] a\n## 設計書と違うところ\nなし\n"

    def test_見出しがそろっている_違反なし(self):
        self.assertEqual(rule_check.check_pr_body(self.FULL), [])

    def test_改行がCRLF_違反なし(self):
        self.assertEqual(rule_check.check_pr_body(self.FULL.replace("\n", "\r\n")), [])

    def test_本文がNone_全部の見出しがない(self):
        violations = rule_check.check_pr_body(None)
        self.assertEqual(len(violations), 1)
        for section in rule_check.REQUIRED_SECTIONS:
            self.assertIn(f"`## {section}`", violations[0].message)

    def test_一部がない_ない見出しだけ出す(self):
        violations = rule_check.check_pr_body("## 概要\nx\n## 確認したこと\n")
        self.assertEqual(violations[0].message,
                         "PR本文に見出し `## 関係する設計書`、`## 設計書と違うところ` がありません")

    def test_見出しの深さが違う_ないものとする(self):
        body = self.FULL.replace("## 概要", "### 概要")
        self.assertIn("`## 概要`", rule_check.check_pr_body(body)[0].message)


class CheckFilesTests(unittest.TestCase):
    """入れてはいけないファイル（92 1.2）。

    名前の判定（複数条件）：
    | 決まった名前 | 決まった拡張子 | 結果 |
    | 真 | 偽 | 違反 |
    | 偽 | 真 | 違反 |
    | 偽 | 偽 | 違反なし |
    | 真 | 真 | （両方に当たる名前はないので作れない） |
    """

    def test_ロゴ画像_違反(self):
        for path in ("src/main/resources/static/images/logo.png", "logo.png"):
            with self.subTest(path=path):
                self.assertIn("ロゴ画像", rule_check.forbidden_reason(path))

    def test_入れないフォルダの中_違反(self):
        for directory in rule_check.FORBIDDEN_DIRS:
            with self.subTest(directory=directory):
                reason = rule_check.forbidden_reason(f"x/{directory}/a.txt")
                self.assertEqual(reason, f"`{directory}/` の中のファイルはリポジトリに入れません")

    def test_決まった名前_違反(self):
        for name in rule_check.FORBIDDEN_NAMES:
            with self.subTest(name=name):
                self.assertEqual(rule_check.forbidden_reason(f"a/{name}"), f"`{name}` はリポジトリに入れません")

    def test_決まった拡張子_違反(self):
        for name in ("app.log", "dump.sql.bak"):
            with self.subTest(name=name):
                self.assertEqual(rule_check.forbidden_reason(name), f"`{name}` はリポジトリに入れません")

    def test_ふつうのファイル_違反なし(self):
        for path in ("src/main/java/A.java", "src/main/resources/db/migration/V1__init.sql",
                     "docs/target.md", "binder/a.txt", "mvnw.cmd"):
            with self.subTest(path=path):
                self.assertIsNone(rule_check.forbidden_reason(path))

    def test_一覧_違反のあるファイルだけ出す(self):
        violations = rule_check.check_files(["a.java", "target/a.class", ".vscode/settings.json"])
        self.assertEqual([v.path for v in violations], ["target/a.class", ".vscode/settings.json"])
        self.assertTrue(all(v.rule == "92 1.2" for v in violations))


class LineRuleTests(unittest.TestCase):
    """追加した行の書き方（91）。決まりごとに、違反になる行・ならない行を確かめる。"""

    def rules_of(self, path, text):
        return [v.rule for v in rule_check.check_line(path, 7, text)]

    def assert_hit(self, path, text, rule):
        self.assertIn(rule, self.rules_of(path, text), f"{path}: {text!r}")

    def assert_no_hit(self, path, text, rule):
        self.assertNotIn(rule, self.rules_of(path, text), f"{path}: {text!r}")

    def test_引数なしのnow_違反(self):
        for text in ("LocalDateTime now = LocalDateTime.now();", "LocalDate.now( )", "Instant.now()"):
            self.assert_hit("A.java", text, "91 7章 No.3")
        self.assert_no_hit("A.java", "LocalDateTime.now(clock)", "91 7章 No.3")

    def test_DateとcurrentTimeMillis_違反(self):
        self.assert_hit("A.java", "Date d = new Date();", "91 3.4")
        self.assert_hit("A.java", "long t = System.currentTimeMillis();", "91 3.4")
        self.assert_no_hit("A.java", "UpdateDate x = newDate();", "91 3.4")

    def test_DateとCalendarのimport_違反(self):
        self.assert_hit("A.java", "import java.util.Date;", "91 3.5")
        self.assert_hit("A.java", "import java.util.Calendar;", "91 3.5")
        self.assert_no_hit("A.java", "import java.util.List;", "91 3.5")

    def test_Autowired_違反(self):
        self.assert_hit("A.java", "    @Autowired", "91 7章 No.4")
        self.assert_no_hit("A.java", "    private final UserService userService;", "91 7章 No.4")

    def test_DataとBuilder_違反_DataJpaTestは違反なし(self):
        self.assert_hit("A.java", "@Data", "91 7章 No.5")
        self.assert_hit("A.java", "@Builder(toBuilder = true)", "91 7章 No.5")
        self.assert_no_hit("A.java", "@DataJpaTest", "91 7章 No.5")
        self.assert_no_hit("A.java", "@Getter", "91 7章 No.5")

    def test_printlnとprintStackTrace_違反(self):
        self.assert_hit("A.java", 'System.out.println("x");', "91 7章 No.14")
        self.assert_hit("A.java", "System.err.print(x);", "91 7章 No.14")
        self.assert_hit("A.java", "e.printStackTrace();", "91 7章 No.14")
        self.assert_no_hit("A.java", 'log.info("x");', "91 7章 No.14")

    def test_Disabled_違反(self):
        self.assert_hit("ATests.java", "    @Disabled", "91 7章 No.13")

    def test_ワイルドカードimport_違反_staticは違反なし(self):
        self.assert_hit("A.java", "import java.util.*;", "91 3.2")
        self.assert_no_hit("A.java", "import static org.mockito.Mockito.*;", "91 3.2")
        self.assert_no_hit("A.java", "import java.util.List;", "91 3.2")

    def test_ControllerでRepository_違反_ほかのクラスは違反なし(self):
        self.assert_hit("web/StaffController.java", "import jp.numajo.fgroup.shift.repository.UserRepository;",
                        "91 7章 No.6")
        self.assert_hit("web/StaffController.java", "    private final UserRepository users;", "91 7章 No.6")
        self.assert_no_hit("web/StaffController.java", "    private final UserService users;", "91 7章 No.6")
        self.assert_no_hit("service/UserService.java", "    private final UserRepository users;", "91 7章 No.6")

    def test_TODO_コードは違反_設計書は違反なし(self):
        for path in ("A.java", "a.html", "a.css", "a.js"):
            self.assert_hit(path, "// TODO あとで", "91 3.3")
        self.assert_no_hit("docs/a.md", "TODO", "91 3.3")
        self.assert_no_hit("A.java", "// TODOS", "91 3.3")

    def test_タブの字下げ_違反(self):
        self.assert_hit("A.java", "\tint a;", "91 3.2・5.1")
        self.assert_hit("a.html", "    \t<p>", "91 3.2・5.1")
        self.assert_no_hit("A.java", "    int a;\t// x", "91 3.2・5.1")
        self.assert_no_hit("a.yml", "\tx: 1", "91 3.2・5.1")

    def test_120文字_境界(self):
        self.assertNotIn("1行が120文字を超えています",
                         [v.message for v in rule_check.check_line("A.java", 1, "a" * 120)])
        self.assertIn("1行が120文字を超えています",
                      [v.message for v in rule_check.check_line("A.java", 1, "あ" * 121)])
        self.assertEqual(rule_check.check_line("a.html", 1, "a" * 121), [])

    def test_utext_違反(self):
        self.assert_hit("a.html", '<p th:utext="${x}"></p>', "91 7章 No.8")
        self.assert_no_hit("a.html", '<p th:text="${x}"></p>', "91 7章 No.8")

    def test_インラインのscriptとstyle_違反_srcありは違反なし(self):
        messages = [v.message for v in rule_check.check_line("a.html", 1, "<script>alert(1)</script>")]
        self.assertTrue(any("<script>" in m for m in messages))
        self.assertEqual(rule_check.check_line("a.html", 1, '<script th:src="@{/js/app.js}"></script>'), [])
        self.assertEqual(rule_check.check_line("a.html", 1, '<SCRIPT defer src="/js/app.js"></SCRIPT>'), [])
        messages = [v.message for v in rule_check.check_line("a.html", 1, "<style>p{}</style>")]
        self.assertTrue(any("<style>" in m for m in messages))

    def test_イベント属性_違反_ロゴのonerrorは例外(self):
        messages = [v.message for v in rule_check.check_line("a.html", 1, '<button onclick="x()">')]
        self.assertTrue(any("イベント属性" in m for m in messages))
        messages = [v.message for v in rule_check.check_line("a.html", 1, '<a th:onclick="x()">')]
        self.assertTrue(any("イベント属性" in m for m in messages))
        self.assertEqual(rule_check.check_line(
            "a.html", 1, '<img th:src="@{/images/logo.png}" onerror="this.hidden=true">'), [])
        self.assertEqual(rule_check.check_line("a.html", 1, '<button data-confirm="C1301">'), [])

    def test_important_違反(self):
        self.assert_hit("a.css", "color: red !important;", "91 5.2")
        self.assert_no_hit("a.css", "color: red;", "91 5.2")

    def test_JavaScriptのvarとfetch_違反(self):
        self.assertEqual(len(rule_check.check_line("a.js", 1, "var x = 1;")), 1)
        self.assertEqual(len(rule_check.check_line("a.js", 1, "fetch('/x');")), 1)
        self.assertEqual(len(rule_check.check_line("a.js", 1, "new XMLHttpRequest();")), 1)
        self.assertEqual(rule_check.check_line("a.js", 1, "const variable = prefetch;"), [])

    def test_設定ファイルのパスワード_違反_環境変数は違反なし(self):
        rule = "91 7章 No.11"
        self.assert_hit("src/main/resources/application.yml", "    password: secret", rule)
        self.assert_hit("application-dev.properties", "spring.datasource.password=secret", rule)
        self.assert_no_hit("application.yml", "    password: ${DB_PASSWORD}", rule)
        self.assert_no_hit("application.yml", '    password: "${DB_PASSWORD}"', rule)
        self.assert_no_hit("application.yml", "    password:", rule)
        self.assert_no_hit("messages.properties", "label.password=パスワード", rule)
        self.assert_no_hit("application.txt", "password: secret", rule)


class CheckLineConditionTests(unittest.TestCase):
    """check_line の判定。

    例外の判定（複数条件）：
    | 例外の形がある（allow が None でない） | 例外の形に当たる | 結果 |
    | 偽 | － | 違反 |
    | 真 | 真 | 違反なし |
    | 真 | 偽 | 違反 |
    """

    def test_対象でないファイル_違反なし(self):
        self.assertEqual(rule_check.check_line("README.md", 1, "@Autowired"), [])

    def test_例外なしの決まり_違反(self):
        self.assertEqual(rule_check.check_line("A.java", 3, "@Autowired"),
                         [Violation("91 7章 No.4", rule_check.LINE_RULES[3].message, "A.java", 3)])

    def test_例外に当たる_違反なし(self):
        self.assertEqual(rule_check.check_line("a.html", 1, '<img onerror="x" src="logo.png">'), [])

    def test_例外に当たらない_違反(self):
        self.assertEqual(len(rule_check.check_line("a.html", 1, '<img onerror="x" src="a.png">')), 1)

    def test_1行で複数の違反_全部出す(self):
        rules = [v.rule for v in rule_check.check_line("A.java", 1, "\t@Autowired // TODO")]
        self.assertEqual(rules, ["91 7章 No.4", "91 3.3", "91 3.2・5.1"])


class ParseAddedLinesTests(unittest.TestCase):
    """git diff の読み取り。"""

    def test_追加の行と行番号を取り出す(self):
        diff = "\n".join([
            "diff --git a/A.java b/A.java",
            "index 1..2 100644",
            "--- a/A.java",
            "+++ b/A.java",
            "@@ -3,0 +4,2 @@ class A {",
            "+    int a;",
            "+++ 内容の行（+++ で始まる）",
            "@@ -10 +12 @@",
            "-    old",
            "+    new",
            "\\ No newline at end of file",
            "diff --git a/b.html b/b.html",
            "new file mode 100644",
            "--- /dev/null",
            "+++ b/b.html",
            "@@ -0,0 +1 @@",
            "+<p>",
        ])
        self.assertEqual(rule_check.parse_added_lines(diff), {
            "A.java": [(4, "    int a;"), (5, "++ 内容の行（+++ で始まる）"), (12, "    new")],
            "b.html": [(1, "<p>")],
        })

    def test_前後の行も数える(self):
        diff = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,3 +1,3 @@\n x\n-y\n+z\n w\n+v\n"
        self.assertEqual(rule_check.parse_added_lines(diff), {"a": [(2, "z"), (4, "v")]})

    def test_消したファイルとバイナリ_取り出さない(self):
        diff = "\n".join([
            "diff --git a/old.java b/old.java",
            "deleted file mode 100644",
            "--- a/old.java",
            "+++ /dev/null",
            "@@ -1 +0,0 @@",
            "-class Old {}",
            "diff --git a/a.png b/a.png",
            "Binary files /dev/null and b/a.png differ",
        ])
        self.assertEqual(rule_check.parse_added_lines(diff), {})

    def test_読めない見出し_行番号0から(self):
        diff = "diff --git a/a b/a\n+++ b/a\n@@ 不明 @@\n+x\n"
        self.assertEqual(rule_check.parse_added_lines(diff), {"a": [(0, "x")]})

    def test_見出しより前の行_無視(self):
        self.assertEqual(rule_check.parse_added_lines("+x\n"), {})

    def test_差分全体を確かめる(self):
        diff = "diff --git a/A.java b/A.java\n+++ b/A.java\n@@ -0,0 +1,2 @@\n+@Autowired\n+int a;\n"
        self.assertEqual(rule_check.check_diff(diff),
                         [Violation("91 7章 No.4", rule_check.LINE_RULES[3].message, "A.java", 1)])


class BuildReportTests(unittest.TestCase):
    """報告の作成。"""

    def test_違反なし_空文字(self):
        self.assertEqual(rule_check.build_report([], "amasato1221", "0" * 40), "")

    def test_違反あり_メンションと表(self):
        violations = [Violation("92 2.1", "ブランチ名 a|b"), Violation("91 3.3", "TODO", "A.java", 3)]
        report = rule_check.build_report(violations, "amasato1221", "abcdef1234")
        self.assertTrue(report.startswith(rule_check.MARKER + "\n"))
        self.assertIn(f"{rule_check.DIGEST_PREFIX}{rule_check.digest(violations)} -->", report)
        self.assertIn("@amasato1221 規約チェックで **2件** の違反が見つかりました（コミット `abcdef1`）。", report)
        self.assertIn("| － | － | 92 2.1 | ブランチ名 a\\|b |", report)
        self.assertIn("| `A.java` | 3 | 91 3.3 | TODO |", report)
        self.assertNotIn("ほかに", report)

    def test_上限を超える_残りの件数を出す(self):
        violations = [Violation("91 3.3", "TODO", "A.java", i + 1) for i in range(rule_check.MAX_REPORT_ROWS + 2)]
        report = rule_check.build_report(violations, "amasato1221", "a" * 40)
        self.assertEqual(report.count("| `A.java` |"), rule_check.MAX_REPORT_ROWS)
        self.assertIn("ほかに 2件 あります。", report)

    def test_同じ違反は同じ値_違う違反は違う値(self):
        a = [Violation("91 3.3", "TODO", "A.java", 1)]
        self.assertEqual(rule_check.digest(a), rule_check.digest(list(a)))
        self.assertNotEqual(rule_check.digest(a), rule_check.digest([Violation("91 3.3", "TODO", "A.java", 2)]))

    def test_セルの改行_空白にする(self):
        self.assertEqual(rule_check._cell("a\nb|c"), "a b\\|c")


class MainTests(unittest.TestCase):
    """本物の git リポジトリを作って、最初から最後まで動かす。"""

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True, text=True).stdout.strip()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name) / "repo"
        self.repo.mkdir()
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "test")
        (self.repo / "README.md").write_text("x\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "docs: 最初")
        self.base = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "-c", "feature/SB-03-request-submit")
        self.cwd = os.getcwd()
        os.chdir(self.repo)

    def tearDown(self):
        os.chdir(self.cwd)
        self.temp.cleanup()

    def run_main(self, title, body):
        head = self.git("rev-parse", "HEAD")
        event = Path(self.temp.name) / "event.json"
        event.write_text(json.dumps({"pull_request": {
            "number": 1, "title": title, "body": body,
            "base": {"sha": self.base}, "head": {"sha": head, "ref": "feature/SB-03-request-submit"},
        }}), encoding="utf-8")
        output = Path(self.temp.name) / "report.md"
        code = rule_check.main(["--event", str(event), "--output", str(output)])
        return code, output.read_text(encoding="utf-8")

    def test_違反なし_空の報告(self):
        (self.repo / "画面.html").write_text('<p th:text="${x}"></p>\n', encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "feat: 画面を作成\n\n関係する設計書：20-SB-03")
        code, report = self.run_main("feat: 画面を作成", CheckPrBodyTests.FULL)
        self.assertEqual(code, 0)
        self.assertEqual(report, "")

    def test_違反あり_報告に全種類が出る(self):
        (self.repo / "src").mkdir()
        (self.repo / "src" / "日本語Controller.java").write_text("class A {\n    @Autowired\n}\n", encoding="utf-8")
        (self.repo / "app.log").write_text("x\n", encoding="utf-8")
        (self.repo / "README.md").unlink()
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "追加\n理由")
        code, report = self.run_main("追加", None)
        self.assertEqual(code, 0)
        self.assertIn("**6件**", report)
        self.assertIn("| `src/日本語Controller.java` | 2 | 91 7章 No.4 |", report)
        self.assertIn("| `app.log` | － | 92 1.2 |", report)
        self.assertNotIn("README.md", report)

    def test_コミットを読む_古い順(self):
        self.git("commit", "-q", "--allow-empty", "-m", "feat: 1つめ")
        self.git("commit", "-q", "--allow-empty", "-m", "feat: 2つめ\n\n本文")
        commits = rule_check.read_commits(self.base, "HEAD")
        self.assertEqual([message for _, message in commits], ["feat: 1つめ", "feat: 2つめ\n\n本文"])


if __name__ == "__main__":
    unittest.main()
