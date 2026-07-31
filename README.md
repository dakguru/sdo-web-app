# Inspection Report Builder

A deterministic, template-based generator for official **Inspection Reports** (DOCX + PDF)
that preserves the master Word document's native formatting — **never** HTML-to-Word.

It clones `IR_MASTER_TEMPLATE.docx`, performs run-level placeholder replacement and
repeatable-table-row expansion at the OOXML level, validates, and converts to PDF via
Microsoft Word (native fidelity).

## Why this stack (on this machine)
The target Windows machine has **no Python / Node / Docker / LibreOffice**, but **does**
have **Microsoft Word 16.0** (COM-accessible). So the runnable engine is built on
**PowerShell** (zero external dependencies):

| Concern | Implementation |
|---|---|
| DOCX templating | PowerShell + `System.IO.Compression` + `System.Xml` (OOXML surgery) |
| PDF conversion | Word COM `ExportAsFixedFormat` (perfect fidelity, live page numbers) |
| Validation | unresolved-placeholder scan, export log with SHA-256 checksums |

The full Next.js + FastAPI + PostgreSQL + Docker product (per the specification) is the
intended Phase-3+ build; this engine is the proven, runnable core it will wrap.

## Layout
```
templates/
  IR_MASTER_TEMPLATE.docx     authoritative source (unchanged)
  IR_BO_TEMPLATE.docx         normalized, placeholdered template (generated)
  ir-template-schema.json     full field/table mapping spec (Phase-1 audit deliverable)
engine/
  Build-Template.ps1          master DOCX -> placeholdered template
  Generate-IR.ps1             template + data JSON -> DOCX + PDF + export log
samples/
  manavasi.json               sample dataset (mirrors the original report)
output/                       generated IR_<OFFICE>_<TYPE>_<DATE>_V01.{docx,pdf,export.json}
```

## Run it
```powershell
# 1. (Re)build the placeholdered template from the master
.\engine\Build-Template.ps1

# 2. Generate DOCX + PDF from a data file
.\engine\Generate-IR.ps1 `
    -Template .\templates\IR_BO_TEMPLATE.docx `
    -Data     .\samples\manavasi.json `
    -OutDir   .\output
# add -SkipPdf to skip the Word COM step
```
Output: `output\IR_MANAVASI_BO_26-05-2026_V01.docx` / `.pdf` / `.export.json`.

## Templating conventions
- Scalar: `{{office_name}}` (single run, format preserved).
- Repeatable row: a `<w:tr>` whose cells contain `{{loopVar.field}}`; the engine clones
  it once per item in the `loopVar` JSON array. `{{loopVar.#}}` = 1-based serial.
- All values are inserted **verbatim as strings** — leading zeros on account/ID numbers
  are never stripped; no figures are inferred or altered.

## Verified (Manavasi sample)
- **0 unresolved placeholders**; DOCX opens in Word with **no repair warning**.
- **19 pages**, matching the original; Palatino Linotype 12pt preserved.
- **All 23 tables** parameterised: scalar key/value (1,5), merged-cell (2,20), fixed
  scoring (23), 16 repeatable tables, and paired (2-up) tables (8,10,12,18) collapsed to
  one record per row with note rows preserved.
- Serial auto-numbering, leading-zero preservation (accounts/IDs/policy numbers), and
  amount-in-words helper all working.
- Header rows repeat on page breaks (`w:tblHeader`); rows don't split (`cantSplit`).
- Empty repeatable tables render an explicit **"Nil"** row (logged as a warning).
- Visually confirmed in Word page-by-page: no clipped text, broken tables, or blank pages.

Tables left as static **by design**: 13 (EOI prescribed format) and 21 (BO business
register prescribed format) — both are printed blank for the office to fill later.

## Known limitations / next steps
- **In-paragraph dynamic values** (e.g. the cash figure in para 25) stay static for now;
  these belong to the Standard Clause Library (next phase) where each numbered point gets
  outcome variants (satisfactory / deficient / not-applicable / no-transaction).
- **App layers** still to build: clause library, forms/table-editor UI, validation engine,
  RBAC, database, versioning/approval, compliance tracker (the spec's full stack).
- PDF requires Microsoft Word installed (present on this machine; swap to LibreOffice
  headless on a server by changing the one conversion call in `Generate-IR.ps1`).
