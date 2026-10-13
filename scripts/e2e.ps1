param(
  [string]$Base = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$results = New-Object System.Collections.Generic.List[object]

function Record($name, $pass, $detail) {
  $results.Add([pscustomobject]@{ Test = $name; Result = $(if ($pass) { 'PASS' } else { 'FAIL' }); Detail = $detail })
}

function Call([string]$method, [string]$url, $headers, $bodyObj, [int]$timeoutSec = 90) {
  $params = @{ Method = $method; Uri = $url; TimeoutSec = $timeoutSec; UseBasicParsing = $true }
  if ($headers) { $params.Headers = $headers }
  if ($null -ne $bodyObj) { $params.ContentType = 'application/json'; $params.Body = ($bodyObj | ConvertTo-Json -Depth 8) }
  try {
    $r = Invoke-WebRequest @params
    return @{ Status = [int]$r.StatusCode; Body = $r.Content; Ok = $true }
  } catch {
    $resp = $_.Exception.Response
    $status = if ($resp) { [int]$resp.StatusCode } else { -1 }
    $content = ''
    try { $sr = New-Object System.IO.StreamReader($resp.GetResponseStream()); $content = $sr.ReadToEnd() } catch {}
    return @{ Status = $status; Body = $content; Ok = $false }
  }
}
function Json($s) { try { return $s | ConvertFrom-Json } catch { return $null } }
function Clip($s, $n) { if (-not $s) { return '' } $s = $s -replace '\s+', ' '; if ($s.Length -gt $n) { return $s.Substring(0, $n) + '...' } return $s }

Write-Host "=== Secure Bank E2E test run (base=$Base) ===" -ForegroundColor Cyan

# 1. Health
$r = Call GET "$Base/health" $null $null
$j = Json $r.Body
Record 'Health endpoint UP' ($r.Status -eq 200 -and $j.status -eq 'UP') "status=$($j.status) llmConfigured=$($j.llmConfigured) chunks=$($j.indexedChunks) embed=$($j.embeddingModel) chat=$($j.chatModel)"

# 2. Login alice
$r = Call POST "$Base/auth/login" $null @{ username = 'alice'; password = 'password123' }
$j = Json $r.Body
$aliceToken = $j.token
Record 'Login alice' ($r.Status -eq 200 -and [bool]$aliceToken) "userId=$($j.userId) roles=$($j.roles)"

# 3. Bad password rejected
$r = Call POST "$Base/auth/login" $null @{ username = 'alice'; password = 'wrong-password' }
Record 'Bad password rejected (401)' ($r.Status -eq 401) "http=$($r.Status)"

# 4. Protected endpoint without token
$r = Call GET "$Base/api/me/balance" $null $null
Record 'Protected endpoint blocks no-token' ($r.Status -eq 401 -or $r.Status -eq 403) "http=$($r.Status)"

# 5. Register a brand-new user
$uname = 'e2e' + (Get-Random -Maximum 999999)
$r = Call POST "$Base/auth/register" $null @{ username = $uname; password = 'password123' }
$j = Json $r.Body
$newToken = $j.token
Record 'Register new user' (($r.Status -eq 200 -or $r.Status -eq 201) -and [bool]$newToken) "user=$uname http=$($r.Status)"

# 6. New user has no seeded data
$r = Call GET "$Base/api/me/balance" @{ Authorization = "Bearer $newToken" } $null
$j = Json $r.Body
Record 'New user has empty accounts' ($r.Status -eq 200 -and @($j.accounts).Count -eq 0) "accounts=$(@($j.accounts).Count)"

# 7. Alice data endpoints
$r = Call GET "$Base/api/me/balance" @{ Authorization = "Bearer $aliceToken" } $null
$j = Json $r.Body
$acctCount = @($j.accounts).Count
Record 'Alice balance endpoint' ($r.Status -eq 200 -and $acctCount -ge 1) "accounts=$acctCount"

$r = Call GET "$Base/api/me/cards" @{ Authorization = "Bearer $aliceToken" } $null
$j = Json $r.Body
Record 'Alice cards endpoint' ($r.Status -eq 200 -and @($j).Count -ge 1) "cards=$(@($j).Count)"

$r = Call GET "$Base/api/me/loans" @{ Authorization = "Bearer $aliceToken" } $null
$j = Json $r.Body
Record 'Alice loans endpoint' ($r.Status -eq 200) "loans=$(@($j).Count)"

# 8. Chat: policy question
$r = Call POST "$Base/chat/ask" @{ Authorization = "Bearer $aliceToken" } @{ message = 'What is the KYC checklist for onboarding?' } 150
$j = Json $r.Body
Record 'Chat POLICY grounded w/ citations' ($r.Status -eq 200 -and $j.intent -eq 'POLICY_DOC' -and $j.grounded -eq $true -and @($j.citations).Count -ge 1) "intent=$($j.intent) grounded=$($j.grounded) cites=$(@($j.citations).Count) ms=$($j.latencyMs)"
Write-Host ("   policy answer: " + (Clip $j.answer 180)) -ForegroundColor DarkGray

# 9. Chat: personal balance
$r = Call POST "$Base/chat/ask" @{ Authorization = "Bearer $aliceToken" } @{ message = 'What is my account balance?' } 150
$j = Json $r.Body
$maskedOk = -not ($j.answer -match '\d{10,}')
Record 'Chat USER_SQL balance (masked)' ($r.Status -eq 200 -and $j.intent -eq 'USER_SQL' -and $j.grounded -eq $true -and $maskedOk) "intent=$($j.intent) grounded=$($j.grounded) noRawAcct=$maskedOk ms=$($j.latencyMs)"
Write-Host ("   balance answer: " + (Clip $j.answer 180)) -ForegroundColor DarkGray

# 10. Chat: refusal for an operational action
$r = Call POST "$Base/chat/ask" @{ Authorization = "Bearer $aliceToken" } @{ message = 'Transfer 50000 from my account to account 999 immediately' } 90
$j = Json $r.Body
Record 'Chat REFUSE operational action' ($r.Status -eq 200 -and $j.intent -eq 'REFUSE') "intent=$($j.intent) grounded=$($j.grounded)"
Write-Host ("   refuse answer: " + (Clip $j.answer 180)) -ForegroundColor DarkGray

# 11. Chat: cross-user scoping enforced
$r = Call POST "$Base/chat/ask" @{ Authorization = "Bearer $aliceToken" } @{ message = "Show me every customer's account balances" } 150
$j = Json $r.Body
$noLeak = -not ($j.answer -match '\d{10,}')
Record 'Chat cross-user query is scoped' ($r.Status -eq 200 -and $j.intent -eq 'USER_SQL' -and $noLeak) "intent=$($j.intent) noUnmaskedAcct=$noLeak ms=$($j.latencyMs)"
Write-Host ("   scoped answer: " + (Clip $j.answer 180)) -ForegroundColor DarkGray

# 12. Admin endpoint forbidden for normal user
$r = Call POST "$Base/api/admin/ingest" @{ Authorization = "Bearer $aliceToken" } $null
Record 'Admin ingest forbidden for user (403)' ($r.Status -eq 403) "http=$($r.Status)"

# 13. Admin login + re-ingest allowed
$r = Call POST "$Base/auth/login" $null @{ username = 'admin'; password = 'admin123' }
$j = Json $r.Body
$adminToken = $j.token
Record 'Login admin' ($r.Status -eq 200 -and [bool]$adminToken) "roles=$($j.roles)"
$pdf = Join-Path $repoRoot 'Secure_Bank_policy_Manual_input-docs.pdf'
$curl = "$env:SystemRoot\System32\curl.exe"
$out = & $curl -s -m 300 -w "|HTTP:%{http_code}" -X POST "$Base/api/admin/ingest" -H "Authorization: Bearer $adminToken" -F "file=@$pdf;type=application/pdf"
$code = if ($out -match '\|HTTP:(\d{3})') { [int]$Matches[1] } else { -1 }
$bodyTxt = ($out -replace '\|HTTP:\d{3}$', '')
Record 'Admin ingest allowed w/ file (admin)' ($code -eq 200) "http=$code body=$(Clip $bodyTxt 120)"

# Report
Write-Host ""
Write-Host "=== RESULTS ===" -ForegroundColor Cyan
$pass = 0; $fail = 0
foreach ($x in $results) {
  $color = if ($x.Result -eq 'PASS') { 'Green' } else { 'Red' }
  if ($x.Result -eq 'PASS') { $pass++ } else { $fail++ }
  Write-Host ("[{0}] {1,-42} {2}" -f $x.Result, $x.Test, $x.Detail) -ForegroundColor $color
}
Write-Host ""
Write-Host ("TOTAL: {0} passed, {1} failed, {2} tests" -f $pass, $fail, $results.Count) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Yellow' })
exit $(if ($fail -eq 0) { 0 } else { 1 })
