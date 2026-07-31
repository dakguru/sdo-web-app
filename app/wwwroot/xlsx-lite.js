/* xlsx-lite.js — tiny dependency-free XLSX read/write for the IR Builder.
   Write : store-method (uncompressed) ZIP + minimal OOXML; every cell is an
           inline string and every column is formatted as Text, so leading
           zeros in account / policy numbers survive a round-trip through Excel.
   Read  : parse the ZIP central directory, inflate entries with the browser's
           built-in DecompressionStream('deflate-raw'), then read sheet1.xml +
           sharedStrings.xml. Returns rows as arrays of cell strings.
   Exposes window.XLSXLite = { build, parse }. No external libraries, fully offline. */
(function () {
  'use strict';

  /* ---------- byte helpers ---------- */
  const enc = new TextEncoder();
  const u16 = v => new Uint8Array([v & 255, (v >> 8) & 255]);
  const u32 = v => new Uint8Array([v & 255, (v >> 8) & 255, (v >> 16) & 255, (v >>> 24) & 255]);
  function concat(arrs) {
    let len = 0; for (const a of arrs) len += a.length;
    const out = new Uint8Array(len); let o = 0;
    for (const a of arrs) { out.set(a, o); o += a.length; }
    return out;
  }
  const CRC = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
    return t;
  })();
  function crc32(buf) { let c = 0xFFFFFFFF; for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; }

  function xmlEsc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
  }
  function xmlUnesc(s) {
    return String(s == null ? '' : s)
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n))
      .replace(/&amp;/g, '&');
  }
  function colLetter(i) { let s = ''; i++; while (i > 0) { const m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = (i - m - 1) / 26; } return s; }
  function colIndex(ref) { const m = /^([A-Z]+)/.exec(ref); if (!m) return 0; let n = 0; for (const ch of m[1]) n = n * 26 + (ch.charCodeAt(0) - 64); return n - 1; }

  /* ---------- ZIP (store / no compression) ---------- */
  function zipStore(files) {                       // files: [{name, bytes}]
    const local = [], central = []; let offset = 0;
    for (const f of files) {
      const name = enc.encode(f.name), data = f.bytes, crc = crc32(data);
      const lh = concat([u32(0x04034b50), u16(20), u16(0), u16(0), u16(0), u16(0),
        u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0), name]);
      local.push(lh, data);
      central.push(concat([u32(0x02014b50), u16(20), u16(20), u16(0), u16(0), u16(0), u16(0),
        u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0), u16(0), u16(0), u16(0),
        u32(0), u32(offset), name]));
      offset += lh.length + data.length;
    }
    let cdLen = 0; for (const c of central) cdLen += c.length;
    const eocd = concat([u32(0x06054b50), u16(0), u16(0), u16(central.length), u16(central.length),
      u32(cdLen), u32(offset), u16(0)]);
    return concat([...local, ...central, eocd]);
  }

  async function unzip(buf) {                       // -> { name: Uint8Array(inflated) }
    const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    let p = buf.length - 22;
    while (p >= 0 && dv.getUint32(p, true) !== 0x06054b50) p--;
    if (p < 0) throw new Error('Not a valid .xlsx file (ZIP end record not found).');
    const count = dv.getUint16(p + 10, true); let cd = dv.getUint32(p + 16, true);
    const out = {};
    for (let i = 0; i < count && dv.getUint32(cd, true) === 0x02014b50; i++) {
      const method = dv.getUint16(cd + 10, true);
      const compSize = dv.getUint32(cd + 20, true);
      const nameLen = dv.getUint16(cd + 28, true);
      const extraLen = dv.getUint16(cd + 30, true);
      const commentLen = dv.getUint16(cd + 32, true);
      const localOff = dv.getUint32(cd + 42, true);
      const name = new TextDecoder().decode(buf.subarray(cd + 46, cd + 46 + nameLen));
      const lhNameLen = dv.getUint16(localOff + 26, true);
      const lhExtraLen = dv.getUint16(localOff + 28, true);
      const start = localOff + 30 + lhNameLen + lhExtraLen;
      const comp = buf.subarray(start, start + compSize);
      out[name] = method === 0 ? comp : await inflateRaw(comp);
      cd += 46 + nameLen + extraLen + commentLen;
    }
    return out;
  }
  async function inflateRaw(bytes) {
    if (typeof DecompressionStream === 'undefined') throw new Error('This browser cannot read .xlsx (no DecompressionStream). Use CSV import instead.');
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  /* ---------- workbook parts (text-formatted) ---------- */
  const CONTENT_TYPES =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
    '<Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
    '</Types>';
  const ROOT_RELS =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
    '</Relationships>';
  const WORKBOOK =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
    '<sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>';
  const WORKBOOK_RELS =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' +
    '</Relationships>';
  /* style 0 = default · 1 = text (@) · 2 = bold text header */
  const STYLES =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>' +
    '<fills count="1"><fill><patternFill patternType="none"/></fill></fills>' +
    '<borders count="1"><border/></borders>' +
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
    '<cellXfs count="3">' +
    '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>' +
    '<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>' +
    '<xf numFmtId="49" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>' +
    '</cellXfs>' +
    '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
    '</styleSheet>';

  function sheetXml(headers, dataRows) {
    const n = headers.length;
    let cols = '<cols>';
    for (let i = 0; i < n; i++) cols += '<col min="' + (i + 1) + '" max="' + (i + 1) + '" width="18" style="1" customWidth="1"/>';
    cols += '</cols>';
    const cell = (ci, ri, val, style) =>
      '<c r="' + colLetter(ci) + ri + '" t="inlineStr" s="' + style + '">' +
      '<is><t xml:space="preserve">' + xmlEsc(val) + '</t></is></c>';
    let body = '<row r="1">';
    for (let c = 0; c < n; c++) body += cell(c, 1, headers[c], 2);
    body += '</row>';
    let r = 2;
    for (const row of dataRows) {
      body += '<row r="' + r + '">';
      for (let c = 0; c < n; c++) body += cell(c, r, row[c] == null ? '' : row[c], 1);
      body += '</row>'; r++;
    }
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
      cols + '<sheetData>' + body + '</sheetData></worksheet>';
  }

  /* ---------- public API ---------- */
  /* build(headers:[string], dataRows:[[string]]) -> Uint8Array (.xlsx bytes) */
  function build(headers, dataRows) {
    const part = s => enc.encode(s);
    return zipStore([
      { name: '[Content_Types].xml', bytes: part(CONTENT_TYPES) },
      { name: '_rels/.rels', bytes: part(ROOT_RELS) },
      { name: 'xl/workbook.xml', bytes: part(WORKBOOK) },
      { name: 'xl/_rels/workbook.xml.rels', bytes: part(WORKBOOK_RELS) },
      { name: 'xl/styles.xml', bytes: part(STYLES) },
      { name: 'xl/worksheets/sheet1.xml', bytes: part(sheetXml(headers, dataRows)) }
    ]);
  }

  /* parse(arrayBuffer) -> rows:[[string]]  (first row is normally the header) */
  async function parse(arrayBuffer) {
    const files = await unzip(new Uint8Array(arrayBuffer));
    const dec = new TextDecoder();
    const shared = parseSharedStrings(files['xl/sharedStrings.xml'] ? dec.decode(files['xl/sharedStrings.xml']) : '');
    let sheetName = files['xl/worksheets/sheet1.xml'] ? 'xl/worksheets/sheet1.xml'
      : Object.keys(files).find(k => /^xl\/worksheets\/.*\.xml$/.test(k));
    if (!sheetName) throw new Error('No worksheet found in the .xlsx file.');
    return parseSheet(dec.decode(files[sheetName]), shared);
  }
  function parseSharedStrings(xml) {
    const arr = []; if (!xml) return arr;
    const siRe = /<si>([\s\S]*?)<\/si>/g; let m;
    while ((m = siRe.exec(xml))) {
      const tRe = /<t[^>]*>([\s\S]*?)<\/t>/g; let t, s = '';
      while ((t = tRe.exec(m[1]))) s += xmlUnesc(t[1]);
      arr.push(s);
    }
    return arr;
  }
  function parseSheet(xml, shared) {
    const rows = [];
    const rowRe = /<row\b[^>]*>([\s\S]*?)<\/row>/g; let rm;
    while ((rm = rowRe.exec(xml))) {
      const cells = [];
      const cRe = /<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g; let cm;
      while ((cm = cRe.exec(rm[1]))) {
        const attrs = cm[1], inner = cm[2] || '';
        const rf = /r="([A-Z]+)\d+"/.exec(attrs);
        const idx = rf ? colIndex(rf[1]) : cells.length;
        const tf = /t="([^"]+)"/.exec(attrs); const type = tf ? tf[1] : 'n';
        let val = '';
        if (type === 's') { const v = /<v>([\s\S]*?)<\/v>/.exec(inner); val = v ? (shared[+v[1]] || '') : ''; }
        else if (type === 'inlineStr') { const t = /<t[^>]*>([\s\S]*?)<\/t>/.exec(inner); val = t ? xmlUnesc(t[1]) : ''; }
        else { const v = /<v>([\s\S]*?)<\/v>/.exec(inner); val = v ? xmlUnesc(v[1]) : ''; }
        cells[idx] = val;
      }
      for (let i = 0; i < cells.length; i++) if (cells[i] == null) cells[i] = '';
      rows.push(cells);
    }
    return rows;
  }

  window.XLSXLite = { build, parse };
})();
