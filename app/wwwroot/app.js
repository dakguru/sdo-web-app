/* Inspection Report Builder — local SPA (vanilla JS, no build step) */
'use strict';

/* ---------- global state ---------- */
const S = {
  boot: null, report: null, settings: null, clauses: null,
  offices: [], staff: [], villages: {}, reports: [], role: null,
  drafts: [], activeDraftId: null,
  view: 'dashboard', section: 'header', lastSaved: null, dirty: false, lastResult: null
};

/* ---------- table definitions (columns mirror engine field keys) ---------- */
const TEXT = 'text', DATE = 'date', AMT = 'amount', AREA = 'area';
const TABLES = {
  establishment:   { title:'Establishment of the BO', serial:true, key:'employee_id', cols:[
    {k:'name',label:'Name',t:TEXT},{k:'designation',label:'Designation',t:TEXT},{k:'employee_id',label:'Employee ID',t:TEXT},
    {k:'trca',label:'TRCA/Level',t:TEXT},{k:'dob',label:'DOB',t:DATE},{k:'doa',label:'Date of appt',t:DATE},{k:'remarks',label:'Remarks',t:AREA}]},
  devices:         { title:'DARPAN devices & versions', serial:true, cols:[
    {k:'device',label:'Device',t:TEXT},{k:'serial',label:'IMEI / serial no(s)',t:AREA},{k:'version',label:'Version',t:TEXT}]},
  deposit_articles:{ title:'Articles in deposit', serial:true, key:'article_no', cols:[
    {k:'article_no',label:'Article no',t:TEXT},{k:'received_date',label:'Received date',t:DATE},{k:'remarks',label:'Remarks',t:AREA}]},
  bo_balance_checks:{ title:'Random BO balance checks', cols:[
    {k:'date',label:'Date',t:DATE},{k:'bal_per_account',label:'BO bal (per account)',t:AMT},{k:'bal_acknowledged',label:'BO bal (acknowledged)',t:AMT}]},
  mo_receipts:     { title:'MO receipts (MS-87a)', key:'from_rect_no', cols:[
    {k:'from_rect_no',label:'From Rcpt No.',t:TEXT},{k:'from_date',label:'Date',t:DATE},{k:'from_amount',label:'Amount',t:AMT},
    {k:'to_rect_no',label:'To Rcpt No.',t:TEXT},{k:'to_date',label:'Date',t:DATE},{k:'to_amount',label:'Amount',t:AMT}]},
  vpcod_checks:    { title:'MO paid / VP-COD checks', cols:[
    {k:'date',label:'Date',t:DATE},{k:'mo_paid',label:'MO paid',t:AMT},{k:'article_no',label:'VP/COD article no',t:TEXT},
    {k:'date_receipt',label:'Date of receipt',t:DATE},{k:'date_delivery',label:'Date of delivery',t:DATE},{k:'amount',label:'VP/COD amount',t:AMT}]},
  sb26_receipts:   { title:'SB-26 receipts', cols:[
    {k:'book_no',label:'Book no',t:TEXT},{k:'rpt_no',label:'Receipt no',t:TEXT},{k:'date',label:'Date',t:DATE},
    {k:'amount',label:'Amount/scheme',t:TEXT},{k:'remarks',label:'Remarks',t:AREA}]},
  passbooks_checked:{ title:'Passbooks checked by scheme', serial:true, key:'account_no', cols:[
    {k:'scheme',label:'Scheme',t:TEXT},{k:'account_no',label:'Account no',t:TEXT},{k:'dlt',label:'DLT',t:DATE},
    {k:'deposit',label:'Deposit/credit',t:AMT},{k:'withdrawal',label:'Wdl/Debit',t:AMT},{k:'bat',label:'Bal after txn',t:AMT},{k:'remarks',label:'Remarks',t:AREA}]},
  sb28_receipts:   { title:'SB-28 receipts', cols:[
    {k:'book_no',label:'Book no',t:TEXT},{k:'receipt_no',label:'Receipt no',t:TEXT},{k:'date',label:'Date',t:DATE}]},
  ssa_collected:   { title:'SSA accounts collected', serial:true, key:'account_no', cols:[
    {k:'account_no',label:'Account no',t:TEXT},{k:'name',label:'Name',t:TEXT},{k:'cif',label:'CIF',t:TEXT},{k:'address',label:'Address',t:AREA}]},
  sb_collected:    { title:'SB accounts collected', serial:true, key:'account_no', cols:[
    {k:'account_no',label:'Account no',t:TEXT},{k:'name',label:'Name',t:TEXT},{k:'cif',label:'CIF',t:TEXT},{k:'address',label:'Address',t:AREA}]},
  txn_totals:      { title:'Transaction totals for verification', cols:[
    {k:'date',label:'Date',t:DATE},{k:'sb_dep',label:'SB DEP',t:AMT},{k:'rd_dep',label:'RD DEP',t:AMT},{k:'rd_df',label:'RD DF',t:AMT},
    {k:'ssa_dep',label:'SSA DEP',t:AMT},{k:'sb_wd',label:'SB WD',t:AMT},{k:'ippb_wd',label:'IPPB WD',t:AMT}]},
  discontinued_accounts:{ title:'Discontinued RD accounts', serial:true, key:'account_no', cols:[
    {k:'account_no',label:'Account no',t:TEXT},{k:'dlt',label:'DLT',t:DATE},{k:'transaction',label:'Transaction',t:AMT},{k:'bat',label:'BAT',t:AMT}]},
  pli_collection:  { title:'PLI/RPLI premium collection', cols:[
    {k:'date',label:'Date',t:DATE},{k:'amount',label:'Amount',t:AMT}]},
  pli_passbooks:   { title:'PLI/RPLI policy passbooks', serial:true, key:'policy_no', cols:[
    {k:'policy_no',label:'Policy no',t:TEXT},{k:'date',label:'Date',t:DATE},{k:'premium',label:'Premium credit',t:AMT},{k:'paid_upto',label:'Paid upto',t:TEXT}]},
  ippb_txns:       { title:'IPPB quarterly transactions', cols:[
    {k:'date',label:'Date',t:DATE},{k:'dep',label:'Dep',t:AMT},{k:'wdl',label:'Wdl',t:AMT}]}
};

/* clauses reference tables by schema id (table1..table23); map those to the
   data loop-variable keys used above. Ids without a row editor (scalar/static
   tables: table1,2,5,13,20,21,23) are intentionally absent. */
const TABLE_ID_MAP = {
  table3:'establishment', table4:'devices', table6:'deposit_articles', table7:'bo_balance_checks',
  table8:'mo_receipts', table9:'vpcod_checks', table10:'sb26_receipts', table11:'passbooks_checked',
  table12:'sb28_receipts', table14:'ssa_collected', table15:'sb_collected', table16:'txn_totals',
  table17:'discontinued_accounts', table18:'pli_collection', table19:'pli_passbooks', table22:'ippb_txns'
};
/* resolve a clause's table ref (schema id OR loopVar) to a TABLES key, or null */
const tableKeyFor=ref=>{ if(!ref)return null; if(TABLES[ref])return ref; const k=TABLE_ID_MAP[ref]; return (k&&TABLES[k])?k:null; };

/* scalar field groups for the Header / basic-details view */
const FIELD_GROUPS = [
  { title:'Office identification', fields:[
    ['office_name','Office name'],['office_type','Office type'],['account_office','Account office'],['account_office_pin','A/O PIN'],
    ['head_office','Head office'],['facility_id','Facility ID'],['apt_office_id','APT Office ID'],['date_of_opening','Date of opening',DATE],
    ['panchayat_hq','Panchayat HQ'],['sub_division','Sub Division'],['division','Division'],['division_pin','Division office + PIN']]},
  { title:'Inspection details', fields:[
    ['inspection_date','Inspection date',DATE],['inspection_year','Inspection year'],['visit_kind','Visit kind'],['inspection_nature','Inspection nature'],
    ['last_inspection_date','Last inspection date',DATE],['last_inspected_by','Last inspected by'],['div_head_last_visit','Divisional head last visit'],
    ['subdiv_head_visits','Sub-div head visits'],['mail_overseer_visits','Mail overseer visits',AREA]]},
  { title:'Working hours & authorised balances', fields:[
    ['working_hours','Working hours'],['wh_receipt','Receipt time'],['wh_delivery','Delivery time'],['wh_lb_clearance','LB clearance'],['wh_dispatch','Dispatch'],
    ['auth_min_cash','Min cash',AMT],['auth_max_cash','Max cash',AMT],['postage_stamp_balance','Postage stamps',AMT],['revenue_stamp_balance','Revenue stamps',AMT],
    ['cash_verified_amount','Physical cash verified',AMT]]},
  { title:'Location & service narrative', fields:[
    ['location_type','Location type'],['location_remark','Location remark'],['building_condition','Building condition',AREA],['cleanliness_remark','Cleanliness',AREA],
    ['villages_served','Villages served',AREA],['fixed_villages','Fixed villages',AREA],['unfixed_villages','Unfixed villages',AREA],['households_remark','Households',AREA],['services_remark','Services covered',AREA],['mail_arrangement','Mail arrangement',AREA],
    ['fg_bonds_remark','FG bonds/security',AREA],['darpan_rollout_date','DARPAN 2.0 rollout',DATE]]},
  { title:'Device user', fields:[['user_name','User name'],['user_id','User ID'],['user_role','User role']]},
  { title:'Table notes', fields:[['sb26_note','SB-26 note',AREA],['sb28_note','SB-28 note',AREA]]},
  { title:'Memo, conclusion & signature', fields:[
    ['conclusion_result','Overall result'],['compliance_days','Compliance days'],['memo_number','Memo number'],['memo_place','Memo place'],['memo_date','Memo date',DATE],
    ['signatory_name','Signatory name'],['signatory_designation','Signatory designation']]}
];

const SECTION_NAMES = {A:'Administration',B:'Technology & DARPAN',C:'Mails',D:'Finance & Accounting',E:'Savings Bank',
  F:'PLI / RPLI',G:'Grievance & PR',H:'Business Development',I:'Training',J:'IPPB',K:'Conclusion'};
const SECTION_ORDER = ['A','B','C','D','E','F','G','H','I','J','K'];
/* clauses wired to deterministic DOCX generation */
const WIRED = new Set();

/* ---------- helpers ---------- */
const el = (t,a={},...kids)=>{const e=document.createElement(t);for(const k in a){if(k==='class')e.className=a[k];else if(k==='html')e.innerHTML=a[k];else if(k.startsWith('on'))e.addEventListener(k.slice(2),a[k]);else if(a[k]!=null)e.setAttribute(k,a[k]);}for(const c of kids.flat()){if(c==null)continue;e.append(c.nodeType?c:document.createTextNode(c));}return e;};
const $=(s,r=document)=>r.querySelector(s);
function toast(msg,err){const t=$('#toast');t.textContent=msg;t.className='toast show'+(err?' err':'');setTimeout(()=>t.className='toast',2200);}
function clauseById(id){return (S.clauses.clauses||[]).find(c=>c.id===id);}
function sel(id){S.report.clauses=S.report.clauses||{};return S.report.clauses[id]||null;}

/* amount in words (Indian) */
function amountWords(n){n=parseInt(String(n).replace(/[^0-9]/g,''),10);if(isNaN(n))return'';if(n===0)return'Rupees Zero only';
  const o=['','One','Two','Three','Four','Five','Six','Seven','Eight','Nine','Ten','Eleven','Twelve','Thirteen','Fourteen','Fifteen','Sixteen','Seventeen','Eighteen','Nineteen'];
  const t=['','','Twenty','Thirty','Forty','Fifty','Sixty','Seventy','Eighty','Ninety'];
  const two=x=>x<20?o[x]:t[Math.floor(x/10)]+(x%10?' '+o[x%10]:'');
  const three=x=>{let h=Math.floor(x/100),r=x%100,s='';if(h)s=o[h]+' Hundred'+(r?' ':'');if(r)s+=two(r);return s;};
  let p=[],cr=Math.floor(n/1e7);n%=1e7;let l=Math.floor(n/1e5);n%=1e5;let th=Math.floor(n/1e3);n%=1e3;
  if(cr)p.push(two(cr)+' Crore');if(l)p.push(two(l)+' Lakh');if(th)p.push(two(th)+' Thousand');if(n)p.push(three(n));
  return'Rupees '+p.join(' ')+' only';}

/* deterministic clause render (mirrors engine Render-Clause) */
function renderClause(id){
  const c=clauseById(id);if(!c)return'';const s=sel(id)||{};const outcome=s.outcome||'satisfactory';
  let tmpl=(c.text&&c.text[outcome])||(S.clauses.outcomeTemplates&&S.clauses.outcomeTemplates[outcome])||(c.text&&c.text.satisfactory)||'';
  const loc={clause_title:c.title||'',reference:c.reference||'',observation:s.observation||'',instruction:s.instruction||'',custom_text:s.custom_text||''};
  let out=tmpl.replace(/\{\{\s*(clause_title|observation|instruction|reference|custom_text)\s*\}\}/g,(m,k)=>loc[k]||'');
  out=out.replace(/\{\{\s*([a-z0-9_]+)\s*\}\}/gi,(m,k)=>S.report[k]!=null?S.report[k]:m);
  return out.replace(/\s+/g,' ').trim();
}

/* ---------- autosave ---------- */
let saveTimer=null;
function markDirty(){S.dirty=true;clearTimeout(saveTimer);saveTimer=setTimeout(saveDraft,900);renderStatus();updateSavebar();}
async function saveDraft(){
  if(!S.activeDraftId)return;
  try{
    const r=await fetch('/api/draft?id='+encodeURIComponent(S.activeDraftId),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(S.report)});
    const j=await r.json();if(!j||!j.ok)return;
    S.lastSaved=j.savedAt;S.dirty=false;
    const idx=S.drafts.findIndex(d=>d.id===S.activeDraftId);
    if(idx>=0){S.drafts[idx].content=S.report;S.drafts[idx].updatedAt=j.savedAt;}
    renderStatus();renderNav();updateSavebar();
  }catch(e){}
}
function renderStatus(){const s=$('#savestat');if(s)s.textContent=S.dirty?'Saving…':(S.lastSaved?('Saved '+new Date(S.lastSaved).toLocaleTimeString()):'Draft');}

/* ---------- bootstrap ---------- */
async function boot(){
  const r=await fetch('/api/bootstrap');const b=await r.json();
  S.boot=b;S.settings=b.settings;S.clauses=b.clauses;S.offices=b.offices||[];S.staff=b.staff||[];S.villages=b.villages||{};S.reports=b.reports||[];
  try { await SB.init(); if(SB.ready) { /* check if cloud has newer data */ } } catch(e) { console.warn('Cloud sync check failed:', e); }
  S.drafts=b.drafts||[];S.activeDraftId=b.activeDraftId||(S.drafts[0]&&S.drafts[0].id)||null;
  const activeRec=S.drafts.find(d=>d.id===S.activeDraftId);
  S.report=b.draft||(activeRec&&activeRec.content)||{};S.report.clauses=S.report.clauses||{};
  S.lastSaved=(activeRec&&activeRec.updatedAt)||null;
  (b.clauses.clauses||[]).forEach(c=>{ if(/^B|^F49|^J58/.test(c.id)) WIRED.add(c.id); });
  // single-role app: always Inspector. Deep links still honour ?view= and ?section=.
  S.role='Inspector';
  const q=new URLSearchParams(location.search);
  if(q.get('view'))S.view=q.get('view');
  if(q.get('section'))S.section=q.get('section');
  renderApp();
}

/* ---------- shell ---------- */
function renderApp(){
  const app=$('#app');app.innerHTML='';
  app.append(
    el('div',{class:'topbar'},
      el('div',{class:'brand'},el('span',{class:'crest'},'IR'),S.settings.appName),
      el('div',{class:'org'},S.settings.organisation),
      el('div',{class:'appsw'},
        el('a',{class:'appsw-pill',href:'/',style:'font-weight:700'},'🏠 Home'),
        el('a',{class:'appsw-pill',href:'/leave-orders'},'Sub Divisional Manager'),
        el('a',{class:'appsw-pill',href:'/employees'},'Employee Directory'),
        el('a',{class:'appsw-pill',href:'/office-management'},'Office Management'),
        el('a',{class:'appsw-pill',href:'/arrangements'},'Temporary Arrangements'),
        el('span',{class:'appsw-pill active'},'Inspection Reports')),
      el('div',{class:'spacer'}),
      el('div',{class:'statusline',id:'savestat'},'Draft')
    )
  );
  const nav=el('div',{class:'sidenav',id:'nav'});
  const main=el('div',{class:'main',id:'main'});
  app.append(el('div',{class:'shell'},nav,main));
  app.append(el('footer',{class:'app-footer'},'Built by Arun Selvaraj, Karur Division'));
  renderNav();renderMain();renderStatus();
}

function navItem(view,label,extra){return el('div',{class:'navitem'+(S.view===view?' active':''),onclick:()=>{S.view=view;renderNav();renderMain();}},label,extra||null);}

function renderNav(){
  const nav=$('#nav');nav.innerHTML='';
  nav.append(el('div',{class:'grp'},'Workspace'));
  nav.append(navItem('dashboard','Dashboard'));
  nav.append(navItem('drafts','Drafts',el('span',{class:'pct'},String(S.drafts.length))));
  nav.append(navItem('offices','Office Master'));
  nav.append(navItem('staff','Staff Master'));
  nav.append(el('div',{class:'grp'},'Current Inspection'));
  nav.append(navItem('inspection','Inspection workspace'));
  nav.append(navItem('templates','Templates',el('span',{class:'pct'},String(Object.keys(TABLES).length))));
  nav.append(navItem('deficiencies','Deficiencies',defBadge()));
  nav.append(navItem('validation','Validation',valBadge()));
  nav.append(navItem('preview','Preview & Export'));
}
function defBadge(){const n=countDeficiencies();return n?el('span',{class:'pct'},String(n)):null;}
function valBadge(){const v=validate();const c=v.filter(x=>x.level==='critical').length;return el('span',{class:'badge '+(c?'err':'ok'),style:'margin-left:auto'},c?String(c):'OK');}

function renderMain(){
  const m=$('#main');m.innerHTML='';
  ({dashboard:viewDashboard,drafts:viewDrafts,offices:viewOffices,staff:viewStaff,inspection:viewInspection,
    templates:viewTemplates,deficiencies:viewDeficiencies,validation:viewValidation,preview:viewPreview}[S.view]||viewDashboard)(m);
}

/* ---------- dashboard ---------- */
function viewDashboard(m){
  m.append(el('h1',{class:'page-title'},'Dashboard'),el('p',{class:'page-sub'},'Overview of inspection reports and compliance.'));
  const defs=countDeficiencies();
  const cards=[['Current draft',S.report.office_name?(S.report.office_name+' '+S.report.office_type):'—'],
    ['Drafts in progress',S.drafts.length],['Active draft completion',draftCompletion(S.report)+'%'],
    ['Open deficiencies',defs],['Generated reports',S.reports.length],
    ['Offices on file',S.offices.length],['Staff on file',S.staff.length],['Clause library','v'+S.clauses.libraryVersion]];
  const grid=el('div',{class:'cards'});
  cards.forEach(c=>{
    const val=String(c[1]);
    // numeric / short values (digits, percent, "v1.0.0", "—") get the larger .num style
    const isShort=/^[0-9—–v.%/\s]+$/i.test(val)&&val.length<=10;
    grid.append(el('div',{class:'card accent'},
      el('div',{class:'k'},c[0]),
      el('div',{class:'v'+(isShort?' num':'')},val)));
  });
  m.append(grid);
  m.append(el('div',{class:'toolbar'},el('button',{class:'btn primary',onclick:()=>{S.view='inspection';renderNav();renderMain();}},'➕ New / Continue inspection')));
  const p=el('div',{class:'panel'},el('h3',{},'Recently generated reports'));
  const body=el('div',{class:'body'});
  if(!S.reports.length)body.append(el('div',{class:'note'},'No reports generated yet. Open the inspection workspace and use Preview & Export.'));
  else{const tb=el('table',{class:'grid'});tb.append(el('tr',{},el('th',{},'Report'),el('th',{},'Template'),el('th',{},'Pages'),el('th',{},'Generated'),el('th',{},'Files')));
    S.reports.forEach(r=>tb.append(el('tr',{},el('td',{},r.reportBase),el('td',{},(r.template||'')+' '),el('td',{},String(r.pages||'')),el('td',{},r.generatedAt||''),
      el('td',{},el('a',{href:'/download/'+r.docx},'DOCX'),' · ',el('a',{href:'/download/'+r.pdf,target:'_blank'},'PDF')))));
    body.append(el('div',{class:'tbl-wrap'},tb));}
  p.append(body);m.append(p);
}

/* ---------- drafts ---------- */
function draftCompletion(c){
  if(!c)return 0;
  const req=['office_name','office_type','account_office','inspection_date','memo_number','signatory_name'];
  const fh=req.filter(k=>c[k]&&String(c[k]).trim()).length;
  const all=(S.clauses&&S.clauses.clauses)||[];
  const fc=all.filter(cl=>clauseDone(cl,c)).length;
  const total=req.length+all.length;
  if(!total)return 0;
  return Math.round(((fh+fc)/total)*100);
}
function draftLabel(c){const n=((c&&c.office_name)||'').trim();const t=((c&&c.office_type)||'').trim();return n?(n+(t?' '+t:'')):'(Untitled draft)';}
function fmtTime(s){if(!s)return'—';try{return new Date(s).toLocaleString();}catch(e){return s;}}
function progressBar(pct){
  return el('div',{class:'prog'},
    el('div',{class:'prog-bar'},el('div',{class:'prog-fill',style:'width:'+pct+'%'})),
    el('div',{class:'prog-label'},pct+'% complete'));
}
function viewDrafts(m){
  m.append(el('h1',{class:'page-title'},'Drafts'),
    el('p',{class:'page-sub'},'All inspection reports you are currently working on. The active draft is what opens in the Inspection workspace.'));
  m.append(el('div',{class:'toolbar'},
    el('button',{class:'btn primary',onclick:newDraft},'➕ New draft'),
    el('button',{class:'btn',onclick:()=>{if(S.dirty){saveDraft().then(()=>toast('Saved'));}else toast('Already saved');}},'💾 Save active draft'),
    el('span',{class:'spacer'}),
    el('span',{class:'statusline',style:'color:var(--muted)'},S.drafts.length+' draft(s) on disk')));
  if(!S.drafts.length){m.append(el('div',{class:'note'},'No drafts yet. Click “New draft” to begin a fresh inspection.'));return;}
  const grid=el('div',{class:'cards drafts-grid'});
  S.drafts.forEach(d=>grid.append(draftCard(d)));
  m.append(grid);
}
function draftCard(d){
  const c=d.content||{};
  const pct=draftCompletion(c);
  const isActive=d.id===S.activeDraftId;
  const insp=(c.inspection_date||'').trim();
  const def=Object.values(c.clauses||{}).filter(s=>s&&s.outcome==='deficiency').length;
  const card=el('div',{class:'card draft-card'+(isActive?' active':'')});
  card.append(
    el('div',{class:'k'},isActive?'★ Active draft':'Draft'),
    el('div',{class:'draft-name'},draftLabel(c)),
    el('div',{class:'draft-meta'},
      insp?('Inspection: '+insp):'No inspection date set',
      el('br'),
      'Last saved: '+fmtTime(d.updatedAt),
      def?el('span',{class:'badge err',style:'margin-left:8px'},def+(def===1?' deficiency':' deficiencies')):null),
    progressBar(pct),
    el('div',{class:'draft-actions'},
      el('button',{class:'btn primary sm',onclick:()=>openDraft(d.id)},isActive?'Reopen ▸':'Open ▸'),
      el('button',{class:'btn sm danger',onclick:()=>deleteDraft(d.id)},'Delete')));
  return card;
}
/* common boilerplate that's the same across most inspections — applied on
   New draft creation. Only fills blanks; never overwrites edits. */
const DRAFT_DEFAULTS={
  cleanliness_remark:'Pay in slips, Forms are not arranged properly. BPM instructed to arrange properly and improve its ambience.',
  services_remark:'Covered maximum public in the villages in most of the schemes.',
  households_remark:'Approximately 1000 HHs, BPM did not having the exact data, he/she was instructed to get the list from the Panchayat authorities.',
  fg_bonds_remark:'No need as per Dte.Lr no. 17-18/2018-GDS dated 17.11.2021',
  darpan_rollout_date:'03.10.2023',
  conclusion_result:'SATISFACTORY',
  compliance_days:'10 days',
  memo_number:'ASP/IR'
};
function applyDraftDefaults(c){
  for(const k in DRAFT_DEFAULTS){
    if(!c[k]||!String(c[k]).trim())c[k]=DRAFT_DEFAULTS[k];
  }
  return c;
}
async function newDraft(){
  if(S.dirty)await saveDraft();
  try{
    const r=await fetch('/api/draft/new',{method:'POST'});
    const j=await r.json();
    if(!j||!j.ok){toast('Could not create draft',true);return;}
    applyDraftDefaults(j.content);
    S.drafts.unshift({id:j.id,updatedAt:j.updatedAt,content:j.content});
    S.activeDraftId=j.id;
    S.report=j.content;S.report.clauses=S.report.clauses||{};
    S.view='inspection';S.section='header';S.lastSaved=j.updatedAt;
    // persist the defaults to disk so they survive a reload
    S.dirty=true;await saveDraft();
    renderNav();renderMain();renderStatus();
    toast('New draft created with common defaults pre-filled');
  }catch(e){toast('Error: '+e.message,true);}
}
async function openDraft(id){
  if(id===S.activeDraftId){S.view='inspection';renderNav();renderMain();return;}
  if(S.dirty)await saveDraft();
  const d=S.drafts.find(x=>x.id===id);if(!d)return;
  S.activeDraftId=id;
  S.report=d.content||{};S.report.clauses=S.report.clauses||{};
  S.lastSaved=d.updatedAt;S.dirty=false;
  try{await fetch('/api/draft/activate?id='+encodeURIComponent(id),{method:'POST'});}catch(e){}
  S.view='inspection';S.section='header';
  renderNav();renderMain();renderStatus();
  toast('Opened: '+draftLabel(d.content));
}
async function deleteDraft(id){
  const d=S.drafts.find(x=>x.id===id);if(!d)return;
  if(!confirm('Delete draft “'+draftLabel(d.content)+'”? This cannot be undone.'))return;
  try{
    const r=await fetch('/api/draft/delete?id='+encodeURIComponent(id),{method:'POST'});
    const j=await r.json();if(!j||!j.ok){toast('Delete failed',true);return;}
    S.drafts=S.drafts.filter(x=>x.id!==id);
    if(id===S.activeDraftId){
      if(S.drafts.length){
        const nx=S.drafts[0];
        S.activeDraftId=nx.id;S.report=nx.content||{};S.report.clauses=S.report.clauses||{};
        S.lastSaved=nx.updatedAt;
        try{await fetch('/api/draft/activate?id='+encodeURIComponent(nx.id),{method:'POST'});}catch(e){}
      }else{
        S.activeDraftId=null;S.report={clauses:{}};S.lastSaved=null;
      }
    }
    renderNav();renderMain();renderStatus();
    toast('Draft deleted');
  }catch(e){toast('Error: '+e.message,true);}
}

/* ---------- office / staff master ---------- */
let _officeSelectedKey=null;
let _officesDirty=false;
const OFFICE_ID_FIELDS=['office_type','facility_id','apt_office_id','ao_apt_office_id','account_office','account_office_pin','head_office','sub_division','division','region','panchayat_hq','panchayat_hq_note','taluk','district','state','sol_id','pli_facility_id','delivery_kind','address','mobile'];
const OFFICE_WH_FIELDS=[
  ['working_hours','Working hours','e.g. 10.00 AM - 02.00 PM'],
  ['wh_receipt','Receipt of mails','HH.MM AM/PM'],
  ['wh_delivery','Delivery','HH.MM AM/PM'],
  ['wh_lb_clearance','Last clearance','HH.MM AM/PM'],
  ['wh_dispatch','Last dispatch','HH.MM AM/PM']
];
function officeLabel(o){return (o.office_name||'')+(o.office_type?' '+o.office_type:'');}
function findOffice(label){
  if(!label)return null;
  const v=String(label).trim();const vu=v.toUpperCase();
  const all=S.offices||[];
  return all.find(o=>officeLabel(o).toUpperCase()===vu)
      || all.find(o=>(o.office_name||'').toUpperCase()===vu)
      || all.find(o=>(o.office_name||'').toUpperCase()===vu.replace(/\s+(BO|SO|HO)$/,''));
}
function viewOffices(m){
  m.append(el('h1',{class:'page-title'},'Office Master'),
    el('p',{class:'page-sub'},'Search for an office to view its details. Working-hours fields are editable; identification fields come from the Office Master and are read-only here.'));
  // datalist + search
  const dl=el('datalist',{id:'omList'});
  (S.offices||[]).slice().sort((a,b)=>(a.office_name||'').localeCompare(b.office_name||'')).forEach(o=>dl.append(el('option',{value:officeLabel(o)})));
  const inp=el('input',{type:'text',list:'omList',id:'omSearch',placeholder:'Type or pick an office (e.g. MANAVASI BO)',autocomplete:'off',style:'flex:1;min-width:320px;padding:10px 13px;border:1.5px solid var(--line-2);border-radius:9px;font-size:14px'});
  inp.value=_officeSelectedKey||'';
  const trySelect=()=>{const o=findOffice(inp.value);if(o){_officeSelectedKey=officeLabel(o);_officesDirty=false;renderOfficeDetail();}};
  inp.addEventListener('change',trySelect);
  inp.addEventListener('input',()=>{const o=findOffice(inp.value);if(o)trySelect();});
  const clear=el('button',{class:'btn',onclick:()=>{inp.value='';_officeSelectedKey=null;_officesDirty=false;renderOfficeDetail();}},'Clear');
  m.append(dl,el('div',{class:'toolbar'},
    inp,clear,
    el('span',{class:'spacer'}),
    el('span',{class:'statusline',style:'color:var(--muted)'},(S.offices||[]).length+' offices in master')));
  m.append(el('div',{id:'officeDetail'}));
  renderOfficeDetail();
}
function renderOfficeDetail(){
  const host=$('#officeDetail');if(!host)return;
  host.innerHTML='';
  if(!_officeSelectedKey){host.append(el('div',{class:'note'},'Start typing an office name above. The dropdown shows all '+(S.offices||[]).length+' offices.'));return;}
  const o=findOffice(_officeSelectedKey);
  if(!o){host.append(el('div',{class:'note'},'No office matches “'+_officeSelectedKey+'”.'));return;}
  // identification (read-only)
  const idP=el('div',{class:'panel'},el('h3',{},(o.office_name||'')+' '+(o.office_type||''),
    el('span',{class:'spacer',style:'flex:1'}),
    el('button',{class:'btn sm primary',onclick:()=>prefillOffice(o)},'Use for current inspection')));
  const idGrid=el('div',{class:'formgrid'});
  OFFICE_ID_FIELDS.forEach(k=>{
    if(o[k]==null||String(o[k]).trim()==='')return;
    idGrid.append(el('div',{class:'field'},el('label',{},k.replace(/_/g,' ')),el('input',{value:o[k],disabled:'true'})));
  });
  idP.append(el('div',{class:'body'},idGrid));
  host.append(idP);
  // working hours + mail arrangement (editable)
  const whP=el('div',{class:'panel'},el('h3',{},'Working hours & mail arrangement · editable'));
  const whGrid=el('div',{class:'formgrid'});
  OFFICE_WH_FIELDS.forEach(([k,label,ph])=>{
    const fld=el('div',{class:'field'},el('label',{},label));
    const w=el('input',{type:'text',value:o[k]||'',placeholder:ph});
    w.addEventListener('input',()=>{o[k]=w.value;_officesDirty=true;refreshOfficeSave();});
    fld.append(w);whGrid.append(fld);
  });
  // mail arrangement (full-width textarea — prose)
  const maFld=el('div',{class:'field',style:'grid-column:1/-1'},
    el('label',{},'Mail arrangement'));
  const maTa=el('textarea',{placeholder:'e.g. BO Bag is conveyed through ABPM of the BO to be exchanged at AO',style:'min-height:80px'});
  maTa.value=o.mail_arrangement||'';
  maTa.addEventListener('input',()=>{o.mail_arrangement=maTa.value;_officesDirty=true;refreshOfficeSave();});
  maFld.append(maTa);whGrid.append(maFld);
  const bar=el('div',{class:'toolbar',style:'margin-top:14px'},
    el('button',{class:'btn primary',id:'officeSaveBtn',disabled:_officesDirty?null:'true',onclick:saveOfficesNow},'💾 Save changes'),
    el('span',{class:'statusline',id:'officeSaveStatus'},_officesDirty?'● Unsaved changes':'✓ Saved'));
  whP.append(el('div',{class:'body'},whGrid,bar));
  host.append(whP);
}
function refreshOfficeSave(){
  const b=$('#officeSaveBtn');if(b)b.disabled=!_officesDirty;
  const s=$('#officeSaveStatus');if(s)s.textContent=_officesDirty?'● Unsaved changes':'✓ Saved';
}
async function saveOfficesNow(){
  const btn=$('#officeSaveBtn');if(btn){btn.disabled=true;btn.textContent='Saving…';}
  try{
    try {
      const r=await fetch('/api/offices',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(S.offices)});
      if (r.ok) { const j=await r.json(); if(!j||!j.ok) console.warn('Local save failed'); }
    } catch(err) { console.warn('Local API unavailable'); }
    try { if(SB.ready) await SB.putDataset('OFFICES', S.offices, 'web'); } catch(e) {}
    _officesDirty=false;if(btn){btn.disabled=true;btn.textContent='💾 Save working hours';}
    refreshOfficeSave();
    toast('Working hours saved');
  }catch(e){toast('Error: '+e.message,true);if(btn){btn.disabled=false;btn.textContent='💾 Save working hours';}}
}
function prefillOffice(o){Object.keys(o).forEach(k=>S.report[k]=o[k]);autoFetchMasters(true);markDirty();toast('Prefilled '+o.office_name+' (staff & villages auto-fetched) — verify before use');S.view='inspection';S.section='header';renderNav();renderMain();}

/* ---------- master auto-fetch (staff + villages for the inspected office) ---------- */
function normOffice(s){return String(s==null?'':s).trim().toUpperCase().replace(/\s*\bB\.?\s*O\.?\s*$/,'').replace(/\s*\bS\.?\s*O\.?\s*$/,'').replace(/\s+/g,' ').trim();}
function knownOfficeNames(){const set={};Object.keys(S.villages||{}).forEach(k=>set[k]=1);(S.offices||[]).forEach(o=>{if(o.office_name)set[normOffice(o.office_name)]=1;});(S.staff||[]).forEach(s=>{if(s.office)set[normOffice(s.office)]=1;});return Object.keys(set).sort();}
/* pull Establishment rows from the staff master and fixed/unfixed villages from the
   village master, keyed by the inspected office name. Returns true if anything matched. */
/* fields populated from offices.json — header & basics identification */
const OFFICE_FIELDS=['office_type','account_office','account_office_pin','head_office','facility_id',
  'apt_office_id','ao_apt_office_id','profit_centre_id','date_of_opening','panchayat_hq','panchayat_hq_note','sub_division','division','division_pin',
  'working_hours','wh_receipt','wh_delivery','wh_lb_clearance','wh_dispatch',
  'auth_min_cash','auth_max_cash','postage_stamp_balance','revenue_stamp_balance',
  'location_type','location_remark','building_condition','mail_arrangement','darpan_rollout_date',
  'taluk','district','state','region','pli_facility_id','sol_id','delivery_kind','mobile','address'];
function autoFetchMasters(silent){
  const key=normOffice(S.report.office_name);
  if(!key){if(!silent)toast('Enter the office name first',true);return false;}
  const parts=[];
  // 1) office identification from offices.json (Office Master)
  const office=(S.offices||[]).find(o=>normOffice(o.office_name)===key);
  if(office){
    let n=0;
    OFFICE_FIELDS.forEach(k=>{const v=office[k];if(v!=null&&String(v).trim()!==''){S.report[k]=String(v);n++;}});
    if(office.office_name)S.report.office_name=office.office_name;
    if(office.address&&!S.report.location_remark)S.report.location_remark=office.address;
    if(n)parts.push(n+' office fields');
  }
  // 2) staff → Establishment
  const seen={};
  const matches=(S.staff||[]).filter(s=>{if(normOffice(s.office)!==key)return false;const id=(s.employee_id||s.name||'').trim();if(seen[id])return false;seen[id]=1;return true;});
  if(matches.length){
    S.report.establishment=matches.map(s=>({name:s.name,designation:s.designation,employee_id:s.employee_id,trca:s.trca,dob:s.dob,doa:s.doa,remarks:''}));
    parts.push(matches.length+' staff → Establishment');
  }
  // 3) villages (overrides account_office / pin when present — village master is authoritative for BO routing)
  const vp=(S.villages||{})[key];
  if(vp){
    S.report.fixed_villages=(vp.fixed||[]).join(', ');
    S.report.unfixed_villages=(vp.unfixed||[]).join(', ');
    if(vp.account_office)S.report.account_office=vp.account_office;
    if(vp.account_office_pin)S.report.account_office_pin=vp.account_office_pin;
    parts.push((vp.fixed||[]).length+' fixed / '+(vp.unfixed||[]).length+' unfixed villages');
  }
  if(!parts.length){if(!silent)toast('No master data found for “'+key+'”. Check the office name spelling.',true);return false;}
  markDirty();
  if(!silent)toast('Auto-fetched: '+parts.join(' · '));
  return true;
}
let _staffDirty=false;
let _staffFilter='';
const STAFF_COLS=[['name','Name','text'],['designation','Designation','text'],['employee_id','Employee ID','mono'],['trca','Level','text'],['dob','DOB','text'],['doa','DOA','text'],['office','Office','text'],['status','Status','text']];
function viewStaff(m){
  m.append(el('h1',{class:'page-title'},'Staff Master'),
    el('p',{class:'page-sub'},'Add, edit, delete or download staff records. Edits are kept in memory until you click “Save changes”.'));
  const filt=el('input',{type:'text',placeholder:'Search by name, designation, ID, office…',value:_staffFilter,style:'flex:1;min-width:280px;padding:9px 12px;border:1.5px solid var(--line-2);border-radius:9px;font-size:13.5px'});
  filt.addEventListener('input',()=>{_staffFilter=filt.value;renderStaffRows();});
  m.append(el('div',{class:'toolbar'},
    filt,
    el('span',{class:'spacer'}),
    el('button',{class:'btn',onclick:downloadStaffCsv},'⬇ CSV'),
    el('button',{class:'btn',onclick:downloadStaffXlsx},'⬇ Excel'),
    el('button',{class:'btn',onclick:addStaff},'➕ Add staff'),
    el('button',{class:'btn primary',id:'staffSaveBtn',disabled:_staffDirty?null:'true',onclick:saveStaffNow},'💾 Save changes')));
  m.append(el('div',{class:'panel',style:'margin:0'},
    el('div',{id:'staffSummary',style:'padding:8px 14px;border-bottom:1px solid var(--line);font-size:12px;color:var(--muted)'}),
    el('div',{id:'staffRowsWrap',class:'tbl-wrap',style:'margin:0;max-height:62vh'})));
  renderStaffRows();
}
function renderStaffRows(){
  const host=$('#staffRowsWrap');if(!host)return;
  const sumEl=$('#staffSummary');
  host.innerHTML='';
  const q=_staffFilter.trim().toLowerCase();
  const filtered=q?S.staff.filter(s=>STAFF_COLS.some(([k])=>(String(s[k]||'')).toLowerCase().includes(q))):S.staff;
  if(sumEl)sumEl.textContent=(q?filtered.length+' of '+S.staff.length+' match “'+_staffFilter+'”':'Showing all '+S.staff.length+' staff')+(_staffDirty?'  ·  Unsaved edits':'');
  const tb=el('table',{class:'grid'});
  const hr=el('tr',{},el('th',{},'#'));STAFF_COLS.forEach(([,label])=>hr.append(el('th',{},label)));hr.append(el('th',{},'Actions'));tb.append(hr);
  if(!filtered.length){
    host.append(tb,el('div',{class:'note',style:'margin:12px'},q?'No staff match this search.':'No staff records yet. Click “➕ Add staff” to create one.'));
    refreshStaffSave();return;
  }
  filtered.forEach((s,i)=>{
    const realIdx=S.staff.indexOf(s);
    const tr=el('tr',{});
    tr.append(el('td',{class:'sn'},String(i+1)));
    STAFF_COLS.forEach(([k,_,kind])=>{
      const td=el('td',{class:kind==='mono'?'mono':''});
      const inp=el('input',{type:'text',value:s[k]||''});
      inp.addEventListener('input',()=>{s[k]=inp.value;_staffDirty=true;refreshStaffSave();if(sumEl&&!sumEl.textContent.includes('Unsaved'))sumEl.textContent+='  ·  Unsaved edits';});
      td.append(inp);tr.append(td);
    });
    tr.append(el('td',{class:'act'},
      el('button',{class:'btn sm',title:'Insert into the active inspection\'s Establishment table',onclick:()=>insertStaff(s)},'➕ Est'),
      el('button',{class:'iconbtn',title:'Delete this row',onclick:()=>{if(confirm('Delete staff “'+(s.name||'(unnamed)')+'”? Unsaved until you click Save changes.')){S.staff.splice(realIdx,1);_staffDirty=true;renderStaffRows();}}},'✕')));
    tb.append(tr);
  });
  host.append(tb);
  refreshStaffSave();
}
function refreshStaffSave(){const b=$('#staffSaveBtn');if(b)b.disabled=!_staffDirty;}
function addStaff(){
  S.staff.unshift({name:'',designation:'',employee_id:'',trca:'',dob:'',doa:'',office:'',status:'Active'});
  _staffDirty=true;_staffFilter='';renderStaffRows();
  setTimeout(()=>{const first=document.querySelector('#staffRowsWrap tr:nth-child(2) input');if(first)first.focus();},0);
  toast('New staff row added (top) — fill it in and Save');
}
async function saveStaffNow(){
  const btn=$('#staffSaveBtn');if(btn){btn.disabled=true;btn.textContent='Saving…';}
  try{
    try {
      const r=await fetch('/api/staff',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(S.staff)});
      if(r.ok) { const j=await r.json(); if(!j||!j.ok) console.warn('Local save failed', j); }
    } catch(err) { console.warn('Local API unavailable', err); }
    try { if(SB.ready) await SB.putDataset('DS', S.staff, 'web'); } catch(e) {}
    _staffDirty=false;if(btn){btn.disabled=true;btn.textContent='💾 Save changes';}
    renderStaffRows();toast('Staff master saved');
  }catch(e){toast('Error: '+e.message,true);if(btn){btn.disabled=false;btn.textContent='💾 Save changes';}}
}
const STAFF_EXPORT_COLS=['name','designation','employee_id','trca','dob','doa','office','status','gender','post_desc','level'];
const STAFF_EXPORT_LABELS=['Name','Designation','Employee ID','Level','DOB','DOA','Office','Status','Gender','Post desc','Raw level'];
function downloadStaffCsv(){
  const esc=v=>'"'+String(v==null?'':v).replace(/"/g,'""')+'"';
  const csv=STAFF_EXPORT_LABELS.join(',')+'\n'+S.staff.map(r=>STAFF_EXPORT_COLS.map(c=>esc(r[c])).join(',')).join('\n');
  const a=el('a',{href:'data:text/csv;charset=utf-8,'+encodeURIComponent(csv),download:'staff_master.csv'});document.body.append(a);a.click();a.remove();
  toast('CSV downloaded ('+S.staff.length+' rows)');
}
function downloadStaffXlsx(){
  if(!window.XLSXLite){toast('Excel module not loaded',true);return;}
  const rows=S.staff.map(r=>STAFF_EXPORT_COLS.map(c=>r[c]||''));
  const bytes=window.XLSXLite.build(STAFF_EXPORT_LABELS,rows);
  const blob=new Blob([bytes],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'});
  const url=URL.createObjectURL(blob);
  const a=el('a',{href:url,download:'staff_master.xlsx'});document.body.append(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1500);
  toast('Excel downloaded ('+S.staff.length+' rows)');
}
function insertStaff(s){S.report.establishment=S.report.establishment||[];S.report.establishment.push({name:s.name,designation:s.designation,employee_id:s.employee_id,trca:s.trca,dob:s.dob,doa:s.doa,remarks:'-'});markDirty();toast('Added '+s.name+' to Establishment');}

/* ---------- inspection workspace (section navigator) ---------- */
function viewInspection(m){
  const wrap=el('div',{style:'display:flex;gap:18px;align-items:flex-start'});
  const siden=el('div',{style:'width:210px;flex-shrink:0'});
  const sub=el('div',{class:'panel'},el('h3',{},'Sections'));
  const list=el('div',{});
  const addS=(key,label)=>{const c=completion(key);list.append(el('div',{class:'navitem'+(S.section===key?' active':''),onclick:()=>{S.section=key;viewInspectionBody($('#insBody'));setActiveSec();}},label,el('span',{class:'pct'},c)));};
  addS('header','Header & basics');
  SECTION_ORDER.forEach(s=>addS(s,s+'. '+SECTION_NAMES[s]));
  sub.append(list);siden.append(sub);
  const body=el('div',{id:'insBody',style:'flex:1;min-width:0'});
  wrap.append(siden,body);
  m.append(el('h1',{class:'page-title'},'Inspection workspace'),
    el('p',{class:'page-sub'},'Office: '+(S.report.office_name||'—')+' '+(S.report.office_type||'')+' · Inspection date '+(S.report.inspection_date||'—')),
    workspaceSaveBar(),
    wrap);
  viewInspectionBody(body);
}
function workspaceSaveBar(){
  const bar=el('div',{class:'savebar'});
  const left=el('div',{class:'savebar-left'},
    el('span',{class:'savebar-label'},'Draft:'),
    el('span',{class:'savebar-name'},draftLabel(S.report)),
    el('span',{class:'savebar-pct'},draftCompletion(S.report)+'% complete'));
  const status=el('span',{class:'savebar-status',id:'savebarStatus'});
  const saveBtn=el('button',{class:'btn primary',id:'savebarBtn',onclick:async()=>{
    const btn=$('#savebarBtn');if(!btn)return;
    btn.disabled=true;btn.textContent='Saving…';
    clearTimeout(saveTimer);
    await saveDraft();
    btn.disabled=false;btn.textContent='💾 Save now';
    updateSavebar();
    toast('Draft saved');
  }},'💾 Save now');
  const goDrafts=el('button',{class:'btn',onclick:()=>{S.view='drafts';renderNav();renderMain();}},'📂 All drafts');
  const right=el('div',{class:'savebar-right'},status,saveBtn,goDrafts);
  bar.append(left,right);
  setTimeout(updateSavebar,0);
  return bar;
}
function updateSavebar(){
  const s=$('#savebarStatus');if(!s)return;
  s.className='savebar-status '+(S.dirty?'is-dirty':(S.lastSaved?'is-saved':'is-new'));
  s.textContent=S.dirty?'● Unsaved changes':(S.lastSaved?('✓ Saved '+new Date(S.lastSaved).toLocaleTimeString()):'New draft — not yet saved');
  const b=$('#savebarBtn');if(b)b.disabled=!S.dirty;
  const nm=document.querySelector('.savebar-name');if(nm)nm.textContent=draftLabel(S.report);
  const pct=document.querySelector('.savebar-pct');if(pct)pct.textContent=draftCompletion(S.report)+'% complete';
}
function setActiveSec(){document.querySelectorAll('#insBody').length;renderMain.activeSync&&0;
  // re-render sections nav active state
  S.view='inspection';/* lightweight: re-render whole inspection */ const m=$('#main');m.innerHTML='';viewInspection(m);}
/* a clause counts as "done" if:
   - it has no associated table (default satisfactory is fine), OR
   - the user explicitly ticked Nil / No transaction (table === []), OR
   - the table has at least one row with any non-empty cell, OR
   - the user has explicitly set a non-default outcome.
   incomplete:
   - table is undefined (never visited), OR
   - table has only blank rows (visited but no data entered). */
function rowHasData(r){return Object.keys(r||{}).some(k=>String(r[k]||'').trim()!=='');}
function clauseDone(c, content){
  content = content || S.report;
  const s = (content.clauses||{})[c.id] || {};
  const tk = tableKeyFor(c.table);
  if (!tk) return true;
  const rows = content[tk];
  if (rows === undefined) return false;                        // never touched
  if (Array.isArray(rows) && rows.length === 0) return true;   // explicit Nil
  if (Array.isArray(rows) && rows.some(rowHasData)) return true;
  if (s.outcome && s.outcome !== 'satisfactory') return true;  // user explicitly addressed
  return false;
}
function completion(key){
  if(key==='header'){const req=['office_name','office_type','account_office','inspection_date','memo_number','signatory_name'];const f=req.filter(k=>S.report[k]&&String(S.report[k]).trim()).length;return Math.round(f/req.length*100)+'%';}
  const cs=(S.clauses.clauses||[]).filter(c=>c.section===key);if(!cs.length)return'';
  const done=cs.filter(c=>clauseDone(c)).length;return Math.round(done/cs.length*100)+'%';
}
function viewInspectionBody(host){
  host.innerHTML='';
  if(S.section==='header')return renderHeaderForm(host);
  renderSectionClauses(host,S.section);
}

/* header form */
function renderHeaderForm(host){
  // master auto-fetch bar (pull staff + villages for the inspected office)
  if(S.role!=='Read-only'){
    const known=knownOfficeNames();
    const bar=el('div',{class:'panel'});
    const body=el('div',{class:'body',style:'display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap'});
    const fld=el('div',{class:'field',style:'min-width:260px;flex:1'});
    fld.append(el('label',{},'Inspected office'));
    const inp=el('input',{type:'text',list:'officeList',placeholder:'e.g. MANAVASI',value:S.report.office_name||''});
    inp.addEventListener('input',()=>{S.report.office_name=inp.value;markDirty();});
    fld.append(inp,el('div',{class:'hint'},known.length+' offices in master data'));
    const dl=el('datalist',{id:'officeList'});known.forEach(n=>dl.append(el('option',{value:n})));
    body.append(fld,dl,
      el('button',{class:'btn primary',onclick:()=>{if(autoFetchMasters(false))renderInspectionRefresh();}},'⤓ Auto-fetch staff & villages'));
    bar.append(el('h3',{},'Master data'),el('div',{class:'note',style:'margin:0 16px'},'Pick or type the office, then auto-fill the Establishment table and Fixed/Unfixed villages from the GDS pay-bill and village-sorting masters.'),body);
    host.append(bar);
  }
  FIELD_GROUPS.forEach(g=>{
    const p=el('div',{class:'panel'},el('h3',{},g.title));
    const grid=el('div',{class:'formgrid'});
    g.fields.forEach(f=>{
      const [key,label,type]=f;const ro=S.role==='Read-only';
      const fld=el('div',{class:'field'});fld.append(el('label',{},label));
      let inp;
      if(type===AREA){inp=el('textarea',{disabled:ro?'true':null});inp.value=S.report[key]||'';}
      else{inp=el('input',{type:'text',disabled:ro?'true':null,value:S.report[key]||'',placeholder:type===DATE?'DD.MM.YYYY':''});}
      inp.addEventListener('input',()=>{S.report[key]=inp.value;markDirty();if(key==='cash_verified_amount'){wd.textContent=amountWords(inp.value);S.report.cash_verified_words=amountWords(inp.value);}if(type===DATE)validateField(fld,key,inp.value);});
      fld.append(inp);
      if(type===DATE)fld.append(el('div',{class:'hint'},'Format DD.MM.YYYY'));
      var wd;if(key==='cash_verified_amount'){wd=el('div',{class:'words'},amountWords(S.report[key]||''));fld.append(wd);}
      p.append.bind(p);grid.append(fld);
    });
    p.append(el('div',{class:'body'},grid));host.append(p);
  });
}

/* section clauses + inline tables */
function renderSectionClauses(host,section){
  const cs=(S.clauses.clauses||[]).filter(c=>c.section===section);
  host.append(el('div',{class:'note'},'Section '+section+' — '+SECTION_NAMES[section]+'. Pick an outcome per point; only required fields show. '+
    'Points marked “live” are wired to the generated document; others are recorded for the deficiency tracker and future template extension.'));
  cs.forEach(c=>host.append(clauseCard(c)));
}
function clauseCard(c){
  const s=sel(c.id)||{};const outcome=s.outcome||'satisfactory';
  const cls=outcome==='deficiency'?'def':(outcome.startsWith('satisfactory')?'sat':'dev');
  const card=el('div',{class:'clause '+cls});
  const head=el('div',{class:'head'},el('span',{class:'num'},c.para),el('span',{class:'title'},c.title),
    WIRED.has(c.id)?el('span',{class:'badge ok',style:'margin-left:8px'},'live'):null,
    el('span',{class:'o-pill o-'+outcome},outcome.replace(/_/g,' ')));
  card.append(head);
  const ro=S.role==='Read-only';
  // outcome selector
  const osel=el('select',{disabled:ro?'true':null});
  S.clauses.outcomes.forEach(o=>{const opt=el('option',{value:o},o.replace(/_/g,' '));if(o===outcome)opt.selected=true;osel.append(opt);});
  osel.addEventListener('change',()=>{setSel(c.id,'outcome',osel.value);S.section&&renderInspectionRefresh();});
  const row=el('div',{class:'formgrid'});
  row.append(el('div',{class:'field'},el('label',{},'Outcome'),osel));
  // conditional fields
  const needsObs=['deficiency','satisfactory_observation','record_not_available','verification_pending'].includes(outcome);
  const needsInstr=['deficiency','record_not_available','verification_pending'].includes(outcome);
  if(needsObs)row.append(fieldArea(c.id,'observation','Observation'+(outcome==='deficiency'?' (required)':'')));
  if(needsInstr)row.append(fieldArea(c.id,'instruction','Instruction / compliance'+(outcome==='deficiency'?' (required)':'')));
  if(outcome==='custom'){row.append(fieldArea(c.id,'custom_text','Custom approved wording (required)'));row.append(fieldArea(c.id,'custom_reason','Reason for override (required)'));}
  row.append(fieldArea(c.id,'internal_note','Internal inspector note (not printed)'));
  card.append(row);
  // associated table (resolve schema id → data loopVar key)
  const tk=tableKeyFor(c.table);if(tk)card.append(tableEditor(tk));
  // live preview
  card.append(el('div',{class:'preview-txt'},el('strong',{},c.para+'. '),renderClause(c.id)||'—'));
  if(outcome==='custom'){const a=s;card.append(el('div',{class:'hint'},'Override by '+(S.role)+' on '+(a.modified_at||new Date().toISOString().slice(0,10))));}
  return card;
}
function renderInspectionRefresh(){const m=$('#main');m.innerHTML='';viewInspection(m);renderNav();}
function fieldArea(id,prop,label){
  const s=sel(id)||{};const ro=S.role==='Read-only';
  const fld=el('div',{class:'field'});fld.append(el('label',{},label));
  const ta=el('textarea',{disabled:ro?'true':null});ta.value=s[prop]||'';
  ta.addEventListener('input',()=>{setSel(id,prop,ta.value);const pv=ta.closest('.clause').querySelector('.preview-txt');if(pv)pv.innerHTML='';if(pv){pv.append(el('strong',{},clauseById(id).para+'. '),renderClause(id)||'—');}});
  fld.append(ta);return fld;
}
function setSel(id,prop,val){S.report.clauses=S.report.clauses||{};S.report.clauses[id]=S.report.clauses[id]||{};S.report.clauses[id][prop]=val;
  if(prop==='outcome'&&val==='custom'){S.report.clauses[id].modified_by=S.role;S.report.clauses[id].modified_at=new Date().toISOString().slice(0,10);}
  markDirty();}

/* ---------- table editor ---------- */
function tableEditor(name){
  const def=TABLES[name];const ro=S.role==='Read-only';
  // default: start with one blank row so the user can type immediately;
  // ticking Nil/No transaction clears the rows explicitly.
  if(S.report[name]==null)S.report[name]=[blankRow(def)];
  const rows=S.report[name];
  const wrap=el('div',{style:'margin-top:10px'});
  const isNil=rows.length===0;
  const bar=el('div',{class:'toolbar'},
    el('strong',{style:'color:var(--navy)'},def.title),
    el('span',{class:'spacer'}),
    el('label',{class:'inline',style:'font-size:12px'},el('input',{type:'checkbox',checked:isNil?'true':null,disabled:ro?'true':null,onchange:e=>{if(e.target.checked){S.report[name]=[];}else{S.report[name]=[blankRow(def)];}markDirty();redraw();}}),'Nil / No transaction'),
    el('button',{class:'btn sm',disabled:ro?'true':null,onclick:()=>{S.report[name].push(blankRow(def));markDirty();redraw();}},'➕ Add row'),
    el('button',{class:'btn sm',title:'Download a blank Excel template with the right column headers',onclick:()=>downloadXlsxTemplate(name)},'⬇ Excel template'),
    el('button',{class:'btn sm',disabled:ro?'true':null,title:'Upload a filled Excel (.xlsx) or CSV to populate this table',onclick:()=>uploadTable(name)},'⬆ Upload Excel'),
    el('button',{class:'btn sm',onclick:()=>exportCsv(name)},'⬇ CSV')
  );
  wrap.append(bar);
  const host=el('div',{});wrap.append(host);
  function redraw(){host.innerHTML='';host.append(drawGrid(name));const cb=bar.querySelector('input[type=checkbox]');if(cb)cb.checked=(S.report[name].length===0);}
  redraw();
  return wrap;
}
function blankRow(def){const r={};def.cols.forEach(c=>r[c.k]='');return r;}
function drawGrid(name){
  const def=TABLES[name];const rows=S.report[name];const ro=S.role==='Read-only';
  if(!rows.length)return el('div',{class:'niltag'},'Nil — no records (will print “Nil” in the report).');
  // duplicate detection
  const dupVals={};if(def.key){const seen={};rows.forEach(r=>{const v=(r[def.key]||'').trim();if(!v)return;seen[v]=(seen[v]||0)+1;});Object.keys(seen).forEach(k=>{if(seen[k]>1)dupVals[k]=1;});}
  const tb=el('table',{class:'grid'});
  const hr=el('tr',{});if(def.serial)hr.append(el('th',{},'#'));def.cols.forEach(c=>hr.append(el('th',{},c.label)));hr.append(el('th',{},''));tb.append(hr);
  rows.forEach((r,i)=>{
    const tr=el('tr',{});
    if(def.serial)tr.append(el('td',{class:'sn'},String(i+1)));
    def.cols.forEach(c=>{
      const td=el('td',{});let inp;
      if(c.t===AREA){inp=el('textarea',{disabled:ro?'true':null});inp.value=r[c.k]||'';}
      else{inp=el('input',{type:'text',disabled:ro?'true':null});inp.value=r[c.k]||'';}
      if(def.key===c.k&&dupVals[(r[c.k]||'').trim()])inp.classList.add('dup');
      inp.addEventListener('input',()=>{r[c.k]=inp.value;markDirty();if(def.key===c.k)setTimeout(()=>{const g=td.closest('table');},0);});
      if(c.t===DATE)inp.addEventListener('blur',()=>{if(inp.value&&!/^\d{2}\.\d{2}\.\d{2,4}$/.test(inp.value.trim()))inp.classList.add('dup');else inp.classList.remove('dup');});
      td.append(inp);tr.append(td);
    });
    tr.append(el('td',{class:'act'},
      el('button',{class:'iconbtn',title:'Duplicate',disabled:ro?'true':null,onclick:()=>{rows.splice(i+1,0,Object.assign({},r));markDirty();refreshTables();}},'⧉'),
      el('button',{class:'iconbtn',title:'Move up',disabled:ro?'true':null,onclick:()=>{if(i>0){rows.splice(i-1,0,rows.splice(i,1)[0]);markDirty();refreshTables();}}},'▲'),
      el('button',{class:'iconbtn',title:'Move down',disabled:ro?'true':null,onclick:()=>{if(i<rows.length-1){rows.splice(i+1,0,rows.splice(i,1)[0]);markDirty();refreshTables();}}},'▼'),
      el('button',{class:'iconbtn',title:'Delete',disabled:ro?'true':null,onclick:()=>{rows.splice(i,1);markDirty();refreshTables();}},'✕')));
    tb.append(tr);
  });
  return el('div',{class:'tbl-wrap'},tb);
}
function refreshTables(){if(S.view==='inspection')viewInspectionBody($('#insBody'));}
function exportCsv(name){const def=TABLES[name];const rows=S.report[name]||[];const cols=def.cols.map(c=>c.k);
  const esc=v=>'"'+String(v==null?'':v).replace(/"/g,'""')+'"';
  let csv=cols.join(',')+'\n'+rows.map(r=>cols.map(c=>esc(r[c])).join(',')).join('\n');
  const a=el('a',{href:'data:text/csv;charset=utf-8,'+encodeURIComponent(csv),download:name+'.csv'});document.body.append(a);a.click();a.remove();}
function importDialog(name){
  const def=TABLES[name];
  const txt=prompt('Paste rows (CSV or Excel tab-separated). Columns in order:\n'+def.cols.map(c=>c.k).join(', '));
  if(!txt)return;const lines=txt.split(/\r?\n/).filter(l=>l.trim());
  let start=0;const first=lines[0].split(/\t|,/).map(s=>s.trim().toLowerCase());
  if(def.cols.some(c=>first.includes(c.k)))start=1;
  for(let i=start;i<lines.length;i++){const parts=lines[i].split(/\t|,/);const r={};def.cols.forEach((c,j)=>r[c.k]=(parts[j]||'').trim().replace(/^"|"$/g,''));S.report[name].push(r);}
  markDirty();refreshTables();toast('Imported '+(lines.length-start)+' rows');
}

/* ---------- Excel template download + upload ---------- */
function saveBytes(bytes,filename,type){
  const blob=new Blob([bytes],{type:type||'application/octet-stream'});
  const url=URL.createObjectURL(blob);
  const a=el('a',{href:url,download:filename});document.body.append(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1500);
}
function downloadXlsxTemplate(name){
  const def=TABLES[name];if(!def)return;
  if(!window.XLSXLite){toast('Excel module not loaded',true);return;}
  const headers=def.cols.map(c=>c.label);
  const bytes=window.XLSXLite.build(headers,[]); // header-only template (Text columns)
  saveBytes(bytes,name+'_template.xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  toast('Downloaded '+name+'_template.xlsx');
}
/* turn parsed sheet rows (arrays) into record objects keyed by field, matching
   the header row to column labels/keys; falls back to positional order. */
function rowsToRecords(def,rows){
  if(!rows||!rows.length)return[];
  const norm=s=>String(s==null?'':s).trim().toLowerCase().replace(/[^a-z0-9]/g,'');
  const header=(rows[0]||[]).map(norm);
  const colIdxFor=def.cols.map(c=>{const nk=norm(c.k),nl=norm(c.label);return header.findIndex(h=>h&&(h===nk||h===nl));});
  const hasHeader=colIdxFor.some(i=>i>=0);
  const recs=[];
  for(let r=hasHeader?1:0;r<rows.length;r++){
    const row=rows[r]||[];
    if(!row.some(v=>String(v==null?'':v).trim()))continue; // skip blank lines
    const rec={};
    def.cols.forEach((c,ci)=>{const idx=hasHeader?colIdxFor[ci]:ci;rec[c.k]=(idx>=0&&idx<row.length&&row[idx]!=null)?String(row[idx]).trim():'';});
    recs.push(rec);
  }
  return recs;
}
function csvToRows(text){
  return text.split(/\r?\n/).filter(l=>l.length).map(l=>{
    if(l.indexOf('\t')>=0)return l.split('\t').map(s=>s.replace(/^"|"$/g,''));
    const out=[];let cur='',q=false;
    for(let i=0;i<l.length;i++){const ch=l[i];
      if(q){if(ch==='"'){if(l[i+1]==='"'){cur+='"';i++;}else q=false;}else cur+=ch;}
      else{if(ch==='"')q=true;else if(ch===','){out.push(cur);cur='';}else cur+=ch;}}
    out.push(cur);return out;});
}
function uploadTable(name){
  const def=TABLES[name];if(!def)return;
  const inp=el('input',{type:'file',accept:'.xlsx,.csv',style:'display:none'});
  document.body.append(inp);
  inp.addEventListener('change',async()=>{
    const file=inp.files&&inp.files[0];if(!file){inp.remove();return;}
    try{
      let rows;
      if(/\.csv$/i.test(file.name))rows=csvToRows(await file.text());
      else{if(!window.XLSXLite)throw new Error('Excel module not loaded');rows=await window.XLSXLite.parse(await file.arrayBuffer());}
      const recs=rowsToRecords(def,rows);
      if(!recs.length){toast('No data rows found in “'+file.name+'”',true);inp.remove();return;}
      const existing=S.report[name]||[];
      let replace=true;
      if(existing.length)replace=confirm('“'+def.title+'” already has '+existing.length+' row(s).\n\nOK = replace with '+recs.length+' uploaded row(s)\nCancel = append them');
      S.report[name]=replace?recs:existing.concat(recs);
      markDirty();refreshTables();
      if(S.view==='templates')renderMain();
      toast('Loaded '+recs.length+' row(s) from '+file.name);
    }catch(e){toast('Upload failed: '+e.message,true);}
    inp.remove();
  });
  inp.click();
}

/* ---------- printable inspection checklist (field-collection PDF) ---------- */
function escHTML(s){return String(s==null?'':s).replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));}
function buildChecklistHTML(){
  const allClauses=S.clauses.clauses||[];
  const outcomes=(S.clauses.outcomes||['satisfactory','satisfactory_observation','deficiency','not_applicable','no_transaction','record_not_available','verification_pending']).filter(o=>o!=='custom'&&o!=='deleted');
  const css=`
@page{size:A4 portrait;margin:14mm 14mm 16mm 14mm}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:"Times New Roman",Georgia,serif;font-size:10.5pt;color:#000;line-height:1.35}
h1{font-size:15pt;text-align:center;margin:0 0 3pt;text-transform:uppercase;letter-spacing:.5pt}
.sub{text-align:center;font-size:9.5pt;margin-bottom:8pt;color:#333}
.hdr-box{border:1pt solid #000;padding:7pt 10pt;margin-bottom:10pt}
.hdr-row{display:flex;gap:14pt;margin-bottom:5pt}
.hdr-row:last-child{margin-bottom:0}
.fld{flex:1;font-size:9.5pt}
.fld label{font-weight:bold;display:block;margin-bottom:1pt;font-size:8.5pt}
.fld .ln{border-bottom:1pt solid #000;min-height:14pt}
h2{font-size:11.5pt;background:#e8e8e8;padding:3pt 8pt;margin-top:12pt;margin-bottom:5pt;border-left:3pt solid #000;page-break-after:avoid}
.cl{margin-bottom:7pt;page-break-inside:avoid;border-bottom:.5pt dashed #aaa;padding-bottom:5pt}
.cl .head{font-size:10.5pt}
.cl .num{font-weight:bold;display:inline-block;min-width:30pt}
.cl .title{font-weight:bold}
.outc{margin:2pt 0 3pt 30pt;font-size:9pt}
.outc span{margin-right:10pt;white-space:nowrap}
.obs{margin-left:30pt}
.obs .lbl{font-size:8.5pt;font-style:italic;color:#444;margin-top:3pt}
.obs .ln{border-bottom:.5pt solid #000;min-height:13pt;margin-top:1pt}
table{border-collapse:collapse;width:100%;margin-top:3pt;font-size:9pt;page-break-inside:auto}
th,td{border:.5pt solid #000;padding:3pt 4pt;text-align:left;vertical-align:top}
th{background:#e8e8e8;font-weight:bold;font-size:8.5pt}
td{height:20pt}
.tbl-title{font-weight:bold;margin-top:10pt;margin-bottom:1pt;font-size:10.5pt}
.tbl-sub{font-size:8.5pt;color:#444;margin-bottom:3pt;font-style:italic}
.pb{page-break-before:always}
.sig{margin-top:22pt;page-break-inside:avoid}
.sig .ln{border-bottom:1pt solid #000;display:inline-block;width:200pt;margin-left:6pt;min-height:14pt}
.legend{font-size:8.5pt;color:#555;border:.5pt solid #aaa;padding:5pt 7pt;margin-bottom:8pt;background:#fafafa}
@media print{.no-print{display:none}}
.no-print{position:fixed;top:8pt;right:8pt;background:#1e3a5f;color:#fff;padding:8pt 14pt;border-radius:6pt;font-size:10pt;cursor:pointer;border:none;font-family:inherit;font-weight:600;box-shadow:0 4pt 12pt rgba(0,0,0,.2)}
`;
  const officeFields=[
    ['Office name','Type (BO/SO/HO)','Facility ID'],
    ['Account office','A/O PIN','Head office'],
    ['Division','Panchayat HQ','Date of opening'],
    ['Inspection date','Inspection nature','Visit kind'],
    ['Inspection year','Last inspection date','Last inspected by'],
    ['Auth. min cash','Auth. max cash','Cash physically verified'],
    ['Postage stamps balance','Revenue stamps balance','Inspector name & designation']
  ];
  let html=`<!doctype html><html><head><meta charset="utf-8"><title>Inspection — Table Sheets</title><style>${css}</style></head><body>
<button class="no-print" onclick="window.print()">🖨 Print / Save as PDF</button>
<h1>Inspection — Table Data Sheets</h1>
<p class="sub">${escHTML(S.settings.organisation||'Department of Posts')} &nbsp;·&nbsp; fill on site, then key into the IR Builder</p>
<div class="hdr-box">
  <div class="hdr-row">
    <div class="fld"><label>Office name</label><div class="ln"></div></div>
    <div class="fld"><label>Type</label><div class="ln"></div></div>
    <div class="fld"><label>Inspection date</label><div class="ln"></div></div>
  </div>
</div>`;
  Object.keys(TABLES).forEach((name,i)=>{
    const def=TABLES[name];
    const info=tableClauseInfo(name);
    if(i>0)html+=`<div class="pb"></div>`;
    html+=`<div class="tbl-title">${info?'Para '+escHTML(info.para)+' · ':''}${escHTML(def.title)}</div>`;
    if(info)html+=`<div class="tbl-sub">${escHTML(info.title)}</div>`;
    html+=`<table><tr>`;
    if(def.serial)html+=`<th style="width:22pt">#</th>`;
    def.cols.forEach(c=>{html+=`<th>${escHTML(c.label)}</th>`;});
    html+=`</tr>`;
    const blankRows=18;
    for(let r=1;r<=blankRows;r++){
      html+=`<tr>`;
      if(def.serial)html+=`<td>${r}</td>`;
      def.cols.forEach(()=>{html+=`<td>&nbsp;</td>`;});
      html+=`</tr>`;
    }
    html+=`</table>`;
  });
  html+=`</body></html>`;
  return html;
}
function printChecklist(){
  const w=window.open('','_blank');
  if(!w){toast('Pop-up blocked — allow pop-ups for this site, then try again.',true);return;}
  w.document.open();w.document.write(buildChecklistHTML());w.document.close();
  // Auto-launch print dialog after layout settles
  setTimeout(()=>{try{w.focus();w.print();}catch(e){}},700);
}

/* ---------- header & basics template (scalar fields) ---------- */
function downloadHeaderTemplate(){
  if(!window.XLSXLite){toast('Excel module not loaded',true);return;}
  const headers=['Section','Field','Key','Value (verify / fill missing)'];
  const rows=[];
  FIELD_GROUPS.forEach(g=>{
    g.fields.forEach(f=>{
      const [key,label]=f;
      rows.push([g.title,label,key,S.report[key]==null?'':String(S.report[key])]);
    });
  });
  const bytes=window.XLSXLite.build(headers,rows);
  const blob=new Blob([bytes],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'});
  const url=URL.createObjectURL(blob);
  const fn='header_basics_'+((S.report.office_name||'draft').replace(/[^A-Za-z0-9]+/g,'_'))+'.xlsx';
  const a=el('a',{href:url,download:fn});document.body.append(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1500);
  const filled=rows.filter(r=>r[3]).length;
  toast('Downloaded '+fn+'  ·  '+filled+' of '+rows.length+' fields already populated');
}
function uploadHeaderTemplate(){
  if(!window.XLSXLite){toast('Excel module not loaded',true);return;}
  const inp=el('input',{type:'file',accept:'.xlsx,.csv',style:'display:none'});
  document.body.append(inp);
  inp.addEventListener('change',async()=>{
    const file=inp.files&&inp.files[0];if(!file){inp.remove();return;}
    try{
      let rows;
      if(/\.csv$/i.test(file.name))rows=csvToRows(await file.text());
      else rows=await window.XLSXLite.parse(await file.arrayBuffer());
      if(!rows||!rows.length)throw new Error('Empty file');
      const header=(rows[0]||[]).map(s=>String(s==null?'':s).trim().toLowerCase());
      const keyCol=header.findIndex(h=>h==='key');
      const valCol=header.findIndex(h=>h==='value'||h.startsWith('value'));
      if(keyCol<0||valCol<0)throw new Error('Could not find "Key" and "Value" columns. Use the supplied template.');
      const validKeys=new Set();
      FIELD_GROUPS.forEach(g=>g.fields.forEach(f=>validKeys.add(f[0])));
      let updated=0,skipped=0,unchanged=0;
      for(let r=1;r<rows.length;r++){
        const k=String(rows[r]?.[keyCol]||'').trim();
        const v=String(rows[r]?.[valCol]||'').trim();
        if(!k||!validKeys.has(k))continue;
        if(!v){skipped++;continue;}
        if(String(S.report[k]||'').trim()===v){unchanged++;continue;}
        S.report[k]=v;updated++;
      }
      markDirty();
      if(S.view==='inspection'&&S.section==='header')renderInspectionRefresh();
      toast('Header upload: '+updated+' updated, '+unchanged+' unchanged, '+skipped+' blank skipped');
    }catch(e){toast('Upload failed: '+e.message,true);}
    inp.remove();
  });
  inp.click();
}

/* ---------- templates (centralised download/upload for every table) ---------- */
function tableClauseInfo(tableName){
  const tableId=Object.entries(TABLE_ID_MAP).find(([id,key])=>key===tableName)?.[0];
  if(!tableId)return null;
  const cl=(S.clauses.clauses||[]).find(c=>c.table===tableId);
  return cl?{para:cl.para,title:cl.title,section:cl.section}:null;
}
function viewTemplates(m){
  m.append(el('h1',{class:'page-title'},'Templates'),
    el('p',{class:'page-sub'},'One place for every table template in the report. Download a blank Excel with the right column headers, fill it offline, then upload it back — the rows land in the matching paragraph of your active draft.'));
  // print-checklist banner (always shown, doesn't need an active draft)
  m.append(el('div',{class:'panel',style:'background:linear-gradient(135deg,#eef2ff,#fdf2f8);border-left:3px solid var(--brand);margin-bottom:14px'},
    el('div',{class:'body',style:'display:flex;align-items:center;gap:14px;flex-wrap:wrap'},
      el('div',{style:'flex:1;min-width:300px'},
        el('div',{style:'font-weight:700;font-size:14.5px;color:var(--ink)'},'📋 Printable inspection checklist (PDF)'),
        el('div',{style:'font-size:12.5px;color:var(--muted);margin-top:3px;line-height:1.5'},'A multi-page paper checklist with every clause (with outcome tick-boxes and write-in space) and every table (with 10 blank rows). Opens in a new tab and triggers your browser\'s Print dialog — pick "Save as PDF" to download, or print directly to take into the field.')),
      el('button',{class:'btn primary',onclick:printChecklist},'🖨 Generate checklist'))));
  if(!S.activeDraftId){
    m.append(el('div',{class:'note'},'No active draft. Create or open one from the Drafts page first — uploaded data needs a draft to land in.'));
    return;
  }
  // header & basics template (scalar fields, pre-filled from active draft)
  const totalHeaderFields=FIELD_GROUPS.reduce((n,g)=>n+g.fields.length,0);
  const filledHeaderFields=FIELD_GROUPS.reduce((n,g)=>n+g.fields.filter(f=>S.report[f[0]]&&String(S.report[f[0]]).trim()).length,0);
  m.append(el('div',{class:'panel',style:'background:linear-gradient(135deg,#ecfdf5,#eff6ff);border-left:3px solid var(--ok);margin-bottom:14px'},
    el('div',{class:'body',style:'display:flex;align-items:center;gap:14px;flex-wrap:wrap'},
      el('div',{style:'flex:1;min-width:300px'},
        el('div',{style:'font-weight:700;font-size:14.5px;color:var(--ink)'},'📝 Header & Basics — verify / fill template'),
        el('div',{style:'font-size:12.5px;color:var(--muted);margin-top:3px;line-height:1.5'},'Download an Excel with every Header & Basics field. Currently '+filledHeaderFields+' of '+totalHeaderFields+' are populated — verify those values and fill the blanks. Upload back to update the active draft.')),
      el('button',{class:'btn',onclick:downloadHeaderTemplate},'⬇ Download'),
      el('button',{class:'btn primary',onclick:uploadHeaderTemplate},'⬆ Upload filled'))));
  m.append(el('div',{class:'toolbar'},
    el('span',{class:'statusline',style:'color:var(--muted)'},'Active draft: '+draftLabel(S.report)+'  ·  '+Object.keys(TABLES).length+' table templates available below')));
  const grid=el('div',{class:'cards',style:'grid-template-columns:repeat(auto-fill,minmax(330px,1fr))'});
  Object.keys(TABLES).forEach(name=>{
    const def=TABLES[name];
    const info=tableClauseInfo(name);
    const rows=(S.report[name]||[]).length;
    const card=el('div',{class:'card',style:'display:flex;flex-direction:column;gap:10px'});
    card.append(
      el('div',{class:'k'},info?('Para '+info.para+' · Section '+info.section):'Table'),
      el('div',{style:'font-size:15px;font-weight:700;color:var(--ink);line-height:1.3'},def.title),
      info?el('div',{style:'font-size:12px;color:var(--muted)'},info.title):null,
      el('div',{style:'font-size:12px;color:'+(rows?'var(--ok)':'var(--muted)')+';font-weight:600'},
        rows?('✓ '+rows+' row'+(rows===1?'':'s')+' in draft'):'No rows yet'),
      el('div',{style:'font-size:11.5px;color:var(--muted)'},'Columns: '+def.cols.map(c=>c.label).join(', ')),
      el('div',{class:'draft-actions'},
        el('button',{class:'btn sm',onclick:()=>downloadXlsxTemplate(name)},'⬇ Empty template'),
        el('button',{class:'btn sm primary',onclick:()=>uploadTable(name)},'⬆ Upload filled')));
    grid.append(card);
  });
  m.append(grid);
}

/* ---------- deficiencies ---------- */
function countDeficiencies(){return Object.values(S.report.clauses||{}).filter(s=>s.outcome==='deficiency').length;}
function viewDeficiencies(m){
  m.append(el('h1',{class:'page-title'},'Deficiency & Compliance Tracker'),el('p',{class:'page-sub'},'Auto-extracted from points marked “Deficiency observed”.'));
  const defs=Object.entries(S.report.clauses||{}).filter(([id,s])=>s.outcome==='deficiency');
  if(!defs.length){m.append(el('div',{class:'note'},'No deficiencies recorded.'));return;}
  const tb=el('table',{class:'grid'});
  tb.append(el('tr',{},['Para','Clause','Deficiency','Instruction','Severity','Target date','Status'].map(h=>el('th',{},h))));
  defs.forEach(([id,s])=>{const c=clauseById(id);
    tb.append(el('tr',{},el('td',{class:'mono'},c?c.para:id),el('td',{},c?c.title:''),el('td',{},s.observation||''),el('td',{},s.instruction||''),
      el('td',{},sevSelect(id,s)),el('td',{},dateInput(id,s),''),el('td',{},statusSelect(id,s))));
  });
  m.append(el('div',{class:'panel'},el('div',{class:'tbl-wrap',style:'margin:0'},tb)));
  m.append(el('div',{class:'toolbar',style:'margin-top:12px'},el('button',{class:'btn',onclick:()=>exportDefCsv()},'⬇ Export compliance summary (CSV)')));
}
function sevSelect(id,s){const sel=el('select',{});['Advisory','Minor','Major','Critical'].forEach(v=>{const o=el('option',{},v);if((s.severity||'Minor')===v)o.selected=true;sel.append(o);});sel.onchange=()=>{setSel(id,'severity',sel.value);};return sel;}
function statusSelect(id,s){const sel=el('select',{});['Pending','Replied','Verified','Closed'].forEach(v=>{const o=el('option',{},v);if((s.status||'Pending')===v)o.selected=true;sel.append(o);});sel.onchange=()=>{setSel(id,'status',sel.value);};return sel;}
function dateInput(id,s){const i=el('input',{type:'text',value:s.target_date||defaultComplianceDate(),placeholder:'DD.MM.YYYY',style:'width:110px'});i.oninput=()=>setSel(id,'target_date',i.value);return i;}
function defaultComplianceDate(){return '';}
function exportDefCsv(){const defs=Object.entries(S.report.clauses||{}).filter(([id,s])=>s.outcome==='deficiency');
  let csv='para,clause,deficiency,instruction,severity,target_date,status\n';
  defs.forEach(([id,s])=>{const c=clauseById(id);csv+=[c?c.para:id,c?c.title:'',s.observation,s.instruction,s.severity||'Minor',s.target_date||'',s.status||'Pending'].map(v=>'"'+String(v||'').replace(/"/g,'""')+'"').join(',')+'\n';});
  const a=el('a',{href:'data:text/csv;charset=utf-8,'+encodeURIComponent(csv),download:'compliance_summary.csv'});document.body.append(a);a.click();a.remove();}

/* ---------- validation ---------- */
function validate(){
  const v=[];const r=S.report;
  const crit=(sec,msg)=>v.push({level:'critical',sec,msg});const warn=(sec,msg)=>v.push({level:'warn',sec,msg});
  if(!r.office_name)crit('Header','Office name is required.');
  if(!r.office_type)crit('Header','Office type is required.');
  if(r.account_office_pin&&!/^\d{3}\s?\d{3}$/.test(String(r.account_office_pin).trim()))crit('Header','PIN code must be 6 digits.');
  ['inspection_date','last_inspection_date','memo_date','date_of_opening'].forEach(k=>{if(r[k]&&!/^\d{2}\.\d{2}\.\d{4}$/.test(String(r[k]).trim()))crit('Header','Date '+k+' must be DD.MM.YYYY.');});
  if(r.inspection_date&&r.last_inspection_date&&/^\d{2}\.\d{2}\.\d{4}$/.test(r.inspection_date)&&/^\d{2}\.\d{2}\.\d{4}$/.test(r.last_inspection_date)){
    const p=s=>{const a=s.split('.');return new Date(+a[2],+a[1]-1,+a[0]);};if(p(r.inspection_date)<=p(r.last_inspection_date))crit('Header','Inspection date must be after the previous inspection date.');}
  if(!r.memo_number)crit('Header','Memo number is required.');
  if(!r.signatory_name)crit('Header','Signatory name is required.');
  // amount in words match
  if(r.cash_verified_amount){const w=amountWords(r.cash_verified_amount);if(r.cash_verified_words&&r.cash_verified_words!==w)crit('Header','Cash amount in words does not match the figure.');}
  // scoring
  const maxes={records:5,hardware:5,rict:40,ippb:10,bd:20,service:20};let tot=0,mx=0;
  Object.keys(maxes).forEach(k=>{const a=parseInt(r['score_'+k]||'0',10)||0;if(a>maxes[k])crit('Conclusion','Points awarded for '+k+' exceed maximum ('+maxes[k]+').');tot+=a;mx+=maxes[k];});
  r._score_total=tot;r._score_max=mx;
  // deficiency completeness
  Object.entries(r.clauses||{}).forEach(([id,s])=>{const c=clauseById(id);const p=c?c.para:id;
    if(s.outcome==='deficiency'){if(!s.observation||!s.observation.trim())crit('Sec '+(c?c.section:''),'Para '+p+': deficiency needs an observation.');if(!s.instruction||!s.instruction.trim())crit('Sec '+(c?c.section:''),'Para '+p+': deficiency needs a compliance instruction.');}
    if(s.outcome==='custom'){if(!s.custom_text||!s.custom_text.trim())crit('Sec '+(c?c.section:''),'Para '+p+': custom wording is empty.');if(!s.custom_reason||!s.custom_reason.trim())crit('Sec '+(c?c.section:''),'Para '+p+': custom override needs a reason.');}
    // contradictory: no_transaction but table has rows
    {const tk=c&&tableKeyFor(c.table);if(s.outcome==='no_transaction'&&tk&&(r[tk]||[]).length)warn('Sec '+(c?c.section:''),'Para '+p+': marked “No transaction” but its table has rows.');}
  });
  // duplicate records in keyed tables
  Object.keys(TABLES).forEach(name=>{const def=TABLES[name];if(!def.key)return;const seen={};(r[name]||[]).forEach(row=>{const val=(row[def.key]||'').trim();if(!val)return;if(seen[val])warn(def.title,'Duplicate '+def.key+' “'+val+'”.');seen[val]=1;});});
  return v;
}
function viewValidation(m){
  m.append(el('h1',{class:'page-title'},'Validation summary'),el('p',{class:'page-sub'},'Critical issues block final generation. Click an item to jump to its section.'));
  const v=validate();const crit=v.filter(x=>x.level==='critical'),warns=v.filter(x=>x.level==='warn');
  m.append(el('div',{class:'cards'},
    el('div',{class:'card'},el('div',{class:'k'},'Critical errors'),el('div',{class:'v',style:'color:'+(crit.length?'var(--err)':'var(--ok)')},String(crit.length))),
    el('div',{class:'card'},el('div',{class:'k'},'Warnings'),el('div',{class:'v',style:'color:var(--warn)'},String(warns.length))),
    el('div',{class:'card'},el('div',{class:'k'},'Conclusion score'),el('div',{class:'v'},(S.report._score_total||0)+' / '+(S.report._score_max||100)))));
  if(!v.length){m.append(el('div',{class:'note'},'✓ All checks passed. Ready to generate.'));return;}
  const p=el('div',{class:'panel'},el('h3',{},'Issues'));const body=el('div',{class:'body'});
  v.forEach(x=>{body.append(el('div',{class:'vrow '+(x.level==='critical'?'critical':'warn'),onclick:()=>jumpToSection(x.sec)},
    el('div',{class:'vmsg'},el('span',{class:'badge '+(x.level==='critical'?'err':'warn')},x.level),' ',x.msg,el('div',{class:'vsec'},x.sec))));});
  p.append(body);m.append(p);
}
function jumpToSection(sec){const mm=sec.match(/Sec ([A-K])/);S.view='inspection';if(mm)S.section=mm[1];else if(/Header|Conclusion/.test(sec))S.section=sec.includes('Conclusion')?'K':'header';renderNav();renderMain();}

/* ---------- preview & export ---------- */
function viewPreview(m){
  m.append(el('h1',{class:'page-title'},'Preview & Export'));
  const tabs=el('div',{class:'toolbar'},
    el('button',{class:'btn',id:'tabStruct',onclick:()=>showPreviewTab('struct')},'Structured preview'),
    el('button',{class:'btn',id:'tabPage',onclick:()=>showPreviewTab('page')},'Final page preview (PDF)'),
    el('span',{class:'spacer'}),
    el('label',{class:'inline',style:'font-size:12.5px;color:var(--muted);margin-right:6px',title:'Replace any {{placeholder}} that you haven\'t filled in with a long dash ( — ) instead of blocking generation.'},
      el('input',{type:'checkbox',id:'allowBlanksCb'}),'Fill missing fields with —'),
    el('button',{class:'btn primary',id:'genBtn',onclick:generate},'⚙ Generate DOCX + PDF'));
  m.append(tabs);
  const host=el('div',{id:'previewHost'});m.append(host);
  showPreviewTab('struct');
}
function showPreviewTab(t){const host=$('#previewHost');if(!host)return;host.innerHTML='';
  if(t==='struct')host.append(structuredPreview());
  else host.append(pagePreview());
}
function structuredPreview(){
  const r=S.report;const d=el('div',{class:'doc'});
  d.append(el('h1',{},'INSPECTION REPORT ON '+(r.office_name||'')+' '+(r.office_type||'')+' A/W '+(r.account_office||'')+' SO - '+(r.account_office_pin||'')+' DATED '+(r.inspection_date||'')));
  d.append(el('p',{},'Paid a '+(r.visit_kind||'')+' to '+(r.office_name||'')+' '+(r.office_type||'')+' a/w '+(r.account_office||'')+' SO on '+(r.inspection_date||'')+' and carried out its '+(r.inspection_nature||'')+' inspection for the year '+(r.inspection_year||'')+'.'));
  SECTION_ORDER.forEach(sec=>{
    d.append(el('h2',{},sec+'. '+SECTION_NAMES[sec].toUpperCase()));
    (S.clauses.clauses||[]).filter(c=>c.section===sec).forEach(c=>{
      d.append(el('p',{},el('strong',{},c.para+'. '),renderClause(c.id)));
    });
  });
  d.append(el('h2',{},'MEMO & SIGNATURE'));
  d.append(el('p',{},el('strong',{},'MEMO: '),(r.memo_number||'')+' dated '+(r.memo_place||'')+' the '+(r.memo_date||'')));
  d.append(el('p',{},'//'+(r.signatory_name||'')+'// '+(r.signatory_designation||'')));
  return d;
}
function pagePreview(){
  const host=el('div',{});
  const r=S.lastResult||(S.reports&&S.reports[0]);
  if(r&&r.pdf){
    host.append(el('div',{class:'note'},'Showing actual generated PDF: '+r.pdf+' ('+r.pages+' pages, '+r.pdfBytes+' bytes). This is the real LibreOffice/Word-rendered document, not an HTML approximation.'),
      el('iframe',{class:'previewframe',src:'/output/'+r.pdf}));
  }else host.append(el('div',{class:'note'},'No generated PDF yet. Click “Generate DOCX + PDF”.'));
  return host;
}
async function generate(){
  const v=validate();const crit=v.filter(x=>x.level==='critical');
  if(crit.length){toast(crit.length+' critical validation error(s) — fix before generating',true);S.view='validation';renderNav();renderMain();return;}
  const btn=$('#genBtn');if(btn){btn.disabled=true;btn.textContent='Generating…';}
  try{
    const allowBlanks=document.getElementById('allowBlanksCb')?.checked?'1':'0';
    const res=await fetch('/api/generate?allowBlanks='+allowBlanks,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(S.report)});
    const j=await res.json();
    if(!j.ok){toast('Generation failed: '+j.error,true);if(btn){btn.disabled=false;btn.textContent='⚙ Generate DOCX + PDF';}return;}
    S.lastResult=j.result;S.reports.unshift(j.result);
    const havePdf=!!j.result.pdf;
    toast(havePdf?('Generated — '+j.result.pages+' pages'):'Generated DOCX (PDF skipped — open the DOCX in Word and Save As PDF)');
    showPreviewTab('page');
    const host=$('#previewHost');
    const bar=el('div',{class:'toolbar'},
      el('a',{class:'btn primary',href:'/download/'+j.result.docx},'⬇ Download DOCX'));
    if(havePdf){
      bar.append(el('a',{class:'btn',href:'/download/'+j.result.pdf,target:'_blank'},'⬇ Download PDF'),
        el('span',{class:'badge ok'},'DOCX '+j.result.docxSha256.slice(0,10)+'…'),
        el('span',{class:'badge muted'},'PDF '+j.result.pages+'p'));
    } else {
      bar.append(el('span',{class:'badge warn'},'PDF skipped — Word COM failed'));
    }
    host.prepend(bar);
    if(!havePdf&&j.result.warnings&&j.result.warnings.length){
      host.prepend(el('div',{class:'note',style:'border-left-color:var(--warn)'},j.result.warnings.join(' · ')));
    }
  }catch(e){toast('Error: '+e.message,true);}
  if(btn){btn.disabled=false;btn.textContent='⚙ Generate DOCX + PDF';}
}

boot();
