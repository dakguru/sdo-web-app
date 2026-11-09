/*  label-engine.js  –  precut address-label sheet engine (Karur SDO web app)
 *  ─────────────────────────────────────────────────────────────────────────
 *  The drawing half of the Address Labels module, lifted out of
 *  address-labels.html so other modules can print the very same labels
 *  rather than growing a second, slightly-different one. SB-46 Notice
 *  Management uses it for the duplicate notice / acknowledgement labels.
 *
 *  What lives here: the sheet geometry (3 columns x 6 rows of precut
 *  labels), the India Post S10 article-number arithmetic, a Code 128
 *  encoder, and the jsPDF drawing of one label and of a whole run.
 *
 *  What does NOT live here: the queue, the dispatch register, the barcode
 *  inventory and the on-screen preview — those are the Address Labels page's
 *  own business.
 *
 *  Nothing in this file touches the DOM except to inject the jsPDF script.
 *  ───────────────────────────────────────────────────────────────────── */

const LabelEngine = (() => {

  // ── sheet geometry (mm) — measured from the precut sheet in use ──
  const DEF = { pageW:211, pageH:298, lblW:65, lblH:48, colGap:3, rowGap:0, offX:0, offY:0 };
  const COLS = 3, ROWS = 6, PER = COLS * ROWS;

  const cclean = v => String(v == null ? '' : v).trim();
  function fmtPin(raw){
    const d = cclean(raw).replace(/\D/g,'');
    return d.length === 6 ? d.slice(0,3) + ' ' + d.slice(3) : cclean(raw);
  }

  // ── India Post article numbers (UPU S10) ──
  // Check digit: weights over the 8 serial digits; a result of 10 becomes 0 and 11 becomes 5.
  function s10Check(serial8){
    const s = String(serial8).padStart(8,'0'), w = [8,6,4,2,3,5,9,7];
    let sum = 0; for (let i = 0; i < 8; i++) sum += (+s[i]) * w[i];
    let c = 11 - (sum % 11); if (c === 10) c = 0; else if (c === 11) c = 5;
    return c;
  }
  // "ET909871256IN" -> {prefix:'ET', serial:90987125, suffix:'IN'}; badCheck when the
  // stated check digit disagrees with the S10 rule. null when it isn't an article number.
  function parseBarcode(raw){
    const s = String(raw == null ? '' : raw).toUpperCase().replace(/\s+/g,'');
    const m = s.match(/^([A-Z]{2})(\d{8})(\d)([A-Z]{2})$/);
    if (!m) return null;
    const base = { prefix:m[1], serial:parseInt(m[2],10), suffix:m[4] };
    if (s10Check(m[2]) !== +m[3]) return { ...base, badCheck:true };
    return base;
  }
  // The k-th number from a parsed base (k = 0 is the base itself).
  function barcodeNoAt(base, k){
    const s8 = String(base.serial + k).padStart(8,'0').slice(-8);
    return base.prefix + s8 + s10Check(s8) + base.suffix;
  }
  // How many numbers lie between two article numbers, inclusive. null when they
  // don't belong to the same series or run backwards.
  function rangeSize(fromRaw, toRaw){
    const a = parseBarcode(fromRaw), b = parseBarcode(toRaw);
    if (!a || !b) return null;
    if (a.prefix !== b.prefix || a.suffix !== b.suffix) return null;
    const n = b.serial - a.serial + 1;
    return n > 0 ? n : null;
  }

  // ── Code 128 ──
  const C128 = ["212222","222122","222221","121223","121322","131222","122213","122312","132212","221213",
  "221312","231212","112232","122132","122231","113222","123122","123221","223211","221132",
  "221231","213212","223112","312131","311222","321122","321221","312212","322112","322211",
  "212123","212321","232121","111323","131123","131321","112313","132113","132311","211313",
  "231113","231311","112133","112331","132131","113123","113321","133121","313121","211331",
  "231131","213113","213311","213131","311123","311321","331121","312113","312311","332111",
  "314111","221411","431111","111224","111422","121124","121421","141122","141221","112214",
  "112412","122114","122411","142112","142211","241211","221114","413111","241112","134111",
  "111242","121142","121241","114212","124112","124211","411212","421112","421211","212141",
  "214121","412121","111143","111341","131141","114113","114311","411113","411311","113141",
  "114131","311141","411131","211412","211214","211232","2331112"];
  // Automatic B/C switching. Runs of digits are packed into subset C (two digits per
  // symbol), matching how India Post encodes the S10 number — this keeps the bars as
  // wide as possible for reliable scanning.
  function code128Bars(data){
    data = String(data);
    const len = data.length;
    const isNum = (i,n) => { if (i+n > len) return false; for (let k=0;k<n;k++){ const c=data.charCodeAt(i+k); if(c<48||c>57) return false; } return true; };
    const codes = []; let i = 0, inC;
    if (isNum(0, len === 2 ? 2 : 4)) { codes.push(105); inC = true; }   // Start C
    else { codes.push(104); inC = false; }                              // Start B
    while (i < len){
      if (inC){
        if (isNum(i,2)) { codes.push(parseInt(data.substr(i,2),10)); i += 2; }
        else { codes.push(100); inC = false; }                          // -> Code B
      } else {
        if (isNum(i, i === 0 ? (len === 2 ? 2 : 4) : 4)) { codes.push(99); inC = true; }  // -> Code C
        else { codes.push(data.charCodeAt(i) - 32); i++; }
      }
    }
    let sum = codes[0]; for (let j = 1; j < codes.length; j++) sum += codes[j] * j;
    codes.push(sum % 103); codes.push(106);                             // checksum + Stop
    let mods = ''; codes.forEach(c => mods += C128[c]);
    const bars = []; let x = 0;
    for (let k = 0; k < mods.length; k++){ const w = +mods[k]; if (k % 2 === 0) bars.push({x,w}); x += w; }
    return { bars, total:x };
  }
  // Inline SVG barcode for on-screen previews (px units).
  function barcodeSVG(data, wpx, hpx){
    const { bars, total } = code128Bars(data), mw = wpx / total;
    const rects = bars.map(b => `<rect x="${(b.x*mw).toFixed(2)}" y="0" width="${(b.w*mw).toFixed(2)}" height="${hpx.toFixed(1)}"/>`).join('');
    return `<svg width="${wpx.toFixed(1)}" height="${hpx.toFixed(1)}" viewBox="0 0 ${wpx.toFixed(1)} ${hpx.toFixed(1)}" preserveAspectRatio="none" xmlns="http://www.w3.org/2000/svg" shape-rendering="crispEdges"><g fill="#000">${rects}</g></svg>`;
  }

  // ── geometry ──
  // Takes whatever of DEF the caller wants to override and returns the computed sheet.
  function geom(over){
    const g = Object.assign({}, DEF, over || {});
    g.gridW = COLS * g.lblW + (COLS - 1) * g.colGap;
    g.gridH = ROWS * g.lblH + (ROWS - 1) * g.rowGap;
    g.marginX = (g.pageW - g.gridW) / 2;
    g.marginY = (g.pageH - g.gridH) / 2;
    return g;
  }
  function cellXY(g, index){            // index 0..17 within a sheet
    const c = index % COLS, r = Math.floor(index / COLS);
    return { x: g.marginX + c * (g.lblW + g.colGap) + g.offX,
             y: g.marginY + r * (g.lblH + g.rowGap) + g.offY };
  }
  // Assign every label to a (sheet, cell). On the FIRST sheet, cells the caller
  // marks as already-used are left empty; later sheets fill completely.
  function placements(labels, skip){
    const out = []; let sheet = 0, cell = 0, li = 0, guard = 0;
    const used = skip || new Set();
    while (li < labels.length && guard++ < 200000){
      if (sheet === 0 && used.has(cell)){ cell++; if (cell >= PER){ cell = 0; sheet++; } continue; }
      out.push({ sheet, cell, label:labels[li], seq:li }); li++;
      cell++; if (cell >= PER){ cell = 0; sheet++; }
    }
    return out;
  }
  function sheetsNeeded(labels, skip){
    const p = placements(labels, skip);
    return p.length ? p[p.length-1].sheet + 1 : 0;
  }

  // ── jsPDF loading ──
  const PDF_URL = 'https://cdn.jsdelivr.net/npm/jspdf@2.5.1/dist/jspdf.umd.min.js';
  let _pdfP;
  function loadScriptOnce(src){
    return new Promise((res, rej) => {
      if ([...document.scripts].some(s => s.src === src)) return res();
      const s = document.createElement('script');
      s.src = src; s.onload = () => res();
      s.onerror = () => rej(new Error('Could not load the PDF engine (check your connection).'));
      document.head.appendChild(s);
    });
  }
  function loadPdf(){ if (_pdfP) return _pdfP; _pdfP = loadScriptOnce(PDF_URL); return _pdfP; }

  // ── drawing ──
  const PT_MM = 0.352778;                       // 1pt in mm
  // Uniform font-scale limits — the block grows to fill short labels (e.g. the tidy
  // office format) and shrinks to fit long addresses from Manual / Excel.
  const FIT_MIN = 0.55, FIT_MAX = 1.6, FIT_FILL = 0.95;

  // A dark Code 128 barcode plus its human-readable number. Returns the height used.
  function drawBarcode(doc, data, x, y, wmm, hmm){
    const { bars, total } = code128Bars(data), mw = wmm / total;
    doc.setFillColor(0,0,0);
    bars.forEach(b => doc.rect(x + b.x*mw, y, b.w*mw, hmm, 'F'));
    doc.setFont('helvetica','bold'); doc.setFontSize(8); doc.setTextColor(0,0,0);
    const tw = doc.getTextWidth(data);
    doc.text(data, x + Math.max(0,(wmm-tw)/2), y + hmm + 2.7);
    doc.setDrawColor(120); doc.setLineWidth(0.15); doc.line(x, y+hmm+3.9, x+wmm, y+hmm+3.9);
    return hmm + 5.1;                           // barcode + number + rule
  }

  // One label. `r` is the recipient {desig,name,address,pincode,mobile,src}; `p` the
  // cell's top-left corner; `opt` may carry {showTo, barcodeNo, reference, tag}.
  // `reference` is a small grey line (a file number, a serial range) printed above
  // the address — below the barcode when there is one. `tag` is a short right-aligned
  // caption on the same line, used to tell duplicate copies of a label apart.
  function drawLabel(doc, r, p, g, opt){
    opt = opt || {};
    const pad = 3.6, innerW = g.lblW - 2*pad;
    let topOffset = 0;
    if (opt.barcodeNo) topOffset = drawBarcode(doc, opt.barcodeNo, p.x+pad+1.5, p.y+pad, innerW-3, 8);
    const innerH = g.lblH - 2*pad - topOffset;

    const segs = [];
    const INK = [8,8,8];                        // very dark, near-black — every content line
    const pinLabel = r.src === 'office' ? 'PINCODE - ' : 'PIN: ';
    if (opt.reference || opt.tag){
      const ref = cclean(opt.reference), tag = cclean(opt.tag);
      segs.push({ text:[ref,tag].filter(Boolean).join('   ·   '), size:7.5, bold:true, color:[85,85,85], rule:true });
    }
    if (opt.showTo) segs.push({ text:'To,', size:9.5, bold:true, color:INK });
    if (r.desig)    segs.push({ text:r.desig, size:11, bold:true, color:INK });
    if (r.name)     segs.push({ text:r.name, size:12.5, bold:true, color:INK });
    if (r.address)  String(r.address).split(/\r?\n/).forEach(ln => { if (cclean(ln)) segs.push({ text:ln, size:10.5, bold:true, color:INK }); });
    if (r.pincode)  segs.push({ text:pinLabel + fmtPin(r.pincode), size:11.5, bold:true, color:INK });
    if (r.mobile)   segs.push({ text:'Mobile: ' + r.mobile, size:9.5, bold:true, color:INK });

    // Measure the wrapped block height at a given uniform scale.
    const measure = scale => {
      const lines = []; let total = 0;
      for (const sg of segs){
        const size = sg.size * scale;
        doc.setFont('helvetica', sg.bold ? 'bold' : 'normal'); doc.setFontSize(size);
        doc.splitTextToSize(sg.text, innerW).forEach(w => {
          const lh = size * PT_MM * 1.16;
          lines.push({ t:w, size, bold:sg.bold, color:sg.color, rule:sg.rule, lh });
          total += lh;
        });
        if (sg.rule) total += 1.1;
      }
      return { lines, total };
    };
    // Binary-search the largest uniform scale whose block fits the available height.
    let lo = FIT_MIN, hi = FIT_MAX;
    if (measure(FIT_MIN).total <= innerH){
      for (let it = 0; it < 18; it++){ const mid = (lo+hi)/2; if (measure(mid).total <= innerH*FIT_FILL) lo = mid; else hi = mid; }
    } // else: even the smallest fit overflows -> draw at FIT_MIN
    const { lines, total } = measure(lo);
    drawLines(doc, lines, p, pad, topOffset, innerW, innerH, total);
  }
  function drawLines(doc, lines, p, pad, topOffset, innerW, innerH, total){
    let y = p.y + pad + topOffset + Math.max(0, innerH - total)/2;   // centre below the barcode
    const x = p.x + pad;
    for (const L of lines){
      doc.setFont('helvetica', L.bold ? 'bold' : 'normal');
      doc.setFontSize(L.size);
      const c = L.color || [20,20,20]; doc.setTextColor(c[0],c[1],c[2]);
      y += L.lh;
      doc.text(L.t, x, y - L.lh*0.28);
      if (L.rule){ doc.setDrawColor(150); doc.setLineWidth(0.15); doc.line(x, y+0.6, x+innerW, y+0.6); y += 1.1; }
    }
  }

  // Build a whole run. `labels` is the already-expanded list, in printing order;
  // each may carry its own `barcodeNo`, `reference` and `tag`, which win over the
  // run-wide `opt` and over `barcodeBase` (the start of a consecutive series).
  // Resolves to { doc, places } — the caller saves the doc and records the run.
  async function buildPdf({ labels, g, skip, opt, barcodeBase }){
    await loadPdf();
    const { jsPDF } = window.jspdf;
    const sheet = g || geom();
    const o = opt || {};
    const orient = sheet.pageH >= sheet.pageW ? 'portrait' : 'landscape';
    const doc = new jsPDF({ orientation:orient, unit:'mm', format:[sheet.pageW, sheet.pageH] });
    const places = placements(labels, skip);
    let curSheet = 0;
    places.forEach(pl => {
      while (pl.sheet > curSheet){ doc.addPage([sheet.pageW, sheet.pageH], orient); curSheet++; }
      const p = cellXY(sheet, pl.cell);
      if (o.guides){
        doc.setDrawColor(150,160,210); doc.setLineWidth(0.2); doc.setLineDashPattern([1,1],0);
        doc.rect(p.x, p.y, sheet.lblW, sheet.lblH); doc.setLineDashPattern([],0);
      }
      const barcodeNo = pl.label.barcodeNo || (barcodeBase ? barcodeNoAt(barcodeBase, pl.seq) : null);
      pl.barcodeNo = barcodeNo;
      drawLabel(doc, pl.label, p, sheet, {
        ...o, barcodeNo,
        reference: pl.label.reference != null ? pl.label.reference : o.reference,
        tag:       pl.label.tag       != null ? pl.label.tag       : o.tag
      });
    });
    return { doc, places };
  }

  return { DEF, COLS, ROWS, PER, PT_MM, FIT_MIN, FIT_MAX, FIT_FILL,
           cclean, fmtPin, s10Check, parseBarcode, barcodeNoAt, rangeSize,
           code128Bars, barcodeSVG, geom, cellXY, placements, sheetsNeeded,
           loadScriptOnce, loadPdf, drawBarcode, drawLabel, drawLines, buildPdf };
})();
if (typeof window !== 'undefined') window.LabelEngine = LabelEngine;
