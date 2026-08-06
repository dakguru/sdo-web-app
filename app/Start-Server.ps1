<#
  Start-Server.ps1 — local Inspection Report Builder web app (zero dependencies).
  Serves the SPA and a small JSON API; generates DOCX/PDF via the document engine.

  Run:   .\app\Start-Server.ps1   (then open http://localhost:8770)
  Stop:  Ctrl+C
#>
[CmdletBinding()]
param([int]$Port = $(if ($env:PORT) { [int]$env:PORT } else { 8770 }))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$wwwroot = Join-Path $PSScriptRoot 'wwwroot'
$dataDir = Join-Path $PSScriptRoot 'data'
$draftsDir = Join-Path $dataDir 'drafts'
$outDir = Join-Path $root 'output'
$tplDir = Join-Path $root 'templates'
$genScript = Join-Path $root 'engine\Generate-IR.ps1'
$template = Join-Path $tplDir 'IR_BO_TEMPLATE.docx'
foreach ($d in @($dataDir, $draftsDir, $outDir)) { if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null } }

function Read-Body($ctx) {
  $sr = New-Object System.IO.StreamReader($ctx.Request.InputStream, $ctx.Request.ContentEncoding)
  $b = $sr.ReadToEnd(); $sr.Close(); return $b
}
function Send-Text($ctx, [string]$text, [string]$type = 'text/plain; charset=utf-8', [int]$code = 200) {
  $ctx.Response.StatusCode = $code
  $ctx.Response.ContentType = $type
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
  $ctx.Response.ContentLength64 = $bytes.Length
  # HEAD (used by health-check probes) must not carry a body — writing one throws.
  if ($ctx.Request.HttpMethod -ne 'HEAD') { $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length) }
  $ctx.Response.OutputStream.Close()
}
function Send-Json($ctx, $obj, [int]$code = 200) { Send-Text $ctx ($obj | ConvertTo-Json -Depth 12 -Compress) 'application/json; charset=utf-8' $code }
function Send-File($ctx, [string]$path, [string]$type) {
  if (-not (Test-Path $path)) { Send-Text $ctx 'Not found' 'text/plain' 404; return }
  $bytes = [System.IO.File]::ReadAllBytes($path)
  $ctx.Response.StatusCode = 200; $ctx.Response.ContentType = $type
  $ctx.Response.ContentLength64 = $bytes.Length
  if ($ctx.Request.HttpMethod -ne 'HEAD') { $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length) }
  $ctx.Response.OutputStream.Close()
}
function MimeOf([string]$p) {
  switch -regex ($p) {
    '\.html?$' { 'text/html; charset=utf-8' }
    '\.js$' { 'application/javascript; charset=utf-8' }
    '\.css$' { 'text/css; charset=utf-8' }
    '\.json$' { 'application/json; charset=utf-8' }
    '\.pdf$' { 'application/pdf' }
    '\.docx$' { 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }
    '\.png$' { 'image/png' }
    '\.jpe?g$' { 'image/jpeg' }
    '\.webp$' { 'image/webp' }
    '\.svg$' { 'image/svg+xml' }
    default { 'application/octet-stream' }
  }
}

# ---------- multi-draft helpers ----------
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function New-DraftId { 'dft_' + (Get-Date -Format 'yyyyMMddHHmmss') + '_' + ('{0:0000}' -f (Get-Random -Maximum 9999)) }
function Test-DraftId([string]$id) { return ($id -and $id -match '^dft_[A-Za-z0-9_]+$') }
function Set-ActiveDraft([string]$id) {
  $f = Join-Path $draftsDir '_active.json'
  [System.IO.File]::WriteAllText($f, ('{"activeId":"' + $id + '"}'), $utf8NoBom)
}
function Get-ActiveDraft {
  $f = Join-Path $draftsDir '_active.json'
  if (Test-Path $f) {
    try { return ((Get-Content -Raw -Encoding UTF8 $f) | ConvertFrom-Json).activeId } catch { return '' }
  }
  return ''
}
function Ensure-Drafts {
  # one-time seed: migrate legacy current.json, or seed from samples/manavasi.json
  $existing = Get-ChildItem $draftsDir -Filter 'dft_*.json' -ErrorAction SilentlyContinue
  if ($existing) { return }
  $legacy = Join-Path $draftsDir 'current.json'
  $sample = Join-Path $root 'samples\manavasi.json'
  $seedRaw = $null
  if (Test-Path $legacy) {
    $seedRaw = (Get-Content -Raw -Encoding UTF8 $legacy).Trim()
  } elseif (Test-Path $sample) {
    $seedRaw = (Get-Content -Raw -Encoding UTF8 $sample).Trim()
  }
  if (-not $seedRaw) { $seedRaw = '{"clauses":{}}' }
  $newId = New-DraftId
  [System.IO.File]::WriteAllText((Join-Path $draftsDir "$newId.json"), $seedRaw, $utf8NoBom)
  Set-ActiveDraft $newId
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
Write-Host "Inspection Report Builder running at http://localhost:$Port  (Ctrl+C to stop)" -ForegroundColor Green

try {
  while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $req = $ctx.Request
    $path = $req.Url.AbsolutePath
    try {
      # Homepage dashboard (served at /). The Leave Orders app is at /leave-orders.
      # The Inspection Report Builder lives at /ir-builder/.
      if ($path -eq '/' ) { Send-File $ctx (Join-Path $root 'Leave Orders\homepage.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/ir-builder' -or $path -eq '/ir-builder/') { Send-File $ctx (Join-Path $wwwroot 'index.html') (MimeOf 'index.html'); continue }

      # Static assets (logos, images) served from project root
      if ($path -eq '/assets/app-logo.jpg')       { Send-File $ctx (Join-Path $root 'App Logo.jpg') 'image/jpeg'; continue }
      if ($path -eq '/assets/india-post-logo.png') { Send-File $ctx (Join-Path $root 'India Post logo.png') 'image/png'; continue }

      if ($path -eq '/api/bootstrap') {
        Ensure-Drafts
        # assemble from raw JSON text to avoid PowerShell array-serialization quirks
        $rd = { param($p, $def) if (Test-Path $p) { (Get-Content -Raw -Encoding UTF8 $p).Trim() } else { $def } }
        $clausesRaw = & $rd (Join-Path $tplDir 'clauses.json') '{}'
        $officesRaw = & $rd (Join-Path $dataDir 'offices.json') '[]'
        $staffRaw = & $rd (Join-Path $dataDir 'staff.json') '[]'
        $villagesRaw = & $rd (Join-Path $dataDir 'villages.json') '{}'
        $settingsRaw = & $rd (Join-Path $dataDir 'settings.json') '{}'
        # list all drafts (id, updatedAt, content)
        $draftFiles = Get-ChildItem $draftsDir -Filter 'dft_*.json' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
        $items = @()
        foreach ($f in $draftFiles) {
          $raw = (Get-Content -Raw -Encoding UTF8 $f.FullName).Trim()
          if (-not $raw) { $raw = '{}' }
          $id = [IO.Path]::GetFileNameWithoutExtension($f.Name)
          $u  = $f.LastWriteTime.ToString('s')
          $items += '{"id":"' + $id + '","updatedAt":"' + $u + '","content":' + $raw + '}'
        }
        $draftsRaw = '[' + ($items -join ',') + ']'
        # active id (fall back to most recently saved)
        $activeId = Get-ActiveDraft
        if (-not $activeId -and $draftFiles) { $activeId = [IO.Path]::GetFileNameWithoutExtension($draftFiles[0].Name) }
        if ($activeId) { Set-ActiveDraft $activeId }
        # active draft content (kept as `draft` for backward compat with frontend)
        $draftRaw = '{}'
        $activeFp = if ($activeId) { Join-Path $draftsDir "$activeId.json" } else { $null }
        if ($activeFp -and (Test-Path $activeFp)) {
          $draftRaw = (Get-Content -Raw -Encoding UTF8 $activeFp).Trim()
          if (-not $draftRaw) { $draftRaw = '{}' }
        }
        $repItems = @()
        Get-ChildItem $outDir -Filter '*.export.json' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | ForEach-Object { $repItems += (Get-Content -Raw -Encoding UTF8 $_.FullName).Trim() }
        $reportsRaw = '[' + ($repItems -join ',') + ']'
        $out = '{"settings":' + $settingsRaw + ',"offices":' + $officesRaw + ',"staff":' + $staffRaw + ',"villages":' + $villagesRaw + ',"clauses":' + $clausesRaw + ',"draft":' + $draftRaw + ',"drafts":' + $draftsRaw + ',"activeDraftId":"' + $activeId + '","reports":' + $reportsRaw + '}'
        Send-Text $ctx $out 'application/json; charset=utf-8'
        continue
      }

      if ($path -eq '/api/draft' -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $id = $req.QueryString['id']
        if (-not (Test-DraftId $id)) { Send-Json $ctx ([ordered]@{ ok = $false; error = 'Missing or invalid draft id' }) 400; continue }
        [System.IO.File]::WriteAllText((Join-Path $draftsDir "$id.json"), $body, $utf8NoBom)
        Send-Json $ctx ([ordered]@{ ok = $true; id = $id; savedAt = (Get-Date).ToString('s') })
        continue
      }

      if ($path -eq '/api/draft/new' -and $req.HttpMethod -eq 'POST') {
        $id = New-DraftId
        $blank = '{"clauses":{}}'
        [System.IO.File]::WriteAllText((Join-Path $draftsDir "$id.json"), $blank, $utf8NoBom)
        Set-ActiveDraft $id
        $u = (Get-Date).ToString('s')
        Send-Text $ctx ('{"ok":true,"id":"' + $id + '","updatedAt":"' + $u + '","content":' + $blank + '}') 'application/json; charset=utf-8'
        continue
      }

      if ($path -eq '/api/draft/delete' -and $req.HttpMethod -eq 'POST') {
        $id = $req.QueryString['id']
        if (-not (Test-DraftId $id)) { Send-Json $ctx ([ordered]@{ ok = $false; error = 'Missing or invalid id' }) 400; continue }
        $fp = Join-Path $draftsDir "$id.json"
        if (Test-Path $fp) { Remove-Item $fp -Force }
        if ((Get-ActiveDraft) -eq $id) {
          $remain = Get-ChildItem $draftsDir -Filter 'dft_*.json' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
          if ($remain) { Set-ActiveDraft ([IO.Path]::GetFileNameWithoutExtension($remain.Name)) } else { Set-ActiveDraft '' }
        }
        Send-Json $ctx ([ordered]@{ ok = $true })
        continue
      }

      if ($path -eq '/api/draft/activate' -and $req.HttpMethod -eq 'POST') {
        $id = $req.QueryString['id']
        if (-not (Test-DraftId $id)) { Send-Json $ctx ([ordered]@{ ok = $false; error = 'Missing or invalid id' }) 400; continue }
        if (-not (Test-Path (Join-Path $draftsDir "$id.json"))) { Send-Json $ctx ([ordered]@{ ok = $false; error = 'No such draft' }) 404; continue }
        Set-ActiveDraft $id
        Send-Json $ctx ([ordered]@{ ok = $true; activeId = $id })
        continue
      }

      if (($path -eq '/api/offices' -or $path -eq '/api/staff') -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $fileName = if ($path -eq '/api/offices') { 'offices.json' } else { 'staff.json' }
        $fp = Join-Path $dataDir $fileName
        if (Test-Path $fp) { Copy-Item $fp ($fp + '.prev') -Force -ErrorAction SilentlyContinue }
        [System.IO.File]::WriteAllText($fp, $body, $utf8NoBom)
        Send-Json $ctx ([ordered]@{ ok = $true; file = $fileName; savedAt = (Get-Date).ToString('s') })
        continue
      }

      # ---------- Employee Directory (Sub Divisional Manager) ----------
      # Monthly payroll extracts (DS + GDS) are parsed in the browser and the
      # combined dataset is persisted here so it survives between sessions and
      # can be refreshed by re-uploading next month.
      if ($path -eq '/api/employees' -and $req.HttpMethod -eq 'GET') {
        $ef = Join-Path $dataDir 'employees.json'
        if (Test-Path $ef) { Send-File $ctx $ef 'application/json; charset=utf-8' }
        else { Send-Text $ctx '{"employees":[],"meta":{}}' 'application/json; charset=utf-8' }
        continue
      }
      if ($path -eq '/api/employees' -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $ef = Join-Path $dataDir 'employees.json'
        if (Test-Path $ef) { Copy-Item $ef ($ef + '.prev') -Force -ErrorAction SilentlyContinue }
        [System.IO.File]::WriteAllText($ef, $body, $utf8NoBom)
        Send-Json $ctx ([ordered]@{ ok = $true; savedAt = (Get-Date).ToString('s'); bytes = $body.Length })
        continue
      }

      # Staff mobile numbers (Employee_id -> phone), matched from the Mobile
      # Numbers roster. Kept separate from employees.json so it survives the
      # monthly payroll re-uploads (which do not carry phone numbers).
      if ($path -eq '/api/mobiles' -and $req.HttpMethod -eq 'GET') {
        $mf = Join-Path $dataDir 'mobiles.json'
        if (Test-Path $mf) { Send-File $ctx $mf 'application/json; charset=utf-8' }
        else { Send-Text $ctx '{"meta":{},"map":{}}' 'application/json; charset=utf-8' }
        continue
      }
      if ($path -eq '/api/mobiles' -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $mf = Join-Path $dataDir 'mobiles.json'
        if (Test-Path $mf) { Copy-Item $mf ($mf + '.prev') -Force -ErrorAction SilentlyContinue }
        [System.IO.File]::WriteAllText($mf, $body, $utf8NoBom)
        Send-Json $ctx ([ordered]@{ ok = $true; savedAt = (Get-Date).ToString('s'); bytes = $body.Length })
        continue
      }

      # Temporary Arrangements (outsource-resource substitutes). Persisted so the
      # uploaded arrangement extract survives between sessions.
      if ($path -eq '/api/arrangements' -and $req.HttpMethod -eq 'GET') {
        $af = Join-Path $dataDir 'arrangements.json'
        if (Test-Path $af) { Send-File $ctx $af 'application/json; charset=utf-8' }
        else { Send-Text $ctx '{"meta":{},"arrangements":[]}' 'application/json; charset=utf-8' }
        continue
      }
      if ($path -eq '/api/arrangements' -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $af = Join-Path $dataDir 'arrangements.json'
        if (Test-Path $af) { Copy-Item $af ($af + '.prev') -Force -ErrorAction SilentlyContinue }
        [System.IO.File]::WriteAllText($af, $body, $utf8NoBom)
        Send-Json $ctx ([ordered]@{ ok = $true; savedAt = (Get-Date).ToString('s'); bytes = $body.Length })
        continue
      }

      # Sanctioned Posts (Static list generated from Excel)
      if ($path -eq '/api/posts' -and $req.HttpMethod -eq 'GET') {
        $pf = Join-Path $dataDir 'posts.json'
        if (Test-Path $pf) { Send-File $ctx $pf 'application/json; charset=utf-8' }
        else { Send-Text $ctx '[]' 'application/json; charset=utf-8' }
        continue
      }
      
      # Vacancies (Manually tracked by users)
      if ($path -eq '/api/vacancies' -and $req.HttpMethod -eq 'GET') {
        $vf = Join-Path $dataDir 'vacancies.json'
        if (Test-Path $vf) { Send-File $ctx $vf 'application/json; charset=utf-8' }
        else { Send-Text $ctx '[]' 'application/json; charset=utf-8' }
        continue
      }
      if ($path -eq '/api/vacancies' -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $vf = Join-Path $dataDir 'vacancies.json'
        if (Test-Path $vf) { Copy-Item $vf ($vf + '.prev') -Force -ErrorAction SilentlyContinue }
        [System.IO.File]::WriteAllText($vf, $body, $utf8NoBom)
        Send-Json $ctx ([ordered]@{ ok = $true; savedAt = (Get-Date).ToString('s'); bytes = $body.Length })
        continue
      }

      # Office master (sub-division-wise office details + hierarchy).
      if ($path -eq '/api/offices-master' -and $req.HttpMethod -eq 'GET') {
        $of = Join-Path $dataDir 'office_master.json'
        if (Test-Path $of) { Send-File $ctx $of 'application/json; charset=utf-8' }
        else { Send-Text $ctx '{"meta":{},"offices":[]}' 'application/json; charset=utf-8' }
        continue
      }
      if ($path -eq '/api/offices-master' -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $of = Join-Path $dataDir 'office_master.json'
        if (Test-Path $of) { Copy-Item $of ($of + '.prev') -Force -ErrorAction SilentlyContinue }
        [System.IO.File]::WriteAllText($of, $body, $utf8NoBom)
        Send-Json $ctx ([ordered]@{ ok = $true; savedAt = (Get-Date).ToString('s'); bytes = $body.Length })
        continue
      }

      if ($path -eq '/api/generate' -and $req.HttpMethod -eq 'POST') {
        $body = Read-Body $ctx
        $allowBlanks = ($req.QueryString['allowBlanks'] -eq '1')
        $genData = Join-Path $dataDir '_generate.json'
        [System.IO.File]::WriteAllText($genData, $body, (New-Object System.Text.UTF8Encoding($false)))
        try {
          # kill any leftover Word + WPS instances that could hold COM in a bad state
          Get-Process winword,wps,wpsoffice -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
          Start-Sleep -Milliseconds 600
          if ($allowBlanks) {
            & $genScript -Template $template -Data $genData -OutDir $outDir -AllowBlanks | Out-Null
          } else {
            & $genScript -Template $template -Data $genData -OutDir $outDir | Out-Null
          }
          $log = Get-ChildItem $outDir -Filter '*.export.json' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
          $logRaw = (Get-Content -Raw -Encoding UTF8 $log.FullName).Trim()
          Send-Text $ctx ('{"ok":true,"result":' + $logRaw + '}') 'application/json; charset=utf-8'
        } catch {
          Send-Json $ctx ([ordered]@{ ok = $false; error = "$($_.Exception.Message)" }) 200
        }
        continue
      }

      if ($path -like '/download/*' -or $path -like '/output/*') {
        $name = Split-Path $path -Leaf
        Send-File $ctx (Join-Path $outDir $name) (MimeOf $name)
        continue
      }

      # Employee Directory page (part of the Sub Divisional Manager app).
      if ($path -eq '/employees' -or $path -eq '/employees/') {
        Send-File $ctx (Join-Path $root 'Leave Orders\employees.html') (MimeOf 'index.html')
        continue
      }

      # Temporary Arrangements page.
      if ($path -eq '/arrangements' -or $path -eq '/arrangements/') {
        Send-File $ctx (Join-Path $root 'Leave Orders\arrangements.html') (MimeOf 'index.html')
        continue
      }

      # Office Management page.
      if ($path -eq '/office-management' -or $path -eq '/office-management/') {
        Send-File $ctx (Join-Path $root 'Leave Orders\office-management.html') (MimeOf 'index.html')
        continue
      }

      # Remaining Leave Orders top-level pages (mirror of vercel.json rewrites so
      # the local server matches production routing).
      if ($path -eq '/login' -or $path -eq '/login/')                     { Send-File $ctx (Join-Path $root 'Leave Orders\login.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/birthdays' -or $path -eq '/birthdays/')             { Send-File $ctx (Join-Path $root 'Leave Orders\birthdays.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/retirements' -or $path -eq '/retirements/')         { Send-File $ctx (Join-Path $root 'Leave Orders\retirements.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/sanctioned-posts' -or $path -eq '/sanctioned-posts/') { Send-File $ctx (Join-Path $root 'Leave Orders\sanctioned-posts.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/update-mobile' -or $path -eq '/update-mobile/')       { Send-File $ctx (Join-Path $root 'Leave Orders\update-mobile.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/high-value-memos' -or $path -eq '/high-value-memos/') { Send-File $ctx (Join-Path $root 'Leave Orders\hmvm.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/ir-library' -or $path -eq '/ir-library/')             { Send-File $ctx (Join-Path $root 'Leave Orders\ir-library.html') (MimeOf 'index.html'); continue }
      if ($path -eq '/verification' -or $path -eq '/verification/')         { Send-File $ctx (Join-Path $root 'Leave Orders\verification.html') (MimeOf 'index.html'); continue }

      # Pages reference auth-guard.js by a root-relative path (e.g. from /employees
      # the browser requests /auth-guard.js); serve it from the Leave Orders folder.
      if ($path -eq '/auth-guard.js') { Send-File $ctx (Join-Path $root 'Leave Orders\auth-guard.js') 'application/javascript; charset=utf-8'; continue }

      # Static data snapshots (vercel maps /data/* -> /app/data/*). Used by the
      # static fallbacks in sanctioned-posts and karur-scope.
      if ($path -like '/data/*') {
        $rel = [uri]::UnescapeDataString($path.Substring('/data/'.Length))
        if ($rel -match '\.\.') { Send-Text $ctx 'Bad request' 'text/plain' 400; continue }
        $file = Join-Path $dataDir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
        if ((Test-Path $file) -and -not (Get-Item $file).PSIsContainer) { Send-File $ctx $file (MimeOf $file); continue }
        Send-Text $ctx 'Not found' 'text/plain' 404; continue
      }

      # Leave Orders sub-app — served straight from the original folder so the
      # user's edits to that file flow through without a copy step.
      if ($path -eq '/leave-orders' -or $path -eq '/leave-orders/') {
        Send-File $ctx (Join-Path $root 'Leave Orders\index.html') (MimeOf 'index.html')
        continue
      }
      if ($path -like '/leave-orders/*') {
        $rel  = [uri]::UnescapeDataString($path.Substring('/leave-orders/'.Length))
        if ($rel -match '\.\.') { Send-Text $ctx 'Bad request' 'text/plain' 400; continue }
        $file = Join-Path $root (Join-Path 'Leave Orders' ($rel -replace '/', [IO.Path]::DirectorySeparatorChar))
        if ((Test-Path $file) -and -not (Get-Item $file).PSIsContainer) { Send-File $ctx $file (MimeOf $file); continue }
        Send-Text $ctx 'Not found' 'text/plain' 404; continue
      }

      # static files from wwwroot
      $rel = $path.TrimStart('/')
      $file = Join-Path $wwwroot $rel
      if ((Test-Path $file) -and -not (Get-Item $file).PSIsContainer) { Send-File $ctx $file (MimeOf $file); continue }

      Send-Text $ctx 'Not found' 'text/plain' 404
    } catch {
      # never let one bad request (or a client that hung up) kill the listener loop
      try { Send-Text $ctx "Server error: $($_.Exception.Message)" 'text/plain' 500 } catch {}
    }
  }
} finally { $listener.Stop() }
