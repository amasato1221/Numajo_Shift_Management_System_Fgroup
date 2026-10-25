"""規約チェックの結果をプルリクエストのコメントで管理者（新井）に知らせる。

rule_check.py が書き出した報告を読み、PR に1件だけコメントを置く。
- 違反が新しく見つかった・内容が変わった：古いコメントを消して書き直す（メンションの通知を出すため）
- 違反が前と同じ：同じコメントを書き換える（コミットの番号だけ新しくする。通知は出さない）
- 違反がなくなった：同じコメントを「直りました」に書き換える
- 違反が一度もない：何もしない
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Callable, NamedTuple

from rule_check import DIGEST_PREFIX, MARKER

RESOLVED_MARK = "<!-- rule-check-resolved -->"
# GitHub Actions の GITHUB_TOKEN で書いたコメントの書き手
BOT_LOGIN = "github-actions[bot]"
PER_PAGE = 100


class Action(NamedTuple):
    """コメントに対して行うこと。"""

    # create（新しく書く）／recreate（消して書き直す）／update（書き換える）／none（何もしない）
    kind: str
    comment_id: int | None = None
    body: str = ""


def extract_digest(body: str) -> str | None:
    """コメントの本文から、違反の内容を表す値を取り出す。"""
    start = body.find(DIGEST_PREFIX)
    if start < 0:
        return None
    start += len(DIGEST_PREFIX)
    end = body.find(" -->", start)
    if end < 0:
        return None
    return body[start:end]


def find_report_comment(comments: list[dict]) -> dict | None:
    """前に書いた規約チェックのコメントを探す。"""
    for comment in comments:
        user = comment.get("user") or {}
        if user.get("login") == BOT_LOGIN and MARKER in (comment.get("body") or ""):
            return comment
    return None


def resolved_body(head_sha: str) -> str:
    """違反がなくなったときのコメント。"""
    return (f"{MARKER}\n{RESOLVED_MARK}\n"
            f"規約チェックの違反はすべて直りました（コミット `{head_sha[:7]}`）。\n")


def decide(report: str, existing: dict | None, head_sha: str) -> Action:
    """報告と前のコメントから、行うことを決める。"""
    if report:
        if existing is None:
            return Action("create", None, report)
        if extract_digest(existing.get("body") or "") == extract_digest(report):
            return Action("update", existing["id"], report)
        return Action("recreate", existing["id"], report)
    if existing is None or RESOLVED_MARK in (existing.get("body") or ""):
        return Action("none")
    return Action("update", existing["id"], resolved_body(head_sha))


class GitHubClient:
    """PR のコメントを読み書きする（GitHub REST API）。"""

    def __init__(self, token: str, repository: str,
                 opener: Callable = urllib.request.urlopen, api: str = "https://api.github.com"):
        self._token = token
        self._base = f"{api}/repos/{repository}"
        self._opener = opener

    def _request(self, method: str, path: str, data: dict | None = None):
        body = None if data is None else json.dumps(data).encode("utf-8")
        request = urllib.request.Request(self._base + path, data=body, method=method, headers={
            "Authorization": f"Bearer {self._token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        })
        with self._opener(request) as response:
            text = response.read().decode("utf-8")
        return json.loads(text) if text else None

    def list_comments(self, number: int) -> list[dict]:
        comments = []
        page = 1
        while True:
            items = self._request("GET", f"/issues/{number}/comments?per_page={PER_PAGE}&page={page}")
            comments.extend(items)
            if len(items) < PER_PAGE:
                return comments
            page += 1

    def create_comment(self, number: int, body: str) -> None:
        self._request("POST", f"/issues/{number}/comments", {"body": body})

    def update_comment(self, comment_id: int, body: str) -> None:
        self._request("PATCH", f"/issues/comments/{comment_id}", {"body": body})

    def delete_comment(self, comment_id: int) -> None:
        self._request("DELETE", f"/issues/comments/{comment_id}")


def apply(client: GitHubClient, number: int, action: Action) -> None:
    """決めたことを GitHub に反映する。"""
    if action.kind == "recreate":
        client.delete_comment(action.comment_id)
        client.create_comment(number, action.body)
    elif action.kind == "create":
        client.create_comment(number, action.body)
    elif action.kind == "update":
        client.update_comment(action.comment_id, action.body)


def main(argv: list[str] | None = None, client_factory: Callable = GitHubClient) -> int:
    parser = argparse.ArgumentParser(description="規約チェックの結果を PR コメントで知らせる")
    parser.add_argument("--event", required=True, help="GitHub のイベント JSON（GITHUB_EVENT_PATH）")
    parser.add_argument("--report", required=True, help="rule_check.py が書き出した報告")
    args = parser.parse_args(argv)

    with open(args.event, encoding="utf-8") as file:
        pull_request = json.load(file)["pull_request"]
    with open(args.report, encoding="utf-8") as file:
        report = file.read()

    client = client_factory(os.environ["GITHUB_TOKEN"], os.environ["GITHUB_REPOSITORY"])
    number = pull_request["number"]
    try:
        existing = find_report_comment(client.list_comments(number))
        action = decide(report, existing, pull_request["head"]["sha"])
        apply(client, number, action)
    except urllib.error.HTTPError as error:
        if error.code == 403:
            # フォークからの PR では書き込みの権限がない。チェックの結果はログに出ているので失敗にしない
            print(f"::warning::PR にコメントを書く権限がありません（HTTP {error.code}）。結果はログを見てください")
            return 0
        print(f"::error::PR へのコメントに失敗しました（HTTP {error.code}）")
        return 1
    print(f"PR #{number} のコメント：{action.kind}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
