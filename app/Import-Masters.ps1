<#
  Import-Masters.ps1 — build the app's master data from the source files the
  user maintains, so the inspection workspace can auto-fetch staff + villages
  for whichever office is being inspected.

  Sources (override with params):
    -GsFiles  : one or more GDS pay-bill .xlsx (cols incl. Employee_name,
                Post_desc, Office_desc, Level, Date_of_birth, Date_of_join, ...)
    -VillageDoc : the "village sorting List ... .docx" (60 per-BO tables)

  Outputs (app/data):
    staff.json     — [{name,designation,employee_id,trca,dob,doa,office,
                       gender,status,post_desc,level,account_office}]
    villages.json  — { "<OFFICE>": {office,account_office,account_office_pin,
                       fixed:[...],unfixed:[...]} }

  Re-run any time the source files change. Reads Excel via COM (read-only) and
  the .docx via OOXML unzip. Existing files are backed up to *.prev.

  Source-file resolution (no hardcoded machine paths — portable):
    1. -GsFiles / -VillageDoc parameters, if given (highest priority)
    2. <project>\data\sources.json  (override for files kept elsewhere, e.g. a
       live G:\ pay-bill; relative paths are resolved against the project root)
    3. <project>\source-files\      (auto-discovered: GS*.xlsx + *village*.docx)
  So on a fresh machine you can just drop the three files into source-files\.
#>
[CmdletBinding()]
param(
  [string[]]$GsFiles,
  [string]$VillageDoc,
  [string]$OfficeMaster,
  [string]$WorkingHoursDir,
  [string]$MailArrangement,
  [string]$AptOfficeMaster,
  [string]$DataDir = "$PSScriptRoot\data"
)
$ErrorActionPreference = 'Stop'
$root   = Split-Path $PSScriptRoot -Parent
$srcDir = Join-Path $root 'source-files'
function Resolve-Src([string]$p) { if ([System.IO.Path]::IsPathRooted($p)) { $p } else { Join-Path $root $p } }

# load optional per-machine config
$cfg = $null
$cfgPath = Join-Path $DataDir 'sources.json'
if (Test-Path $cfgPath) { try { $cfg = Get-Content -Raw -Encoding UTF8 $cfgPath | ConvertFrom-Json } catch { Write-Warning "Bad sources.json: $($_.Exception.Message)" } }

if (-not $GsFiles -or $GsFiles.Count -eq 0) {
  if ($cfg -and $cfg.gsFiles) { $GsFiles = @($cfg.gsFiles | ForEach-Object { Resolve-Src $_ }) }
  elseif (Test-Path $srcDir) { $GsFiles = @(Get-ChildItem $srcDir -Filter 'GS*.xlsx' -File | Sort-Object Name | Select-Object -ExpandProperty FullName) }
}
if (-not $VillageDoc) {
  if ($cfg -and $cfg.villageDoc) { $VillageDoc = Resolve-Src $cfg.villageDoc }
  elseif (Test-Path $srcDir) { $VillageDoc = (Get-ChildItem $srcDir -Filter '*village*.docx' -File | Select-Object -First 1 -ExpandProperty FullName) }
}
if (-not $OfficeMaster) {
  if ($cfg -and $cfg.officeMaster) { $OfficeMaster = Resolve-Src $cfg.officeMaster }
  elseif (Test-Path $srcDir) { $OfficeMaster = (Get-ChildItem $srcDir -Filter '*Office*Master*.xls*' -File | Select-Object -First 1 -ExpandProperty FullName) }
  if (-not $OfficeMaster) {
    $rootCandidate = Join-Path $root 'Office Master.xls'
    if (Test-Path $rootCandidate) { $OfficeMaster = $rootCandidate }
  }
}
if (-not $WorkingHoursDir) {
  if ($cfg -and $cfg.workingHoursDir) { $WorkingHoursDir = Resolve-Src $cfg.workingHoursDir }
  elseif (Test-Path (Join-Path $srcDir 'BO WORKING HOURS')) { $WorkingHoursDir = (Join-Path $srcDir 'BO WORKING HOURS') }
  elseif (Test-Path (Join-Path $root 'BO WORKING HOURS')) { $WorkingHoursDir = (Join-Path $root 'BO WORKING HOURS') }
}
if (-not $MailArrangement) {
  if ($cfg -and $cfg.mailArrangement) { $MailArrangement = Resolve-Src $cfg.mailArrangement }
  elseif (Test-Path $srcDir) { $MailArrangement = (Get-ChildItem $srcDir -Filter '*Mail*Arrangement*.xls*' -File -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName) }
  if (-not $MailArrangement) {
    $cand = Join-Path $root 'Mail Arrangement.xlsx'
    if (Test-Path $cand) { $MailArrangement = $cand }
  }
}
if (-not $AptOfficeMaster) {
  if ($cfg -and $cfg.aptOfficeMaster) { $AptOfficeMaster = Resolve-Src $cfg.aptOfficeMaster }
  elseif (Test-Path $srcDir) { $AptOfficeMaster = (Get-ChildItem $srcDir -Filter '*Karur*SubDivision*.xls*' -File -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName) }
  if (-not $AptOfficeMaster) {
    $cand = Join-Path $root 'Karur_SubDivision_Offices_PanchayatHQ.xlsx'
    if (Test-Path $cand) { $AptOfficeMaster = $cand }
  }
}
if (-not $GsFiles -or $GsFiles.Count -eq 0) { throw "No GS pay-bill .xlsx found. Put GS*.xlsx in '$srcDir', set them in data\sources.json, or pass -GsFiles." }
if (-not $VillageDoc) { Write-Warning "No village .docx found in '$srcDir' / sources.json - villages.json will be empty." }
if (-not $OfficeMaster) { Write-Warning "No Office Master.xls found in '$srcDir' or project root - offices.json will not be regenerated." }
if (-not $WorkingHoursDir) { Write-Warning "No 'BO WORKING HOURS' folder found in '$srcDir' or project root - working-hours fields will be blank." }
if (-not $MailArrangement) { Write-Warning "No 'Mail Arrangement.xlsx' found - mail_arrangement field will be blank." }
if (-not $AptOfficeMaster) { Write-Warning "No 'Karur_SubDivision_Offices_PanchayatHQ.xlsx' found - APT Office IDs will be blank." }
Write-Host "GS files:        $($GsFiles -join '; ')"
Write-Host "Village doc:     $VillageDoc"
Write-Host "Office master:   $OfficeMaster"
Write-Host "Working-hrs dir: $WorkingHoursDir"
Write-Host "Mail arrangmnt:  $MailArrangement"
Write-Host "APT office mstr: $AptOfficeMaster`n"

function Norm-Office([string]$s) {
  if (-not $s) { return '' }
  $t = ($s -replace ' ', ' ').Trim()
  $t = $t -replace '\s*\bB\.?\s*O\.?\s*$', ''   # trailing B.O / BO
  $t = $t -replace '\s*\bS\.?\s*O\.?\s*$', ''   # trailing S.O / SO
  ($t -replace '\s+', ' ').Trim().ToUpper()
}
function Conv-Date($v) {
  if ($null -eq $v -or "$v" -eq '') { return '' }
  $d = $null
  if ([double]::TryParse("$v", [ref]$d)) {
    try { return [DateTime]::FromOADate([math]::Floor($d)).ToString('dd.MM.yyyy') } catch { return "$v" }
  }
  return "$v"
}
function Map-Designation([string]$p) {
  if (-not $p) { return '' }
  $u = $p.ToUpper()
  if ($u -match 'ABPM' -or $u -match 'ASSISTANT BRANCH') { return 'ABPM' }
  if ($u -match 'BRANCH POST MASTER' -or $u -eq 'BPM') { return 'BPM' }
  if ($u -match 'MAIL') { return 'GDS MC' }       # GDS Mail Carrier / Deliverer
  if ($u -match 'DELIVER') { return 'GDS MD' }    # GDS Mail Deliverer / Deliver Agent
  return $p
}
function Map-Level([string]$lv) {
  if (-not $lv) { return '' }
  $first = ($lv -split '[-/ ]')[0]
  switch ($first) { '1' { 'L-I' } '2' { 'L-II' } default { $lv } }
}
# split "Manavasi B.O" / "Kulittalai H.O" -> @{name='MANAVASI'; type='BO'}
function Split-OfficeDesc([string]$desc) {
  if (-not $desc) { return @{ name = ''; type = '' } }
  $t = ($desc -replace '\s+', ' ').Trim()
  if ($t -match '^(?i)(.*?)\s+B\.?\s*O\.?$') { return @{ name = $matches[1].Trim().ToUpper(); type = 'BO' } }
  if ($t -match '^(?i)(.*?)\s+S\.?\s*O\.?$') { return @{ name = $matches[1].Trim().ToUpper(); type = 'SO' } }
  if ($t -match '^(?i)(.*?)\s+H\.?\s*O\.?$') { return @{ name = $matches[1].Trim().ToUpper(); type = 'HO' } }
  return @{ name = $t.ToUpper(); type = '' }
}

# ---------- 1) STAFF from GDS pay-bill xlsx ----------
$staff = New-Object System.Collections.ArrayList
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false; $excel.DisplayAlerts = $false
try {
  foreach ($gs in $GsFiles) {
    if (-not (Test-Path $gs)) { Write-Warning "GS file not found, skipping: $gs"; continue }
    Write-Host "Reading $gs ..."
    $wb = $excel.Workbooks.Open($gs, $null, $true)
    try {
      $ws = $wb.Worksheets.Item(1)
      $ur = $ws.UsedRange; $vals = $ur.Value2
      $rc = $ur.Rows.Count; $cc = $ur.Columns.Count
      # header name -> column index
      $col = @{}
      for ($c = 1; $c -le $cc; $c++) { $h = "$($vals[1,$c])".Trim(); if ($h) { $col[$h] = $c } }
      $need = 'Employee_name','Post_desc','Office_desc','Employee_id','Level','Date_of_birth','Date_of_join','Gender','Status'
      foreach ($n in $need) { if (-not $col.ContainsKey($n)) { Write-Warning "  column '$n' missing in $gs" } }
      $get = { param($r,$name) if ($col.ContainsKey($name)) { $vals[$r, $col[$name]] } else { $null } }
      for ($r = 2; $r -le $rc; $r++) {
        $name = "$(& $get $r 'Employee_name')".Trim()
        if (-not $name) { continue }
        $officeDesc = "$(& $get $r 'Office_desc')".Trim()
        [void]$staff.Add([ordered]@{
          name          = $name
          designation   = Map-Designation "$(& $get $r 'Post_desc')"
          employee_id   = "$(& $get $r 'Employee_id')".Trim()
          trca          = Map-Level "$(& $get $r 'Level')"
          dob           = Conv-Date (& $get $r 'Date_of_birth')
          doa           = Conv-Date (& $get $r 'Date_of_join')
          office        = Norm-Office $officeDesc
          office_desc   = $officeDesc
          gender        = "$(& $get $r 'Gender')".Trim()
          status        = "$(& $get $r 'Status')".Trim()
          post_desc     = "$(& $get $r 'Post_desc')".Trim()
          level         = "$(& $get $r 'Level')".Trim()
        })
      }
    } finally { $wb.Close($false) }
  }

  # ---------- 1b) OFFICES from Office Master.xls ----------
  $offices = New-Object System.Collections.ArrayList
  if ($OfficeMaster -and (Test-Path $OfficeMaster)) {
    Write-Host "Reading $OfficeMaster ..."
    $wbOM = $excel.Workbooks.Open($OfficeMaster, $null, $true)
    try {
      $wsOM = $wbOM.Worksheets.Item(1)
      $urOM = $wsOM.UsedRange; $vOM = $urOM.Value2
      $rcOM = $urOM.Rows.Count; $ccOM = $urOM.Columns.Count
      # header -> column index (tolerant of whitespace/case)
      $colOM = @{}
      for ($c = 1; $c -le $ccOM; $c++) {
        $h = ("$($vOM[1,$c])" -replace '\s+', ' ').Trim().ToLower()
        if ($h) { $colOM[$h] = $c }
      }
      $pick = {
        param($r, [string[]]$names)
        foreach ($n in $names) { $k = $n.ToLower(); if ($colOM.ContainsKey($k)) { return "$($vOM[$r, $colOM[$k]])".Trim() } }
        return ''
      }
      for ($r = 2; $r -le $rcOM; $r++) {
        $desc = & $pick $r @('facility id description','facility_id description','facility id  description')
        if (-not $desc) { continue }
        $sp = Split-OfficeDesc $desc
        if (-not $sp.name) { continue }
        $facType = & $pick $r @('facility type')
        $type = $sp.type
        if (-not $type -and $facType) { $type = ($facType.Trim().ToUpper()) }   # fallback to raw "PO"/"BO"
        $accountSp = Split-OfficeDesc (& $pick $r @('account office'))
        $hoSp      = Split-OfficeDesc (& $pick $r @('reporting ho name','reporting ho','head office'))
        $pinRaw    = (& $pick $r @('pincode','pin code','pin'))
        $pin       = if ($pinRaw -match '^\d{6}$') { $pinRaw.Substring(0,3) + ' ' + $pinRaw.Substring(3,3) } else { $pinRaw }
        $division  = ((& $pick $r @('reporting division name','reporting division','division')) -replace '\s+', ' ').Trim()
        [void]$offices.Add([ordered]@{
          office_name        = $sp.name
          office_type        = $type
          facility_id        = (& $pick $r @('facility id','facility_id'))
          account_office     = $accountSp.name
          account_office_pin = $pin
          head_office        = $hoSp.name
          division           = $division
          region             = (& $pick $r @('reporting region name','reporting region','region'))
          panchayat_hq       = (& $pick $r @('taluk'))   # closest field available in master
          taluk              = (& $pick $r @('taluk'))
          district           = (& $pick $r @('district'))
          state              = (& $pick $r @('state'))
          pli_facility_id    = (& $pick $r @('pli facility id','pli_facility_id'))
          sol_id             = (& $pick $r @('sol id','sol_id'))
          delivery_kind      = (& $pick $r @('delivery /non-delivery office','delivery /non-delivery  office','delivery /non delivery office','delivery /non-delivery'))
          address            = (& $pick $r @('address'))
          mobile             = (& $pick $r @('mobile number','mobile'))
        })
      }
    } finally { $wbOM.Close($false) }
  }

  # ---------- 1c) WORKING HOURS from BO WORKING HOURS\*.xlsx ----------
  # Each workbook lists the BOs in account with one SO. Title row:
  #   "WORKING HOURS OF BRANCH OFFICE IN ACCOUNT WITH <AO> SO <PIN>"
  # Data cols (from row 3): #, BO name, Working hours, Receipt, Delivery, Last clearance, Last dispatch, Remarks
  $workingHours = @{}
  if ($WorkingHoursDir -and (Test-Path $WorkingHoursDir)) {
    $whFiles = Get-ChildItem $WorkingHoursDir -Filter '*.xlsx' -File -ErrorAction SilentlyContinue
    $titleRe = [regex]'(?i)WITH\s+(.+?)\s+SO\s+(\d{6})'
    foreach ($wf in $whFiles) {
      $wbWH = $excel.Workbooks.Open($wf.FullName, $null, $true)
      try {
        $wsWH = $wbWH.Worksheets.Item(1)
        $urWH = $wsWH.UsedRange; $vWH = $urWH.Value2
        $rcWH = $urWH.Rows.Count
        $title = "$($vWH[1,1])".Trim()
        $ao = ''; $pin = ''
        $mt = $titleRe.Match($title)
        if ($mt.Success) {
          $ao  = ($mt.Groups[1].Value.Trim().ToUpper())
          $pin = $mt.Groups[2].Value
          if ($pin -match '^\d{6}$') { $pin = $pin.Substring(0,3) + ' ' + $pin.Substring(3,3) }
        }
        for ($r = 3; $r -le $rcWH; $r++) {
          $boRaw = "$($vWH[$r,2])".Trim()
          if (-not $boRaw) { continue }
          $boKey = Norm-Office $boRaw
          if (-not $boKey) { continue }
          $rec = [ordered]@{
            working_hours       = "$($vWH[$r,3])".Trim()
            wh_receipt          = "$($vWH[$r,4])".Trim()
            wh_delivery         = "$($vWH[$r,5])".Trim()
            wh_lb_clearance     = "$($vWH[$r,6])".Trim()
            wh_dispatch         = "$($vWH[$r,7])".Trim()
            account_office      = $ao
            account_office_pin  = $pin
            source_file         = $wf.Name
          }
          $workingHours[$boKey] = $rec
        }
      } finally { $wbWH.Close($false) }
    }
  }

  # ---------- 1d) MAIL ARRANGEMENT (per-office prose) ----------
  $mailArr = @{}
  if ($MailArrangement -and (Test-Path $MailArrangement)) {
    Write-Host "Reading $MailArrangement ..."
    $wbMA = $excel.Workbooks.Open($MailArrangement, $null, $true)
    try {
      $wsMA = $wbMA.Worksheets.Item(1)
      $urMA = $wsMA.UsedRange; $vMA = $urMA.Value2
      $rcMA = $urMA.Rows.Count
      # cols: 1=Name of the Office, 2=Office Name (with type suffix), 3=Mail arrangement
      for ($r = 2; $r -le $rcMA; $r++) {
        $bareName  = "$($vMA[$r,1])".Trim()
        $fullName  = "$($vMA[$r,2])".Trim()
        $text      = "$($vMA[$r,3])".Trim()
        if (-not $text) { continue }
        $key = Norm-Office $(if ($bareName) { $bareName } else { $fullName })
        if (-not $key) { continue }
        $mailArr[$key] = $text
      }
    } finally { $wbMA.Close($false) }
  }

  # ---------- 1e) APT OFFICE IDs from Karur_SubDivision_Offices_PanchayatHQ.xlsx ----------
  # Cols: 1=S.No, 2=Type, 3=Office Name, 4=Panchayat HQ, 5=APT Office ID, 6=Account Office,
  #       7=AO Office ID, 8=Sub Division, 9=Division, 10=Region, 11=Panchayat HQ source/status
  $aptIds = @{}
  if ($AptOfficeMaster -and (Test-Path $AptOfficeMaster)) {
    Write-Host "Reading $AptOfficeMaster ..."
    $wbAPT = $excel.Workbooks.Open($AptOfficeMaster, $null, $true)
    try {
      $wsAPT = $wbAPT.Worksheets.Item(1)
      $urAPT = $wsAPT.UsedRange; $vAPT = $urAPT.Value2
      $rcAPT = $urAPT.Rows.Count
      for ($r = 2; $r -le $rcAPT; $r++) {
        $name = "$($vAPT[$r,3])".Trim()
        if (-not $name) { continue }
        $key = Norm-Office $name
        if (-not $key) { continue }
        $aptIds[$key] = [ordered]@{
          apt_office_id      = "$($vAPT[$r,5])".Trim()
          ao_apt_office_id   = "$($vAPT[$r,7])".Trim()
          panchayat_hq       = "$($vAPT[$r,4])".Trim()
          panchayat_hq_note  = "$($vAPT[$r,11])".Trim()
          sub_division       = "$($vAPT[$r,8])".Trim()
        }
      }
    } finally { $wbAPT.Close($false) }
  }
} finally {
  $excel.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($excel)
  [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}
Write-Host ("Staff records:   {0}  across {1} offices" -f $staff.Count, (($staff | ForEach-Object { $_.office } | Sort-Object -Unique).Count))
Write-Host ("Office records:  {0}" -f $offices.Count)
Write-Host ("Working hours:   {0} BOs" -f $workingHours.Count)
Write-Host ("Mail arrangmnt:  {0} offices" -f $mailArr.Count)
Write-Host ("APT office IDs:  {0} offices" -f $aptIds.Count)

# ---------- 2) VILLAGES from the docx ----------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$villages = [ordered]@{}
if (Test-Path $VillageDoc) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($VillageDoc)
  try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'word/document.xml' }
    $sr = New-Object System.IO.StreamReader($entry.Open()); $xml = $sr.ReadToEnd(); $sr.Close()
  } finally { $zip.Dispose() }

  $tRe  = [regex]'(?s)<w:t[ >].*?</w:t>'
  $trRe = [regex]'(?s)<w:tr\b.*?</w:tr>'
  $tcRe = [regex]'(?s)<w:tc\b.*?</w:tc>'
  function Text-Of($frag) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($m in $tRe.Matches($frag)) { [void]$sb.Append([regex]::Replace($m.Value,'<[^>]+>','')) }
    (($sb.ToString() -replace '&amp;','&' -replace '&lt;','<' -replace '&gt;','>') -replace '\s+',' ').Trim()
  }
  $blockRe = [regex]'(?s)<w:p\b.*?</w:p>|<w:tbl>.*?</w:tbl>'
  # BO name, optional "BEAT n" segment, "A\W" (BO/A may be stuck together), account office, PIN
  $headRe  = [regex]'(?i)LIST OF\s+(.*?)\s*B\.?O(?:\s+BEAT\s*\w+)?\s*A\\?\s*W\s+(.*?)\s+(\d{3}\s?\d{3})'
  $pending = $null
  foreach ($b in $blockRe.Matches($xml)) {
    $v = $b.Value
    if ($v.StartsWith('<w:tbl')) {
      if (-not $pending) { continue }
      $fixed = New-Object System.Collections.ArrayList
      $unfixed = New-Object System.Collections.ArrayList
      $ri = 0
      foreach ($tr in $trRe.Matches($v)) {
        $ri++; if ($ri -eq 1) { continue }   # skip header row
        $cells = @($tcRe.Matches($tr.Value) | ForEach-Object { Text-Of $_.Value })
        $f = if ($cells.Count -ge 2) { $cells[1] } else { '' }
        $u = if ($cells.Count -ge 4) { $cells[3] } else { '' }
        if ($f -and $f -notmatch '^(?i)nil+l?$') { [void]$fixed.Add($f) }
        if ($u -and $u -notmatch '^(?i)nil+l?$') { [void]$unfixed.Add($u) }
      }
      $key = Norm-Office ($pending.bo -replace '(?i)\s*BEAT\s*\w+\s*$','')
      if ($villages.Contains($key)) {            # merge multiple beats of same BO
        foreach ($x in $fixed)   { if ($villages[$key].fixed   -notcontains $x) { [void]$villages[$key].fixed.Add($x) } }
        foreach ($x in $unfixed) { if ($villages[$key].unfixed -notcontains $x) { [void]$villages[$key].unfixed.Add($x) } }
      } else {
        $villages[$key] = [ordered]@{
          office             = $key
          account_office     = $pending.ao
          account_office_pin = $pending.pin
          fixed              = $fixed
          unfixed            = $unfixed
        }
      }
      $pending = $null
    } else {
      $t = Text-Of $v
      $m = $headRe.Match($t)
      if ($m.Success) {
        $ao = ($m.Groups[2].Value -replace '(?i)\s*(CF|SF|FF)?\s*S\.?O\.?\s*$','' -replace '(?i)\s*COLLECTORATE\s*$',' COLLECTORATE').Trim()
        $pending = @{ bo = $m.Groups[1].Value.Trim(); ao = $ao.ToUpper(); pin = ($m.Groups[3].Value -replace '\s+',' ').Trim() }
      }
    }
  }
  Write-Host ("Village BOs: {0}" -f $villages.Count)
} else { Write-Warning "Village doc not found: $VillageDoc" }

# ---------- 3) write outputs ----------
if (-not (Test-Path $DataDir)) { New-Item -ItemType Directory -Path $DataDir -Force | Out-Null }
function Save-Json($obj, $path) {
  if (Test-Path $path) { Copy-Item $path "$path.prev" -Force }
  $json = $obj | ConvertTo-Json -Depth 8
  [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "Wrote $path"
}
Save-Json @($staff) (Join-Path $DataDir 'staff.json')
Save-Json $villages (Join-Path $DataDir 'villages.json')
if ($offices.Count -gt 0) {
  # merge BO working-hours + mail-arrangement + APT Office IDs into the matching office row (by normalized office_name)
  $mergedWh = 0; $mergedMa = 0; $mergedApt = 0
  foreach ($o in $offices) {
    $k = Norm-Office $o.office_name
    if ($workingHours.ContainsKey($k)) {
      $w = $workingHours[$k]
      $o.working_hours    = $w.working_hours
      $o.wh_receipt       = $w.wh_receipt
      $o.wh_delivery      = $w.wh_delivery
      $o.wh_lb_clearance  = $w.wh_lb_clearance
      $o.wh_dispatch      = $w.wh_dispatch
      # AO/PIN from working-hours title row only if office row doesn't already have them
      if (-not $o.account_office     -or $o.account_office.Length -eq 0)     { $o.account_office     = $w.account_office }
      if (-not $o.account_office_pin -or $o.account_office_pin.Length -eq 0) { $o.account_office_pin = $w.account_office_pin }
      $mergedWh++
    }
    if ($mailArr.ContainsKey($k)) {
      $o.mail_arrangement = $mailArr[$k]
      $mergedMa++
    }
    if ($aptIds.ContainsKey($k)) {
      $a = $aptIds[$k]
      $o.apt_office_id      = $a.apt_office_id
      $o.ao_apt_office_id   = $a.ao_apt_office_id
      $o.profit_centre_id   = $a.apt_office_id     # mirror — legacy template still uses {{profit_centre_id}}
      if ($a.panchayat_hq)      { $o.panchayat_hq      = $a.panchayat_hq }
      if ($a.panchayat_hq_note) { $o.panchayat_hq_note = $a.panchayat_hq_note }
      if ($a.sub_division)      { $o.sub_division      = $a.sub_division }
      $mergedApt++
    }
  }
  Write-Host ("Merged working-hours into {0} office row(s)" -f $mergedWh)
  Write-Host ("Merged mail-arrangement into {0} office row(s)" -f $mergedMa)
  Write-Host ("Merged APT Office IDs into {0} office row(s)" -f $mergedApt)
  Save-Json @($offices) (Join-Path $DataDir 'offices.json')
} else { Write-Host "(offices.json left untouched - no Office Master input)" }
Write-Host "Done."
