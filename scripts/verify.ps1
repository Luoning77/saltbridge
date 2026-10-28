#requires -Version 5.0
<#
.SYNOPSIS
    SaltBridge M5 + M6 Windows 验证脚本
    合并 docs/M5_NOTES.md 第 6 节（V1-V11）与 docs/M6_NOTES.md 第 5 节（V1-V10）。

.DESCRIPTION
    逐项执行 21 个验证项：每项先打印「编号 / 说明 / 请求 / 期望」，执行后打印
    [PASS] 或 [FAIL] 与关键响应片段（前 200 字符），结尾打印「通过数 / 总数」。

    用法：
        powershell -ExecutionPolicy Bypass -File .\verify.ps1
        powershell -ExecutionPolicy Bypass -File .\verify.ps1 -Port 8737
        powershell -ExecutionPolicy Bypass -File .\verify.ps1 -Token "sb_xxxx"
        powershell -ExecutionPolicy Bypass -File .\verify.ps1 -TokenFile "D:\...\token.txt"

    参数：
        -Port       插件 HTTP 端口，默认 8737
        -Token      直接给访问令牌（与 -TokenFile 二选一）
        -TokenFile  令牌文件路径，默认 .\token.txt

    前提：
        1. 已在宿主中安装并启用 SaltBridge 插件，监听端口与 -Port 一致；
        2. 已勾选「曲库读取」权限（libraryRead=true）；
        3. 已在插件界面点「显示访问令牌」，从插件数据目录取得 token.txt（或直接拿令牌）；
        4. 当前曲目已加载歌词、且含内嵌封面（否则 M5-V5 / M5-V6 会失败）；
        5. Windows 10 及以上（自带 curl.exe；本脚本全部用 curl.exe 发请求）；
        6. M5-V9 至 M5-V11、M6-V2 至 M6-V4、M6-V10 需要人工在宿主界面配合操作，
           脚本会停下来等回车；请按提示逐步操作。

    兼容性：Windows PowerShell 5.1（未使用 PowerShell 7 专有语法）。
    说明：M6 段开始前先调 /health 取 instanceId、调 /status 取 generation 供 seek 使用。
#>

[CmdletBinding()]
param(
    [int]$Port = 8737,
    [string]$Token = '',
    [string]$TokenFile = '.\token.txt',
    [switch]$QueueBurst
)

# ==================== 通用工具 ====================

# 安全读取对象属性：对象或属性不存在时返回 $null（不用 PowerShell 7 的 ?. 语法）
function Get-Prop {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return $null }
    $p = $Obj.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

# 把响应体解析为 JSON 对象；失败返回 $null
function ConvertTo-JsonObj {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $null }
    try { return ($Text | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

# 取错误响应里的 error.code
function Get-ErrCode {
    param($Json)
    return (Get-Prop (Get-Prop $Json 'error') 'code')
}

# 响应体前 200 字符（压掉换行空白）
function Get-BodySnippet {
    param($Resp)
    $t = ''
    if ($null -ne $Resp) { $t = $Resp.Body }
    if ([string]::IsNullOrEmpty($t)) {
        if ($null -ne $Resp -and $Resp.Code -eq 0) {
            $t = '(连接失败，无 HTTP 响应，curl exit=' + $Resp.CurlExit + ')'
        } else {
            $t = '(空响应体)'
        }
    }
    $t = $t -replace '\s+', ' '
    if ($t.Length -gt 200) { $t = $t.Substring(0, 200) + '...' }
    return $t
}

# 从响应头里取某一行
function Get-HeaderLine {
    param($Resp, [string]$Name)
    if ($null -eq $Resp) { return ($Name + ': (无响应)') }
    $m = [regex]::Match($Resp.Headers, ('(?im)^\s*' + [regex]::Escape($Name) + '\s*:\s*(.+?)\s*$'))
    if ($m.Success) { return ($Name + ': ' + $m.Groups[1].Value) }
    return ($Name + ': (缺失)')
}

# 用 curl.exe 发一次请求，返回 Code / Body / Headers / CurlExit
function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [switch]$NoAuth,
        [string]$JsonBody = '',
        [hashtable]$ExtraHeaders = @{}
    )
    $url = $script:Base + $Path
    $bodyFile = Join-Path $script:TmpDir 'resp-body.bin'
    $hdrFile = Join-Path $script:TmpDir 'resp-headers.txt'
    $curlArgs = @('-s', '-X', $Method, '-o', $bodyFile, '-D', $hdrFile, '-w', '%{http_code}', '--max-time', '10')
    if (-not $NoAuth) {
        $curlArgs += @('-H', ('Authorization: Bearer ' + $script:Token))
    }
    if ($JsonBody -ne '') {
        $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', $JsonBody)
    }
    foreach ($k in $ExtraHeaders.Keys) {
        $curlArgs += @('-H', ($k + ': ' + $ExtraHeaders[$k]))
    }
    $curlArgs += $url
    $codeOut = & curl.exe @curlArgs 2>$null
    $curlExit = $LASTEXITCODE
    $codeText = ($codeOut | Out-String)
    $code = 0
    $m = [regex]::Match($codeText, '\d{3}')
    if ($m.Success) { $code = [int]$m.Value }
    $bodyText = ''
    if (Test-Path -Path $bodyFile) {
        $bodyText = Get-Content -Path $bodyFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    }
    if ($null -eq $bodyText) { $bodyText = '' }
    $hdrText = ''
    if (Test-Path -Path $hdrFile) {
        $hdrText = Get-Content -Path $hdrFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    }
    if ($null -eq $hdrText) { $hdrText = '' }
    return [pscustomobject]@{
        Code     = $code
        Body     = $bodyText
        Headers  = $hdrText
        CurlExit = $curlExit
    }
}

# 打印验证项的编号 / 说明 / 请求 / 期望
function Write-ItemHeader {
    param([string]$Id, [string]$Desc, [string]$Request, [string]$Expect)
    Write-Host ''
    Write-Host ('==== [' + $Id + '] ' + $Desc) -ForegroundColor Cyan
    Write-Host ('请求：' + $Request)
    Write-Host ('期望：' + $Expect)
}

# 布尔值转 PASS/FAIL 字符串
function Get-PassFail {
    param([bool]$Ok)
    if ($Ok) { return 'PASS' } else { return 'FAIL' }
}

# 人工确认项的判定：API 断言失败→FAIL；用户否定→FAIL；都满足→PASS
function Get-ManualResult {
    param([bool]$ApiOk, [bool]$HumanOk)
    if (-not $ApiOk) { return 'FAIL' }
    if (-not $HumanOk) { return 'FAIL' }
    return 'PASS'
}

# 打印验证结果并计数（四种状态：PASS / FAIL / SKIP / MANUAL）
function Write-ItemResult {
    param([string]$Id, [string]$Status, [string]$Snippet)
    $script:Total++
    switch ($Status) {
        'PASS' {
            $script:PassCount++
            Write-Host ('[PASS] ' + $Snippet) -ForegroundColor Green
        }
        'FAIL' {
            $script:FailCount++
            $script:FailedIds += $Id
            Write-Host ('[FAIL] ' + $Snippet) -ForegroundColor Red
        }
        'SKIP' {
            $script:SkipCount++
            $script:SkippedIds += $Id
            Write-Host ('[SKIP] ' + $Snippet) -ForegroundColor Yellow
        }
        'MANUAL' {
            $script:ManualCount++
            $script:ManualIds += $Id
            Write-Host ('[MANUAL] ' + $Snippet) -ForegroundColor Magenta
        }
    }
}

# 等待人工操作
function Wait-Operator {
    param([string]$Message)
    Write-Host ''
    Write-Host ('>> ' + $Message) -ForegroundColor Yellow
    Read-Host '完成后按回车继续' | Out-Null
}

# 向操作者确认一个观察结论（y/n），最多问 3 次
function Ask-YesNo {
    param([string]$Question)
    for ($i = 0; $i -lt 3; $i++) {
        $a = Read-Host ($Question + '（y=是 / n=否）')
        if ($null -eq $a) { break }
        $a = $a.Trim()
        if ($a -match '^[yY]') { return $true }
        if ($a -match '^[nN]') { return $false }
        Write-Host '请输入 y 或 n。'
    }
    Write-Host '（未得到有效确认，按「否」处理）'
    return $false
}

# 从 /status 取当前 generation
function Get-CurrentGeneration {
    $r = Invoke-Api -Path '/status' -Method 'GET'
    $j = ConvertTo-JsonObj $r.Body
    return (Get-Prop $j 'generation')
}

# 等待 generation 变化（切歌后异步生效），超时返回 $null
function Wait-GenerationChange {
    param($OldGen, [int]$TimeoutMs = 5000)
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    while ([DateTime]::UtcNow -lt $deadline) {
        $g = Get-CurrentGeneration
        if ($null -ne $g) {
            if ($null -eq $OldGen) { return $g }
            if ([int64]$g -ne [int64]$OldGen) { return $g }
        }
        Start-Sleep -Milliseconds 300
    }
    return $null
}

# ==================== 前置检查 ====================

$curlCmd = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
if ($null -eq $curlCmd) {
    Write-Host '未找到 curl.exe（Windows 10+ 自带）。请在 Windows 10 或更高的 PowerShell 中运行。' -ForegroundColor Red
    return
}

$script:Token = ''
$tokenSource = ''
if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $script:Token = $Token.Trim()
    $tokenSource = '参数 -Token'
} else {
    if (-not (Test-Path -Path $TokenFile)) {
        Write-Host ('找不到令牌文件：' + $TokenFile) -ForegroundColor Red
        Write-Host '请先在插件界面点「显示访问令牌」取得 token.txt，或用 -Token 直接给令牌。'
        return
    }
    $rawToken = Get-Content -Path $TokenFile -Raw -ErrorAction SilentlyContinue
    if ($null -eq $rawToken) { $rawToken = '' }
    $script:Token = $rawToken.Trim()
    $tokenSource = '文件 ' + $TokenFile
    if ($script:Token -eq '') {
        Write-Host ('令牌文件为空：' + $TokenFile) -ForegroundColor Red
        return
    }
}

$script:Base = 'http://127.0.0.1:' + $Port + '/api/v1'
$script:RunTag = [guid]::NewGuid().ToString('N').Substring(0, 6)
$script:TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ('saltbridge-verify-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $script:TmpDir -Force | Out-Null

$script:PassCount = 0
$script:FailCount = 0
$script:SkipCount = 0
$script:ManualCount = 0
$script:Total = 0
$script:FailedIds = @()
$script:SkippedIds = @()
$script:ManualIds = @()

Write-Host '============================================================'
Write-Host 'SaltBridge M5 + M6 验证脚本' -ForegroundColor Cyan
Write-Host ('目标：' + $script:Base)
Write-Host ('令牌来源：' + $tokenSource + '（长度 ' + $script:Token.Length + '，不回显内容）')
Write-Host ('本次运行标识 RunTag：' + $script:RunTag + '（用于避免 requestId 撞去重表）')
Write-Host '前提：插件已启用、已勾「曲库读取」、已取 token.txt；带 >> 的步骤需要人工配合。'
Write-Host '============================================================'

# ==================== M5 验证清单（V1-V11） ====================

Write-Host ''
Write-Host '---------- M5 Windows 验证清单（只读 HTTP 层） ----------' -ForegroundColor Cyan

# ---- M5-V1 探活（免令牌） ----
Write-ItemHeader 'M5-V1' '探活（免令牌）'  'GET /api/v1/health'  'HTTP 200；body.ok=true；含 instanceId、hostVersion（示例 hostVersion=1.19.2，以实际宿主为准）'
$r = Invoke-Api -Path '/health' -Method 'GET' -NoAuth
$j = ConvertTo-JsonObj $r.Body
$inst = Get-Prop $j 'instanceId'
$hv = Get-Prop $j 'hostVersion'
$okField = Get-Prop $j 'ok'
$script:InstanceId = ''
if ($null -ne $inst) { $script:InstanceId = [string]$inst }
$v1ok = ($r.Code -eq 200) -and ($okField -eq $true) -and ($script:InstanceId -ne '') -and ($null -ne $hv) -and ([string]$hv -ne '')
Write-ItemResult 'M5-V1' (Get-PassFail $v1ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M5-V2 令牌缺失 -> 401 ----
Write-ItemHeader 'M5-V2' '令牌缺失 -> 401'  'GET /api/v1/status（不带 Authorization）'  'HTTP 401；响应头含 WWW-Authenticate: Bearer'
$r = Invoke-Api -Path '/status' -Method 'GET' -NoAuth
$v2ok = ($r.Code -eq 401) -and ($r.Headers -match '(?im)WWW-Authenticate') -and ($r.Headers -match '(?im)Bearer')
Write-ItemResult 'M5-V2' (Get-PassFail $v2ok) ('HTTP ' + $r.Code + ' | ' + (Get-HeaderLine $r 'WWW-Authenticate') + ' | ' + (Get-BodySnippet $r))

# ---- M5-V3 状态 ----
Write-ItemHeader 'M5-V3' '状态'  'GET /api/v1/status（带令牌）'  'trackStatus=available；playback.state 与界面一致（人工确认）；animation 不是 disabled(no_position)'
$r = Invoke-Api -Path '/status' -Method 'GET'
$j = ConvertTo-JsonObj $r.Body
$trackStatus = Get-Prop $j 'trackStatus'
$pb = Get-Prop $j 'playback'
$pbState = Get-Prop $pb 'state'
$anim = Get-Prop $pb 'animation'
$animMode = Get-Prop $anim 'mode'
$animReason = Get-Prop $anim 'reason'
$animOk = -not (($animMode -eq 'disabled') -and ($animReason -eq 'no_position'))
$v3api = ($r.Code -eq 200) -and ($trackStatus -eq 'available') -and ($null -ne $pbState) -and $animOk
Write-Host ('    trackStatus=' + $trackStatus + '；playback.state=' + $pbState + '；animation=' + $animMode + '(' + $animReason + ')')
$v3human = Ask-YesNo ('playback.state=' + $pbState + ' 是否与播放器界面一致')
Write-ItemResult 'M5-V3' (Get-ManualResult $v3api $v3human) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M5-V4 能力 ----
Write-ItemHeader 'M5-V4' '能力'  'GET /api/v1/capabilities（带令牌）'  'permissions.libraryRead=true；features.control 五项齐全且取值一致（M5 全 false；M6 起全 true）；limits.httpWorkers=8'
$r = Invoke-Api -Path '/capabilities' -Method 'GET'
$j = ConvertTo-JsonObj $r.Body
$libRead = Get-Prop (Get-Prop $j 'permissions') 'libraryRead'
$workers = Get-Prop (Get-Prop $j 'limits') 'httpWorkers'
$ctrl = Get-Prop (Get-Prop $j 'features') 'control'
$ctrlKeys = @('play', 'pause', 'next', 'previous', 'seek')
$ctrlOk = $true
$ctrlDesc = ''
$ctrlFirst = $null
foreach ($k in $ctrlKeys) {
    $v = Get-Prop $ctrl $k
    if ($null -eq $v -or ($v -isnot [bool])) { $ctrlOk = $false }
    if ($null -eq $ctrlFirst) { $ctrlFirst = $v }
    if ($v -ne $ctrlFirst) { $ctrlOk = $false }
    if ($ctrlDesc -ne '') { $ctrlDesc = $ctrlDesc + ',' }
    $ctrlDesc = $ctrlDesc + $k + '=' + $v
}
$v4ok = ($r.Code -eq 200) -and ($libRead -eq $true) -and ($workers -eq 8) -and $ctrlOk
Write-Host ('    libraryRead=' + $libRead + '；httpWorkers=' + $workers + '；control[ ' + $ctrlDesc + ' ]')
Write-ItemResult 'M5-V4' (Get-PassFail $v4ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M5-V5 歌词 ----
$gen5 = Get-CurrentGeneration
Write-ItemHeader 'M5-V5' '歌词'  ('GET /api/v1/lyrics?expectedGeneration=' + $gen5 + '（代次取自 /status）')  'status=available；timesBase=trackStart；所有 lines[].timesValid=true；有逐字单元的行 timing=word'
$r = Invoke-Api -Path ('/lyrics?expectedGeneration=' + [string]$gen5) -Method 'GET'
$j = ConvertTo-JsonObj $r.Body
$lyStatus = Get-Prop $j 'status'
$timesBase = Get-Prop $j 'timesBase'
$lines = Get-Prop $j 'lines'
$lineCount = 0
$wordLines = 0
$timesValidOk = $true
$wordTimingOk = $true
if ($null -ne $lines) {
    foreach ($ln in @($lines)) {
        $lineCount++
        if ((Get-Prop $ln 'timesValid') -ne $true) { $timesValidOk = $false }
        $cells = Get-Prop $ln 'cells'
        $hasCells = $false
        if ($null -ne $cells) { if (@($cells).Count -gt 0) { $hasCells = $true } }
        $lnTiming = Get-Prop $ln 'timing'
        if ($lnTiming -eq 'word') { $wordLines++ }
        if ($hasCells -and ($lnTiming -ne 'word')) { $wordTimingOk = $false }
    }
}
$v5ok = ($r.Code -eq 200) -and ($lyStatus -eq 'available') -and ($timesBase -eq 'trackStart') -and $timesValidOk -and $wordTimingOk -and ($lineCount -gt 0)
Write-Host ('    status=' + $lyStatus + '；timesBase=' + $timesBase + '；lines=' + $lineCount + '；逐字行=' + $wordLines)
Write-ItemResult 'M5-V5' (Get-PassFail $v5ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M5-V6 封面 ----
$gen6 = Get-CurrentGeneration
Write-ItemHeader 'M5-V6' '封面（202 pending -> 200）'  ('GET /api/v1/cover?expectedGeneration=' + $gen6 + '（首次可能 202，按 retryAfterMs 约 500ms 重试，最多 7 次 / 3 秒）')  '最终 200；Content-Type: image/*（清单示例 image/jpeg，按魔数嗅探实测为准）；响应头 X-SaltBridge-Generation'
$coverPath = '/cover?expectedGeneration=' + [string]$gen6
$cr = $null
for ($att = 1; $att -le 7; $att++) {
    $cr = Invoke-Api -Path $coverPath -Method 'GET'
    if ($cr.Code -eq 202) {
        Start-Sleep -Milliseconds 500
        continue
    }
    break
}
$ctOk = $false
$genHdrOk = $false
if ($null -ne $cr) {
    if ($cr.Headers -match '(?im)^\s*Content-Type\s*:\s*image/') { $ctOk = $true }
    if ($cr.Headers -match '(?im)X-SaltBridge-Generation') { $genHdrOk = $true }
}
$v6ok = ($null -ne $cr) -and ($cr.Code -eq 200) -and $ctOk -and $genHdrOk
Write-ItemResult 'M5-V6' (Get-PassFail $v6ok) ('HTTP ' + $cr.Code + ' | ' + (Get-HeaderLine $cr 'Content-Type') + ' | ' + (Get-HeaderLine $cr 'X-SaltBridge-Generation') + ' | ' + (Get-BodySnippet $cr))

# ---- M5-V7 错误代次 -> 409 ----
Write-ItemHeader 'M5-V7' '错误代次 -> 409'  'GET /api/v1/lyrics?expectedGeneration=999'  'HTTP 409；error.code=generation_mismatch；detail.currentGeneration 是当前值（不等于 999）'
$r = Invoke-Api -Path '/lyrics?expectedGeneration=999' -Method 'GET'
$j = ConvertTo-JsonObj $r.Body
$errCode = Get-ErrCode $j
$curGen7 = Get-Prop (Get-Prop (Get-Prop $j 'error') 'detail') 'currentGeneration'
$v7ok = ($r.Code -eq 409) -and ($errCode -eq 'generation_mismatch') -and ($null -ne $curGen7) -and ([int64]$curGen7 -ne 999)
Write-Host ('    error.code=' + $errCode + '；detail.currentGeneration=' + $curGen7)
Write-ItemResult 'M5-V7' (Get-PassFail $v7ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M5-V8 带 Origin -> 403 ----
Write-ItemHeader 'M5-V8' '带 Origin -> 403'  'GET /api/v1/health，请求头 Origin: http://evil'  'HTTP 403；error.code=forbidden_origin'
$r = Invoke-Api -Path '/health' -Method 'GET' -NoAuth -ExtraHeaders @{ 'Origin' = 'http://evil' }
$j = ConvertTo-JsonObj $r.Body
$errCode = Get-ErrCode $j
$v8ok = ($r.Code -eq 403) -and ($errCode -eq 'forbidden_origin')
Write-ItemResult 'M5-V8' (Get-PassFail $v8ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M5-V9 停用插件后 /health -> 连接被拒绝 ----
Write-ItemHeader 'M5-V9' '停用插件后再请求 /health（人工）'  'GET /api/v1/health（停用插件后）'  '连接被拒绝（端口释放，curl 无法连接、无 HTTP 状态码）'
$script:InstanceIdOld = $script:InstanceId
$hr = Invoke-Api -Path '/health' -Method 'GET' -NoAuth
$hj = ConvertTo-JsonObj $hr.Body
$hInst = Get-Prop $hj 'instanceId'
if ($null -ne $hInst) { $script:InstanceIdOld = [string]$hInst }
Wait-Operator '请在宿主里【停用】SaltBridge 插件（端口应释放），然后回来按回车。'
$r = Invoke-Api -Path '/health' -Method 'GET' -NoAuth
$v9ok = ($r.Code -eq 0) -and ($r.CurlExit -ne 0)
$v9snip = '连接被拒绝（curl exit=' + $r.CurlExit + '，无 HTTP 响应），端口已释放'
if ($r.Code -ne 0) { $v9snip = '意外收到 HTTP ' + $r.Code + '：' + (Get-BodySnippet $r) }
Write-ItemResult 'M5-V9' (Get-PassFail $v9ok) $v9snip

# ---- M5-V10 重新启用后用旧令牌 ----
Write-ItemHeader 'M5-V10' '重新启用后用旧令牌请求（人工）'  'GET /api/v1/status（旧令牌）'  'HTTP 200（令牌跨启用复用仍有效，协议 2.2）且 instanceId 已变化（变化的是实例不是令牌；M5 清单表格里的 401 与其括注/附注矛盾，本脚本按附注实现）'
Wait-Operator '请【重新启用】SaltBridge 插件，然后回来按回车。'
$r = Invoke-Api -Path '/status' -Method 'GET'
$j = ConvertTo-JsonObj $r.Body
$newInst = Get-Prop $j 'instanceId'
$v10ok = ($r.Code -eq 200) -and ($null -ne $newInst) -and ([string]$newInst -ne '') -and ([string]$newInst -ne $script:InstanceIdOld)
$v10snip = 'HTTP ' + $r.Code + ' | instanceId 旧=' + $script:InstanceIdOld + ' 新=' + $newInst + ' | ' + (Get-BodySnippet $r)
if ($r.Code -eq 401) {
    $v10snip = 'HTTP 401：旧令牌被拒。注意：协议 2.2 与清单附注都说令牌跨启用复用仍有效，变化的应是 instanceId；若确为 401 请与文档核对语义。 | ' + (Get-BodySnippet $r)
}
Write-ItemResult 'M5-V10' (Get-PassFail $v10ok) $v10snip

# ---- M5-V11 重置令牌后旧令牌 ----
Write-ItemHeader 'M5-V11' '「重置访问令牌」后立刻用旧令牌（人工）'  'GET /api/v1/status（旧令牌）'  'HTTP 401（旧令牌立即失效）'
Wait-Operator '请在插件界面点【重置访问令牌】，然后回来按回车。'
$r = Invoke-Api -Path '/status' -Method 'GET'
$v11ok = ($r.Code -eq 401)
Write-ItemResult 'M5-V11' (Get-PassFail $v11ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M5-V11 之后：为 M6 刷新令牌（若刚重置过，旧令牌已失效） ----
Write-Host ''
Write-Host '—— M6 需要有效令牌 ——' -ForegroundColor Cyan
Write-Host ('若刚刚重置过令牌：请再次点「显示访问令牌」把新令牌写入 ' + $TokenFile + '，或直接粘贴新令牌。')
$pasted = Read-Host '粘贴新令牌（直接回车则从令牌文件重读）'
if ($null -eq $pasted) { $pasted = '' }
$pasted = $pasted.Trim()
if ($pasted -ne '') {
    $script:Token = $pasted
    $tokenSource = '手动粘贴'
} else {
    if (Test-Path -Path $TokenFile) {
        $rawToken = Get-Content -Path $TokenFile -Raw -ErrorAction SilentlyContinue
        if ($null -eq $rawToken) { $rawToken = '' }
        $script:Token = $rawToken.Trim()
        $tokenSource = '文件 ' + $TokenFile
    }
}
Write-Host ('令牌来源：' + $tokenSource + '（长度 ' + $script:Token.Length + '，不回显内容）')

# ==================== M6 验证清单（V1-V10） ====================

Write-Host ''
Write-Host '---------- M6 Windows 验证清单（控制端点） ----------' -ForegroundColor Cyan

# 预备：/health 取 instanceId，/status 取 generation（供 seek 用）
$hr = Invoke-Api -Path '/health' -Method 'GET' -NoAuth
$hj = ConvertTo-JsonObj $hr.Body
$script:InstanceId = [string](Get-Prop $hj 'instanceId')
$genInit = Get-CurrentGeneration
Write-Host ('预备：instanceId = ' + $script:InstanceId + '；generation = ' + $genInit)

# ---- M6-V1 播放 ----
$bodyPlay = '{"requestId":"t-play-0001-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"play"}'
Write-ItemHeader 'M6-V1' '播放'  ('POST /api/v1/control  ' + $bodyPlay)  'HTTP 202；accepted=true；deduplicated=false'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyPlay
$j = ConvertTo-JsonObj $r.Body
$v1ok = ($r.Code -eq 202) -and ((Get-Prop $j 'accepted') -eq $true) -and ((Get-Prop $j 'deduplicated') -eq $false)
Write-ItemResult 'M6-V1' (Get-PassFail $v1ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M6-V2 同一 requestId 重发 -> 200 deduplicated ----
Write-ItemHeader 'M6-V2' '同一 requestId 重发（人工确认只执行一次）'  ('POST /api/v1/control  ' + $bodyPlay + '（与 V1 完全相同）')  'HTTP 200；deduplicated=true；播放器不会被触发两次（人工确认）'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyPlay
$j = ConvertTo-JsonObj $r.Body
$v2api = ($r.Code -eq 200) -and ((Get-Prop $j 'deduplicated') -eq $true)
$v2human = Ask-YesNo '播放器是否只被触发了一次（没有播两次）'
Write-ItemResult 'M6-V2' (Get-ManualResult $v2api $v2human) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M6-V3 next 两次 + 同 requestId 重发 ----
Write-ItemHeader 'M6-V3' 'next 两次（不同 requestId）+ 重发（人工确认切歌次数）'  'POST /api/v1/control  command=next（t-next-0001 / t-next-0002 各一次），再重发 t-next-0001'  '前两条 202（各切一首）；重发 200 + deduplicated=true（只切一首）；代次已变化（人工确认切换次数）'
$script:GenBeforeNext = Get-CurrentGeneration
$bodyNext1 = '{"requestId":"t-next-0001-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"next"}'
$bodyNext2 = '{"requestId":"t-next-0002-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"next"}'
$r1 = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyNext1
$r2 = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyNext2
$genAfter = Wait-GenerationChange -OldGen $script:GenBeforeNext
$r3 = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyNext1
$j1 = ConvertTo-JsonObj $r1.Body
$j2 = ConvertTo-JsonObj $r2.Body
$j3 = ConvertTo-JsonObj $r3.Body
$v3api = ($r1.Code -eq 202) -and ((Get-Prop $j1 'accepted') -eq $true)  -and ($r2.Code -eq 202) -and ((Get-Prop $j2 'accepted') -eq $true)  -and ($r3.Code -eq 200) -and ((Get-Prop $j3 'deduplicated') -eq $true)  -and ($null -ne $genAfter)
Write-Host ('    generation：切歌前=' + $script:GenBeforeNext + '，之后=' + $genAfter)
$v3human = Ask-YesNo '是否观察到切了两首歌，且重发 requestId 后没有再切'
Write-ItemResult 'M6-V3' (Get-ManualResult $v3api $v3human) ('HTTP ' + $r1.Code + '/' + $r2.Code + '/' + $r3.Code + ' | ' + (Get-BodySnippet $r3))

# ---- M6-V4 seek ----
$genSeek = Get-CurrentGeneration
$bodySeek = '{"requestId":"t-seek-0001-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"seek","positionMs":30000,"expectedGeneration":' + [string]$genSeek + '}'
Write-ItemHeader 'M6-V4' 'seek'  ('POST /api/v1/control  ' + $bodySeek + '（expectedGeneration=' + $genSeek + ' 取自 /status）')  'HTTP 202；accepted=true；位置跳到约 30 秒（人工确认）'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodySeek
$j = ConvertTo-JsonObj $r.Body
$v4api = ($r.Code -eq 202) -and ((Get-Prop $j 'accepted') -eq $true) -and ((Get-Prop $j 'deduplicated') -eq $false)
$v4human = Ask-YesNo '播放位置是否跳到了约 30 秒'
Write-ItemResult 'M6-V4' (Get-ManualResult $v4api $v4human) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M6-V5 seek 缺 expectedGeneration ----
$bodySeekNoGen = '{"requestId":"t-seek-0002-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"seek","positionMs":30000}'
Write-ItemHeader 'M6-V5' 'seek 删掉 expectedGeneration'  ('POST /api/v1/control  ' + $bodySeekNoGen)  'HTTP 400；error.code=generation_required'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodySeekNoGen
$j = ConvertTo-JsonObj $r.Body
$errCode = Get-ErrCode $j
$v5ok = ($r.Code -eq 400) -and ($errCode -eq 'generation_required')
Write-ItemResult 'M6-V5' (Get-PassFail $v5ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M6-V6 非法命令 ----
$bodyBadCmd = '{"requestId":"t-vol-0001-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"volume"}'
Write-ItemHeader 'M6-V6' 'command=volume -> invalid_command'  ('POST /api/v1/control  ' + $bodyBadCmd)  'HTTP 400；error.code=invalid_command；detail.supported 含 5 个命令（play/pause/next/previous/seek）'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyBadCmd
$j = ConvertTo-JsonObj $r.Body
$errCode = Get-ErrCode $j
$supported = Get-Prop (Get-Prop (Get-Prop $j 'error') 'detail') 'supported'
$supList = @()
if ($null -ne $supported) { $supList = @($supported | ForEach-Object { [string]$_ }) }
$supOk = ($supList.Count -eq 5) -and ($supList -contains 'play') -and ($supList -contains 'pause') -and ($supList -contains 'next') -and ($supList -contains 'previous') -and ($supList -contains 'seek')
$v6ok = ($r.Code -eq 400) -and ($errCode -eq 'invalid_command') -and $supOk
Write-Host ('    error.code=' + $errCode + '；supported=[' + ($supList -join ',') + ']')
Write-ItemResult 'M6-V6' (Get-PassFail $v6ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M6-V7 错误 expectedInstanceId ----
$bodyBadInst = '{"requestId":"t-inst-0001-' + $script:RunTag + '","expectedInstanceId":"00000000-0000-0000-0000-000000000000","command":"play"}'
Write-ItemHeader 'M6-V7' '错误的 expectedInstanceId'  ('POST /api/v1/control  ' + $bodyBadInst)  'HTTP 409；error.code=instance_mismatch'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyBadInst
$j = ConvertTo-JsonObj $r.Body
$errCode = Get-ErrCode $j
$v7ok = ($r.Code -eq 409) -and ($errCode -eq 'instance_mismatch')
Write-ItemResult 'M6-V7' (Get-PassFail $v7ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M6-V8 旧 expectedGeneration（切歌后） ----
$staleGen = $script:GenBeforeNext
$bodyOldGen = '{"requestId":"t-gen-0001-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"next","expectedGeneration":' + [string]$staleGen + '}'
Write-ItemHeader 'M6-V8' '用旧的 expectedGeneration（切歌后）'  ('POST /api/v1/control  ' + $bodyOldGen + '（旧代次=' + $staleGen + '，来自 V3 切歌之前）')  'HTTP 409；error.code=generation_mismatch；detail.currentGeneration 是新值（不等于旧代次）'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyOldGen
$j = ConvertTo-JsonObj $r.Body
$errCode = Get-ErrCode $j
$curGen8 = Get-Prop (Get-Prop (Get-Prop $j 'error') 'detail') 'currentGeneration'
$v8ok = ($r.Code -eq 409) -and ($errCode -eq 'generation_mismatch') -and ($null -ne $curGen8) -and ([int64]$curGen8 -ne [int64]$staleGen)
Write-Host ('    error.code=' + $errCode + '；旧代次=' + $staleGen + '；detail.currentGeneration=' + $curGen8)
Write-ItemResult 'M6-V8' (Get-PassFail $v8ok) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ---- M6-V9 队列容量（默认 SKIP，确定性测试在 ControlQueueTest） ----
Write-ItemHeader 'M6-V9' '队列容量与入队失败回滚（默认 SKIP）'  'POST /api/v1/control  连续发送（command=next，requestId 各不相同）'  '出现 HTTP 429 queue_full 且带 Retry-After: 1；对拿到 429 的 requestId 重发不得是 200+deduplicated（失败不留痕/回滚）'
Write-Host '    说明：队列容量与回滚的单元测试见 ControlQueueTest（验证 admit/reject/rollback 逻辑），此处仅做实机观察。'
Write-Host '    默认跳过：连续切歌 40-80 次来碰队列满不可靠，且会真实切歌。如需实机验证，加 -QueueBurst 参数。'

if ($QueueBurst) {
    $burstTpl = '{"requestId":"__RID__","expectedInstanceId":"' + $script:InstanceId + '","command":"next"}'
    $foundQueueFull = $false
    $sawRetryAfter = $false
    $burstRid = ''
    $burstNote = ''
    for ($round = 1; $round -le 2; $round++) {
        if ($foundQueueFull) { break }
        $roundDir = Join-Path $script:TmpDir ('burst-r' + $round)
        New-Item -ItemType Directory -Path $roundDir -Force | Out-Null
        # curl --next 分隔 URL 组：每组必须有自己的 URL，不能只在最后加一次
        $burstArgs = @()
        for ($i = 0; $i -lt 40; $i++) {
            $rid = ('t-burst-r{0}-{1:d4}-{2}' -f $round, $i, $script:RunTag)
            $bodyText = $burstTpl.Replace('__RID__', $rid)
            $bodyFile = Join-Path $roundDir ('b{0:d2}.body' -f $i)
            $hdrFile = Join-Path $roundDir ('b{0:d2}.hdr' -f $i)
            if ($i -gt 0) { $burstArgs += '--next' }
            $burstArgs += @('-s', '-X', 'POST', '-H', ('Authorization: Bearer ' + $script:Token), '-H', 'Content-Type: application/json', '--data-binary', $bodyText, '-o', $bodyFile, '-D', $hdrFile, '-w', '%{http_code} ', '--max-time', '10', ($script:Base + '/control'))
        }
        $codeOut = & curl.exe @burstArgs 2>$null
        $codeText = ($codeOut | Out-String)
        $codeList = @()
        foreach ($tok in ($codeText -split '\s+')) {
            if ($tok -match '^\d{3}$') { $codeList += [int]$tok }
        }
        $n202 = 0; $n200 = 0; $n429 = 0; $nOther = 0
        for ($i = 0; $i -lt 40; $i++) {
            $bodyFile = Join-Path $roundDir ('b{0:d2}.body' -f $i)
            $hdrFile = Join-Path $roundDir ('b{0:d2}.hdr' -f $i)
            $bText = ''
            if (Test-Path -Path $bodyFile) {
                $bText = Get-Content -Path $bodyFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            }
            if ($null -eq $bText) { $bText = '' }
            $hText = ''
            if (Test-Path -Path $hdrFile) {
                $hText = Get-Content -Path $hdrFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            }
            if ($null -eq $hText) { $hText = '' }
            $thisCode = 0
            if ($i -lt $codeList.Count) { $thisCode = $codeList[$i] }
            $bj = ConvertTo-JsonObj $bText
            $ec = Get-ErrCode $bj
            if ($thisCode -eq 202) { $n202++ }
            elseif ($thisCode -eq 200) { $n200++ }
            elseif ($thisCode -eq 429) {
                $n429++
                if ($ec -eq 'queue_full') {
                    if (-not $foundQueueFull) {
                        $burstRid = ('t-burst-r{0}-{1:d4}-{2}' -f $round, $i, $script:RunTag)
                        if ($hText -match '(?im)^\s*Retry-After\s*:\s*1\s*$') { $sawRetryAfter = $true }
                        $foundQueueFull = $true
                    }
                }
            }
            else { $nOther++ }
        }
        $burstNote = '第 ' + $round + ' 轮 40 条：202x' + $n202 + ' 200x' + $n200 + ' 429x' + $n429 + ' 其它x' + $nOther + '；'
    }
    $v9a = $foundQueueFull -and $sawRetryAfter
    $v9b = $true
    $burstNote = $burstNote + '命中 429 queue_full 的 requestId=' + $burstRid
    if ($burstRid -ne '') {
        $retryBody = $burstTpl.Replace('__RID__', $burstRid)
        $rr = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $retryBody
        $rj = ConvertTo-JsonObj $rr.Body
        $falseDedup = ($rr.Code -eq 200) -and ((Get-Prop $rj 'deduplicated') -eq $true)
        $v9b = -not $falseDedup
        $burstNote = $burstNote + '；重发该 requestId 得 HTTP ' + $rr.Code + '（不得是 200+deduplicated）'
    } else {
        $v9b = $false
        $burstNote = $burstNote + '；未出现 queue_full——未触发队列满不算通过（宿主执行过快时队列可能塞不满）'
    }
    Write-Host ('    ' + $burstNote)
    if ($v9a -and $v9b) {
        Write-ItemResult 'M6-V9' 'PASS' $burstNote
    } else {
        Write-ItemResult 'M6-V9' 'FAIL' $burstNote
    }
} else {
    Write-ItemResult 'M6-V9' 'SKIP' '队列容量实机验证已跳过；单元测试见 ControlQueueTest'
}

# ---- M6-V10 发一条命令后立刻停用插件 ----
$bodyStop = '{"requestId":"t-stop-0001-' + $script:RunTag + '","expectedInstanceId":"' + $script:InstanceId + '","command":"pause"}'
Write-ItemHeader 'M6-V10' '发一条命令后立刻停用插件（人工）'  ('POST /api/v1/control  ' + $bodyStop + '，发送后立刻停用插件')  '命令最多执行一次（不重复执行；bridge.log 可能有 control.dropped；202 只承诺「可能执行」）'
Write-Host '即将发送 pause 命令；发送后请【立刻】在宿主里停用插件（命令异步执行，越快越好）。'
Wait-Operator '准备好后回车：脚本会立即发送命令。'
$r = Invoke-Api -Path '/control' -Method 'POST' -JsonBody $bodyStop
Write-Host ('已发送（HTTP ' + $r.Code + '），请立刻停用插件！') -ForegroundColor Yellow
Read-Host '停用完成后按回车继续' | Out-Null
$v10api = ($r.Code -eq 202) -or ($r.Code -eq 200)
$v10human = Ask-YesNo '播放器是否对该命令最多执行了一次（未重复执行）'
Write-ItemResult 'M6-V10' (Get-ManualResult $v10api $v10human) ('HTTP ' + $r.Code + ' | ' + (Get-BodySnippet $r))

# ==================== 汇总 ====================

Remove-Item -Path $script:TmpDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '============================================================'
Write-Host ('验证汇总：通过 ' + $script:PassCount + ' / 失败 ' + $script:FailCount + ' / 跳过 ' + $script:SkipCount + ' / 待确认 ' + $script:ManualCount + ' / 共 ' + $script:Total) -ForegroundColor Cyan
if ($script:FailCount -gt 0) {
    Write-Host ('未通过项：' + ($script:FailedIds -join '、')) -ForegroundColor Red
}
if ($script:SkipCount -gt 0) {
    Write-Host ('跳过项：' + ($script:SkippedIds -join '、') + '（未验证，不算通过）') -ForegroundColor Yellow
}
if ($script:ManualCount -gt 0) {
    Write-Host ('待确认项：' + ($script:ManualIds -join '、') + '（人工确认尚未完成）') -ForegroundColor Magenta
}
if ($script:FailCount -eq 0 -and $script:SkipCount -eq 0 -and $script:ManualCount -eq 0) {
    Write-Host '全部验证项通过。' -ForegroundColor Green
} elseif ($script:FailCount -eq 0) {
    Write-Host '自动验证项全部通过；跳过/待确认项需另行处理。' -ForegroundColor Yellow
} else {
    Write-Host '存在未通过项，需要修复后重跑。' -ForegroundColor Red
}
Write-Host '============================================================'
