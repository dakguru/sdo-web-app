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

  /* ---------- legacy .xls (OLE2 / BIFF8) reader ----------
     Old India-Post exports come as binary .xls (OLE2 compound file, magic
     D0CF11E0), not zipped .xlsx. This reads the "Workbook" stream out of the
     OLE2 container and decodes just enough BIFF8 to rebuild the cell grid as
     rows of strings — numbers are emitted as plain numeric strings so leading
     zeros are re-padded downstream and Excel date serials survive. */
  const OLE_ENDOFCHAIN = 0xFFFFFFFE, OLE_FREESECT = 0xFFFFFFFF;
  function looksLikeOle(bytes) {
    return bytes.length > 8 && bytes[0] === 0xD0 && bytes[1] === 0xCF &&
      bytes[2] === 0x11 && bytes[3] === 0xE0 && bytes[4] === 0xA1 && bytes[5] === 0xB1;
  }
  function le32(u8, o) { return (u8[o] | (u8[o + 1] << 8) | (u8[o + 2] << 16) | (u8[o + 3] << 24)) >>> 0; }

  function parseXls(bytes) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const g16 = o => dv.getUint16(o, true), g32 = o => dv.getUint32(o, true) >>> 0;
    const sectorSize = 1 << g16(30);          // usually 512
    const miniSize = 1 << g16(32);            // usually 64
    const dirStart = g32(48);
    const miniCutoff = g32(56);               // usually 4096
    const miniFatStart = g32(60);
    const difatStart = g32(68), numDifat = g32(72);

    // Collect FAT sector numbers: first 109 live in the header DIFAT, the rest
    // (only for very large files) chain through dedicated DIFAT sectors.
    const fatSectors = [];
    for (let i = 0; i < 109; i++) { const v = g32(76 + i * 4); if (v === OLE_FREESECT) break; fatSectors.push(v); }
    let ds = difatStart, guard = 0;
    while (numDifat > 0 && ds !== OLE_ENDOFCHAIN && ds !== OLE_FREESECT && guard++ < numDifat + 8) {
      const off = 512 + ds * sectorSize, per = (sectorSize / 4) - 1;
      for (let i = 0; i < per; i++) { const v = g32(off + i * 4); if (v !== OLE_FREESECT) fatSectors.push(v); }
      ds = g32(off + per * 4);
    }
    // Build the FAT (sector → next sector).
    const perSec = sectorSize / 4;
    const fat = new Uint32Array(fatSectors.length * perSec);
    let fi = 0;
    for (const fs of fatSectors) { const off = 512 + fs * sectorSize; for (let i = 0; i < perSec; i++) fat[fi++] = g32(off + i * 4); }

    const readChain = (start, size) => {           // walk regular FAT chain
      const parts = []; let s = start, seen = new Set();
      while (s !== OLE_ENDOFCHAIN && s !== OLE_FREESECT && s < fat.length && !seen.has(s)) {
        seen.add(s); const off = 512 + s * sectorSize;
        parts.push(bytes.subarray(off, off + sectorSize)); s = fat[s];
      }
      let out = concat(parts); return size != null ? out.subarray(0, size) : out;
    };

    // Directory entries.
    const dir = readChain(dirStart, null);
    const entries = [];
    for (let o = 0; o + 128 <= dir.length; o += 128) {
      const type = dir[o + 66]; if (type === 0) continue;   // 1 storage · 2 stream · 5 root
      const nameLen = dir[o + 64] | (dir[o + 65] << 8);
      let name = '';
      for (let i = 0; i + 1 < nameLen && i < 64; i += 2) { const c = dir[o + i] | (dir[o + i + 1] << 8); if (c) name += String.fromCharCode(c); }
      entries.push({ name, type, start: le32(dir, o + 116), size: le32(dir, o + 120) });
    }
    const root = entries.find(e => e.type === 5);
    if (!root) throw new Error('This .xls file is unreadable (no OLE root). Save it as .xlsx or CSV and retry.');

    // Mini-stream + mini-FAT (small streams live here).
    let miniStream = null, miniFat = null;
    const ensureMini = () => {
      if (miniStream) return;
      miniStream = readChain(root.start, root.size);
      const mf = readChain(miniFatStart, null);
      miniFat = new Uint32Array(Math.floor(mf.length / 4));
      for (let i = 0; i < miniFat.length; i++) miniFat[i] = le32(mf, i * 4);
    };
    const readMiniChain = (start, size) => {
      ensureMini(); const parts = []; let s = start, seen = new Set();
      while (s !== OLE_ENDOFCHAIN && s !== OLE_FREESECT && s < miniFat.length && !seen.has(s)) {
        seen.add(s); const off = s * miniSize; parts.push(miniStream.subarray(off, off + miniSize)); s = miniFat[s];
      }
      let out = concat(parts); return size != null ? out.subarray(0, size) : out;
    };

    const wbEntry = entries.find(e => e.type === 2 && (e.name === 'Workbook' || e.name === 'Book'));
    if (!wbEntry) throw new Error('No Workbook stream found in this .xls file.');
    const wb = wbEntry.size < miniCutoff ? readMiniChain(wbEntry.start, wbEntry.size) : readChain(wbEntry.start, wbEntry.size);
    return biffToRows(wb);
  }

  // Decode an RK-encoded number (BIFF).
  function decodeRK(rk) {
    let n;
    if (rk & 0x02) { n = (rk | 0) >> 2; }
    else { const b = new ArrayBuffer(8), d = new DataView(b); d.setUint32(4, rk & 0xFFFFFFFC, true); n = d.getFloat64(0, true); }
    return (rk & 0x01) ? n / 100 : n;
  }
  // Numbers → plain strings (no exponent) so account numbers & date serials survive.
  function numStr(n) { return Number.isFinite(n) ? String(n) : ''; }
  // BIFF8 XLUnicodeString with a 16-bit char count and a single option-flags byte
  // (used by LABEL and the STRING result record). Rich/phonetic extras are ignored.
  function readXLString16(u8, off) {
    const cch = u8[off] | (u8[off + 1] << 8); const grbit = u8[off + 2];
    const high = grbit & 0x01; let o = off + 3, s = '';
    for (let i = 0; i < cch; i++) { if (high) { s += String.fromCharCode(u8[o] | (u8[o + 1] << 8)); o += 2; } else { s += String.fromCharCode(u8[o++]); } }
    return s;
  }

  // Parse the SST (shared string table), which may be split across CONTINUE
  // records. Each split re-emits the option-flags byte inside character data.
  function parseSST(segs) {
    let si = 0, pos = 0;
    const need = () => { while (si < segs.length && pos >= segs[si].length) { si++; pos = 0; } };
    const u8 = () => { need(); return segs[si][pos++]; };
    const u16 = () => u8() | (u8() << 8);
    const u32 = () => (u8() | (u8() << 8) | (u8() << 16) | (u8() << 24)) >>> 0;
    const skip = n => { while (n > 0) { if (pos >= segs[si].length) { si++; pos = 0; } const take = Math.min(segs[si].length - pos, n); pos += take; n -= take; } };
    u32(); const cstUnique = u32();           // skip cstTotal, keep unique count
    const out = [];
    for (let k = 0; k < cstUnique; k++) {
      const cch = u16(); let grbit = u8();
      let high = grbit & 0x01; const rich = grbit & 0x08, ext = grbit & 0x04;
      const cRun = rich ? u16() : 0, cbExt = ext ? u32() : 0;
      let s = '';
      for (let i = 0; i < cch; i++) {
        if (pos >= segs[si].length) { si++; pos = 0; grbit = segs[si][pos++]; high = grbit & 0x01; }   // fresh flags at boundary
        if (high) { s += String.fromCharCode(segs[si][pos] | (segs[si][pos + 1] << 8)); pos += 2; }
        else { s += String.fromCharCode(segs[si][pos++]); }
      }
      skip(cRun * 4 + cbExt);
      out.push(s);
    }
    return out;
  }

  function biffToRows(wb) {
    const dv = new DataView(wb.buffer, wb.byteOffset, wb.byteLength);
    const u16 = o => dv.getUint16(o, true), u32 = o => dv.getUint32(o, true) >>> 0;
    const n = wb.length;
    let sst = [], sheetCount = 0, maxRow = -1, maxCol = -1;
    const cells = Object.create(null);
    const set = (r, c, v) => { cells[r + ',' + c] = v; if (r > maxRow) maxRow = r; if (c > maxCol) maxCol = c; };
    let p = 0;
    while (p + 4 <= n) {
      const type = u16(p), len = u16(p + 2), d = p + 4;
      if (type === 0x0809) {                    // BOF — stop after the first worksheet substream
        const dt = u16(d + 2);
        if (dt === 0x0010) { sheetCount++; if (sheetCount > 1) break; }
      } else if (type === 0x00FC) {             // SST (+ following CONTINUE records)
        const segs = [wb.subarray(d, d + len)]; let q = d + len;
        while (q + 4 <= n && u16(q) === 0x003C) { const l2 = u16(q + 2); segs.push(wb.subarray(q + 4, q + 4 + l2)); q += 4 + l2; }
        sst = parseSST(segs); p = q; continue;
      } else if (type === 0x00FD) {             // LABELSST — row, col, ixfe, isst(u32 @ +6)
        set(u16(d), u16(d + 2), sst[u32(d + 6)] || '');
      } else if (type === 0x0204) {             // LABEL
        set(u16(d), u16(d + 2), readXLString16(wb, d + 6));
      } else if (type === 0x0203) {             // NUMBER
        set(u16(d), u16(d + 2), numStr(dv.getFloat64(d + 6, true)));
      } else if (type === 0x027E) {             // RK
        set(u16(d), u16(d + 2), numStr(decodeRK(u32(d + 6))));
      } else if (type === 0x00BD) {             // MULRK
        const r = u16(d), cFirst = u16(d + 2), cLast = u16(d + len - 2);
        let o = d + 4;
        for (let c = cFirst; c <= cLast; c++) { set(r, c, numStr(decodeRK(u32(o + 2)))); o += 6; }
      } else if (type === 0x0006) {             // FORMULA — cached number, or STRING record follows
        const r = u16(d), c = u16(d + 2);
        if (wb[d + 12] === 0xFF && wb[d + 13] === 0xFF) {
          if (wb[d + 6] === 0x00 && p + 4 + len + 4 <= n && u16(d + len) === 0x0207) set(r, c, readXLString16(wb, d + len + 4));
        } else { set(r, c, numStr(dv.getFloat64(d + 6, true))); }
      }
      p = d + len;
    }
    const rows = [];
    for (let r = 0; r <= maxRow; r++) { const row = new Array(maxCol + 1).fill(''); for (let c = 0; c <= maxCol; c++) { const v = cells[r + ',' + c]; if (v != null) row[c] = v; } rows.push(row); }
    return rows;
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
    const raw = new Uint8Array(arrayBuffer);
    if (looksLikeOle(raw)) return parseXls(raw);          // legacy binary .xls
    const files = await unzip(raw);
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
