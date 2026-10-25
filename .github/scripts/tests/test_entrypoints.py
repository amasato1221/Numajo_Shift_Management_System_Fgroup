"""スクリプトとして実行したとき（python rule_check.py …）の入口のテスト。"""

import io
import runpy
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parent.parent


class EntrypointTests(unittest.TestCase):
    """--help で起動し、main() の戻り値で終わることを確かめる。"""

    def run_script(self, name):
        with mock.patch.object(sys, "argv", [name, "--help"]), redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(SystemExit) as raised:
                runpy.run_path(str(SCRIPTS / name), run_name="__main__")
        return raised.exception.code, output.getvalue()

    def test_規約チェック_使い方を出して0(self):
        code, output = self.run_script("rule_check.py")
        self.assertEqual(code, 0)
        self.assertIn("--event", output)

    def test_通知_使い方を出して0(self):
        code, output = self.run_script("notify_pr.py")
        self.assertEqual(code, 0)
        self.assertIn("--report", output)


if __name__ == "__main__":
    unittest.main()
