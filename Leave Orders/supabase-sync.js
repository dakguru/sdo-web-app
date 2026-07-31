/*  supabase-sync.js  –  Supabase connector for Karur SDO web app (Leave Orders pages)
 *  ─────────────────────────────────────────────────────────────────────────────────
 *  Identical API surface to /supabase-sync.js in wwwroot.
 *  This copy is placed in the Leave Orders folder so the self-contained HTML pages
 *  can load it via a relative path:  <script src="supabase-sync.js"></script>
 *
 *  Both copies share the same Supabase credentials and table names.
 *  ────────────────────────────────────────────────────────────── */

const SB = (() => {
  const SUPABASE_URL  = 'https://hfzbvqpxraeoqmvhtjto.supabase.co';
  const SUPABASE_KEY  = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhmemJ2cXB4cmFlb3Ftdmh0anRvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ5NDc4NDUsImV4cCI6MjEwMDUyMzg0NX0.kPcH1_YyWD32syqD2NBKWZpAVRds-SpwPTiXPpi6nIw';
  const REST          = `${SUPABASE_URL}/rest/v1`;
  let _ready = false;

  function headers(extra) {
    return Object.assign({
      'apikey': SUPABASE_KEY, 'Authorization': `Bearer ${SUPABASE_KEY}`,
      'Content-Type': 'application/json', 'Accept': 'application/json',
    }, extra || {});
  }

  async function select(table, query) {
    const url = `${REST}/${table}${query ? '?' + query : ''}`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`SB.select ${table}: ${res.status} ${await res.text()}`);
    return res.json();
  }

  async function upsert(table, rows) {
    const url = `${REST}/${table}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: headers({ 'Prefer': 'resolution=merge-duplicates,return=minimal' }),
      body: JSON.stringify(Array.isArray(rows) ? rows : [rows]),
    });
    if (!res.ok) throw new Error(`SB.upsert ${table}: ${res.status} ${await res.text()}`);
    return true;
  }

  async function del(table, filter) {
    const url = `${REST}/${table}?${filter}`;
    const res = await fetch(url, { method: 'DELETE', headers: headers({ 'Prefer': 'return=minimal' }) });
    if (!res.ok) throw new Error(`SB.del ${table}: ${res.status} ${await res.text()}`);
    return true;
  }

  // ── Cross-app dataset codec ─────────────────────────────────────────
  // The Android app stores each dataset as strict typed entities (camelCase,
  // ISO-free dd-mm-yyyy dates, TEL as a list). The web app works in its own
  // record shape (Snake_Case + _type, ISO dates, TEL as an object map). This
  // codec converts web <-> the shared WIRE format (= the Android entity shape)
  // so an upload from EITHER app is readable by BOTH. The full original web
  // record is tucked into `payloadJson` to preserve pay columns Android drops;
  // Android-origin records are synthesized back into web shape from typed fields.
  const DatasetCodec = (() => {
    const s = v => (v == null ? '' : String(v));
    const num = v => { if (v == null || v === '') return null; const n = parseFloat(v); return isNaN(n) ? null : n; };
    const isoToDmy = d => { const m = String(d||'').match(/^(\d{4})-(\d{2})-(\d{2})$/); return m ? `${m[3]}-${m[2]}-${m[1]}` : (d||''); };
    const dmyToIso = d => { const m = String(d||'').match(/^(\d{2})-(\d{2})-(\d{4})$/); return m ? `${m[3]}-${m[2]}-${m[1]}` : (d||''); };

    function empToWire(e) {
      return {
        type: s(e._type) || 'DS', employeeId: s(e.Employee_id), payrollId: s(e.Payroll_id),
        ddoOfficeId: s(e.DDO_Office_ID), name: s(e.Employee_name), gender: s(e.Gender),
        level: s(e.Level), postId: s(e.Post_id), designation: s(e.Post_desc),
        estKey: s(e.Est_key), estDescription: s(e.Est_description), officeId: s(e.Office_id),
        officeName: s(e.Office_desc), pan: s(e.Pancard_number), dateOfBirth: s(e.Date_of_birth),
        dateOfJoin: s(e.Date_of_join), bankAccNo: s(e.Bank_acc_no), ifsc: s(e.IFSC_code),
        bankType: s(e.Bank_type), status: s(e.Status), totalEarnings: num(e.Total_earnings),
        totalDeductions: num(e.Total_deductions), totalThirdparty: num(e.Total_thirdparty_deduction),
        netPayable: num(e.Net_payable), payloadJson: JSON.stringify(e)
      };
    }
    function empFromWire(w) {
      if (w && w._type && !w.employeeId) return w;                       // already web-shaped (legacy)
      if (w && w.payloadJson) { try { const o = JSON.parse(w.payloadJson); if (o && (o._type || o.Employee_id)) return o; } catch (_) {} }
      return {
        _type: s(w.type) || 'DS', Payroll_id: s(w.payrollId), DDO_Office_ID: s(w.ddoOfficeId),
        Employee_id: s(w.employeeId), Employee_name: s(w.name), Gender: s(w.gender), Level: s(w.level),
        Post_id: s(w.postId), Post_desc: s(w.designation), Est_key: s(w.estKey), Est_description: s(w.estDescription),
        Office_id: s(w.officeId), Office_desc: s(w.officeName), Pancard_number: s(w.pan),
        Date_of_birth: s(w.dateOfBirth), Date_of_join: s(w.dateOfJoin), Bank_acc_no: s(w.bankAccNo),
        IFSC_code: s(w.ifsc), Bank_type: s(w.bankType), Status: s(w.status), Total_earnings: w.totalEarnings,
        Total_deductions: w.totalDeductions, Total_thirdparty_deduction: w.totalThirdparty, Net_payable: w.netPayable
      };
    }

    function outToWire(o) {
      const full = [o.first_name, o.middle_name, o.last_name].map(s).filter(Boolean).join(' ').trim();
      return {
        resourceId: s(o.resource_id), fullName: full || s(o.resource_id), firstName: s(o.first_name),
        middleName: s(o.middle_name), lastName: s(o.last_name), fatherName: s(o.father_name),
        dateOfBirth: s(o.date_of_birth), community: s(o.community), gender: s(o.gender),
        address1: s(o.address1), address2: s(o.address2), address3: s(o.address3), address4: s(o.address4),
        pinCode: s(o.pin_code), email: s(o.personal_email_id), mobile: s(o.mobile_no), aadhaar: s(o.aadhaar_number),
        pan: s(o.pan_number), education: s(o.education_qualification), approverPostId: s(o.approver_post_id),
        loginRequired: s(o.login_required), makerId: s(o.maker_id), remarks: s(o.remarks), officeId: s(o.office_id),
        officeName: s(o.office_name), subDivisionId: s(o.sub_division_id), divisionId: s(o.division_id),
        divisionName: s(o.division_name), bankAccNo: s(o.bank_acc_no), ifsc: s(o.ifsc_code), bankType: s(o.bank_type),
        resourceStatus: s(o.resource_status), payloadJson: JSON.stringify(o)
      };
    }
    function outFromWire(w) {
      if (w && w._type && !w.resourceId) return w;                       // already web-shaped (legacy)
      if (w && w.payloadJson) { try { const o = JSON.parse(w.payloadJson); if (o && (o.resource_id || o._type)) return o; } catch (_) {} }
      return {
        _type: 'OUT', maker_id: s(w.makerId), resource_id: s(w.resourceId), first_name: s(w.firstName),
        middle_name: s(w.middleName), last_name: s(w.lastName), father_name: s(w.fatherName),
        date_of_birth: s(w.dateOfBirth), community: s(w.community), gender: s(w.gender), address1: s(w.address1),
        address2: s(w.address2), address3: s(w.address3), address4: s(w.address4), pin_code: s(w.pinCode),
        personal_email_id: s(w.email), mobile_no: s(w.mobile), aadhaar_number: s(w.aadhaar), pan_number: s(w.pan),
        education_qualification: s(w.education), approver_post_id: s(w.approverPostId), remarks: s(w.remarks),
        login_required: s(w.loginRequired), office_id: s(w.officeId), office_name: s(w.officeName),
        sub_division_id: s(w.subDivisionId), bank_acc_no: s(w.bankAccNo), ifsc_code: s(w.ifsc),
        bank_type: s(w.bankType), division_id: s(w.divisionId), division_name: s(w.divisionName),
        resource_status: s(w.resourceStatus)
      };
    }

    const OFF = {
      office_id: 'officeId', office_name: 'officeName', office_type: 'officeType', office_class: 'officeClass',
      office_status: 'officeStatus', sub_division: 'subDivision', sub_division_office_id: 'subDivisionOfficeId',
      division: 'division', ho_name: 'hoName', so_name: 'soName', region: 'region', circle: 'circle', email: 'email',
      contact: 'contact', pincode: 'pincode', address1: 'address1', address2: 'address2', address3: 'address3',
      village: 'village', taluk: 'taluk', city: 'city', state: 'state', latitude: 'latitude', longitude: 'longitude',
      working_days: 'workingDays', working_hours_from: 'workingHoursFrom', working_hours_to: 'workingHoursTo',
      sol_id: 'solId', csi_facility_id: 'csiFacilityId', pli_id: 'pliId', gstn: 'gstn', pao_code: 'paoCode',
      delivery_office: 'deliveryOffice', reporting_office_id: 'reportingOfficeId', head_of_office_id: 'headOfOfficeId'
    };
    function offToWire(o) { const w = {}; for (const k in OFF) w[OFF[k]] = o[k] == null ? null : s(o[k]); if (!w.officeId) w.officeId = s(o.office_id); if (!w.officeName) w.officeName = s(o.office_name); return w; }
    function offFromWire(w) { if (w && w.office_id != null && w.officeId == null) return w; const o = {}; for (const k in OFF) o[k] = w[OFF[k]] == null ? '' : s(w[OFF[k]]); return o; }

    const CAT_TO_WIRE = { GDS: 'GDS', NONSANC: 'NONSANC_OUT' };
    const ATYPE = { GDS: 'GDS Post by Outsrc Resource', NONSANC: 'Non Sanc Post by Outsrc Resource' };
    function arrToWire(a) {
      const cat = s(a.cat).toUpperCase();
      return {
        category: CAT_TO_WIRE[cat] || cat || 'GDS', arrangementType: ATYPE[cat] || null, post: s(a.post),
        officeName: s(a.office), officeId: s(a.officeId), personId: s(a.subId), personName: s(a.subName),
        substituteId: s(a.subId), substituteName: s(a.subName), fromDate: isoToDmy(a.from), toDate: isoToDmy(a.to),
        status: s(a.status)
      };
    }
    function arrFromWire(w) {
      if (w && w.cat != null && w.category == null) return w;            // already web-shaped (legacy)
      const cat = s(w.category).toUpperCase();
      return {
        cat: cat === 'GDS' ? 'GDS' : 'NONSANC', post: s(w.post), office: s(w.officeName), officeId: s(w.officeId),
        subId: s(w.substituteId || w.personId), subName: s(w.substituteName || w.personName),
        from: dmyToIso(w.fromDate), to: dmyToIso(w.toDate), status: s(w.status)
      };
    }

    function telToWire(map) { const out = []; if (map && typeof map === 'object' && !Array.isArray(map)) for (const k in map) out.push({ employeeId: s(k), phone: s(map[k]) }); return out; }
    function telFromWire(list) { const map = {}; if (Array.isArray(list)) { for (const r of list) if (r && r.employeeId != null) map[s(r.employeeId)] = s(r.phone); } else if (list && typeof list === 'object') Object.assign(map, list); return map; }

    function toWire(type, data) {
      switch (type) {
        case 'DS': case 'GDS': return (data || []).map(empToWire);
        case 'OUT': return (data || []).map(outToWire);
        case 'OFFICES': return (data || []).map(offToWire);
        case 'ARR': return (data || []).map(arrToWire);
        case 'TEL': return telToWire(data || {});
        default: return data;
      }
    }
    function fromWire(type, data) {
      switch (type) {
        case 'DS': case 'GDS': return (Array.isArray(data) ? data : []).map(empFromWire);
        case 'OUT': return (Array.isArray(data) ? data : []).map(outFromWire);
        case 'OFFICES': return (Array.isArray(data) ? data : []).map(offFromWire);
        case 'ARR': return (Array.isArray(data) ? data : []).map(arrFromWire);
        case 'TEL': return telFromWire(data);
        default: return data;
      }
    }
    return { toWire, fromWire };
  })();
  if (typeof window !== 'undefined') window.DatasetCodec = DatasetCodec;

  async function getDataset(type) {
    const rows = await select('app_datasets', `type=eq.${encodeURIComponent(type)}&select=payload,count,uploaded_by,uploaded_at_ms`);
    if (!rows.length) return null;
    try { return { data: DatasetCodec.fromWire(type, JSON.parse(rows[0].payload)), count: rows[0].count, uploadedBy: rows[0].uploaded_by, uploadedAt: rows[0].uploaded_at_ms }; }
    catch (e) { console.error(`SB.getDataset parse error for ${type}`, e); return null; }
  }

  async function putDataset(type, dataObj, uploadedBy) {
    const wire = DatasetCodec.toWire(type, dataObj);
    const dataStr = JSON.stringify(wire);
    const count = Array.isArray(wire) ? wire.length : (typeof wire === 'object' && wire ? Object.keys(wire).length : 0);
    return upsert('app_datasets', { type: type, payload: dataStr, count, uploaded_by: uploadedBy || 'web', uploaded_at_ms: Date.now() });
  }

  async function getAllDatasets() {
    const map = {};
    const rows = await select('app_datasets', 'select=type,payload,count,uploaded_by,uploaded_at_ms');
    for (const r of rows) { try { map[r.type] = { data: DatasetCodec.fromWire(r.type, JSON.parse(r.payload)), count: r.count, uploadedBy: r.uploaded_by, uploadedAt: r.uploaded_at_ms }; } catch (e) {} }
    return map;
  }

  async function getPhoneEdits() { return select('app_staff_phones', 'select=target_type,target_id,phone,updated_by,updated_at_ms'); }
  async function putPhoneEdit(tt, ti, phone, by) { return upsert('app_staff_phones', { target_type: tt, target_id: ti, phone: phone||'', updated_by: by||'web', updated_at_ms: Date.now() }); }
  async function getUsers() { return select('app_users', 'select=username,display_name,role,active,created_at,updated_at_ms'); }
  async function getNotes() { return select('app_notes', 'deleted=eq.false&select=*'); }
  async function getFavorites() { return select('app_favorites', 'deleted=eq.false&select=*'); }
  async function getActivity(limit) { return select('app_activity', `select=*&order=created_at.desc&limit=${limit||50}`); }
  async function getMessages(since) { let q='deleted=eq.false&select=*&order=created_at.asc'; if(since)q+=`&created_at=gt.${since}`; return select('app_messages',q); }
  async function postMessage(msg) { return upsert('app_messages', msg); }
  async function ping() { try { return (await fetch(`${REST}/app_prefs?select=key&limit=1`, { headers: headers() })).ok; } catch { return false; } }
  function _updateBadge(connected) {
    try {
      if (typeof document === 'undefined') return;
      document.querySelectorAll('.privacy').forEach(b => {
        const dot = b.querySelector('.dot');
        b.textContent = '';
        if (dot) b.appendChild(dot);
        b.appendChild(document.createTextNode(connected ? ' Cloud · synced' : ' Offline · local only'));
      });
    } catch (e) {}
  }
  async function init() { if (_ready) return true; try { const ok = await ping(); _ready = ok; if(ok) console.log('%c☁ Supabase connected','color:#4ade80;font-weight:bold'); else console.warn('⚠ Supabase unreachable'); _updateBadge(ok); return ok; } catch(e) { _ready=false; _updateBadge(false); return false; } }

  return { init, ping, get ready(){return _ready}, select, upsert, del, getDataset, putDataset, getAllDatasets, getPhoneEdits, putPhoneEdit, getUsers, getNotes, getFavorites, getActivity, getMessages, postMessage, SUPABASE_URL, REST };
})();
