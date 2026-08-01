/*  karur-scope.js  —  Single source of truth for the Karur Sub Division boundary.
 *  ─────────────────────────────────────────────────────────────────────────────
 *  This web / Android application serves ONLY the Karur Sub Division. A user may
 *  upload a whole-division (or region) export — DS / GDS / Outsiders payroll,
 *  the Office Master, or the Temporary Arrangements dump — that also carries the
 *  neighbouring sub-divisions (Aravakurichi, Kulittalai, Manaparai). Every page
 *  runs each dataset through this module so that data for other sub-divisions is
 *  never saved, shown, counted or exported here.
 *
 *  Authority  : the Office Master. An office belongs to our scope when its
 *               sub_division is "Karur" — plus the three Karur H.O admin units
 *               (LPC Karur, WCTC Karur, Karur Division PDN) that carry no
 *               sub-division of their own. load() rebuilds the live scope from
 *               the saved (already Karur-clamped) Office Master; the embedded
 *               list below is the fallback used before it loads / when offline.
 *  Matching   : primarily by office_id; a normalised office-name fallback keeps
 *               a genuine Karur office that happens to be missing from the master
 *               (e.g. a S.O whose B.O twin is listed).
 *
 *  Record shapes handled:
 *    Departmental (DS) / GDS staff : Office_id  / Office_desc
 *    Outsiders (OUT)               : office_id  / office_name
 *    Office Master                 : office_id  / office_name   (+ sub_division)
 *    Arrangements                  : officeId   / office
 *    Sanctioned posts              : Office ID  / Facility Description
 */
window.KarurScope = (function () {
  // ── Embedded fallback: the 82 Karur-scope office ids (79 Karur + 3 admin) ──
  var FALLBACK_IDS = ['29106748','29106752','29106765','29106766','29106770','29106785','29106786','29106788','29106811','29106813','29106814','29106821','29106829','29106834','29106847','29106848','29106853','29106857','29106861','29106865','29106866','29106867','29106879','29106887','29106889','29106893','29106894','29106898','29106899','29106904','29106905','29106908','29106914','29106925','29106933','29106937','29106938','29106939','29106940','29106941','29106949','29106950','29106952','29106959','29106961','29106970','29106972','29106975','29106984','29106986','29106990','29106996','29106998','29107002','29107004','29107013','29107015','29107016','29107017','29109278','29260061','29360072','29400034','29530036','29640128','29661915','29661920','29661922','29661923','29661924','29661932','29661940','29661941','29661942','29661944','29661945','29661947','29661952','29661955','29661956','29661958','29690029'];
  var FALLBACK_NAMES = ['appipalayam','athur','dalavapalayam','eastthavittupalayam','emur','kadambankurichi','kadaparai','kakkavadi','karuppur','kathalapatti','kattalai','kodangipatti','koyampalli','kuppachipalayam','manavadi','manavasi','manmangalam','melapalayam','minnampalli','mookanankurichi','moolimangalam','mudiganam','nadayanur','nanjaipugalur','nanniyur','nerur','noyyal','paganatham','palaiyur','pallamarudapatti','pallapalayam','panjamadevi','pavithram','poolampalayam','puliyur','punjaipugalur','punjaithottakurichi','punnam','punnamchatram','puthambur','renganathampettai','renganathapuram','svellalapatti','semangi','senapiratti','somur','sukkaliyur','thalapatti','thoranakkalpatti','thumbivadi','uppidamangalam','valayalkaranpudur','vangaleast','vedichipalayam','veerarakkiam','vennamalai','vettamangalam','viswanathapuri','westorathai','othaiyur','karurbpc','karur','lpckarur','karurdivision','andankoil','gandhigramam','kagtithapuram','karurcollectorate','karurwest','mayanur','pasupathipalayam','pugalursugarfactory','puliyurcementfactory','ramakrishnapuram','sengunthapuram','thanthonimalai','tirumanilaiyur','vangal','velayuthampalayam','vengamedu','wctckarur'];

  var idSet = new Set(FALLBACK_IDS);
  var nameSet = new Set(FALLBACK_NAMES);
  var loaded = false;

  function str(v) { return v == null ? '' : String(v).trim(); }

  // Mirror of employees.html normOffice() so name matching stays consistent.
  function normOffice(s) {
    s = str(s).toLowerCase().replace(/[^a-z0-9]/g, '');
    return s.replace(/(s\.?o|b\.?o|h\.?o|sso)$/, '');
  }

  function officeIdOf(r) {
    if (!r) return '';
    return str(r.office_id != null ? r.office_id
      : r.Office_id != null ? r.Office_id
      : r.officeId != null ? r.officeId
      : r['Office ID']);
  }
  function officeNameOf(r) {
    if (!r) return '';
    return str(r.office_name != null ? r.office_name
      : r.Office_desc != null ? r.Office_desc
      : r.office_desc != null ? r.office_desc
      : r.office != null ? r.office
      : r.officeName != null ? r.officeName
      : r['Facility Description']);
  }

  // Does an office-master row belong to the Karur Sub Division scope?
  // Accepts both the parsed shape (sub_division) and the raw export
  // (sub_division_name, blank for the Karur H.O admin offices).
  function isKarurOfficeMasterRow(o) {
    if (!o) return false;
    var sd = str(o.sub_division != null ? o.sub_division : o.sub_division_name);
    if (/karur/i.test(sd)) return true;                     // the "Karur" sub-division
    if (sd === '' || sd === '(Head / Admin)') {             // Karur H.O admin units
      var div = str(o.division != null ? o.division : o.division_name);
      return div === '' || /karur/i.test(div);
    }
    return false;                                           // Aravakurichi / Kulittalai / Manaparai
  }

  // Is a staff / arrangement / post record inside our scope?
  function inScope(r) {
    var id = officeIdOf(r);
    if (id && idSet.has(id)) return true;
    var nm = officeNameOf(r);
    if (nm) { var n = normOffice(nm); if (n && nameSet.has(n)) return true; }
    return false;
  }

  // Rebuild the live scope from a Karur-only office list.
  function setFromOffices(offices) {
    var ids = new Set(), names = new Set();
    for (var i = 0; i < (offices || []).length; i++) {
      var o = offices[i];
      var id = officeIdOf(o); if (id) ids.add(id);
      var nm = normOffice(officeNameOf(o)); if (nm) names.add(nm);
    }
    if (ids.size) { idSet = ids; nameSet = names; loaded = true; }
  }

  // Keep only Karur-scope office-master rows.
  function filterOffices(offices) { return (offices || []).filter(isKarurOfficeMasterRow); }
  // Keep only records (staff / arrangements / posts) whose office is in scope.
  function filterRecords(rows) { return (rows || []).filter(inScope); }

  // Load the saved Office Master (Supabase → /api fallback), clamp to Karur,
  // and refresh the live scope. Safe to call repeatedly; a no-op once loaded.
  async function load() {
    if (loaded) return;
    try {
      if (typeof SB !== 'undefined') {
        await SB.init();
        if (SB.ready) {
          var ds = await SB.getDataset('OFFICES');
          if (ds && ds.data && ds.data.length) { setFromOffices(filterOffices(ds.data)); return; }
        }
      }
    } catch (e) { /* fall through to static */ }
    var urls = ['/api/offices-master', '/data/office_master.json'];
    for (var u = 0; u < urls.length; u++) {
      try {
        var res = await fetch(urls[u], { cache: 'no-store' });
        if (!res.ok) continue;
        var j = await res.json();
        var offs = j.offices || j || [];
        if (offs.length) { setFromOffices(filterOffices(offs)); return; }
      } catch (e) { /* try next / keep embedded fallback */ }
    }
  }

  return {
    load: load, inScope: inScope, filterRecords: filterRecords,
    filterOffices: filterOffices, isKarurOfficeMasterRow: isKarurOfficeMasterRow,
    setFromOffices: setFromOffices, officeIdOf: officeIdOf, officeNameOf: officeNameOf,
    normOffice: normOffice,
    get size() { return idSet.size; }, get loaded() { return loaded; }
  };
})();
