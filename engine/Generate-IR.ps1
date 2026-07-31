<#
  Generate-IR.ps1  -  Inspection Report document-generation engine.

  Deterministic, template-based. Clones the placeholdered DOCX template, performs
  run-level scalar replacement and repeatable-row expansion at the OOXML level,
  validates, then converts to PDF via Microsoft Word COM (native fidelity).

  Usage:
    .\Generate-IR.ps1 -Template ..\templates\IR_BO_TEMPLATE.docx `
                      -Data ..\samples\manavasi.json -OutDir ..\output
    (add -SkipPdf to skip PDF conversion)
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$Template,
  [Parameter(Mandatory)][string]$Data,
  [string]$OutDir = "$PSScriptRoot\..\output",
  [switch]$SkipPdf,
  [switch]$AllowBlanks  # replace remaining {{placeholders}} with — instead of erroring
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'

$Template = (Resolve-Path $Template).Path
$Data = (Resolve-Path $Data).Path
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$OutDir = (Resolve-Path $OutDir).Path

$json = Get-Content -Raw -Encoding UTF8 $Data | ConvertFrom-Json
$warnings = New-Object System.Collections.Generic.List[string]

# ---- amount-to-words (Indian) -------------------------------------------
function Convert-AmountToWords([long]$n) {
  if ($n -eq 0) { return 'Rupees Zero only' }
  $ones = @('', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten',
    'Eleven', 'Twelve', 'Thirteen', 'Fourteen', 'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen')
  $tens = @('', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety')
  function two($x) { if ($x -lt 20) { $ones[$x] } elseif ($x % 10 -eq 0) { $tens[[int]($x / 10)] } else { $tens[[int]($x / 10)] + ' ' + $ones[$x % 10] } }
  function three($x) { $h = [int]($x / 100); $r = $x % 100; $s = ''; if ($h) { $s = $ones[$h] + ' Hundred'; if ($r) { $s += ' ' } }; if ($r) { $s += (two $r) }; $s }
  $parts = @()
  $crore = [int]($n / 10000000); $n = $n % 10000000
  $lakh = [int]($n / 100000); $n = $n % 100000
  $thou = [int]($n / 1000); $n = $n % 1000
  $hund = [int]$n
  if ($crore) { $parts += (two $crore) + ' Crore' }
  if ($lakh) { $parts += (two $lakh) + ' Lakh' }
  if ($thou) { $parts += (two $thou) + ' Thousand' }
  if ($hund) { $parts += (three $hund) }
  'Rupees ' + ($parts -join ' ') + ' only'
}

# ---- deterministic clause rendering -------------------------------------
# Picks the approved wording for the selected outcome and resolves clause-local
# variables. Global variables ({{office_name}} etc.) pass through to the scalar pass.
function Render-Clause($lib, $clause, $outcome, $sel) {
  $tmpl = $null
  if ($clause.text -and ($clause.text.PSObject.Properties.Name -contains $outcome)) { $tmpl = $clause.text.$outcome }
  elseif ($lib.outcomeTemplates.PSObject.Properties.Name -contains $outcome) { $tmpl = $lib.outcomeTemplates.$outcome }
  if ($null -eq $tmpl) { $tmpl = $clause.text.satisfactory }
  $loc = @{
    clause_title = [string]$clause.title
    reference    = [string]$clause.reference
    observation  = if ($sel) { [string]$sel.observation } else { '' }
    instruction  = if ($sel) { [string]$sel.instruction } else { '' }
    custom_text  = if ($sel) { [string]$sel.custom_text } else { '' }
  }
  $out = [regex]::Replace($tmpl, '\{\{\s*(clause_title|observation|instruction|reference|custom_text)\s*\}\}', {
      param($m) $k = $m.Groups[1].Value; $v = $loc[$k]; if ($null -eq $v) { '' } else { [string]$v } })
  return (($out -replace '\s+', ' ').Trim())
}

# ---- load template ------------------------------------------------------
$work = Join-Path $env:TEMP ("irgen_" + [guid]::NewGuid().ToString('N'))
[System.IO.Compression.ZipFile]::ExtractToDirectory($Template, $work)
$docPath = Join-Path $work 'word\document.xml'
[xml]$doc = Get-Content -Raw -Encoding UTF8 $docPath
$ns = New-Object System.Xml.XmlNamespaceManager $doc.NameTable
$ns.AddNamespace('w', $W)

function Get-NodeText($node) { ($node.SelectNodes('.//w:t', $ns) | ForEach-Object { $_.InnerText }) -join '' }

# ---- 1. repeatable-row expansion (supports 2-up paired layout) -----------
# Token forms inside a template <w:tr>:
#   {{loop.field}}    value from record A (left)
#   {{loop.field@2}}  value from record B (right, paired tables) - blank if no B
#   {{loop.field+}}   joined A+B values for a shared/merged cell (e.g. remarks)
#   {{loop.#}}        1-based row serial
# A row containing any '@2' token is a PAIRED row: two records per output row.
function Resolve-RowTokens([string]$s, [string]$loopVar, $a, $b, [int]$serial) {
  $pat = '\{\{\s*' + [regex]::Escape($loopVar) + '\.(#|[A-Za-z0-9_]+)(@2)?(\+)?\s*\}\}'
  return [regex]::Replace($s, $pat, {
      param($m)
      $f = $m.Groups[1].Value; $two = $m.Groups[2].Success; $plus = $m.Groups[3].Success
      if ($f -eq '#') { return [string]$serial }
      if ($plus) {
        $vals = @()
        if ($a -and $null -ne $a.$f -and "$($a.$f)".Length) { $vals += [string]$a.$f }
        if ($b -and $null -ne $b.$f -and "$($b.$f)".Length) { $vals += [string]$b.$f }
        return ($vals -join '; ')
      }
      if ($two) { if ($b -and $null -ne $b.$f) { return [string]$b.$f } else { return '' } }
      $v = if ($a) { $a.$f } else { $null }
      if ($null -eq $v) { return '' } else { return [string]$v }
    })
}

$allRows = @($doc.SelectNodes('//w:tr', $ns))
$templateRows = @()
foreach ($tr in $allRows) {
  if ((Get-NodeText $tr) -match '\{\{\s*([A-Za-z0-9_]+)\.') { $templateRows += $tr }
}
foreach ($donor in $templateRows) {
  $txt = Get-NodeText $donor
  $null = $txt -match '\{\{\s*([A-Za-z0-9_]+)\.'
  $loopVar = $Matches[1]
  $paired = $txt -match '@2'
  $items = @($json.$loopVar)
  $parent = $donor.ParentNode
  if (-not $json.$loopVar -or $items.Count -eq 0) {
    # empty -> keep one row, clear tokens, mark first cell 'Nil'
    $warnings.Add("Repeatable table '$loopVar' had no data; rendered as 'Nil'.")
    foreach ($t in $donor.SelectNodes('.//w:t', $ns)) {
      $t.InnerText = [regex]::Replace($t.InnerText, '\{\{\s*' + [regex]::Escape($loopVar) + '\.(#|[A-Za-z0-9_]+)(@2)?(\+)?\s*\}\}', '')
    }
    $firstCell = $donor.SelectSingleNode('w:tc', $ns)
    if ($firstCell) { $fp = $firstCell.SelectSingleNode('w:p', $ns); if ($fp) {
        $ft = $fp.SelectSingleNode('.//w:t', $ns); if ($ft) { $ft.InnerText = 'Nil' } } }
    continue
  }
  $serial = 0
  $step = if ($paired) { 2 } else { 1 }
  for ($i = 0; $i -lt $items.Count; $i += $step) {
    $serial++
    $a = $items[$i]
    $b = if ($paired -and ($i + 1) -lt $items.Count) { $items[$i + 1] } else { $null }
    $clone = $donor.CloneNode($true)
    foreach ($t in $clone.SelectNodes('.//w:t', $ns)) { $t.InnerText = Resolve-RowTokens $t.InnerText $loopVar $a $b $serial }
    [void]$parent.InsertBefore($clone, $donor)
  }
  [void]$parent.RemoveChild($donor)
}

# ---- 1b. clause pass (deterministic paragraph generation) ---------------
$clausePath = Join-Path (Split-Path $Template) 'clauses.json'
if (Test-Path $clausePath) {
  $clauseLib = Get-Content -Raw -Encoding UTF8 $clausePath | ConvertFrom-Json
  $byId = @{}; foreach ($c in $clauseLib.clauses) { $byId[$c.id] = $c }
  foreach ($t in $doc.SelectNodes('//w:t', $ns)) {
    if ($t.InnerText -notmatch '\{\{clause_') { continue }
    $t.InnerText = [regex]::Replace($t.InnerText, '\{\{clause_([A-Za-z0-9_]+)\}\}', {
        param($m)
        $id = $m.Groups[1].Value
        $clause = $byId[$id]
        if (-not $clause) { return $m.Value }
        $sel = $null; if ($json.clauses) { $sel = $json.clauses.$id }
        $outcome = if ($sel -and $sel.outcome) { $sel.outcome } else { 'satisfactory' }
        Render-Clause $clauseLib $clause $outcome $sel
      })
  }
}

# ---- 2. scalar replacement ----------------------------------------------
# Build flat scalar map from all non-array top-level properties.
$scalars = @{}
foreach ($prop in $json.PSObject.Properties) {
  if ($prop.Value -is [System.Array]) { continue }
  if ($prop.Value -is [System.Management.Automation.PSCustomObject]) { continue }
  $scalars[$prop.Name] = [string]$prop.Value
}
# auto-derive *_words from matching amount field if absent
foreach ($k in @($scalars.Keys)) {
  if ($k -like '*_amount' -and -not $scalars.ContainsKey($k -replace '_amount$', '_words')) {
    $wk = ($k -replace '_amount$', '') + '_words'
    [void]$scalars  # no-op placeholder
  }
}

foreach ($t in $doc.SelectNodes('//w:t', $ns)) {
  $s = $t.InnerText
  if ($s -notmatch '\{\{') { continue }
  $s = [regex]::Replace($s, '\{\{\s*([A-Za-z0-9_]+)\s*\}\}', {
      param($m) $key = $m.Groups[1].Value
      if ($scalars.ContainsKey($key)) { return $scalars[$key] }
      return $m.Value   # leave unresolved for the scan to catch
    })
  $t.InnerText = $s
}

# ---- 3. save document.xml ----------------------------------------------
$settings = New-Object System.Xml.XmlWriterSettings
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$settings.Indent = $false
$wr = [System.Xml.XmlWriter]::Create($docPath, $settings)
$doc.Save($wr); $wr.Close()

# ---- 4. unresolved-placeholder scan ------------------------------------
$finalXml = Get-Content -Raw -Encoding UTF8 $docPath
$unresolved = [regex]::Matches($finalXml, '\{\{[^}]*\}\}') | ForEach-Object { $_.Value } | Sort-Object -Unique
if ($unresolved.Count -gt 0) {
  if ($AllowBlanks) {
    # replace every remaining placeholder with an em-dash (U+2014) and rewrite document.xml
    $emDash = [string][char]0x2014
    foreach ($t in $doc.SelectNodes('//w:t', $ns)) {
      if ($t.InnerText -match '\{\{') {
        $t.InnerText = [regex]::Replace($t.InnerText, '\{\{\s*[A-Za-z0-9_]+\s*\}\}', $emDash)
      }
    }
    $wr2 = [System.Xml.XmlWriter]::Create($docPath, $settings)
    $doc.Save($wr2); $wr2.Close()
    $warnings.Add(("AllowBlanks: filled {0} unresolved placeholder(s) with em-dash: {1}" -f $unresolved.Count, ($unresolved -join ', ')))
  } else {
    Remove-Item $work -Recurse -Force
    throw "BLOCKED: unresolved placeholders remain: $($unresolved -join ', ')"
  }
}

# ---- 5. build filename & repackage DOCX --------------------------------
function Clean([string]$s) { ($s -replace '[\\/:*?""<>|]', '_' -replace '\s+', '_').Trim('_') }
$fnDate = (Clean $scalars['inspection_date']) -replace '\.', '-'
$base = "IR_{0}_{1}_{2}_V01" -f (Clean $scalars['office_name']), (Clean $scalars['office_type']), $fnDate
$docxOut = Join-Path $OutDir "$base.docx"
if (Test-Path $docxOut) { Remove-Item $docxOut -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory($work, $docxOut)
Remove-Item $work -Recurse -Force

$docxInfo = Get-Item $docxOut
$docxHash = (Get-FileHash $docxOut -Algorithm SHA256).Hash
Write-Host "DOCX : $docxOut ($($docxInfo.Length) bytes)" -ForegroundColor Green

# ---- 6. PDF via Word COM (non-fatal: DOCX always returned even if PDF fails) ----
$pdfOut = $null
$pdfInfo = $null
$pdfHash = $null
$pageCount = 0
$pdfError = $null
if (-not $SkipPdf) {
  $pdfOut = [string](Join-Path $OutDir "$base.pdf")
  if (Test-Path $pdfOut) { (Get-Item $pdfOut).IsReadOnly = $false; Remove-Item $pdfOut -Force }
  (Get-Item $docxOut).IsReadOnly = $false
  $maxAttempts = 2
  $success = $false
  for ($attempt = 1; $attempt -le $maxAttempts -and -not $success; $attempt++) {
    $word = $null
    try {
      $word = New-Object -ComObject Word.Application
      $word.Visible = $false; $word.DisplayAlerts = 0
      $wdoc = $word.Documents.Open($docxOut, $false, $true)   # ReadOnly (export repaginates PAGE fields)
      $wdoc.ExportAsFixedFormat($pdfOut, 17)                  # 17 = wdExportFormatPDF
      $pageCount = $wdoc.ComputeStatistics(2)                 # 2 = wdStatisticPages
      $wdoc.Close($false)
      $success = $true
    } catch {
      $pdfError = $_.Exception.Message
      Write-Warning ("Word COM attempt {0}/{1} failed: {2}" -f $attempt, $maxAttempts, $pdfError)
      Get-Process winword -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
      Start-Sleep -Milliseconds 800
    } finally {
      if ($word) { try { $word.Quit() } catch {}; try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word) } catch {} }
      [GC]::Collect(); [GC]::WaitForPendingFinalizers()
    }
  }
  if ($success) {
    $pdfInfo = Get-Item $pdfOut
    $pdfHash = (Get-FileHash $pdfOut -Algorithm SHA256).Hash
    Write-Host "PDF  : $pdfOut ($($pdfInfo.Length) bytes, $pageCount pages)" -ForegroundColor Green
  } else {
    $pdfOut = $null
    $warnings.Add("PDF generation skipped after $maxAttempts Word COM failures: $pdfError. DOCX was produced; open it in Word and use File > Save As > PDF.")
    Write-Warning "PDF step gave up; DOCX is still available."
  }
}

# ---- 7. export log ------------------------------------------------------
$log = [ordered]@{
  reportBase    = $base
  template      = (Split-Path $Template -Leaf)
  data          = (Split-Path $Data -Leaf)
  generatedAt   = (Get-Date).ToString('s')
  docx          = (Split-Path $docxOut -Leaf)
  docxBytes     = $docxInfo.Length
  docxSha256    = $docxHash
  pdf           = if ($pdfOut) { Split-Path $pdfOut -Leaf } else { $null }
  pdfBytes      = if ($pdfOut) { (Get-Item $pdfOut).Length } else { $null }
  pdfSha256     = if ($pdfOut) { $pdfHash } else { $null }
  pages         = if (-not $SkipPdf) { $pageCount } else { $null }
  warnings      = $warnings
}
$logPath = Join-Path $OutDir "$base.export.json"
$log | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $logPath
if ($warnings.Count) { $warnings | ForEach-Object { Write-Host "  WARN: $_" -ForegroundColor Yellow } }
Write-Host "LOG  : $logPath" -ForegroundColor Green
