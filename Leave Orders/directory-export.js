/* Staff-directory download card (Excel / PDF) — shared by the Employee
   Directory (/employees) and Office Management (/office-management) pages,
   included via <script src="/leave-orders/directory-export.js"></script>.

   Renders into <div id="dir-export"></div> using the host page's card styles.
   Data (staff, mobiles, office master) is fetched fresh at download time, so
   edits made on any page are always reflected. Pay details are never included.

   Columns: Sl. No · Name of the Official · Designation · Name of their Office
   · BO/SO · Office ID · Account Office · Mobile Number
   Sorted office-name ascending, then official-name ascending, grouped
   sub-division-wise (Excel: one sheet per sub-division; PDF: one section). */
(function(){
  const host=document.getElementById('dir-export');
  if(!host) return;
  const cln=v=>(v==null?'':String(v)).trim();
  const say=(m,err)=>{ if(typeof toast==='function') toast(m,err); else alert(m); };
  const HEADERS=['Sl. No','Name of the Official','Designation','Name of their Office','BO/SO','Office ID','Account Office','Mobile Number'];
  const BOSO={BPO:'BO',SPO:'SO',SDO:'SO',HPO:'HO'};
  const UNMAPPED='(Not in office master)';

  // Staff-type sub-categories offered in the picker.
  const TYPES={
    BOTH:{label:'Both (Departmental + GDS)', match:t=>t==='DS'||t==='GDS'},
    DS:  {label:'Departmental',              match:t=>t==='DS'},
    GDS: {label:'GDS',                       match:t=>t==='GDS'},
    ALL: {label:'All (incl. Outsiders)',     match:()=>true},
  };
  host.className='card';
  host.innerHTML=
    '<h2><span class="num">&#8595;</span> Download staff directory</h2>'+
    '<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">'+
      '<select id="dx-sub" class="pick" style="max-width:300px;flex:0 1 300px"><option value="">All sub-divisions</option></select>'+
      '<select id="dx-type" class="pick" style="max-width:250px;flex:0 1 250px">'+
        Object.keys(TYPES).map(k=>'<option value="'+k+'">'+TYPES[k].label+'</option>').join('')+
      '</select>'+
      '<button class="btn" id="dx-xlsx">&#128202; Download Excel</button>'+
      '<button class="btn" id="dx-pdf">&#128196; Download PDF</button>'+
    '</div>'+
    '<div class="muted" style="font-size:12px;margin-top:8px">Sl. No &middot; Name of the Official &middot; Designation &middot; Name of their Office &middot; BO/SO &middot; Office ID &middot; Account Office &middot; Mobile Number &mdash; sorted by Account Office, then office, then designation (BPM &middot; ABPM &middot; Dak Sevak); grouped sub-division-wise. Pay details are never included.</div>';
  const subSel=host.querySelector('#dx-sub'), typeSel=host.querySelector('#dx-type'), xlsBtn=host.querySelector('#dx-xlsx'), pdfBtn=host.querySelector('#dx-pdf');

  // Populate the sub-division picker from the office master (best effort).
  fetch('/api/offices-master',{cache:'no-store'}).then(r=>r.json()).then(d=>{
    const subs=new Set();
    // Only the Karur Sub Division is served here, so the picker lists Karur only.
    const offs = window.KarurScope ? KarurScope.filterOffices(d.offices||[]) : (d.offices||[]);
    for(const o of offs) subs.add(cln(o.sub_division)||'(Head / Admin)');
    for(const s of [...subs].sort((a,b)=>a.localeCompare(b))){
      const opt=document.createElement('option'); opt.value=s; opt.textContent=s; subSel.append(opt);
    }
  }).catch(()=>{});

  /* ---------- data assembly (always fetched fresh) ---------- */
  async function buildRows(){
    if(window.KarurScope){ try{ await KarurScope.load(); }catch(e){} }
    const [ed,md,od]=await Promise.all([
      fetch('/api/employees',{cache:'no-store'}).then(r=>r.json()),
      fetch('/api/mobiles',{cache:'no-store'}).then(r=>r.json()),
      fetch('/api/offices-master',{cache:'no-store'}).then(r=>r.json()).catch(()=>({offices:[]})),
    ]);
    // Only Karur Sub Division staff and offices are exported.
    const emps=(Array.isArray(ed.employees)?ed.employees:[]).filter(e=>window.KarurScope?KarurScope.inScope(e):true);
    const mobiles=(md&&md.map)||{};
    const masterOffs=window.KarurScope?KarurScope.filterOffices(od.offices||[]):(od.offices||[]);
    const master=new Map(masterOffs.map(o=>[cln(o.office_id),o]));
    const rows=[];
    for(const e of emps){
      const isOut=e._type==='OUT';
      const name=isOut? [e.first_name,e.middle_name,e.last_name].map(cln).filter(Boolean).join(' ') : cln(e.Employee_name);
      if(!name) continue;
      const oid=cln(isOut? e.office_id : e.Office_id);
      const m=master.get(oid);
      rows.push({
        type: e._type||'DS',
        sub: m? (cln(m.sub_division)||'(Head / Admin)') : UNMAPPED,
        officeName: m? cln(m.office_name) : (isOut? cln(e.office_name) : cln(e.Office_desc)),
        boso: m? (BOSO[cln(m.office_type)]||cln(m.office_type)) : '',
        oid,
        // A BO's accounts are kept at its Sub Office; SOs (and the HO) account to the Head Office.
        acct: m? (cln(m.office_type)==='BPO' ? (cln(m.so_name)||cln(m.ho_name)) : (cln(m.ho_name)||cln(m.office_name))) : '',
        name,
        desig: isOut? (cln(e.post_desc)||'Outsider') : cln(e.Post_desc),
        mobile: isOut? cln(e.mobile_no) : cln(mobiles[cln(e.Employee_id)]),
      });
    }
    return rows;
  }
  // Designation precedence inside an office: BPM, then ABPM, then Dak Sevak,
  // then everything else (alphabetical). Handles both the abbreviations and
  // the spelled-out payroll designations.
  function desigRank(d){
    const s=cln(d).toUpperCase();
    if(s==='ABPM'||s.includes('ASSISTANT')) return 2;      // before the BPM check: "Assistant Branch Post Master" contains "Branch Post"
    if(s==='BPM'||s.includes('BRANCH POST')) return 1;
    if(s==='DS'||s.includes('DAK SEVAK')) return 3;
    return 4;
  }
  // -> [ [subName, rows-sorted-acct-office → office → designation → name], ... ] (parenthesised groups last)
  function groupRows(rows, subFilter, typeKey){
    const match=(TYPES[typeKey]||TYPES.BOTH).match;
    const list=rows.filter(r=>match(r.type) && (!subFilter || r.sub===subFilter));
    const m=new Map();
    for(const r of list){ if(!m.has(r.sub)) m.set(r.sub,[]); m.get(r.sub).push(r); }
    const keys=[...m.keys()].sort((a,b)=>{
      const pa=a.startsWith('('), pb=b.startsWith('(');
      if(pa!==pb) return pa?1:-1;
      return a.localeCompare(b);
    });
    return keys.map(k=>[k, m.get(k).sort((x,y)=>
      x.acct.localeCompare(y.acct) ||
      x.officeName.localeCompare(y.officeName) ||
      (desigRank(x.desig)-desigRank(y.desig)) ||
      x.desig.localeCompare(y.desig) ||
      x.name.localeCompare(y.name))]);
  }
  const today=()=>{ const d=new Date(),p=n=>String(n).padStart(2,'0'); return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate()); };
  const typeFile={BOTH:'Departmental-GDS',DS:'Departmental',GDS:'GDS',ALL:'All-Staff'};
  const fileBase=(sub,typeKey)=>('Staff-Directory_'+(typeFile[typeKey]||typeKey)+'_'+(sub||'All-Sub-Divisions')+'_'+today()).replace(/[^A-Za-z0-9._-]+/g,'-');

  /* ---------- Excel ---------- */
  function downloadExcel(groups,sub,typeKey){
    const wb=XLSX.utils.book_new();
    const used=new Set();
    for(const [g,rows] of groups){
      let nm=g.replace(/[\\\/?*\[\]:]/g,' ').trim().slice(0,31)||'Sheet';
      let i=2; while(used.has(nm)){ nm=nm.slice(0,28)+' '+(i++); } used.add(nm);
      const aoa=[HEADERS, ...rows.map((r,i2)=>[i2+1,r.name,r.desig,r.officeName,r.boso,r.oid,r.acct,r.mobile])];
      const ws=XLSX.utils.aoa_to_sheet(aoa);
      ws['!cols']=[{wch:6},{wch:32},{wch:24},{wch:30},{wch:7},{wch:11},{wch:26},{wch:14}];
      XLSX.utils.book_append_sheet(wb,ws,nm);
    }
    XLSX.writeFile(wb,fileBase(sub,typeKey)+'.xlsx');
  }

  /* ---------- PDF (jsPDF + autotable, loaded on first use) ---------- */
  let pdfLoad=null;
  const loadScript=src=>new Promise((res,rej)=>{ const s=document.createElement('script'); s.src=src; s.onload=res; s.onerror=()=>rej(new Error('Could not load '+src)); document.head.appendChild(s); });
  function ensurePdfLibs(){
    if(window.jspdf&&window.jspdf.jsPDF&&window.jspdf.jsPDF.API.autoTable) return Promise.resolve();
    if(!pdfLoad) pdfLoad=loadScript('https://cdn.jsdelivr.net/npm/jspdf@2.5.1/dist/jspdf.umd.min.js')
      .then(()=>loadScript('https://cdn.jsdelivr.net/npm/jspdf-autotable@3.8.2/dist/jspdf.plugin.autotable.min.js'))
      .catch(e=>{ pdfLoad=null; throw e; });
    return pdfLoad;
  }
  function downloadPdf(groups,sub,typeKey){
    const {jsPDF}=window.jspdf;
    const doc=new jsPDF({orientation:'landscape',unit:'pt',format:'a4'});
    const pageW=doc.internal.pageSize.getWidth(), pageH=doc.internal.pageSize.getHeight();
    groups.forEach(([g,rows],gi)=>{
      if(gi>0) doc.addPage();
      doc.setFont('helvetica','bold'); doc.setFontSize(13); doc.setTextColor(31,56,100);
      doc.text('Department of Posts — Staff Directory, Karur Division',pageW/2,40,{align:'center'});
      doc.setFont('helvetica','normal'); doc.setFontSize(10); doc.setTextColor(60,72,88);
      doc.text('Sub Division: '+g+'   ·   '+(TYPES[typeKey]||TYPES.BOTH).label+'   ·   '+rows.length+' officials   ·   generated '+today(),pageW/2,58,{align:'center'});
      doc.autoTable({
        startY:72,
        head:[HEADERS],
        body:rows.map((r,i)=>[String(i+1),r.name,r.desig,r.officeName,r.boso,r.oid,r.acct,r.mobile]),
        styles:{font:'helvetica',fontSize:8,cellPadding:3.5,overflow:'linebreak',textColor:[30,41,59]},
        headStyles:{fillColor:[31,56,100],textColor:255,fontStyle:'bold'},
        alternateRowStyles:{fillColor:[243,246,252]},
        columnStyles:{0:{cellWidth:36,halign:'right'},4:{cellWidth:42,halign:'center'},5:{cellWidth:64},7:{cellWidth:84}},
        margin:{left:24,right:24,top:72,bottom:34},
      });
    });
    const n=doc.getNumberOfPages();
    for(let i=1;i<=n;i++){
      doc.setPage(i); doc.setFont('helvetica','normal'); doc.setFontSize(8); doc.setTextColor(120,130,145);
      doc.text('Page '+i+' of '+n,pageW-24,pageH-16,{align:'right'});
      doc.text('No pay details included · generated from the Employee Directory',24,pageH-16);
    }
    doc.save(fileBase(sub,typeKey)+'.pdf');
  }

  /* ---------- wire ---------- */
  async function run(kind){
    const btn=kind==='xlsx'?xlsBtn:pdfBtn;
    const old=btn.innerHTML; btn.disabled=true; btn.textContent='Preparing…';
    try{
      const sub=subSel.value, typeKey=typeSel.value||'BOTH';
      const groups=groupRows(await buildRows(),sub,typeKey);
      const total=groups.reduce((n,[,r])=>n+r.length,0);
      if(!total){ say('No staff to export for that selection — upload payroll data first.',true); return; }
      if(kind==='xlsx'){ downloadExcel(groups,sub,typeKey); }
      else { await ensurePdfLibs(); downloadPdf(groups,sub,typeKey); }
      say('Downloaded '+total+' officials · '+(TYPES[typeKey]||TYPES.BOTH).label+' · '+(sub||'all sub-divisions')+' · '+(kind==='xlsx'?'Excel':'PDF')+'.');
    }catch(e){ say('Download failed: '+e.message,true); }
    finally{ btn.disabled=false; btn.innerHTML=old; }
  }
  xlsBtn.addEventListener('click',()=>run('xlsx'));
  pdfBtn.addEventListener('click',()=>run('pdf'));
})();
