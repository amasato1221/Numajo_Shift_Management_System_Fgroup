"""プルリクエストが 91_コーディング規約・92_Git運用規約 に合っているかを確かめる。

GitHub Actions の「規約チェック」から呼ばれ、見つかった違反を Markdown にまとめてファイルに書き出す。
違反があってもこのスクリプトは失敗にしない（マージは止めず、新井に知らせるだけにするため）。
機械で判定できる項目だけを確かめる。設計書どおりかどうかは、今までどおり新井が確かめる（92_Git運用規約 5章）。
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Callable

# PRコメントを見分けるための目印（notify_pr.py と同じ値）
MARKER = "<!-- rule-check -->"
DIGEST_PREFIX = "<!-- rule-check-digest:"

# 1つのコメントに載せる違反の上限（GitHub のコメントは 65536 文字まで）
MAX_REPORT_ROWS = 100


@dataclass(frozen=True)
class Violation:
    """違反1件。"""

    # 規約の場所（例：91 7章 No.3）
    rule: str
    # 何が違反か、どう直すか
    message: str
    # ファイルのパス（ファイルに関係ないときは空）
    path: str = ""
    # 行番号（行に関係ないときは0）
    line: int = 0


# ---------------------------------------------------------------------------
# 92_Git運用規約：ブランチ名（2.1）
# ---------------------------------------------------------------------------

# 単語は英小文字・数字か、画面ID（SB-03、SB-03sp など）
_BRANCH_WORD = r"(?:[a-z0-9]+|[A-Z]{2}-\d{2}(?:sp)?)"
BRANCH_PATTERN = re.compile(rf"(?:feature|docs|fix)/{_BRANCH_WORD}(?:-{_BRANCH_WORD})*")


def check_branch(name: str) -> list[Violation]:
    """ブランチ名が「種類/画面ID（ないときは common）-英小文字の説明」の形か確かめる。"""
    if BRANCH_PATTERN.fullmatch(name):
        return []
    return [Violation(
        "92 2.1",
        f"ブランチ名 `{name}` が「種類/画面ID（ないときは common）-英小文字の説明」の形ではありません"
        "（種類は feature・docs・fix。例：`feature/SB-03-request-submit`）",
    )]


# ---------------------------------------------------------------------------
# 92_Git運用規約：コミットメッセージ・PRタイトル（3.1・4.2）
# ---------------------------------------------------------------------------

SUBJECT_PATTERN = re.compile(r"(?:feat|fix|docs|test|refactor|chore): (\S.*)")
MAX_SUBJECT_LENGTH = 50
# git pull origin main で作られるマージコミットは対象外（92 4.1 で認めている）
MERGE_SUBJECT_PATTERN = re.compile(r"Merge (?:branch|remote-tracking branch|pull request) ")


def check_subject(subject: str, where: str) -> list[str]:
    """1行目が「種類: 何をしたか（50文字まで）」の形か確かめ、違反の説明を返す。"""
    match = SUBJECT_PATTERN.fullmatch(subject)
    if match is None:
        return [f"{where}が「種類: 何をしたか」の形ではありません"
                "（種類は feat・fix・docs・test・refactor・chore。`:` の後に半角スペース）"]
    length = len(match.group(1))
    if length > MAX_SUBJECT_LENGTH:
        return [f"{where}の「何をしたか」が{length}文字です（{MAX_SUBJECT_LENGTH}文字まで）"]
    return []


def check_pr_title(title: str) -> list[Violation]:
    """PRタイトルを確かめる（コミットメッセージの1行目と同じ形。92 4.2）。"""
    return [Violation("92 4.2", message) for message in check_subject(title, "PRタイトル")]


def check_commits(commits: list[tuple[str, str]]) -> list[Violation]:
    """コミットメッセージを確かめる。commits は（SHA, メッセージ全体）の並び。"""
    violations = []
    for sha, message in commits:
        lines = message.splitlines()
        subject = lines[0] if lines else ""
        if MERGE_SUBJECT_PATTERN.match(subject):
            continue
        where = f"コミット `{sha[:7]}` の1行目"
        violations.extend(Violation("92 3.1", text) for text in check_subject(subject, where))
        if len(lines) > 1 and lines[1].strip() != "":
            violations.append(Violation("92 3.1", f"コミット `{sha[:7]}` の2行目が空いていません（1行目の次は1行空ける）"))
    return violations


# ---------------------------------------------------------------------------
# 92_Git運用規約：PR本文（4.2）
# ---------------------------------------------------------------------------

# 「画面のスクリーンショット」は画面を変えたときだけなので確かめない
REQUIRED_SECTIONS = ("概要", "関係する設計書", "確認したこと", "設計書と違うところ")
HEADING_PATTERN = re.compile(r"^##[ \t]+(.+?)\s*$", re.MULTILINE)


def check_pr_body(body: str | None) -> list[Violation]:
    """PR本文に決まった見出しがそろっているか確かめる。"""
    headings = {match.group(1) for match in HEADING_PATTERN.finditer(body or "")}
    missing = [section for section in REQUIRED_SECTIONS if section not in headings]
    if not missing:
        return []
    names = "、".join(f"`## {section}`" for section in missing)
    return [Violation("92 4.2", f"PR本文に見出し {names} がありません")]


# ---------------------------------------------------------------------------
# 92_Git運用規約：リポジトリに入れないもの（1.2）
# ---------------------------------------------------------------------------

FORBIDDEN_DIRS = ("target", "bin", ".metadata", ".settings", ".vscode", "backup")
FORBIDDEN_NAMES = (".project", ".classpath", ".factorypath", "Thumbs.db", ".DS_Store")
FORBIDDEN_SUFFIXES = (".log", ".sql.bak")


def forbidden_reason(path: str) -> str | None:
    """リポジトリに入れてはいけないファイルなら理由を、そうでなければ None を返す。"""
    parts = PurePosixPath(path).parts
    name = parts[-1]
    if name == "logo.png":
        return "ロゴ画像は公開リポジトリに置きません（91 7章 No.12）"
    for directory in parts[:-1]:
        if directory in FORBIDDEN_DIRS:
            return f"`{directory}/` の中のファイルはリポジトリに入れません"
    if name in FORBIDDEN_NAMES or name.endswith(FORBIDDEN_SUFFIXES):
        return f"`{name}` はリポジトリに入れません"
    return None


def check_files(paths: list[str]) -> list[Violation]:
    """追加・変更されたファイルの中に、入れてはいけないものがないか確かめる。"""
    violations = []
    for path in paths:
        reason = forbidden_reason(path)
        if reason is not None:
            violations.append(Violation("92 1.2", reason, path))
    return violations


# ---------------------------------------------------------------------------
# 91_コーディング規約：追加した行の書き方
# ---------------------------------------------------------------------------


def _suffix(*suffixes: str) -> Callable[[str], bool]:
    """拡張子で対象のファイルを決める関数を作る。"""
    return lambda path: path.endswith(suffixes)


def _is_controller(path: str) -> bool:
    return path.endswith("Controller.java")


def _is_application_config(path: str) -> bool:
    name = PurePosixPath(path).name
    return name.startswith("application") and name.endswith((".yml", ".yaml", ".properties"))


@dataclass(frozen=True)
class LineRule:
    """追加した1行に当てはめる決まり。"""

    rule: str
    message: str
    # 対象のファイルか
    applies: Callable[[str], bool]
    # この形があれば違反
    pattern: re.Pattern
    # この形も含む行は違反にしない（例外）
    allow: re.Pattern | None = None


_JAVA = _suffix(".java")
_HTML = _suffix(".html")
_CSS = _suffix(".css")
_JS = _suffix(".js")
_CODE = _suffix(".java", ".html", ".css", ".js")

LINE_RULES = (
    LineRule("91 7章 No.3", "現在日時は `now(clock)` で取ります（引数なしの `now()` は禁止）", _JAVA,
             re.compile(r"\b(?:LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime|Instant)\.now\(\s*\)")),
    LineRule("91 3.4", "`new Date()`・`System.currentTimeMillis()` は使いません（`LocalDateTime.now(clock)`）", _JAVA,
             re.compile(r"\bnew\s+Date\s*\(|\bSystem\.currentTimeMillis\s*\(")),
    LineRule("91 3.5", "`java.util.Date`・`Calendar` は使いません（`java.time` を使う）", _JAVA,
             re.compile(r"^\s*import\s+java\.util\.(?:Date|Calendar)\s*;")),
    LineRule("91 7章 No.4", "`@Autowired` は使いません（`private final` ＋ `@RequiredArgsConstructor`）", _JAVA,
             re.compile(r"@Autowired\b")),
    LineRule("91 7章 No.5", "`@Data`・`@Builder` は使いません（`@Getter`・`@Setter`）", _JAVA,
             re.compile(r"@(?:Data|Builder)(?!\w)")),
    LineRule("91 7章 No.14", "`System.out.println`・`printStackTrace` は使いません（`log` を使う）", _JAVA,
             re.compile(r"\bSystem\.(?:out|err)\.print|\.printStackTrace\s*\(")),
    LineRule("91 7章 No.13", "`@Disabled` でテストを止めません（直してから通す）", _JAVA,
             re.compile(r"@Disabled\b")),
    LineRule("91 3.2", "ワイルドカード import は使いません", _JAVA,
             re.compile(r"^\s*import\s+(?!static\s)[\w.]+\.\*\s*;")),
    LineRule("91 7章 No.6", "Controller から Repository を使いません（Service を通す）", _is_controller,
             re.compile(r"\.repository\.|\b\w+Repository\b")),
    LineRule("91 3.3", "`TODO` を残したままマージしません", _CODE,
             re.compile(r"\bTODO\b")),
    LineRule("91 3.2・5.1", "字下げにタブを使いません（半角スペース4つ）", _CODE,
             re.compile(r"^ *\t")),
    LineRule("91 3.2", "1行が120文字を超えています", _JAVA,
             re.compile(r"^.{121,}$")),
    LineRule("91 7章 No.8", "`th:utext` は使いません（`th:text`）", _HTML,
             re.compile(r"\bth:utext\b")),
    LineRule("91 5.1", "インラインの `<script>` は書きません（`app.js` に書く）", _HTML,
             re.compile(r"<script\b(?![^>]*\bsrc\s*=)", re.IGNORECASE)),
    LineRule("91 5.1", "インラインの `<style>` は書きません（`app.css` に書く）", _HTML,
             re.compile(r"<style\b", re.IGNORECASE)),
    LineRule("91 5.1", "`onclick` などのイベント属性は書きません（`app.js` で `data-` 属性に付ける）", _HTML,
             re.compile(r"\s(?:th:)?on[a-z]+\s*=", re.IGNORECASE),
             # 例外：logo.png の onerror（91 5.1）
             allow=re.compile(r"logo\.png")),
    LineRule("91 5.2", "`!important` は使いません（印刷用 `@media print` の中なら問題ありません）", _CSS,
             re.compile(r"!important")),
    LineRule("91 5.3", "`var` は使いません（`const`・`let`）", _JS,
             re.compile(r"\bvar\s+[A-Za-z_$]")),
    LineRule("91 5.3", "`fetch`・`XMLHttpRequest`（Ajax）は使いません", _JS,
             re.compile(r"\bfetch\s*\(|\bXMLHttpRequest\b")),
    LineRule("91 7章 No.11", "パスワードを設定ファイルに書きません（環境変数 `${DB_PASSWORD}` を使う）",
             _is_application_config,
             re.compile(r"(?i)password\s*[:=][ \t]*(?![\"']?\$\{)\S")),
)


def check_line(path: str, line_no: int, text: str) -> list[Violation]:
    """追加した1行を、当てはまる決まりすべてで確かめる。"""
    violations = []
    for rule in LINE_RULES:
        if not rule.applies(path):
            continue
        if rule.pattern.search(text) is None:
            continue
        if rule.allow is not None and rule.allow.search(text):
            continue
        violations.append(Violation(rule.rule, rule.message, path, line_no))
    return violations


# ---------------------------------------------------------------------------
# git diff の読み取り
# ---------------------------------------------------------------------------

HUNK_PATTERN = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def parse_added_lines(diff_text: str) -> dict[str, list[tuple[int, str]]]:
    """git diff の出力から、ファイルごとに追加した行（行番号, 内容）を取り出す。"""
    added: dict[str, list[tuple[int, str]]] = {}
    path = None
    in_header = False
    line_no = 0
    for raw in diff_text.splitlines():
        if raw.startswith("diff --git "):
            # ファイルの見出しの始まり。内容の行に「+++ 」があっても見出しと間違えないようにする
            in_header = True
            path = None
            continue
        if in_header:
            if raw.startswith("+++ "):
                target = raw[4:]
                # 削除されたファイルは「+++ /dev/null」
                path = target[2:] if target.startswith("b/") else None
                continue
            if not raw.startswith("@@"):
                continue
            in_header = False
        if raw.startswith("@@"):
            match = HUNK_PATTERN.match(raw)
            line_no = int(match.group(1)) if match else 0
            continue
        if path is None:
            continue
        if raw.startswith("+"):
            added.setdefault(path, []).append((line_no, raw[1:]))
            line_no += 1
        elif raw.startswith(" "):
            line_no += 1
        # 「-」（消した行）と「\ No newline at end of file」は新しい行番号を進めない
    return added


def check_diff(diff_text: str) -> list[Violation]:
    """追加した行すべてを確かめる。"""
    violations = []
    for path, lines in parse_added_lines(diff_text).items():
        for line_no, text in lines:
            violations.extend(check_line(path, line_no, text))
    return violations


# ---------------------------------------------------------------------------
# 報告の作成
# ---------------------------------------------------------------------------


def _cell(text: str) -> str:
    """表のセルに入れられるようにする。"""
    return text.replace("|", "\\|").replace("\n", " ")


def digest(violations: list[Violation]) -> str:
    """違反の内容から短い値を作る（前と同じ結果か比べるため）。"""
    source = "\n".join(f"{v.rule}\t{v.message}\t{v.path}\t{v.line}" for v in violations)
    return hashlib.sha256(source.encode("utf-8")).hexdigest()[:16]


def build_report(violations: list[Violation], admin: str, head_sha: str) -> str:
    """違反の一覧を PR コメント用の Markdown にする。違反がなければ空文字を返す。"""
    if not violations:
        return ""
    lines = [
        MARKER,
        f"{DIGEST_PREFIX}{digest(violations)} -->",
        f"@{admin} 規約チェックで **{len(violations)}件** の違反が見つかりました（コミット `{head_sha[:7]}`）。",
        "",
        "作成者の方は、下の表を見て直し、もう一度プッシュしてください。"
        "直すとこのコメントが書き換わります。マージは止めていません。",
        "",
        "| ファイル | 行 | 規約 | 内容 |",
        "| --- | --- | --- | --- |",
    ]
    for violation in violations[:MAX_REPORT_ROWS]:
        path = f"`{_cell(violation.path)}`" if violation.path else "－"
        line = str(violation.line) if violation.line else "－"
        lines.append(f"| {path} | {line} | {violation.rule} | {_cell(violation.message)} |")
    if len(violations) > MAX_REPORT_ROWS:
        lines.append("")
        lines.append(f"ほかに {len(violations) - MAX_REPORT_ROWS}件 あります。上の分を直してからもう一度確かめてください。")
    lines.append("")
    lines.append("<sub>規約は docs/91_コーディング規約.md・docs/92_Git運用規約.md。"
                 "判定は .github/scripts/rule_check.py（機械で判定できる項目だけ）。</sub>")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# 実行
# ---------------------------------------------------------------------------


def run_git(*args: str) -> str:
    """git を実行して標準出力を返す。日本語のファイル名をそのまま出すため quotepath を切る。"""
    result = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args],
        check=True, capture_output=True, text=True, encoding="utf-8",
    )
    return result.stdout


def read_commits(base: str, head: str) -> list[tuple[str, str]]:
    """base から head までのコミット（SHA, メッセージ）を古い順に返す。"""
    output = run_git("log", "--reverse", "--format=%H%x1f%B%x1e", f"{base}..{head}")
    commits = []
    for record in output.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        sha, _, message = record.partition("\x1f")
        commits.append((sha, message.strip("\n")))
    return commits


def collect_violations(pull_request: dict) -> list[Violation]:
    """プルリクエストの情報と git の内容から、違反をすべて集める。"""
    base = pull_request["base"]["sha"]
    head = pull_request["head"]["sha"]
    changed = run_git("diff", "--name-only", "--diff-filter=d", f"{base}...{head}").splitlines()
    diff_text = run_git("diff", "--no-color", "--no-ext-diff", "--unified=0", f"{base}...{head}")
    violations = []
    violations.extend(check_branch(pull_request["head"]["ref"]))
    violations.extend(check_pr_title(pull_request.get("title") or ""))
    violations.extend(check_pr_body(pull_request.get("body")))
    violations.extend(check_commits(read_commits(base, head)))
    violations.extend(check_files(changed))
    violations.extend(check_diff(diff_text))
    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="プルリクエストの規約チェック")
    parser.add_argument("--event", required=True, help="GitHub のイベント JSON（GITHUB_EVENT_PATH）")
    parser.add_argument("--output", required=True, help="報告（Markdown）を書き出すファイル")
    parser.add_argument("--admin", default="amasato1221", help="知らせる管理者の GitHub ログイン名")
    args = parser.parse_args(argv)

    with open(args.event, encoding="utf-8") as file:
        pull_request = json.load(file)["pull_request"]
    violations = collect_violations(pull_request)
    report = build_report(violations, args.admin, pull_request["head"]["sha"])
    with open(args.output, "w", encoding="utf-8") as file:
        file.write(report)
    print(f"規約チェック：違反 {len(violations)}件")
    for violation in violations:
        where = violation.path
        if violation.line:
            where += f":{violation.line}"
        print(f"  {where or '－'} [{violation.rule}] {violation.message}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
