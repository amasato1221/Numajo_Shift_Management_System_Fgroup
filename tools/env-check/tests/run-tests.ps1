# env-check のテストを実行し、命令網羅（C0）と判定の組み合わせ（C1・C2・複数条件網羅）を確かめる
# 使い方：pwsh -File tools/env-check/tests/run-tests.ps1
# 結果：全テスト成功・命令網羅 100%・組み合わせ表の全行をテスト済みなら終了コード 0
param()

$ErrorActionPreference = 'Continue'
$TestDir = $PSScriptRoot
$LibDir = Split-Path -Parent $TestDir
$LibFile = Join-Path $LibDir 'EnvCheck.Lib.ps1'
$TargetFiles = @($LibFile, (Join-Path $LibDir 'check-environment.ps1'), (Join-Path $LibDir 'check-repository.ps1'))

# Windows でだけ動くため、命令網羅の対象から外す関数（README.md 4.3。Windows での初回実行で確かめる）
$ExcludedFunctions = @('Get-OsInfo', 'Get-ServiceStatus', 'Test-PortListening')

# ---------------------------------------------------------------
# 1. テストの道具
# ---------------------------------------------------------------

$script:Passed = 0
$script:Failed = New-Object System.Collections.ArrayList
$script:Covered = @{}

function Test-Case {
    param([string]$Name, [string[]]$Covers = @(), [object]$Data = $null, [scriptblock]$Body)
    try {
        $null = & $Body $Data
        $script:Passed++
        foreach ($id in $Covers) {
            $script:Covered[$id] = $true
        }
    } catch {
        [void]$script:Failed.Add("$Name：$($_.Exception.Message)")
    }
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Message = '')
    if (-not ($Expected -ceq $Actual)) {
        throw "$Message 期待：[$Expected] 実際：[$Actual]"
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message = '条件が偽')
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Null {
    param($Value)
    if ($null -ne $Value) {
        throw "null ではない：[$Value]"
    }
}

function Assert-Match {
    param([string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "[$Text] が /$Pattern/ に合わない"
    }
}

# ---------------------------------------------------------------
# 2. 命令網羅の準備：すべての命令の位置にブレークポイントを置き、通ったら記録する
# ---------------------------------------------------------------

$global:EnvCheckHits = @{}
$points = New-Object System.Collections.ArrayList
foreach ($file in $TargetFiles) {
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$null, [ref]$null)
    $statements = $ast.FindAll({
            param($node)
            # return・exit の命令は、その中の式（CommandBaseAst）で数える
            $node -is [System.Management.Automation.Language.CommandBaseAst]
        }, $true)
    foreach ($statement in $statements) {
        # 引数の既定値は命令に数えない
        $parent = $statement.Parent
        $inParam = $false
        $function = $null
        while ($null -ne $parent) {
            if ($parent -is [System.Management.Automation.Language.ParameterAst]) {
                $inParam = $true
            }
            if ($null -eq $function -and $parent -is [System.Management.Automation.Language.FunctionDefinitionAst]) {
                $function = $parent.Name
            }
            $parent = $parent.Parent
        }
        if ($inParam -or ($ExcludedFunctions -contains $function)) {
            continue
        }
        $key = "$file|$($statement.Extent.StartLineNumber)|$($statement.Extent.StartColumnNumber)"
        if (-not ($points | Where-Object { $_.Key -eq $key })) {
            [void]$points.Add([pscustomobject]@{ Key = $key; File = $file; Line = $statement.Extent.StartLineNumber; Column = $statement.Extent.StartColumnNumber; Text = $statement.Extent.Text.Split("`n")[0].Trim() })
        }
    }
}
$breakpoints = foreach ($point in $points) {
    Set-PSBreakpoint -Script $point.File -Line $point.Line -Column $point.Column -Action {
        $global:EnvCheckHits["$($_.Script)|$($_.Line)|$($_.Column)"] = $true
    }
}

# ---------------------------------------------------------------
# 3. テストを実行する
# ---------------------------------------------------------------

. $LibFile
. (Join-Path $TestDir 'Fakes.ps1')
. (Join-Path $TestDir 'LogicTests.ps1')
& {
    . (Join-Path $TestDir 'WrapperTests.ps1')
}

$breakpoints | Remove-PSBreakpoint

# ---------------------------------------------------------------
# 4. 判定の組み合わせ表（README.md 4章と同じ。条件網羅・複数条件網羅）
# ---------------------------------------------------------------

$expected = @(
    'W1-T', 'W1-F', 'W2-OK', 'W2-T', 'W2-F',
    'D01-T', 'D01-F', 'D02-T', 'D02-F', 'D03-T', 'D03-F', 'D04-T', 'D04-F',
    'D05-T', 'D05-F', 'D06-T', 'D06-F', 'D07-T', 'D07-F',
    'D08-T', 'D08-F', 'D09-T', 'D09-F', 'D10-T', 'D10-F', 'D11-T', 'D11-F',
    'D12-TT', 'D12-TF', 'D12-FT', 'D12-FF', 'D13-T', 'D13-F', 'D14-T', 'D14-F',
    'D15-TT', 'D15-TF', 'D15-FT', 'D15-FF', 'D16-T', 'D16-F',
    'D17-T', 'D17-F', 'D18-T', 'D18-F', 'D19-T', 'D19-F', 'D20-T', 'D20-F',
    'D21-T', 'D21-F', 'D22-T', 'D22-F', 'D23-T', 'D23-F',
    'D24-T', 'D24-F', 'D25-T', 'D25-F', 'D26-T', 'D26-F',
    'D27-TT', 'D27-TF', 'D27-FT', 'D27-FF',
    'D28-T', 'D28-F', 'D29-TT', 'D29-TF', 'D29-FT', 'D29-FF',
    'D30-T', 'D30-F', 'D31-T', 'D31-F', 'D32-T', 'D32-F', 'D33-T', 'D33-F',
    'D34-TTT', 'D34-TTF', 'D34-TFT', 'D34-TFF', 'D34-FTT', 'D34-FTF', 'D34-FFT', 'D34-FFF',
    'D35-T', 'D35-F', 'D36-TT', 'D36-TF', 'D36-FT', 'D36-FF',
    'D37-T', 'D37-F', 'D38-T', 'D38-F', 'D39-TT', 'D39-TF', 'D39-FT', 'D39-FF',
    'D40-T', 'D40-F', 'D41-TT', 'D41-TF', 'D41-FT', 'D41-FF', 'D42-T', 'D42-F', 'D43-T', 'D43-F',
    'D44-T', 'D44-F', 'D45-T', 'D45-F', 'D46-T', 'D46-F', 'D47-T', 'D47-F', 'D48-T', 'D48-F', 'D49-T', 'D49-F',
    'D50-T', 'D50-F', 'D51-T', 'D51-F', 'D52-T', 'D52-F', 'D53-T', 'D53-F',
    'D54-OK', 'D54-WARN', 'D54-NG', 'D54-OTHER', 'D55-T', 'D55-F',
    'D56-TT', 'D56-TF', 'D56-FT', 'D56-FF', 'D57', 'D58-TT', 'D58-TF', 'D58-FT', 'D58-FF',
    'D59-TF', 'D59-FT', 'D59-FF', 'D60-T', 'D60-F',
    'M1-T', 'M1-F', 'M2-T', 'M2-F', 'M3-T', 'M3-F'
)

# ---------------------------------------------------------------
# 5. 結果
# ---------------------------------------------------------------

$missed = @($points | Where-Object { -not $global:EnvCheckHits.ContainsKey("$($_.File)|$($_.Line)|$($_.Column)") })
$hitCount = $points.Count - $missed.Count
$notCovered = @($expected | Where-Object { -not $script:Covered.ContainsKey($_) })
$unknown = @($script:Covered.Keys | Where-Object { $expected -notcontains $_ })

Write-Output ''
Write-Output "テスト：成功 $($script:Passed) 件、失敗 $($script:Failed.Count) 件"
foreach ($failure in $script:Failed) {
    Write-Output "  失敗：$failure"
}
Write-Output ("命令網羅（C0）：{0} / {1}（{2:N1}%）" -f $hitCount, $points.Count, (100.0 * $hitCount / $points.Count))
foreach ($point in $missed) {
    Write-Output "  通っていない：$(Split-Path -Leaf $point.File):$($point.Line) $($point.Text)"
}
Write-Output "判定の組み合わせ（C1・C2・複数条件網羅）：$($expected.Count - $notCovered.Count) / $($expected.Count)"
foreach ($id in $notCovered) {
    Write-Output "  テストがない組み合わせ：$id"
}
foreach ($id in $unknown) {
    Write-Output "  表にない ID：$id"
}

if ($script:Failed.Count -eq 0 -and $missed.Count -eq 0 -and $notCovered.Count -eq 0 -and $unknown.Count -eq 0) {
    Write-Output '結果：合格'
    exit 0
}
Write-Output '結果：不合格'
exit 1
