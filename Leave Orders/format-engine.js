/*  format-engine.js  —  IR Report Alignment / Format Corrector engine
 *  ─────────────────────────────────────────────────────────────────────────
 *  Normalises an uploaded .docx (IR / VR inspection report) into an ultra-clean,
 *  uniform, professional document — WITHOUT touching the actual text content,
 *  tables data, images or list logic. It only rewrites *formatting*:
 *
 *    • Font          → Palatino Linotype (Latin runs only; complex-script /
 *                      East-Asian fonts are left intact so Tamil etc. still render)
 *    • Font size     → uniform 11 or 11.5 pt everywhere
 *    • Line spacing  → 1.15  +  tidy paragraph spacing after each para
 *    • Indentation   → negative / margin-busting indents flattened
 *    • Bullets/lists → uniform per-level indents, clean bullet glyphs
 *    • Tables        → centred, clamped to page width, clean single borders,
 *                      padded cells, vertically-centred content, bold shaded
 *                      repeating header row
 *    • Headings      → detected section headings emboldened
 *    • Page          → A4 with balanced margins
 *
 *  Works in the browser (global DOMParser / XMLSerializer) and under Node with
 *  @xmldom/xmldom globals injected (used by the test harness).
 *  ───────────────────────────────────────────────────────────────────────── */
(function (root) {
  'use strict';

  var WNS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
  var XMLNS = 'http://www.w3.org/XML/1998/namespace';

  var DEFAULTS = {
    fontName: 'Palatino Linotype',
    sizePt: 11.5,          // 11 or 11.5
    lineSpacing: 1.15,     // multiple
    paraAfterPt: 6,        // space after each body paragraph, in points
    tidySpacing: true,     // trim fake leading-space indentation on headings
    boldHeadings: true,    // embolden detected section headings
    marginTwips: { top: 1440, bottom: 1440, left: 1080, right: 1080 }
  };

  // Canonical child orders (subset — only elements we touch need be present)
  var ORDER = {
    pPr: ['pStyle', 'keepNext', 'keepLines', 'pageBreakBefore', 'framePr', 'widowControl',
      'numPr', 'suppressLineNumbers', 'pBdr', 'shd', 'tabs', 'suppressAutoHyphens', 'kinsoku',
      'wordWrap', 'overflowPunct', 'topLinePunct', 'autoSpaceDE', 'autoSpaceDN', 'bidi',
      'adjustRightInd', 'snapToGrid', 'spacing', 'ind', 'contextualSpacing', 'mirrorIndents',
      'suppressOverlap', 'jc', 'textDirection', 'textAlignment', 'textboxTightWrap',
      'outlineLvl', 'divId', 'cnfStyle', 'rPr', 'sectPr', 'pPrChange'],
    rPr: ['rStyle', 'rFonts', 'b', 'bCs', 'i', 'iCs', 'caps', 'smallCaps', 'strike', 'dstrike',
      'outline', 'shadow', 'emboss', 'imprint', 'noProof', 'snapToGrid', 'vanish', 'webHidden',
      'color', 'spacing', 'w', 'kern', 'position', 'sz', 'szCs', 'highlight', 'u', 'effect',
      'bdr', 'shd', 'fitText', 'vertAlign', 'rtl', 'cs', 'em', 'lang', 'eastAsianLayout',
      'specVanish', 'oMath'],
    tblPr: ['tblStyle', 'tblpPr', 'tblOverlap', 'bidiVisual', 'tblStyleRowBandSize',
      'tblStyleColBandSize', 'tblW', 'jc', 'tblCellSpacing', 'tblInd', 'tblBorders', 'shd',
      'tblLayout', 'tblCellMar', 'tblLook', 'tblCaption', 'tblDescription'],
    trPr: ['cnfStyle', 'divId', 'gridBefore', 'gridAfter', 'wBefore', 'wAfter', 'cantSplit',
      'trHeight', 'tblHeader', 'tblCellSpacing', 'jc', 'hidden'],
    tcPr: ['cnfStyle', 'tcW', 'gridSpan', 'hMerge', 'vMerge', 'tcBorders', 'shd', 'noWrap',
      'tcMar', 'textDirection', 'tcFitText', 'vAlign', 'hideMark', 'cellIns', 'cellDel',
      'cellMerge', 'tcPrChange']
  };

  // ── tiny DOM helpers ─────────────────────────────────────────────
  function el(doc, local) { return doc.createElementNS(WNS, 'w:' + local); }
  function setA(node, name, val) { node.setAttributeNS(WNS, 'w:' + name, String(val)); }
  function getA(node, name) { return node.getAttributeNS(WNS, name); }
  function kids(parent, local) {
    var out = [], c = parent.firstChild;
    for (; c; c = c.nextSibling)
      if (c.nodeType === 1 && c.localName === local && c.namespaceURI === WNS) out.push(c);
    return out;
  }
  function firstKid(parent, local) { var k = kids(parent, local); return k.length ? k[0] : null; }
  function tagAll(root, local) {
    var list = root.getElementsByTagNameNS(WNS, local), out = [], i;
    for (i = 0; i < list.length; i++) out.push(list[i]);
    return out;
  }
  // find-or-create a child in canonical position; returns the element
  function upsert(doc, parent, local, orderKey) {
    var existing = firstKid(parent, local);
    if (existing) return existing;
    var node = el(doc, local);
    var order = ORDER[orderKey] || [];
    var idx = order.indexOf(local), ref = null, c = parent.firstChild;
    for (; c; c = c.nextSibling) {
      if (c.nodeType !== 1) continue;
      var ci = order.indexOf(c.localName);
      if (ci !== -1 && ci > idx) { ref = c; break; }
    }
    parent.insertBefore(node, ref);
    return node;
  }
  function clearChildren(node) { while (node.firstChild) node.removeChild(node.firstChild); }
  function ancestorOf(node, local) {
    var p = node.parentNode;
    for (; p; p = p.parentNode) if (p.nodeType === 1 && p.localName === local && p.namespaceURI === WNS) return p;
    return null;
  }
  function textOf(node) {
    return tagAll(node, 't').map(function (t) { return t.textContent || ''; }).join('');
  }
  // a paragraph with no visible text and no embedded object/picture
  function paraIsEmpty(p) {
    var ts = tagAll(p, 't');
    for (var i = 0; i < ts.length; i++) if ((ts[i].textContent || '').trim() !== '') return false;
    if (tagAll(p, 'drawing').length || tagAll(p, 'pict').length || tagAll(p, 'object').length) return false;
    return true;
  }
  // safe to remove?  empty, and carries nothing structural (break / bookmark / section)
  function paraDeletable(p) {
    if (!paraIsEmpty(p)) return false;
    if (tagAll(p, 'br').length) return false;                 // page / column / text break
    if (tagAll(p, 'bookmarkStart').length || tagAll(p, 'bookmarkEnd').length) return false;
    var pPr = firstKid(p, 'pPr');
    if (pPr && firstKid(pPr, 'sectPr')) return false;         // section-defining paragraph
    return true;
  }
  function prevElem(n) { var p = n.previousSibling; while (p && p.nodeType !== 1) p = p.previousSibling; return p; }
  function nextElem(n) { var x = n.nextSibling; while (x && x.nodeType !== 1) x = x.nextSibling; return x; }

  // delete the blank paragraphs that hug tables so no dead space is left around them
  function collapseTableGaps(doc) {
    var bodies = tagAll(doc, 'body');
    if (!bodies.length) return;
    var body = bodies[0], changed = true, guard = 0;
    while (changed && guard++ < 60) {
      changed = false;
      var nodes = [], c;
      for (c = body.firstChild; c; c = c.nextSibling) if (c.nodeType === 1) nodes.push(c);
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        if (n.localName !== 'p' || !paraDeletable(n)) continue;
        var prev = prevElem(n), next = nextElem(n);
        if (!next) continue;                                  // never drop the final block
        var pT = prev && prev.localName === 'tbl', nT = next && next.localName === 'tbl';
        if (pT && nT) continue;                               // required separator between two tables
        if (pT || nT) { body.removeChild(n); changed = true; }
      }
    }
  }

  // ── font + size normalisation over any XML root (document or styles) ──
  function normalizeFontsAndSizes(rootNode, o) {
    tagAll(rootNode, 'rFonts').forEach(function (rf) {
      setA(rf, 'ascii', o.fontName);
      setA(rf, 'hAnsi', o.fontName);
      // NB: cs (complex-script) & eastAsia left untouched so Tamil / CJK keep working fonts
    });
    var half = String(Math.round(o.sizePt * 2));
    tagAll(rootNode, 'sz').forEach(function (s) { setA(s, 'val', half); });
    tagAll(rootNode, 'szCs').forEach(function (s) { setA(s, 'val', half); });
  }

  // ── ensure a run's rPr carries font + size (used where inheritance is unsafe) ──
  function ensureRunFontSize(doc, r, o) {
    if (r.localName !== 'r') return;
    var rpr = upsert(doc, r, 'rPr', null) || firstKid(r, 'rPr');
    if (!firstKid(r, 'rPr')) { rpr = el(doc, 'rPr'); r.insertBefore(rpr, r.firstChild); }
    else rpr = firstKid(r, 'rPr');
    var rf = firstKid(rpr, 'rFonts');
    if (!rf) { rf = el(doc, 'rFonts'); rpr.insertBefore(rf, rpr.firstChild); }
    setA(rf, 'ascii', o.fontName); setA(rf, 'hAnsi', o.fontName);
  }

  function makeBold(doc, r) {
    if (r.localName !== 'r') return;
    if (!textOf(r).trim() && !tagAll(r, 't').length) return;
    var rpr = firstKid(r, 'rPr');
    if (!rpr) { rpr = el(doc, 'rPr'); r.insertBefore(rpr, r.firstChild); }
    if (!firstKid(rpr, 'b')) { upsert(doc, rpr, 'b', 'rPr'); }
    if (!firstKid(rpr, 'bCs')) { upsert(doc, rpr, 'bCs', 'rPr'); }
  }

  // ── paragraph-level spacing / indentation / heading logic ──
  function isHeadingText(t) {
    var s = (t || '').trim();
    if (!s || s.length > 150) return false;
    if (/^[A-Z]\.\s+\S/.test(s)) return true;                 // "A. ADMINISTRATION:"
    if (/^\d+(\.\d+)*[.)]\s+\S/.test(s) && s.length <= 130) return true; // "1. General ..."
    if (/^(SECTION|PART|ANNEXURE|APPENDIX|SUMMARY|CONCLUSION|GENERAL|OBSERVATION)/i.test(s) && s.length <= 90) return true;
    if (s.length <= 80 && /:$/.test(s) && /[A-Za-z]/.test(s)) return true; // short label ending ':'
    if (s.length <= 70 && s === s.toUpperCase() && /[A-Z]{3,}/.test(s)) return true; // ALL CAPS line
    return false;
  }

  function processParagraph(doc, p, o) {
    var inCell = !!ancestorOf(p, 'tc');
    var pPr = firstKid(p, 'pPr');
    if (!pPr) { pPr = el(doc, 'pPr'); p.insertBefore(pPr, p.firstChild); }

    // spacing: 1.15 line everywhere; a modest, uniform gap hugs tables but
    // blank/empty paragraphs never add dead space of their own
    var empty = paraIsEmpty(p);
    var gap = Math.round(o.paraAfterPt * 20);
    var afterTw = inCell || empty ? 0 : gap;
    var beforeTw = (!inCell && !empty && prevElem(p) && prevElem(p).localName === 'tbl') ? gap : 0;
    var sp = upsert(doc, pPr, 'spacing', 'pPr');
    setA(sp, 'before', beforeTw);
    setA(sp, 'after', afterTw);
    setA(sp, 'line', String(Math.round(o.lineSpacing * 240)));
    setA(sp, 'lineRule', 'auto');

    // drop contextualSpacing so paragraph spacing actually shows
    kids(pPr, 'contextualSpacing').forEach(function (c) { pPr.removeChild(c); });

    // flatten negative / margin-busting indents
    var ind = firstKid(pPr, 'ind');
    if (ind) {
      ['left', 'start'].forEach(function (a) {
        var v = getA(ind, a); if (v !== '' && v != null && parseInt(v, 10) < 0) setA(ind, a, '0');
      });
      var fl = getA(ind, 'firstLine'), hg = getA(ind, 'hanging');
      if (fl && parseInt(fl, 10) > 2000) setA(ind, 'firstLine', '0');
      if (hg && parseInt(hg, 10) < 0) setA(ind, 'hanging', '0');
    }

    var txt = textOf(p);

    // tidy fake leading-space indentation (outside tables only)
    if (o.tidySpacing && !inCell) {
      var ts = tagAll(p, 't');
      if (ts.length) {
        var first = ts[0], v0 = first.textContent || '';
        if (/^\s{2,}/.test(v0)) {
          first.textContent = v0.replace(/^\s+/, '');
          if (/\s$/.test(first.textContent)) first.setAttributeNS(XMLNS, 'xml:space', 'preserve');
        }
      }
    }

    // embolden detected headings (outside tables)
    if (o.boldHeadings && !inCell && isHeadingText(txt)) {
      kids(p, 'r').forEach(function (r) { makeBold(doc, r); });
    }
  }

  // ── bullet / numbering normalisation ──
  function normalizeNumbering(doc, o) {
    var STEP = 720; // 0.5"
    tagAll(doc, 'lvl').forEach(function (lvl) {
      var ilvl = parseInt(getA(lvl, 'ilvl') || '0', 10) || 0;
      var fmtEl = firstKid(lvl, 'numFmt');
      var fmt = fmtEl ? getA(fmtEl, 'val') : '';

      // uniform per-level indentation
      var pPr = firstKid(lvl, 'pPr');
      if (!pPr) { pPr = el(doc, 'pPr'); lvl.appendChild(pPr); }
      var ind = firstKid(pPr, 'ind');
      if (!ind) { ind = el(doc, 'ind'); pPr.appendChild(ind); }
      setA(ind, 'left', String((ilvl + 1) * STEP));
      setA(ind, 'hanging', '360');

      // left-justify the marker
      var jc = firstKid(lvl, 'lvlJc');
      if (jc) setA(jc, 'val', 'left');

      if (fmt === 'bullet') {
        var lt = firstKid(lvl, 'lvlText');
        if (!lt) { lt = el(doc, 'lvlText'); lvl.appendChild(lt); }
        var glyph, font;
        switch (ilvl % 3) {
          case 0: glyph = ''; font = 'Symbol'; break;      // •
          case 1: glyph = 'o'; font = 'Courier New'; break;      // o
          default: glyph = ''; font = 'Wingdings'; break;  // ▪
        }
        setA(lt, 'val', glyph);
        var lrpr = firstKid(lvl, 'rPr');
        if (!lrpr) { lrpr = el(doc, 'rPr'); lvl.appendChild(lrpr); }
        var lrf = firstKid(lrpr, 'rFonts');
        if (!lrf) { lrf = el(doc, 'rFonts'); lrpr.insertBefore(lrf, lrpr.firstChild); }
        setA(lrf, 'ascii', font); setA(lrf, 'hAnsi', font); setA(lrf, 'cs', font); setA(lrf, 'hint', 'default');
      }
    });
  }

  // ── table normalisation ──
  function setBorders(doc, tblPr) {
    var b = firstKid(tblPr, 'tblBorders');
    if (b) tblPr.removeChild(b);
    b = upsert(doc, tblPr, 'tblBorders', 'tblPr');
    clearChildren(b);
    ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].forEach(function (side) {
      var s = el(doc, side);
      setA(s, 'val', 'single'); setA(s, 'sz', '4'); setA(s, 'space', '0'); setA(s, 'color', 'auto');
      b.appendChild(s);
    });
  }
  function setCellMargins(doc, tblPr) {
    var m = firstKid(tblPr, 'tblCellMar');
    if (m) tblPr.removeChild(m);
    m = upsert(doc, tblPr, 'tblCellMar', 'tblPr');
    clearChildren(m);
    [['top', 40], ['left', 108], ['bottom', 40], ['right', 108]].forEach(function (pair) {
      var s = el(doc, pair[0]); setA(s, 'w', pair[1]); setA(s, 'type', 'dxa'); m.appendChild(s);
    });
  }

  function processTable(doc, tbl, o, textWidth) {
    var tblPr = firstKid(tbl, 'tblPr');
    if (!tblPr) { tblPr = el(doc, 'tblPr'); tbl.insertBefore(tblPr, tbl.firstChild); }

    // centre the table
    var jc = upsert(doc, tblPr, 'jc', 'tblPr'); setA(jc, 'val', 'center');
    // flatten table indent
    var tblInd = upsert(doc, tblPr, 'tblInd', 'tblPr'); setA(tblInd, 'w', '0'); setA(tblInd, 'type', 'dxa');
    setBorders(doc, tblPr);
    setCellMargins(doc, tblPr);
    // no colour shading on tables — strip any table-level fill
    kids(tblPr, 'shd').forEach(function (s) { tblPr.removeChild(s); });

    // clamp width to page text-width so wide tables never overflow / trigger repair
    var tblW = firstKid(tblPr, 'tblW');
    var factor = 1, curW = 0;
    if (tblW && getA(tblW, 'type') === 'dxa') {
      curW = parseInt(getA(tblW, 'w') || '0', 10);
      if (curW > textWidth && curW > 0) {
        factor = textWidth / curW;
        setA(tblW, 'w', String(Math.round(curW * factor)));
      }
    }
    if (factor < 1) {
      var grid = firstKid(tbl, 'tblGrid');
      if (grid) kids(grid, 'gridCol').forEach(function (g) {
        var w = parseInt(getA(g, 'w') || '0', 10); if (w) setA(g, 'w', String(Math.round(w * factor)));
      });
    }

    var rows = kids(tbl, 'tr');
    rows.forEach(function (tr, ri) {
      var isHeader = ri === 0;
      if (isHeader) {
        var trPr = firstKid(tr, 'trPr');
        if (!trPr) { trPr = el(doc, 'trPr'); tr.insertBefore(trPr, tr.firstChild); }
        if (!firstKid(trPr, 'tblHeader')) upsert(doc, trPr, 'tblHeader', 'trPr');
        if (!firstKid(trPr, 'cantSplit')) upsert(doc, trPr, 'cantSplit', 'trPr');
      }
      kids(tr, 'tc').forEach(function (tc) {
        var tcPr = firstKid(tc, 'tcPr');
        if (!tcPr) { tcPr = el(doc, 'tcPr'); tc.insertBefore(tcPr, tc.firstChild); }
        // scale cell width too
        if (factor < 1) {
          var tcW = firstKid(tcPr, 'tcW');
          if (tcW && getA(tcW, 'type') === 'dxa') {
            var w = parseInt(getA(tcW, 'w') || '0', 10); if (w) setA(tcW, 'w', String(Math.round(w * factor)));
          }
        }
        // vertical-centre every cell
        var vA = upsert(doc, tcPr, 'vAlign', 'tcPr'); setA(vA, 'val', 'center');
        // no colour shading on tables — strip any existing cell fill
        kids(tcPr, 'shd').forEach(function (s) { tcPr.removeChild(s); });
        if (isHeader) {
          kids(tc, 'p').forEach(function (p) {
            var pPr = firstKid(p, 'pPr'); if (!pPr) { pPr = el(doc, 'pPr'); p.insertBefore(pPr, p.firstChild); }
            var pjc = upsert(doc, pPr, 'jc', 'pPr'); setA(pjc, 'val', 'center');
            kids(p, 'r').forEach(function (r) { makeBold(doc, r); });
          });
        }
      });
    });
  }

  // ── section / page setup ──
  function processSection(doc, o) {
    tagAll(doc, 'sectPr').forEach(function (sect) {
      var pgSz = upsert(doc, sect, 'pgSz', null);
      if (!firstKid(sect, 'pgSz')) { pgSz = el(doc, 'pgSz'); sect.appendChild(pgSz); } else pgSz = firstKid(sect, 'pgSz');
      setA(pgSz, 'w', '11906'); setA(pgSz, 'h', '16838'); // A4 portrait
      var pgMar = firstKid(sect, 'pgMar');
      if (!pgMar) { pgMar = el(doc, 'pgMar'); sect.appendChild(pgMar); }
      var m = o.marginTwips;
      setA(pgMar, 'top', m.top); setA(pgMar, 'bottom', m.bottom);
      setA(pgMar, 'left', m.left); setA(pgMar, 'right', m.right);
      setA(pgMar, 'header', '720'); setA(pgMar, 'footer', '720'); setA(pgMar, 'gutter', '0');
    });
  }

  // ── styles.xml: bake defaults so nothing re-introduces stray fonts/sizes ──
  function processStyles(doc, o) {
    var half = String(Math.round(o.sizePt * 2));
    var dd = firstKid(doc.documentElement, 'docDefaults');
    if (!dd) { dd = el(doc, 'docDefaults'); doc.documentElement.insertBefore(dd, doc.documentElement.firstChild); }
    var rd = firstKid(dd, 'rPrDefault'); if (!rd) { rd = el(doc, 'rPrDefault'); dd.insertBefore(rd, dd.firstChild); }
    var rpr = firstKid(rd, 'rPr'); if (!rpr) { rpr = el(doc, 'rPr'); rd.appendChild(rpr); }
    var rf = firstKid(rpr, 'rFonts'); if (!rf) { rf = el(doc, 'rFonts'); rpr.insertBefore(rf, rpr.firstChild); }
    setA(rf, 'ascii', o.fontName); setA(rf, 'hAnsi', o.fontName);
    var sz = firstKid(rpr, 'sz'); if (!sz) { sz = el(doc, 'sz'); rpr.appendChild(sz); } setA(sz, 'val', half);
    var szCs = firstKid(rpr, 'szCs'); if (!szCs) { szCs = el(doc, 'szCs'); rpr.appendChild(szCs); } setA(szCs, 'val', half);

    // normalise fonts/sizes referenced inside individual style defs too
    normalizeFontsAndSizes(doc.documentElement, o);
  }

  // ── entry point ──────────────────────────────────────────────────
  //  zip: a loaded JSZip instance;  opts: partial DEFAULTS.  Mutates zip in place.
  function reformat(zip, opts) {
    var o = {}; for (var k in DEFAULTS) o[k] = DEFAULTS[k];
    if (opts) for (var k2 in opts) if (opts[k2] != null) o[k2] = opts[k2];

    var DP = (typeof DOMParser !== 'undefined') ? DOMParser : (root.DOMParser);
    var XS = (typeof XMLSerializer !== 'undefined') ? XMLSerializer : (root.XMLSerializer);
    var parser = new DP(), ser = new XS();

    var textWidth = 11906 - o.marginTwips.left - o.marginTwips.right;

    return Promise.resolve().then(function () {
      // ---- styles.xml (optional) ----
      var stylesFile = zip.file('word/styles.xml');
      var p = stylesFile ? stylesFile.async('string') : Promise.resolve(null);
      return p.then(function (stylesXml) {
        if (stylesXml) {
          var sdoc = parser.parseFromString(stylesXml, 'application/xml');
          if (!sdoc.getElementsByTagName('parsererror').length) {
            processStyles(sdoc, o);
            zip.file('word/styles.xml', ser.serializeToString(sdoc));
          }
        }
      });
    }).then(function () {
      // ---- numbering.xml (optional) ----
      var numFile = zip.file('word/numbering.xml');
      if (!numFile) return;
      return numFile.async('string').then(function (numXml) {
        var ndoc = parser.parseFromString(numXml, 'application/xml');
        if (ndoc.getElementsByTagName('parsererror').length) return;
        normalizeFontsAndSizes(ndoc.documentElement, o); // fonts/sizes on numbering runs
        normalizeNumbering(ndoc, o);                      // then override bullet glyph fonts
        zip.file('word/numbering.xml', ser.serializeToString(ndoc));
      });
    }).then(function () {
      // ---- document.xml (required) ----
      var docFile = zip.file('word/document.xml');
      if (!docFile) throw new Error('This file is missing word/document.xml — it may be an old .doc, not a .docx.');
      return docFile.async('string').then(function (docXml) {
        var doc = parser.parseFromString(docXml, 'application/xml');
        if (doc.getElementsByTagName('parsererror').length)
          throw new Error('The document could not be parsed. It may be corrupt.');

        normalizeFontsAndSizes(doc.documentElement, o);
        collapseTableGaps(doc);
        tagAll(doc, 'p').forEach(function (pp) { processParagraph(doc, pp, o); });
        tagAll(doc, 'tbl').forEach(function (t) { processTable(doc, t, o, textWidth); });
        processSection(doc, o);

        zip.file('word/document.xml', ser.serializeToString(doc));

        // headers / footers: fonts + sizes only (keep their own layout)
        return zip;
      });
    }).then(function () {
      var jobs = [];
      zip.forEach(function (path) {
        if (/^word\/(header|footer)\d+\.xml$/.test(path)) {
          jobs.push(zip.file(path).async('string').then(function (xml) {
            var d = parser.parseFromString(xml, 'application/xml');
            if (d.getElementsByTagName('parsererror').length) return;
            normalizeFontsAndSizes(d.documentElement, o);
            zip.file(path, ser.serializeToString(d));
          }));
        }
      });
      return Promise.all(jobs);
    }).then(function () { return zip; });
  }

  // ── quick pre-flight stats (for the UI preview) ──
  function inspect(zip) {
    return zip.file('word/document.xml').async('string').then(function (xml) {
      var count = function (re) { var m = xml.match(re); return m ? m.length : 0; };
      var fonts = (xml.match(/w:ascii="([^"]+)"/g) || []).map(function (s) { return s.slice(9, -1); });
      var sizes = (xml.match(/<w:sz w:val="([^"]+)"/g) || []).map(function (s) { return s.replace(/\D/g, ''); });
      return {
        paragraphs: count(/<w:p[ >]/g),
        tables: count(/<w:tbl>/g),
        rows: count(/<w:tr[ >]/g),
        lists: count(/<w:numPr>/g),
        fonts: Array.from(new Set(fonts)),
        sizesHalfPt: Array.from(new Set(sizes))
      };
    });
  }

  var api = { reformat: reformat, inspect: inspect, DEFAULTS: DEFAULTS, WNS: WNS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.FormatEngine = api;
})(typeof window !== 'undefined' ? window : globalThis);
