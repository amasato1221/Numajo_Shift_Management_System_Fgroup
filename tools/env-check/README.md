# 開発環境・リポジトリ連携の確認スクリプト

実装を始める前に、各自の PC で「開発環境が整っているか」「リポジトリと連携できているか」を確かめるスクリプトです。使う時期と結果の扱いは [98_実装計画書](../../docs/98_実装計画書.md) の3章にあります。

| 項目 | 内容 |
| --- | --- |
| 版 | 第1.0版（令和8年10月22日） |
| 対象読者 | 新井・栗原・川端（実行する人）、テストを確かめる人 |
| 動く環境 | Windows 11 の Windows PowerShell 5.1（Windows に最初から入っているもの） |

## 1. ファイル

| ファイル | 内容 |
| --- | --- |
| `check-environment.cmd` | ダブルクリックで開発環境の確認を実行する。結果はワークスペースのフォルダ（リポジトリの1つ上）の `env-result.txt` に保存する |
| `check-repository.cmd` | ダブルクリックでリポジトリ連携の確認を実行する。結果は同じフォルダの `repo-result.txt` |
| `check-environment.ps1` | 開発環境の確認（E01〜E15） |
| `check-repository.ps1` | リポジトリ連携の確認（R01〜R06） |
| `EnvCheck.Lib.ps1` | 確認の中身（上の2つから読み込む） |
| `tests/` | このスクリプトのテスト（4章） |

結果のファイルはリポジトリの外に保存するので、誤ってコミットされません。

## 2. 使い方

1. 96_環境構築手順書の 2〜6章を終えておく（2章の版の確認はこのスクリプトがまとめて行う）。
2. エクスプローラーでリポジトリの `tools\env-check` を開き、`check-environment.cmd` をダブルクリックする。
3. `check-repository.cmd` をダブルクリックする。初めてのプッシュでは GitHub へのサインインの画面が出るので、自分のアカウントでサインインする。
4. 最後の行が「結果：合格です。」になるまで、`→` の直し方で直して実行し直す。
5. 合格したら、`env-result.txt` と `repo-result.txt` を栗原さんに渡す（98_実装計画書 3.3）。

PowerShell から実行するときは次のとおりです（リポジトリのフォルダで）。

```powershell
> powershell -ExecutionPolicy Bypass -File tools\env-check\check-environment.ps1 -OutFile ..\env-result.txt
> powershell -ExecutionPolicy Bypass -File tools\env-check\check-repository.ps1 -OutFile ..\repo-result.txt
```

GitHub の招待を受ける前は `check-repository.ps1 -SkipPush` で、プッシュ以外を先に確かめられます（R05 は「未確認」になるので、結果は不合格のままです）。

### 2.1 結果の見方

| 表示 | 意味 | 合否 |
| --- | --- | --- |
| `[OK]` | 手順書どおり | 合格 |
| `[注意]` | 動くが手順書と違う（版の小さな違い、ロゴがまだない など）。直し方を読んで、直せるものは直す | 合格（ただし 98_実装計画書 3.3 の表に書く） |
| `[NG]` | このままでは実装やテストができない | 不合格 |
| `[未確認]` | 前の項目が NG のため確かめていない | 不合格 |

終了コードは、NG か未確認が1件でもあれば 1、なければ 0 です。

### 2.2 確かめる項目

| ID | 項目 | OK になる条件 | 手順書 |
| --- | --- | --- | --- |
| E01 | Windows の版 | ビルド番号 22000 以上（Windows 11）。違えば注意 | 96 1章 |
| E02 | Java の版 | `java -version` が 22.0.1 | 96 2.1 |
| E03 | JAVA_HOME | 環境変数があり、その中の `bin\java.exe` が 22.0.1 | 96 2.3 |
| E04 | Git の版 | 2.54。違えば注意 | 96 2.1 |
| E05 | Git の名前とメール | `user.name`・`user.email` の両方がある | 96 3章 |
| E06 | Git の改行・表示設定 | `core.autocrlf=false`（違えば NG）、`core.quotepath=false`（違えば注意） | 96 3章 |
| E07 | MySQL の版 | 8.4.5。8.4 系の別の版は注意 | 96 2.1 |
| E08 | MySQL のサービス | `MySQL84` が動いている | 96 2.1 |
| E09 | 環境変数 DB_PASSWORD | ある（値は表示しない） | 96 2.3 |
| E10 | DB への接続 | `shift_app` で接続でき、`shift_management`・`shift_test` がある。E07〜E09 のどれかが NG なら未確認 | 96 6.1 |
| E11 | バックアップの権限 | `SHOW_ROUTINE` がある。なければ注意 | 96 6.1 |
| E12 | Eclipse と Lombok | `C:\pleiades\2025-12\eclipse\eclipse.exe` があり、`eclipse.ini` に Lombok の `-javaagent` がある | 96 2.2 |
| E13 | ポート 8080 | 空いている。使われていれば注意 | 96 10章 No.3 |
| E14 | バックアップの保存先 | `C:\shift-management\backup` がある | 96 6.2 |
| E15 | インターネット接続 | `github.com` と Maven Central（ライブラリのダウンロード先）に届く | － |
| R01 | リポジトリのフォルダ | フォルダがあり、Git のリポジトリになっている。NG なら R02〜R06 は未確認 | 96 3章 |
| R02 | リモート（origin） | 班のリポジトリの URL（大文字小文字・末尾の `.git` は問わない） | 92 1章 |
| R03 | GitHub から取得 | `git fetch` ができる。NG なら R04・R05 は未確認 | － |
| R04 | main が最新か | 手元の `main` が GitHub と同じ。遅れていれば注意、GitHub にないコミットがあれば NG（main への直接コミット） | 92 2.1 |
| R05 | GitHub へのプッシュ | `main` と同じ場所を指す一時ブランチ `test/env-check-名前-日時` を作り、すぐ消せる。ファイルは何も変えない。作れたが消せなければ注意 | 98 4章 |
| R06 | ロゴの扱い | `logo.png` が `.gitignore` で除外されている（されていなければ NG）。手元になければ注意 | 10 5.4 |

DB のパスワードはコマンドラインに出さず、一時ファイルで `mysql` に渡し、実行後すぐに消します（94_セキュリティ設計書 3.10）。

## 3. 直し方が分からないとき

`env-result.txt`・`repo-result.txt` を新井さんに見せてください。パスワードは結果に出ないので、そのまま渡してかまいません。

## 4. このスクリプトのテスト

97_テスト計画書 2章の4つの網羅（命令・判定条件・条件・複数条件）で確かめています。

### 4.1 実行のしかた

Pester などの外部モジュールは使わず、`tests/run-tests.ps1` だけで動きます。PowerShell 7（`pwsh`）で実行します。

```
pwsh -File tools/env-check/tests/run-tests.ps1
```

| ファイル | 内容 |
| --- | --- |
| `tests/run-tests.ps1` | テストの道具、命令網羅の計測、組み合わせ表との照合、結果の表示 |
| `tests/Fakes.ps1` | 外とのやりとり（コマンド・ファイル・環境変数・サービスなど）を、決めた値を返す関数に置き換える |
| `tests/LogicTests.ps1` | 判定のテスト（置き換えを使う） |
| `tests/WrapperTests.ps1` | 外とのやりとりの関数と、実行用スクリプト（`check-*.ps1`）のテスト（本物を動かす。手元に HTTP サーバーを立てて届く・届かないを確かめる） |

### 4.2 合格の基準と結果

| 網羅 | 確かめ方 | 結果（令和8年10月22日、Linux の PowerShell 7.4.6） |
| --- | --- | --- |
| 命令網羅（C0） | すべての命令の位置にブレークポイントを置き、通ったかを記録する | 418 / 418（100%） |
| 判定条件網羅（C1） | 4.4 の表で、すべての判定の真と偽にテストがあるか | 表の全行にテストあり |
| 条件網羅（C2） | 4.4 の表で、`-and`・`-or` の1つ1つの条件の真と偽にテストがあるか | 表の全行にテストあり |
| 複数条件網羅（MCC） | 4.4 の表で、条件の真偽の組み合わせすべてにテストがあるか | 157 / 157 |
| テスト | 全件成功 | 124 件成功、失敗 0 件 |

各テストは、通る組み合わせの ID（`D12-TF` など）を `-Covers` に書きます。`run-tests.ps1` は、成功したテストの ID と 4.4 の表を照らし合わせ、テストのない組み合わせがあれば不合格にします。テストを1件消すと、その組み合わせが「テストがない組み合わせ」として出ることも確かめました（D01-F、D49-F で確認）。

### 4.3 除外したもの

| 対象 | 理由 | 代わりの確認 |
| --- | --- | --- |
| `Get-OsInfo`・`Get-ServiceStatus`・`Test-PortListening` | Windows にしかないコマンド（`Get-CimInstance`・`Get-Service`・`Get-NetTCPConnection`）を呼ぶだけの関数で、Linux では動かない | 新井の PC での初回実行（98_実装計画書 3.2）で、E01・E08・E13 の表示が実際の状態と合うことを確かめる |
| Windows PowerShell 5.1 での動き | テストは Linux の PowerShell 7 で行った。5.1 で使えない書き方（三項演算子・`??` など）は使っていない | 同じく初回実行で確かめる |

### 4.4 判定の組み合わせ表

T＝真、F＝偽。`-and`・`-or` で後ろの条件が評価されない組み合わせ（短絡評価）も、入力を用意してテストしています。

| ID | 関数 | 判定（条件） | 組み合わせ |
| --- | --- | --- | --- |
| W1 | Invoke-Tool | コマンドがない | T、F |
| W2 | Test-HttpsReachable | 返事あり（成功）／失敗したが返事（`Response`）がある | OK（成功）、T（404）、F（届かない） |
| D01 | Test-OsVersion | ビルド番号 ≧ 22000 | T（26100・22000）、F（21999） |
| D02 | Get-JavaVersionFromText | `version "…"` がある | T、F |
| D03 | Test-JavaVersion | 終了コード ≠ 0 | T、F |
| D04 | Test-JavaVersion | 版 ＝ 22.0.1 | T、F |
| D05 | Test-JavaHome | JAVA_HOME が null か空 | T（null・空の両方）、F |
| D06 | Test-JavaHome | java.exe がない | T、F |
| D07 | Test-JavaHome | 版 ＝ 22.0.1 | T、F |
| D08 | Test-GitVersion | 終了コード ≠ 0 | T、F |
| D09 | Test-GitVersion | `git version x.y` がある | T、F |
| D10 | Test-GitVersion | 版 ＝ 2.54 | T、F |
| D11 | Get-GitGlobalConfig | 終了コード ≠ 0 | T、F |
| D12 | Test-GitIdentity | (a) 名前あり `-and` (b) メールあり | TT、TF、FT、FF |
| D13 | Test-GitIdentity | 名前なし | T、F |
| D14 | Test-GitIdentity | メールなし | T、F |
| D15 | Test-GitSettings | (a) autocrlf＝false `-and` (b) quotepath＝false | TT、TF、FT、FF |
| D16 | Test-GitSettings | autocrlf ≠ false | T、F |
| D17 | Test-MySqlVersion | mysql.exe がない | T、F |
| D18 | Test-MySqlVersion | `Ver x.y.z` がある | T、F |
| D19 | Test-MySqlVersion | 版 ＝ 8.4.5 | T、F |
| D20 | Test-MySqlVersion | 8.4. で始まる | T、F |
| D21 | Test-MySqlService | サービスがない | T、F |
| D22 | Test-MySqlService | Running | T、F |
| D23 | Test-DbPassword | DB_PASSWORD が null か空 | T、F |
| D24 | Test-DbConnection | 終了コード ≠ 0 | T、F |
| D25 | Test-DbConnection | （DB ごとに）見つかった中にない | T、F |
| D26 | Test-DbConnection | ない DB が0件 | T、F |
| D27 | Test-DbBackupGrant | (a) 終了コード＝0 `-and` (b) SHOW_ROUTINE がある | TT、TF、FT、FF |
| D28 | Test-Eclipse | eclipse.exe がない | T、F |
| D29 | Test-Eclipse | (a) eclipse.ini がある `-and` (b) Lombok の行がある | TT、TF、FT、FF |
| D30 | Test-AppPort | 8080 が使われている | T、F |
| D31 | Test-BackupDir | 保存先がある | T、F |
| D32 | Test-Network | （URL ごとに）届かない | T、F |
| D33 | Test-Network | 届かない URL が0件 | T、F |
| D34 | Invoke-EnvironmentCheck | (a) MySQL の版が NG でない `-and` (b) サービス OK `-and` (c) DB_PASSWORD OK | TTT、TTF、TFT、TFF、FTT、FTF、FFT、FFF |
| D35 | Test-RepoFolder | フォルダがない | T、F |
| D36 | Test-RepoFolder | (a) 終了コード＝0 `-and` (b) 出力＝true | TT、TF、FT、FF |
| D37 | ConvertTo-NormalizedRepoUrl | `/` で終わる | T、F |
| D38 | ConvertTo-NormalizedRepoUrl | `.git` で終わる | T、F（D37 との4通りすべて） |
| D39 | Test-RepoRemote | (a) 終了コード＝0 `-and` (b) URL が同じ | TT、TF、FT、FF |
| D40 | Test-RepoFetch | 終了コード＝0 | T、F |
| D41 | Test-RepoMainSync | (a) 終了コード ≠ 0 `-or` (b) 「数字 数字」の形でない | TT、TF、FT、FF |
| D42 | Test-RepoMainSync | 先に進んでいる ＞ 0 | T（遅れ 0・遅れあり の両方）、F |
| D43 | Test-RepoMainSync | 遅れている ＞ 0 | T、F |
| D44 | Get-BranchTag | user.name から作った文字が空 | T、F |
| D45 | Get-BranchTag | USERNAME から作った文字が空 | T、F |
| D46 | Test-RepoPush | -SkipPush | T、F |
| D47 | Test-RepoPush | 一時ブランチの作成が失敗 | T、F |
| D48 | Test-RepoPush | 出力が 403・denied・not found | T、F |
| D49 | Test-RepoPush | 一時ブランチを消せた | T、F |
| D50 | Test-RepoLogo | 除外されていない | T（ロゴあり・なしの両方）、F |
| D51 | Test-RepoLogo | ロゴがある | T、F |
| D52 | Invoke-RepositoryCheck | R01 が OK でない | T、F |
| D53 | Invoke-RepositoryCheck | R03 が OK | T、F |
| D54 | Get-StatusLabel | switch | OK、WARN、NG、その他 |
| D55 | Format-CheckReport | foreach（1回以上回る） | T、F（0件） |
| D56 | Format-CheckReport | (a) OK でない `-and` (b) 直し方がある | TT、TF、FT、FF |
| D57 | Format-CheckReport | 件数を数える4つの Where-Object | 各状態が混ざった結果で T・F の両方 |
| D58 | Format-CheckReport | (a) NG＝0 `-and` (b) 未確認＝0 | TT、TF、FT、FF |
| D59 | Get-CheckExitCode | (a) NG `-or` (b) 未確認 | TF、FT、FF（TT は1つの結果が NG と未確認を同時に持てないため、ありえない） |
| D60 | Get-CheckExitCode | 悪い結果 ＞ 0 | T、F |
| M1 | check-environment.ps1 | 保存するファイルの指定がある | T、F |
| M2 | check-repository.ps1 | 保存するファイルの指定がある | T、F |
| M3 | check-repository.ps1 | RepoPath を省いた | T、F |
