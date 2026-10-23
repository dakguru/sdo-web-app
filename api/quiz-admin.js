// api/quiz-admin.js — quiz administration API.
//
// Every action re-checks the admin session and the caller's quiz role on the
// server. Hiding a button in the UI is not access control; this file is.
//
// Roles, least to most privileged:
//   QUIZ_VIEWER  read-only reports
//   QUIZ_EDITOR  question bank, previews, test attempts
//   QUIZ_ADMIN   everything, including publishing and result release
//
// Dispatch is by ?action=, so the whole module costs one Vercel function.

'use strict';

const Q = require('./_quizlib');

const ROUTES = {
  // ── overview ──────────────────────────────────────────────
  'overview':              { role: 'QUIZ_VIEWER', fn: overview },
  // ── employee master directory ─────────────────────────────
  'employees-list':        { role: 'QUIZ_VIEWER', fn: employeesList },
  'employees-import':      { role: 'QUIZ_ADMIN',  fn: employeesImport },
  'employee-save':         { role: 'QUIZ_ADMIN',  fn: employeeSave },
  'import-jobs':           { role: 'QUIZ_VIEWER', fn: importJobs },
  // ── registrations ─────────────────────────────────────────
  'registrations-list':    { role: 'QUIZ_VIEWER', fn: registrationsList },
  'registration-stats':    { role: 'QUIZ_VIEWER', fn: registrationStats },
  'registrations-backfill':{ role: 'QUIZ_ADMIN',  fn: registrationsBackfill },
  'registration-status':   { role: 'QUIZ_ADMIN',  fn: registrationStatus },
  'registration-edit':     { role: 'QUIZ_ADMIN',  fn: registrationEdit },
  'registration-add':      { role: 'QUIZ_ADMIN',  fn: registrationAdd },
  'registration-unlink':   { role: 'QUIZ_ADMIN',  fn: registrationUnlink },
  // ── question bank ─────────────────────────────────────────
  'questions-list':        { role: 'QUIZ_EDITOR', fn: questionsList },
  'question-save':         { role: 'QUIZ_EDITOR', fn: questionSave },
  'question-status':       { role: 'QUIZ_EDITOR', fn: questionStatus },
  'question-duplicate':    { role: 'QUIZ_EDITOR', fn: questionDuplicate },
  'question-history':      { role: 'QUIZ_EDITOR', fn: questionHistory },
  'questions-import':      { role: 'QUIZ_EDITOR', fn: questionsImport },
  // Deleting outright is an administrator's call; editors archive instead.
  'questions-delete':      { role: 'QUIZ_ADMIN',  fn: questionsDelete },
  // ── examinations ──────────────────────────────────────────
  'exams-list':            { role: 'QUIZ_VIEWER', fn: examsList },
  'exam-save':             { role: 'QUIZ_ADMIN',  fn: examSave },
  'exam-schedule':         { role: 'QUIZ_ADMIN',  fn: examSchedule },
  'exam-delete':           { role: 'QUIZ_ADMIN',  fn: examDelete },
  'exam-duplicate':        { role: 'QUIZ_ADMIN',  fn: examDuplicate },
  'exam-validate':         { role: 'QUIZ_EDITOR', fn: examValidate },
  'exam-publish':          { role: 'QUIZ_ADMIN',  fn: examPublish },
  'exam-status':           { role: 'QUIZ_ADMIN',  fn: examStatus },
  'exam-paper':            { role: 'QUIZ_EDITOR', fn: examPaper },
  'exam-monitor':          { role: 'QUIZ_VIEWER', fn: examMonitor },
  // Who may sit an examination. Reading the list is a viewer's business;
  // changing who may sit a paper is an administrator's.
  'exam-allotment':        { role: 'QUIZ_VIEWER', fn: examAllotment },
  'exam-allotment-save':   { role: 'QUIZ_ADMIN',  fn: examAllotmentSave },
  // ── admin test mode ───────────────────────────────────────
  'test-start':            { role: 'QUIZ_EDITOR', fn: testStart },
  'test-reset':            { role: 'QUIZ_EDITOR', fn: testReset },
  // ── results ───────────────────────────────────────────────
  'results-list':          { role: 'QUIZ_VIEWER', fn: resultsList },
  'results-publish':       { role: 'QUIZ_ADMIN',  fn: resultsPublish },
  'result-withhold':       { role: 'QUIZ_ADMIN',  fn: resultWithhold },
  'results-recalculate':   { role: 'QUIZ_ADMIN',  fn: resultsRecalculate },
  // ── reports, logs, settings ───────────────────────────────
  'report':                { role: 'QUIZ_VIEWER', fn: report },
  'events-list':           { role: 'QUIZ_VIEWER', fn: eventsList },
  'audit-list':            { role: 'QUIZ_VIEWER', fn: auditList },
  'settings-get':          { role: 'QUIZ_VIEWER', fn: settingsGet },
  'settings-set':          { role: 'QUIZ_ADMIN',  fn: settingsSet },
  'roles-list':            { role: 'QUIZ_ADMIN',  fn: rolesList },
  'role-grant':            { role: 'QUIZ_ADMIN',  fn: roleGrant },
  'sweep-expired':         { role: 'QUIZ_ADMIN',  fn: sweepExpired },
};

module.exports = async function handler(req, res) {
  try {
    const cfgErr = Q.configError();
    if (cfgErr) throw new Q.AppError(cfgErr, 503, 'NOT_CONFIGURED');
    Q.requireJsonRequest(req);

    const action = String((req.query && req.query.action) || '').toLowerCase();
    const route = ROUTES[action];
    if (!route) throw new Q.AppError('Unknown action.', 404, 'UNKNOWN_ACTION');

    const actor = await Q.requireAdmin(req, route.role);
    return await route.fn(req, res, actor);
  } catch (err) {
    return Q.fail(res, err);
  }
};

// ═══════════════════════════════════════════════════════════════════
//  OVERVIEW
// ═══════════════════════════════════════════════════════════════════
async function overview(req, res, actor) {
  const now = Date.now();
  const [
    employees, questionsActive, questionsTotal, exams,
    regTotal, regApproved, regPending,
  ] = await Promise.all([
    Q.sbCount('quiz_employees', 'active=is.true'),
    Q.sbCount('quiz_questions', 'status=' + Q.eq('ACTIVE')),
    Q.sbCount('quiz_questions'),
    Q.sbSelect('quiz_exams', 'select=id,title,status,exam_open_ms,exam_close_ms,question_count,positive_marks&order=exam_open_ms.asc'),
    Q.sbCount('quiz_registrations'),
    Q.sbCount('quiz_registrations', 'status=' + Q.eq('APPROVED')),
    Q.sbCount('quiz_registrations', 'status=' + Q.eq('PENDING')),
  ]);

  const upcoming = (exams || []).filter(e => e.exam_open_ms && e.exam_open_ms > now &&
    !['CANCELLED', 'COMPLETED', 'RESULTS_PUBLISHED'].includes(e.status));
  const active = (exams || []).filter(e =>
    e.exam_open_ms && e.exam_open_ms <= now && (!e.exam_close_ms || e.exam_close_ms >= now) &&
    !['DRAFT', 'CANCELLED'].includes(e.status));

  // Per-exam participation. OFFICIAL only — a dry run must never move these
  // numbers, which is why mode is in every filter.
  const perExam = [];
  for (const ex of exams || []) {
    const [started, completed, results] = await Promise.all([
      Q.sbCount('quiz_attempts', 'exam_id=' + Q.eq(ex.id) + '&mode=' + Q.eq('OFFICIAL')),
      Q.sbCount('quiz_attempts', 'exam_id=' + Q.eq(ex.id) + '&mode=' + Q.eq('OFFICIAL') + '&status=neq.IN_PROGRESS'),
      Q.sbSelect('quiz_results', 'select=score,percentage,published&exam_id=' + Q.eq(ex.id) + '&mode=' + Q.eq('OFFICIAL')),
    ]);
    const scores = (results || []).map(r => Number(r.score));
    perExam.push({
      id: ex.id, title: ex.title, status: ex.status,
      examOpenMs: ex.exam_open_ms, examCloseMs: ex.exam_close_ms,
      maxMarks: Number(ex.positive_marks) * ex.question_count,
      started, completed,
      inProgress: started - completed,
      absent: Math.max(0, regApproved - started),
      completionRate: regApproved ? Math.round((completed / regApproved) * 1000) / 10 : 0,
      averageScore: scores.length ? Math.round((scores.reduce((a, b) => a + b, 0) / scores.length) * 100) / 100 : null,
      highestScore: scores.length ? Math.max.apply(null, scores) : null,
      publishedCount: (results || []).filter(r => r.published).length,
    });
  }

  const suspects = await Q.sbCount('quiz_events', 'severity=' + Q.eq('SUSPECT'));

  return Q.ok(res, {
    serverTimeMs: now,
    actor: { username: actor.username, role: actor.role },
    counts: {
      employees,
      questionsActive, questionsTotal,
      exams: (exams || []).length,
      examsUpcoming: upcoming.length,
      examsActive: active.length,
      registrations: regTotal,
      approved: regApproved,
      pending: regPending,
      suspectEvents: suspects,
    },
    // These three are what the module shows before any content is uploaded.
    readiness: {
      directoryImported: employees > 0,
      questionBankUploaded: questionsActive > 0,
      examScheduled: (exams || []).some(e => e.status !== 'DRAFT' && e.status !== 'CANCELLED'),
    },
    exams: perExam,
  });
}

// ═══════════════════════════════════════════════════════════════════
//  EMPLOYEE MASTER DIRECTORY
// ═══════════════════════════════════════════════════════════════════
async function employeesList(req, res) {
  const qp = req.query || {};
  const limit = clampInt(qp.limit, 1, 1000, 100);
  const offset = clampInt(qp.offset, 0, 1e6, 0);
  const search = String(qp.q || '').trim();

  // `unit` only exists after migration_v22; filtering or searching on it
  // before then would reject the whole query.
  const hasUnit = await Q.columnsReady('quiz_employees');

  let filter = 'select=*&order=employee_name.asc&limit=' + limit + '&offset=' + offset;
  if (search) {
    const safe = search.replace(/[,()*]/g, ' ').trim();
    filter += '&or=(employee_id.ilike.*' + encodeURIComponent(safe) + '*,' +
              'employee_name.ilike.*' + encodeURIComponent(safe) + '*,' +
              'designation.ilike.*' + encodeURIComponent(safe) + '*,' +
              (hasUnit ? 'unit.ilike.*' + encodeURIComponent(safe) + '*,' : '') +
              'office.ilike.*' + encodeURIComponent(safe) + '*)';
  }
  if (qp.office)      filter += '&office=' + Q.eq(qp.office);
  if (qp.designation) filter += '&designation=' + Q.eq(qp.designation);
  if (qp.unit && hasUnit) filter += '&unit=' + Q.eq(qp.unit);
  // "Unspecified" is a real thing to filter by — it is how the administrator
  // finds the people who would otherwise fall outside both prize categories.
  if (qp.category === 'UNSPECIFIED') filter += '&category=is.null';
  else if (qp.category)             filter += '&category=' + Q.eq(String(qp.category).toUpperCase());
  if (qp.active === 'true')  filter += '&active=is.true';
  if (qp.active === 'false') filter += '&active=is.false';

  const rows = await Q.sbSelect('quiz_employees', filter);
  const total = await Q.sbCount('quiz_employees');
  return Q.ok(res, { employees: rows || [], total, limit, offset });
}

// Bulk import. The browser parses the .xlsx/.csv with SheetJS (already used
// elsewhere in this portal), maps the columns, and posts clean rows here —
// so this endpoint validates and persists rather than parsing binaries.
async function employeesImport(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const rows = Array.isArray(body.rows) ? body.rows : [];
  const mode = body.mode === 'REPLACE' ? 'REPLACE' : 'MERGE';
  const dryRun = body.dry_run === true;

  if (!rows.length) throw new Q.AppError('No rows were received.', 400);
  if (rows.length > 5000) throw new Q.AppError('Please import at most 5000 rows per batch.', 413);

  const now = Date.now();
  const issues = [];
  const clean = [];
  const seen = new Set();

  rows.forEach((r, i) => {
    const line = i + 2;                           // +2: header row, 1-based
    const id = String(r.employee_id == null ? '' : r.employee_id).trim();
    const name = String(r.employee_name || '').trim();
    const desig = String(r.designation || '').trim();
    const office = String(r.office || '').trim();

    if (!id)     return issues.push({ line, issue: 'Employee ID is blank', skipped: true });
    if (!name)   return issues.push({ line, employee_id: id, issue: 'Name is blank', skipped: true });
    if (!desig)  return issues.push({ line, employee_id: id, issue: 'Designation is blank', skipped: true });
    if (!office) return issues.push({ line, employee_id: id, issue: 'Office is blank', skipped: true });
    if (!/^[0-9A-Za-z/-]{3,24}$/.test(id)) {
      return issues.push({ line, employee_id: id, issue: 'Employee ID has unexpected characters', skipped: true });
    }
    if (seen.has(id)) {
      return issues.push({ line, employee_id: id, issue: 'Duplicate Employee ID within this file', skipped: true });
    }
    seen.add(id);

    // Category decides which prize list this employee competes in, so an
    // unrecognised spelling is reported rather than stored as-is. The row is
    // still imported — withholding an employee from the directory over a
    // category typo would stop them registering at all.
    const category = Q.normCategory(r.category);
    if (r.category && !category) {
      issues.push({
        line, employee_id: id,
        issue: 'Staff category "' + String(r.category).trim() + '" was not recognised — ' +
               'imported without a category, please set it to Departmental or GDS',
        skipped: false,
      });
    }

    clean.push({
      employee_id: id, employee_name: name, designation: desig, office,
      division: String(r.division || 'Karur Division').trim(),
      category,
      unit: Q.normUnit(r.unit),
      active: r.active === false ? false : true,
      created_at_ms: now, updated_at_ms: now,
    });
  });

  // `skipped` must mean "this row is not in the directory". Category warnings
  // ride along in the same list so the administrator sees them, but they are
  // not failures and must not inflate the skipped count.
  const skippedCount = issues.filter(i => i.skipped).length;

  // Which of these already exist? Needed for an honest inserted/updated split
  // and for safe re-import: running the same file twice updates, never doubles.
  const existingIds = new Set();
  for (const chunk of chunked(clean.map(c => c.employee_id), 150)) {
    const found = await Q.sbSelect('quiz_employees',
      'select=employee_id&employee_id=in.(' + chunk.map(csvQuote).join(',') + ')');
    for (const f of found || []) existingIds.add(f.employee_id);
  }
  const inserted = clean.filter(c => !existingIds.has(c.employee_id)).length;
  const updated = clean.length - inserted;

  // A preview is also where the administrator confirms the two columns that
  // decide the prize lists landed on the right employees, so the category and
  // unit breakdown of the file is reported before anything is written.
  const tally = (field) => {
    const out = {};
    for (const c of clean) {
      const k = c[field] || 'Unspecified';
      out[k] = (out[k] || 0) + 1;
    }
    return out;
  };
  const breakdown = { byCategory: tally('category'), byUnit: tally('unit') };

  if (dryRun) {
    return Q.ok(res, {
      preview: true, mode,
      summary: { total: rows.length, toInsert: inserted, toUpdate: updated, skipped: skippedCount },
      breakdown,
      issues: issues.slice(0, 200),
      sample: clean.slice(0, 20),
    });
  }

  const jobRows = await Q.sbInsert('quiz_import_jobs', {
    kind: 'EMPLOYEES', filename: body.filename || null, mode,
    total_rows: rows.length, inserted, updated, skipped: skippedCount, failed: 0,
    issues: issues.slice(0, 500),
    status: issues.length ? 'PARTIAL' : 'DONE',
    created_by: actor.username, created_ms: now,
  });
  const jobId = jobRows[0].id;

  // REPLACE deactivates rather than deletes: an employee who has already sat
  // an examination must keep their historical registration and result.
  if (mode === 'REPLACE') {
    await Q.sbUpdate('quiz_employees', 'active=is.true',
      { active: false, updated_at_ms: now }, { returning: false });
  }

  // Before migration_v22 the directory has no `unit` column. The import still
  // runs — the roster is more useful than the one field — and `unit` lands on
  // a later re-import once the migration has been applied.
  const canStoreUnit = await Q.columnsReady('quiz_employees');
  for (const chunk of chunked(clean.map(c => {
    const row = Object.assign({}, c, { import_job_id: jobId });
    if (!canStoreUnit) delete row.unit;
    return row;
  }), 200)) {
    await Q.sbInsert('quiz_employees', chunk, { onConflict: 'employee_id', returning: false });
  }

  // Employees who registered BEFORE the roster carried a category would
  // otherwise sit outside both prize lists. Filling the gap is the whole point
  // of re-importing, so it happens here rather than waiting for a second
  // deliberate click — and it only ever fills blanks (see backfillRegistrations).
  const filled = await backfillRegistrations(false);

  await Q.logAudit(req, actor, 'EMPLOYEES_IMPORT', 'quiz_employees', jobId, null,
    { mode, total: rows.length, inserted, updated, skipped: skippedCount,
      backfilled: filled.updated, filename: body.filename || null });

  return Q.ok(res, {
    jobId, mode,
    summary: { total: rows.length, inserted, updated, skipped: skippedCount },
    breakdown,
    backfilled: filled.updated,
    issues: issues.slice(0, 200),
    message: inserted + ' records imported, ' + updated + ' records updated, ' +
             skippedCount + ' rows requiring review' +
             (filled.updated
               ? ', and ' + filled.updated + ' existing registration(s) given their category and unit.'
               : '.'),
  });
}

// Copy category and unit from the directory onto registrations (and onto the
// results already computed from them).
//
// `overwrite === false` — the normal case — writes ONLY where the registration
// has no category or no unit yet. That is what makes it safe to run after every
// import: a registration whose category an administrator corrected by hand, or
// which was recorded under a category the employee has since moved out of, is
// never rewritten. `overwrite === true` is the explicit "re-sync everything
// from the directory" action, which is audited.
async function backfillRegistrations(overwrite) {
  // Nothing to copy into until migration_v22 has run.
  if (!await Q.columnsReady('quiz_registrations') || !await Q.columnsReady('quiz_employees')) {
    return { scanned: 0, updated: 0, unmatched: 0, migrationPending: true };
  }

  const regs = await Q.sbSelect('quiz_registrations',
    'select=id,employee_id,category,unit&limit=5000');
  const targets = (regs || []).filter(r => overwrite || !r.category || !r.unit);
  if (!targets.length) return { scanned: (regs || []).length, updated: 0, unmatched: 0 };

  const empMap = {};
  for (const chunk of chunked([...new Set(targets.map(r => r.employee_id))], 150)) {
    const found = await Q.sbSelect('quiz_employees',
      'select=employee_id,category,unit&employee_id=in.(' + chunk.map(csvQuote).join(',') + ')');
    for (const e of found || []) empMap[e.employee_id] = e;
  }

  const now = Date.now();
  let updated = 0, unmatched = 0;
  for (const r of targets) {
    const e = empMap[r.employee_id];
    if (!e) { unmatched++; continue; }

    const patch = {};
    if ((overwrite || !r.category) && e.category && e.category !== r.category) patch.category = e.category;
    if ((overwrite || !r.unit) && e.unit && e.unit !== r.unit) patch.unit = e.unit;
    if (!Object.keys(patch).length) continue;

    patch.updated_at_ms = now;
    await Q.sbUpdate('quiz_registrations', 'id=' + Q.eq(r.id), patch, { returning: false });

    // A result already computed for this registration carries its own copy, so
    // that a published result sheet stays internally consistent. Keep it level.
    const rp = {};
    if (patch.category) rp.category = patch.category;
    if (patch.unit) rp.unit = patch.unit;
    await Q.sbUpdate('quiz_results', 'registration_id=' + Q.eq(r.id), rp, { returning: false });

    updated++;
  }
  return { scanned: (regs || []).length, updated, unmatched };
}

// The same operation as an explicit, audited administrator action — used when
// the directory has been corrected in place rather than re-imported.
async function registrationsBackfill(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const overwrite = body.overwrite === true;
  const out = await backfillRegistrations(overwrite);

  if (out.migrationPending) {
    throw new Q.AppError(
      'The staff category and unit columns do not exist in the database yet. Run ' +
      'android-app/supabase/migration_v22_quiz_category_unit.sql in the Supabase SQL editor ' +
      'first — it performs this backfill itself.', 409, 'MIGRATION_PENDING');
  }

  await Q.logAudit(req, actor, overwrite ? 'REGISTRATIONS_RESYNC' : 'REGISTRATIONS_BACKFILL',
    'quiz_registrations', null, null, out);

  return Q.ok(res, Object.assign({}, out, {
    message: out.updated + ' registration(s) updated from the employee directory' +
      (out.unmatched ? ', ' + out.unmatched + ' not found in the directory.' : '.'),
  }));
}

// Correcting a designation or office must not disturb anyone's examination
// history: registrations hold their own snapshot, so this only affects future
// registrations and lookups.
async function employeeSave(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.employee_id || '').trim();
  if (!id) throw new Q.AppError('Employee ID is required.', 400);

  const before = await Q.sbOne('quiz_employees', 'select=*&employee_id=' + Q.eq(id));
  const now = Date.now();
  const patch = { updated_at_ms: now };
  for (const f of ['employee_name', 'designation', 'office', 'division']) {
    if (body[f] !== undefined) patch[f] = body[f] === null ? null : String(body[f]).trim();
  }
  // Category and unit go through the same normalisation as an import, so a
  // record corrected by hand and a record imported from Excel are identical.
  if (body.category !== undefined) {
    const c = Q.normCategory(body.category);
    if (body.category && !c) {
      throw new Q.AppError('Staff category must be Departmental or GDS (or left blank).', 400);
    }
    patch.category = c;
  }
  // `category` predates migration_v22 on this table; `unit` does not.
  if (body.unit !== undefined && await Q.columnsReady('quiz_employees')) {
    patch.unit = Q.normUnit(body.unit);
  }
  if (body.active !== undefined) patch.active = !!body.active;

  if (before) {
    await Q.sbUpdate('quiz_employees', 'employee_id=' + Q.eq(id), patch, { returning: false });
  } else {
    if (!patch.employee_name || !patch.designation || !patch.office) {
      throw new Q.AppError('Name, designation and office are required for a new employee.', 400);
    }
    await Q.sbInsert('quiz_employees',
      Object.assign({ employee_id: id, created_at_ms: now }, patch), { returning: false });
  }
  await Q.logAudit(req, actor, before ? 'EMPLOYEE_UPDATE' : 'EMPLOYEE_CREATE',
    'quiz_employees', id, before, patch);
  return Q.ok(res, { saved: true });
}

async function importJobs(req, res) {
  const kind = String((req.query || {}).kind || '').toUpperCase();
  let f = 'select=*&order=created_ms.desc&limit=50';
  if (kind === 'EMPLOYEES' || kind === 'QUESTIONS') f += '&kind=' + Q.eq(kind);
  return Q.ok(res, { jobs: (await Q.sbSelect('quiz_import_jobs', f)) || [] });
}

// ═══════════════════════════════════════════════════════════════════
//  REGISTRATIONS
// ═══════════════════════════════════════════════════════════════════
async function registrationsList(req, res) {
  const qp = req.query || {};
  const limit = clampInt(qp.limit, 1, 2000, 200);
  const offset = clampInt(qp.offset, 0, 1e6, 0);

  let f = 'select=*&order=created_at_ms.desc&limit=' + limit + '&offset=' + offset;
  const hasCategory = await Q.columnsReady('quiz_registrations');

  if (qp.status) f += '&status=' + Q.eq(qp.status);
  if (qp.office) f += '&office=' + Q.eq(qp.office);
  if (hasCategory) {
    if (qp.unit) f += '&unit=' + Q.eq(qp.unit);
    if (qp.category === 'UNSPECIFIED') f += '&category=is.null';
    else if (qp.category)              f += '&category=' + Q.eq(String(qp.category).toUpperCase());
  }
  if (qp.q) {
    const safe = String(qp.q).replace(/[,()*]/g, ' ').trim();
    f += '&or=(employee_id.ilike.*' + encodeURIComponent(safe) + '*,' +
         'employee_name.ilike.*' + encodeURIComponent(safe) + '*,' +
         'reg_code.ilike.*' + encodeURIComponent(safe) + '*,' +
         'office.ilike.*' + encodeURIComponent(safe) + '*,' +
         (hasCategory ? 'unit.ilike.*' + encodeURIComponent(safe) + '*,' : '') +
         'designation.ilike.*' + encodeURIComponent(safe) + '*)';
  }

  const rows = await Q.sbSelect('quiz_registrations', f);

  // Attach the linked account so an administrator can see HOW each employee
  // signed in — a Google account, or the weaker Employee ID fallback.
  const ids = [...new Set((rows || []).map(r => r.identity_id).filter(Boolean))];
  const idMap = {};
  for (const chunk of chunked(ids, 100)) {
    const found = await Q.sbSelect('quiz_identities',
      'select=id,provider,email,email_verified,display_name,last_login_ms&id=in.(' + chunk.join(',') + ')');
    for (const i of found || []) idMap[i.id] = i;
  }

  const total = await Q.sbCount('quiz_registrations');
  return Q.ok(res, {
    registrations: (rows || []).map(r => Object.assign({}, r, { identity: idMap[r.identity_id] || null })),
    total, limit, offset,
  });
}

// ═══════════════════════════════════════════════════════════════════
//  REGISTRATION DASHBOARD
//
//  Who has registered, broken down the way the division actually thinks about
//  itself: by prize category (Departmental / GDS), by unit or sub division,
//  and by both at once. Each line carries the directory strength beside the
//  registration count, because "41 registered" means nothing on its own —
//  41 of 198 and 41 of 45 are very different stories, and the second number is
//  what tells a Sub Divisional Head whether to chase anybody.
//
//  Everything is counted in one pass over two full table reads rather than a
//  count query per bucket: ~800 employees and a few hundred registrations is
//  small, and forty sequential round trips to PostgREST would not be.
// ═══════════════════════════════════════════════════════════════════
async function registrationStats(req, res) {
  // Without migration_v22 there is nothing to break down by, and a generic
  // 500 would leave the administrator guessing. Say exactly what is missing
  // and exactly what to run.
  if (!await Q.columnsReady('quiz_registrations') || !await Q.columnsReady('quiz_employees')) {
    return Q.ok(res, {
      migrationPending: true,
      generatedMs: Date.now(),
      message: 'The staff category and unit columns do not exist in the database yet. ' +
               'Run android-app/supabase/migration_v22_quiz_category_unit.sql in the Supabase ' +
               'SQL editor, then reload this page. Registration and the examination are ' +
               'unaffected in the meantime.',
      totals: null, byCategory: [], byUnit: [], byUnitCategory: [], byOffice: [], byDay: [],
    });
  }

  const [employees, regs] = await Promise.all([
    Q.sbSelect('quiz_employees', 'select=employee_id,office,unit,category,active&limit=5000'),
    Q.sbSelect('quiz_registrations', 'select=employee_id,office,unit,category,status,created_at_ms&limit=5000'),
  ]);

  const active = (employees || []).filter(e => e.active !== false);
  const KEY = v => v || 'Unspecified';

  // One accumulator shape, used for every dimension.
  const blank = () => ({
    eligible: 0, registered: 0,
    approved: 0, pending: 0, rejected: 0, suspended: 0, cancelled: 0,
    departmental: 0, gds: 0, uncategorised: 0,
  });
  const bump = (bucket, reg) => {
    bucket.registered++;
    const s = String(reg.status || '').toLowerCase();
    if (bucket[s] !== undefined) bucket[s]++;
    if (reg.category === 'DEPARTMENTAL') bucket.departmental++;
    else if (reg.category === 'GDS') bucket.gds++;
    else bucket.uncategorised++;
  };

  const byCategory = {}, byUnit = {}, byOffice = {}, byUnitCategory = {};
  for (const code of Q.CATEGORIES.concat(['Unspecified'])) byCategory[code] = blank();

  for (const e of active) {
    const c = e.category || 'Unspecified';
    (byCategory[c] || (byCategory[c] = blank())).eligible++;
    (byUnit[KEY(e.unit)] || (byUnit[KEY(e.unit)] = blank())).eligible++;
    (byOffice[KEY(e.office)] || (byOffice[KEY(e.office)] = blank())).eligible++;
    const uc = KEY(e.unit) + ' │ ' + c;
    (byUnitCategory[uc] || (byUnitCategory[uc] = blank())).eligible++;
  }

  for (const r of regs || []) {
    const c = r.category || 'Unspecified';
    bump(byCategory[c] || (byCategory[c] = blank()), r);
    bump(byUnit[KEY(r.unit)] || (byUnit[KEY(r.unit)] = blank()), r);
    bump(byOffice[KEY(r.office)] || (byOffice[KEY(r.office)] = blank()), r);
    const uc = KEY(r.unit) + ' │ ' + c;
    bump(byUnitCategory[uc] || (byUnitCategory[uc] = blank()), r);
  }

  const shape = (map, labelKey) => Object.entries(map)
    .map(([k, v]) => Object.assign({ [labelKey]: k }, v, {
      coverage: v.eligible ? Math.round((v.registered / v.eligible) * 1000) / 10 : null,
    }))
    .sort((a, b) => b.registered - a.registered || b.eligible - a.eligible);

  // Registrations per day, so the administrator can see whether the campaign
  // is still moving or has gone quiet.
  const byDay = {};
  for (const r of regs || []) {
    const d = new Date(r.created_at_ms).toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
    byDay[d] = (byDay[d] || 0) + 1;
  }

  const statusOf = s => (regs || []).filter(r => r.status === s).length;

  return Q.ok(res, {
    generatedMs: Date.now(),
    totals: {
      directory: (employees || []).length,
      eligible: active.length,
      registered: (regs || []).length,
      approved: statusOf('APPROVED'),
      pending: statusOf('PENDING'),
      rejected: statusOf('REJECTED'),
      suspended: statusOf('SUSPENDED'),
      cancelled: statusOf('CANCELLED'),
      uncategorised: (regs || []).filter(r => !r.category).length,
      coverage: active.length
        ? Math.round(((regs || []).length / active.length) * 1000) / 10 : null,
    },
    byCategory: shape(byCategory, 'category').filter(x => x.eligible || x.registered),
    byUnit: shape(byUnit, 'unit'),
    byUnitCategory: shape(byUnitCategory, 'bucket').filter(x => x.eligible || x.registered),
    byOffice: shape(byOffice, 'office'),
    byDay: Object.entries(byDay).sort().map(([date, count]) => ({ date, count })),
  });
}

async function registrationStatus(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.id || '').trim();
  const status = String(body.status || '').toUpperCase();
  const VALID = ['PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED', 'CANCELLED'];
  if (!id || !VALID.includes(status)) throw new Q.AppError('Invalid registration status change.', 400);

  const before = await Q.sbOne('quiz_registrations', 'select=*&id=' + Q.eq(id));
  if (!before) throw new Q.AppError('Registration not found.', 404);

  const now = Date.now();
  await Q.sbUpdate('quiz_registrations', 'id=' + Q.eq(id), {
    status,
    approved_by: status === 'APPROVED' ? actor.username : before.approved_by,
    approved_at_ms: status === 'APPROVED' ? now : before.approved_at_ms,
    remarks: body.remarks !== undefined ? String(body.remarks || '').slice(0, 500) : before.remarks,
    updated_at_ms: now,
  }, { returning: false });

  await Q.logAudit(req, actor, 'REGISTRATION_' + status, 'quiz_registrations', id,
    { status: before.status }, { status, remarks: body.remarks || null });
  return Q.ok(res, { updated: true });
}

// Bulk approve/reject of the whole pending queue, which is what makes manual
// approval workable for a division of this size.
async function registrationEdit(req, res, actor) {
  const body = await Q.readJsonBody(req);

  if (Array.isArray(body.bulk_ids) && body.bulk_ids.length) {
    const status = String(body.status || '').toUpperCase();
    if (!['APPROVED', 'REJECTED', 'SUSPENDED', 'PENDING'].includes(status)) {
      throw new Q.AppError('Invalid bulk status.', 400);
    }
    const now = Date.now();
    let n = 0;
    for (const chunk of chunked(body.bulk_ids.map(String), 100)) {
      await Q.sbUpdate('quiz_registrations', 'id=in.(' + chunk.join(',') + ')', {
        status,
        approved_by: status === 'APPROVED' ? actor.username : null,
        approved_at_ms: status === 'APPROVED' ? now : null,
        updated_at_ms: now,
      }, { returning: false });
      n += chunk.length;
    }
    await Q.logAudit(req, actor, 'REGISTRATION_BULK_' + status, 'quiz_registrations', null, null, { count: n });
    return Q.ok(res, { updated: n });
  }

  const id = String(body.id || '').trim();
  if (!id) throw new Q.AppError('Registration id is required.', 400);
  const before = await Q.sbOne('quiz_registrations', 'select=*&id=' + Q.eq(id));
  if (!before) throw new Q.AppError('Registration not found.', 404);

  const patch = { updated_at_ms: Date.now() };
  for (const f of ['employee_name', 'designation', 'office', 'remarks']) {
    if (body[f] !== undefined) patch[f] = String(body[f] || '').trim();
  }
  // Moving someone between prize categories is a real decision, so it is
  // validated and audited like any other correction rather than accepted raw.
  if (body.category !== undefined || body.unit !== undefined) {
    if (!await Q.columnsReady('quiz_registrations')) {
      throw new Q.AppError(
        'The staff category and unit columns do not exist in the database yet. Run ' +
        'android-app/supabase/migration_v22_quiz_category_unit.sql in the Supabase SQL ' +
        'editor first.', 409, 'MIGRATION_PENDING');
    }
  }
  if (body.category !== undefined) {
    const c = Q.normCategory(body.category);
    if (body.category && !c) {
      throw new Q.AppError('Staff category must be Departmental or GDS (or left blank).', 400);
    }
    patch.category = c;
  }
  if (body.unit !== undefined) patch.unit = Q.normUnit(body.unit);

  await Q.sbUpdate('quiz_registrations', 'id=' + Q.eq(id), patch, { returning: false });

  // Any result already computed carries its own copy of both fields.
  if (patch.category !== undefined || patch.unit !== undefined) {
    const rp = {};
    if (patch.category !== undefined) rp.category = patch.category;
    if (patch.unit !== undefined) rp.unit = patch.unit;
    await Q.sbUpdate('quiz_results', 'registration_id=' + Q.eq(id), rp, { returning: false });
  }
  await Q.logAudit(req, actor, 'REGISTRATION_EDIT', 'quiz_registrations', id, before, patch);
  return Q.ok(res, { updated: true });
}

// A manually created registration is NOT verified: it has no linked account
// yet, so it stays PENDING and cannot sit the examination until the real
// employee signs in and an administrator links them.
async function registrationAdd(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const empId = String(body.employee_id || '').trim();
  if (!empId) throw new Q.AppError('Employee ID is required.', 400);

  const emp = await Q.sbOne('quiz_employees', 'select=*&employee_id=' + Q.eq(empId));
  if (!emp) throw new Q.AppError('That Employee ID is not in the employee directory.', 404);

  const clash = await Q.sbOne('quiz_registrations', 'select=id&employee_id=' + Q.eq(empId));
  if (clash) throw new Q.AppError('That Employee ID is already registered.', 409);

  const now = Date.now();
  const settings = await Q.getSettings();
  const prefix = (settings.registration || {}).reg_code_prefix || 'NPW2026';

  // A placeholder identity keeps the one-registration-per-identity invariant
  // intact while the row waits to be linked to a real account.
  const idRows = await Q.sbInsert('quiz_identities', {
    provider: 'employee_id', google_sub: null, email: null, email_verified: false,
    display_name: emp.employee_name + ' (manual)', created_at_ms: now,
  });

  const count = await Q.sbCount('quiz_registrations');
  const manual = {
    reg_code: prefix + '-M' + String(count + 1).padStart(4, '0'),
    identity_id: idRows[0].id,
    employee_id: emp.employee_id,
    employee_name: emp.employee_name,
    designation: emp.designation,
    office: emp.office,
    category: emp.category || null,
    unit: emp.unit || null,
    status: 'PENDING',
    roster_verified: true,
    manual_entry: true,
    remarks: 'Created manually by ' + actor.username + ' — awaiting account linking.',
    created_at_ms: now, updated_at_ms: now,
  };
  const rows = await Q.sbInsert('quiz_registrations',
    await Q.columnsReady('quiz_registrations') ? manual : Q.withoutCategory(manual));

  await Q.logAudit(req, actor, 'REGISTRATION_MANUAL_ADD', 'quiz_registrations', rows[0].id, null,
    { employee_id: empId });
  return Q.ok(res, { registration: rows[0] });
}

// Re-assigning an Employee ID to a different account. Deliberately a two-step,
// audited process: unlink here, then the correct employee signs in and
// registers normally. We never silently move an ID onto a new Google account.
async function registrationUnlink(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.id || '').trim();
  const reason = String(body.reason || '').trim();
  if (!id) throw new Q.AppError('Registration id is required.', 400);
  if (reason.length < 5) {
    throw new Q.AppError('Please record a reason — this action is audited.', 400);
  }

  const before = await Q.sbOne('quiz_registrations', 'select=*&id=' + Q.eq(id));
  if (!before) throw new Q.AppError('Registration not found.', 404);

  const attempts = await Q.sbCount('quiz_attempts',
    'registration_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL'));
  if (attempts > 0) {
    throw new Q.AppError(
      'This employee has already sat the examination. Unlinking would orphan an ' +
      'official attempt — suspend the registration instead, and record the correction ' +
      'in the remarks.', 409, 'HAS_ATTEMPTS');
  }

  await Q.sbDelete('quiz_registrations', 'id=' + Q.eq(id));
  await Q.logAudit(req, actor, 'REGISTRATION_UNLINK', 'quiz_registrations', id, before, { reason });
  await Q.logEvent(req, {
    registration_id: id, type: 'REGISTRATION_UNLINKED', severity: 'NOTICE',
    detail: { by: actor.username, reason, employee_id: before.employee_id },
  });
  return Q.ok(res, {
    unlinked: true,
    message: 'Employee ID ' + before.employee_id + ' is free again. Ask the employee to ' +
             'sign in and register; the action has been recorded in the audit log.',
  });
}

// ═══════════════════════════════════════════════════════════════════
//  QUESTION BANK
//
//  A question either belongs to one examination or to the shared bank that
//  any examination may draw from. `scope` is how the caller says which:
//
//    'ALL'     every question, whoever it belongs to — the plain list
//    'SHARED'  the shared bank only (exam_id is null)
//    <uuid>    the question set belonging to that examination
//
//  Everything below — the list, the counts, the category filter, saving,
//  importing, duplicating and deleting — takes the same scope, so the page
//  the administrator is looking at and the rows the action touches can never
//  be two different things.
// ═══════════════════════════════════════════════════════════════════

// Has migration_v23 been run? Until it has, there is no exam_id column, so
// every scope but 'ALL' is a question the database cannot answer.
async function examScopeReady() {
  return Q.columnsReady('quiz_questions', 'exam_id');
}

function normaliseScope(raw) {
  const s = String(raw == null ? '' : raw).trim();
  if (!s || s.toUpperCase() === 'ALL') return 'ALL';
  if (s.toUpperCase() === 'SHARED') return 'SHARED';
  return s;                                   // an examination id
}

// The PostgREST fragment for a scope, as a filter to append to a query.
function scopeFilter(scope) {
  if (scope === 'ALL') return '';
  if (scope === 'SHARED') return '&exam_id=is.null';
  return '&exam_id=' + Q.eq(scope);
}

// The exam_id a row saved into this scope should carry.
function scopeExamId(scope) {
  return (scope === 'ALL' || scope === 'SHARED') ? null : scope;
}

// A scope naming an examination has to name one that exists, or a typo in a
// query string would quietly file questions against nothing.
async function requireScopeExam(scope) {
  if (scope === 'ALL' || scope === 'SHARED') return null;
  const ex = await Q.sbOne('quiz_exams', 'select=id,title,status,question_count&id=' + Q.eq(scope));
  if (!ex) throw new Q.AppError('That examination no longer exists.', 404, 'EXAM_NOT_FOUND');
  return ex;
}

// The next free number for a qcode.
//
// This used to be derived from the row count, which holds only while nothing
// is ever removed. Questions can now be deleted — a set uploaded against the
// wrong examination has to be removable — and after a single deletion the
// count points back at a number already in use, so the next insert would
// collide with a unique qcode and the whole import would fail.
//
// The highest number actually in use is the only safe basis, so that is what
// is asked for. qcode is unique and indexed, so this is one ordered read.
async function nextQcodeNumber() {
  const rows = await Q.sbSelect('quiz_questions',
    'select=qcode&qcode=not.is.null&order=qcode.desc&limit=1');
  const top = rows && rows[0] ? String(rows[0].qcode) : '';
  const m = /^Q(\d+)$/.exec(top);
  if (m) return parseInt(m[1], 10) + 1;
  // A bank whose codes are not in the Q0000 form at all (or an empty one):
  // fall back to the count, which is right for an empty table and no worse
  // than what it replaces for anything else.
  return (await Q.sbCount('quiz_questions')) + 1;
}

const SCOPE_PENDING =
  'Separate question sets per examination need migration_v23_quiz_exam_questions.sql, ' +
  'which has not been run on this database yet. Until it is, every question lives in ' +
  'the one shared bank. Run the migration in the Supabase SQL editor and reload.';

async function questionsList(req, res) {
  const qp = req.query || {};
  const limit = clampInt(qp.limit, 1, 500, 50);
  const offset = clampInt(qp.offset, 0, 1e6, 0);

  const scopeReady = await examScopeReady();
  const scope = scopeReady ? normaliseScope(qp.scope) : 'ALL';
  if (scope !== 'ALL') await requireScopeExam(scope);
  const sf = scopeFilter(scope);

  let f = 'select=*&order=created_at_ms.desc&limit=' + limit + '&offset=' + offset + sf;
  if (qp.status)     f += '&status=' + Q.eq(qp.status);
  if (qp.category)   f += '&category=' + Q.eq(qp.category);
  if (qp.difficulty) f += '&difficulty=' + Q.eq(qp.difficulty);
  if (qp.q) {
    const safe = String(qp.q).replace(/[,()*]/g, ' ').trim();
    f += '&or=(question_text.ilike.*' + encodeURIComponent(safe) + '*,' +
         'qcode.ilike.*' + encodeURIComponent(safe) + '*,' +
         'category.ilike.*' + encodeURIComponent(safe) + '*)';
  }

  const rows = await Q.sbSelect('quiz_questions', f);

  // The counts and the category list describe the scope on screen, not the
  // whole table — a set of 25 questions for one examination should not report
  // the division's entire bank above it.
  const within = tail => (sf ? sf.slice(1) + (tail ? '&' + tail : '') : (tail || ''));
  const [total, active, draft, archived] = await Promise.all([
    Q.sbCount('quiz_questions', within('')),
    Q.sbCount('quiz_questions', within('status=' + Q.eq('ACTIVE'))),
    Q.sbCount('quiz_questions', within('status=' + Q.eq('DRAFT'))),
    Q.sbCount('quiz_questions', within('status=' + Q.eq('ARCHIVED'))),
  ]);

  const cats = await Q.sbSelect('quiz_questions', 'select=category' + sf);
  const categories = [...new Set((cats || []).map(c => c.category).filter(Boolean))].sort();

  return Q.ok(res, {
    questions: rows || [],
    counts: { total, active, draft, archived },
    categories, limit, offset,
    scope, scopeReady,
    scopePending: scopeReady ? null : SCOPE_PENDING,
    scopes: await scopeOptions(scopeReady),
  });
}

// The picker at the top of the Question Bank: the shared bank, then every
// examination with the size of its own set, so an administrator can see at a
// glance which examinations have a paper of their own and which do not.
async function scopeOptions(scopeReady) {
  if (!scopeReady) return [];
  const exams = await Q.sbSelect('quiz_exams',
    'select=id,title,status,question_count&order=created_at_ms.desc&limit=200');
  const tally = await questionTally();
  const of = key => tally[key] || { own: 0, active: 0 };

  const shared = of('SHARED');
  const out = [{
    id: 'SHARED', title: 'Shared bank — available to every examination',
    status: null, question_count: null,
    ownCount: shared.own, activeCount: shared.active,
  }];
  for (const ex of exams || []) {
    const t = of(ex.id);
    out.push({
      id: ex.id, title: ex.title, status: ex.status,
      question_count: ex.question_count,
      ownCount: t.own, activeCount: t.active,
    });
  }
  return out;
}

// How many questions each set holds, and how many of them are active.
//
// Counted in one read rather than two COUNT queries per examination: with the
// 200 examinations the picker allows, the per-examination version was 400
// round trips to draw a drop-down. Only two small columns are fetched.
async function questionTally() {
  const rows = await Q.sbSelect('quiz_questions', 'select=exam_id,status&limit=20000');
  const out = {};
  for (const r of rows || []) {
    const key = r.exam_id || 'SHARED';
    if (!out[key]) out[key] = { own: 0, active: 0 };
    out[key].own++;
    if (r.status === 'ACTIVE') out[key].active++;
  }
  return out;
}

function validateQuestion(body) {
  const text = String(body.question_text || '').trim();
  const opts = {
    option_a: String(body.option_a || '').trim(),
    option_b: String(body.option_b || '').trim(),
    option_c: String(body.option_c || '').trim(),
    option_d: String(body.option_d || '').trim(),
  };
  const correct = String(body.correct_option || '').trim().toUpperCase();
  const errors = [];

  if (text.length < 5) errors.push('Question text is too short.');
  // Exactly four valid options, and exactly one correct answer configured.
  for (const [k, v] of Object.entries(opts)) {
    if (!v) errors.push('Option ' + k.slice(-1).toUpperCase() + ' is blank.');
  }
  const values = Object.values(opts).map(v => v.toLowerCase());
  const dupes = values.filter((v, i) => v && values.indexOf(v) !== i);
  if (dupes.length) errors.push('Two or more options are identical.');
  if (!['A', 'B', 'C', 'D'].includes(correct)) errors.push('Select exactly one correct option (A, B, C or D).');

  return { text, opts, correct, errors };
}

async function questionSave(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const v = validateQuestion(body);
  if (v.errors.length) throw new Q.AppError(v.errors.join(' '), 400, 'VALIDATION');

  const now = Date.now();
  const id = body.id ? String(body.id).trim() : null;
  const hash = Q.questionHash(v.text);

  // Which set this question belongs to. On an edit the scope may be left out,
  // and then the question stays where it is rather than being swept into the
  // shared bank by an omission.
  const scopeReady = await examScopeReady();
  const scopeGiven = scopeReady && body.scope !== undefined;
  const scope = scopeGiven ? normaliseScope(body.scope) : null;
  if (scopeGiven && scope !== 'ALL') await requireScopeExam(scope);

  const fields = {
    question_text: v.text,
    option_a: v.opts.option_a, option_b: v.opts.option_b,
    option_c: v.opts.option_c, option_d: v.opts.option_d,
    correct_option: v.correct,
    explanation: body.explanation ? String(body.explanation).trim() : null,
    source_ref: body.source_ref ? String(body.source_ref).trim() : null,
    category: body.category ? String(body.category).trim() : null,
    difficulty: ['EASY', 'MEDIUM', 'HARD'].includes(String(body.difficulty || '').toUpperCase())
      ? String(body.difficulty).toUpperCase() : 'MEDIUM',
    status: ['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(String(body.status || '').toUpperCase())
      ? String(body.status).toUpperCase() : 'DRAFT',
    dedupe_hash: hash,
    updated_by: actor.username,
    updated_at_ms: now,
  };
  if (scopeGiven) fields.exam_id = scopeExamId(scope);

  if (id) {
    const before = await Q.sbOne('quiz_questions', 'select=*&id=' + Q.eq(id));
    if (!before) throw new Q.AppError('Question not found.', 404);

    // Full modification history, written before the change lands.
    await Q.sbInsert('quiz_question_revisions', {
      question_id: id, version: before.version, snapshot: before,
      change_note: body.change_note ? String(body.change_note).slice(0, 300) : null,
      changed_by: actor.username, changed_at_ms: now,
    }, { returning: false });

    // Warn, but do not block: the published paper is a frozen snapshot, so the
    // edit cannot alter a live attempt either way.
    const usedIn = await Q.sbSelect('quiz_exam_questions',
      'select=exam_id&question_id=' + Q.eq(id));
    const liveExams = [];
    for (const u of usedIn || []) {
      const ex = await Q.sbOne('quiz_exams', 'select=id,title,status&id=' + Q.eq(u.exam_id));
      if (ex && !['DRAFT', 'CANCELLED'].includes(ex.status)) liveExams.push(ex.title);
    }

    await Q.sbUpdate('quiz_questions', 'id=' + Q.eq(id),
      Object.assign({}, fields, { version: before.version + 1 }), { returning: false });
    await Q.logAudit(req, actor, 'QUESTION_UPDATE', 'quiz_questions', id, before, fields);

    return Q.ok(res, {
      saved: true, id, version: before.version + 1,
      notice: liveExams.length
        ? 'Saved. Note: this question is part of the already-published paper for ' +
          liveExams.join(', ') + '. Published papers are frozen, so attempts in progress ' +
          'are unaffected — re-publish the examination to pick up this change.'
        : null,
    });
  }

  // Scoped: the same question may legitimately appear in two different
  // papers, so "already exists" is asked within the set being added to, not
  // across the division's entire bank.
  const dupe = await Q.sbOne('quiz_questions',
    'select=id,qcode&dedupe_hash=' + Q.eq(hash) +
    (scopeGiven ? scopeFilter(scope) : ''));
  const count = (await nextQcodeNumber()) - 1;
  const rows = await Q.sbInsert('quiz_questions', Object.assign({}, fields, {
    qcode: 'Q' + String(count + 1).padStart(4, '0'),
    version: 1, created_by: actor.username, created_at_ms: now,
  }));
  await Q.logAudit(req, actor, 'QUESTION_CREATE', 'quiz_questions', rows[0].id, null, fields);

  return Q.ok(res, {
    saved: true, id: rows[0].id, qcode: rows[0].qcode,
    duplicateWarning: dupe ? 'A very similar question already exists (' + (dupe.qcode || dupe.id) + ').' : null,
  });
}

// Archive rather than delete: a question that has been answered in an
// examination must stay readable for the evaluation record.
async function questionStatus(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const ids = Array.isArray(body.ids) ? body.ids.map(String) : (body.id ? [String(body.id)] : []);
  const status = String(body.status || '').toUpperCase();
  if (!ids.length) throw new Q.AppError('No question was specified.', 400);
  if (!['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(status)) throw new Q.AppError('Invalid status.', 400);

  for (const chunk of chunked(ids, 100)) {
    await Q.sbUpdate('quiz_questions', 'id=in.(' + chunk.join(',') + ')',
      { status, updated_by: actor.username, updated_at_ms: Date.now() }, { returning: false });
  }
  await Q.logAudit(req, actor, 'QUESTION_STATUS', 'quiz_questions', ids.join(','), null, { status, count: ids.length });
  return Q.ok(res, { updated: ids.length });
}

// Duplicating is also how a new examination is seeded from an existing set:
// with a target scope the copy lands in that examination's own questions, and
// its text is kept verbatim because it is a copy into a different paper, not a
// second version of the same one.
async function questionDuplicate(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const ids = Array.isArray(body.ids) ? body.ids.map(String).filter(Boolean)
            : (body.id ? [String(body.id)] : []);
  if (!ids.length) throw new Q.AppError('No question was specified.', 400);

  const scopeReady = await examScopeReady();
  const intoGiven = scopeReady && body.into !== undefined;
  const into = intoGiven ? normaliseScope(body.into) : null;
  const intoExam = intoGiven ? await requireScopeExam(into) : null;

  if (ids.length > 1 || intoGiven) return duplicateMany(req, res, actor, ids, intoGiven, into, intoExam);

  const id = ids[0];
  const src = await Q.sbOne('quiz_questions', 'select=*&id=' + Q.eq(id));
  if (!src) throw new Q.AppError('Question not found.', 404);

  const now = Date.now();
  const count = (await nextQcodeNumber()) - 1;
  const rows = await Q.sbInsert('quiz_questions', {
    qcode: 'Q' + String(count + 1).padStart(4, '0'),
    question_text: src.question_text + ' (copy)',
    option_a: src.option_a, option_b: src.option_b, option_c: src.option_c, option_d: src.option_d,
    correct_option: src.correct_option, explanation: src.explanation, source_ref: src.source_ref,
    category: src.category, difficulty: src.difficulty,
    status: 'DRAFT', dedupe_hash: Q.questionHash(src.question_text + ' (copy)'),
    version: 1, created_by: actor.username, created_at_ms: now,
    updated_by: actor.username, updated_at_ms: now,
    // A copy made in place stays in the set it was copied from. Without this
    // it would land in the shared bank, where an examination's own question
    // would suddenly be offered to every other examination.
    ...(scopeReady ? { exam_id: src.exam_id || null } : {}),
  });
  await Q.logAudit(req, actor, 'QUESTION_DUPLICATE', 'quiz_questions', rows[0].id, { from: id }, null);
  return Q.ok(res, { id: rows[0].id, qcode: rows[0].qcode, copied: 1 });
}

// Copying a selection into another set — the usual way a new examination's
// paper is started from an existing one.
async function duplicateMany(req, res, actor, ids, intoGiven, into, intoExam) {
  const scopeReady = await examScopeReady();
  let src = [];
  for (const chunk of chunked(ids, 100)) {
    const rows = await Q.sbSelect('quiz_questions', 'select=*&id=in.(' + chunk.join(',') + ')');
    src = src.concat(rows || []);
  }
  if (!src.length) throw new Q.AppError('None of those questions could be found.', 404);

  const targetId = intoGiven ? scopeExamId(into) : null;

  // Don't copy what is already there. Re-running "copy the shared bank into
  // this examination" after adding three questions should copy three, not
  // produce a second complete set.
  let present = new Set();
  if (scopeReady && intoGiven) {
    const have = await Q.sbSelect('quiz_questions',
      'select=dedupe_hash' + scopeFilter(into) + '&limit=5000');
    present = new Set((have || []).map(h => h.dedupe_hash).filter(Boolean));
  }

  const now = Date.now();
  const base = (await nextQcodeNumber()) - 1;
  const fresh = [];
  for (const q of src) {
    const hash = q.dedupe_hash || Q.questionHash(q.question_text);
    if (present.has(hash)) continue;
    present.add(hash);
    fresh.push({
      qcode: 'Q' + String(base + fresh.length + 1).padStart(4, '0'),
      question_text: q.question_text,
      option_a: q.option_a, option_b: q.option_b, option_c: q.option_c, option_d: q.option_d,
      correct_option: q.correct_option, explanation: q.explanation, source_ref: q.source_ref,
      category: q.category, difficulty: q.difficulty,
      // Copies arrive as drafts for review, exactly as an import does, so a
      // paper is never filled by questions nobody has looked at in context.
      status: 'DRAFT', dedupe_hash: hash,
      version: 1, created_by: actor.username, created_at_ms: now,
      updated_by: actor.username, updated_at_ms: now,
      ...(scopeReady ? { exam_id: intoGiven ? targetId : (q.exam_id || null) } : {}),
    });
  }

  for (const chunk of chunked(fresh, 100)) {
    await Q.sbInsert('quiz_questions', chunk, { returning: false });
  }
  const setName = intoExam ? intoExam.title : (intoGiven ? 'the shared bank' : 'the same set');
  await Q.logAudit(req, actor, 'QUESTION_DUPLICATE', 'quiz_questions', '', null,
    { requested: ids.length, copied: fresh.length, into: setName });

  return Q.ok(res, {
    copied: fresh.length, skipped: src.length - fresh.length, setName,
    message: fresh.length + ' question(s) copied into ' + setName + ' as drafts' +
      (src.length - fresh.length
        ? '; ' + (src.length - fresh.length) + ' already there and left alone.'
        : '.'),
  });
}

async function questionHistory(req, res) {
  const id = String((req.query || {}).id || '').trim();
  if (!id) throw new Q.AppError('Question id is required.', 400);
  const rows = await Q.sbSelect('quiz_question_revisions',
    'select=*&question_id=' + Q.eq(id) + '&order=version.desc&limit=50');
  return Q.ok(res, { revisions: rows || [] });
}

async function questionsImport(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const rows = Array.isArray(body.rows) ? body.rows : [];
  const dryRun = body.dry_run === true;
  if (!rows.length) throw new Q.AppError('No rows were received.', 400);
  if (rows.length > 2000) throw new Q.AppError('Please import at most 2000 questions per batch.', 413);

  // The set this upload lands in. An upload meant for one examination must
  // never spill into the shared bank, so a named scope that the schema cannot
  // yet honour is refused outright rather than silently widened.
  const scopeReady = await examScopeReady();
  const scope = scopeReady ? normaliseScope(body.scope) : 'ALL';
  if (!scopeReady && body.scope && normaliseScope(body.scope) !== 'ALL') {
    throw new Q.AppError(SCOPE_PENDING, 409, 'MIGRATION_PENDING');
  }
  const scopeExam = await requireScopeExam(scope);
  const examId = scopeExamId(scope);

  const now = Date.now();
  const issues = [];
  const clean = [];
  const hashes = new Set();

  rows.forEach((r, i) => {
    const line = i + 2;
    const v = validateQuestion(r);
    if (v.errors.length) return issues.push({ line, issue: v.errors.join(' '), skipped: true });
    const hash = Q.questionHash(v.text);
    if (hashes.has(hash)) return issues.push({ line, issue: 'Duplicate question within this file', skipped: true });
    hashes.add(hash);
    const row = {
      question_text: v.text,
      option_a: v.opts.option_a, option_b: v.opts.option_b,
      option_c: v.opts.option_c, option_d: v.opts.option_d,
      correct_option: v.correct,
      explanation: r.explanation ? String(r.explanation).trim() : null,
      source_ref: r.source_ref ? String(r.source_ref).trim() : null,
      category: r.category ? String(r.category).trim() : null,
      difficulty: ['EASY', 'MEDIUM', 'HARD'].includes(String(r.difficulty || '').toUpperCase())
        ? String(r.difficulty).toUpperCase() : 'MEDIUM',
      status: body.activate === true ? 'ACTIVE' : 'DRAFT',
      dedupe_hash: hash, version: 1,
      created_by: actor.username, created_at_ms: now,
      updated_by: actor.username, updated_at_ms: now,
    };
    if (scopeReady) row.exam_id = examId;
    clean.push(row);
  });

  // Flag questions that already exist in THIS set, so a re-import does not
  // silently double the paper. Scoped deliberately: the same question in
  // another examination's set is not a duplicate of this one.
  const existing = new Set();
  for (const chunk of chunked([...hashes], 100)) {
    const found = await Q.sbSelect('quiz_questions',
      'select=dedupe_hash&dedupe_hash=in.(' + chunk.map(csvQuote).join(',') + ')' +
      (scopeReady ? scopeFilter(scope) : ''));
    for (const f of found || []) existing.add(f.dedupe_hash);
  }
  const fresh = clean.filter(c => !existing.has(c.dedupe_hash));
  const dupCount = clean.length - fresh.length;

  const setName = scopeExam ? scopeExam.title : 'the shared bank';

  if (dryRun) {
    return Q.ok(res, {
      preview: true, scope, setName,
      summary: { total: rows.length, toInsert: fresh.length, duplicates: dupCount, skipped: issues.length },
      issues: issues.slice(0, 200),
      sample: fresh.slice(0, 10),
    });
  }

  const base = (await nextQcodeNumber()) - 1;
  fresh.forEach((c, i) => { c.qcode = 'Q' + String(base + i + 1).padStart(4, '0'); });

  const jobRows = await Q.sbInsert('quiz_import_jobs', {
    kind: 'QUESTIONS', filename: body.filename || null, mode: 'MERGE',
    total_rows: rows.length, inserted: fresh.length, updated: 0,
    skipped: issues.length + dupCount, failed: 0,
    issues: issues.slice(0, 500),
    status: (issues.length || dupCount) ? 'PARTIAL' : 'DONE',
    created_by: actor.username, created_ms: now,
  });
  const jobId = jobRows[0].id;

  for (const chunk of chunked(fresh.map(c => Object.assign({}, c, { import_job_id: jobId })), 100)) {
    await Q.sbInsert('quiz_questions', chunk, { returning: false });
  }
  await Q.logAudit(req, actor, 'QUESTIONS_IMPORT', 'quiz_questions', jobId, null,
    { total: rows.length, inserted: fresh.length, duplicates: dupCount,
      skipped: issues.length, scope, set: setName });

  return Q.ok(res, {
    jobId, scope, setName,
    summary: { total: rows.length, inserted: fresh.length, duplicates: dupCount, skipped: issues.length },
    issues: issues.slice(0, 200),
    message: fresh.length + ' question(s) imported into ' + setName + ', ' +
             dupCount + ' duplicate(s) skipped, ' +
             issues.length + ' row(s) requiring review.',
  });
}

// Remove questions outright. Archiving is the usual course and stays the
// default in the UI, but a set uploaded to the wrong examination, or a batch
// imported from the wrong file, has no reason to sit in the bank for ever.
//
// A question frozen into a published paper is never deleted — a result refers
// to it, and the evaluation record has to stay readable. Those are reported
// back by name rather than skipped quietly, so the administrator knows which
// ones remain and why.
async function questionsDelete(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const ids = Array.isArray(body.ids) ? body.ids.map(String).filter(Boolean)
            : (body.id ? [String(body.id)] : []);
  if (!ids.length) throw new Q.AppError('No question was specified.', 400);
  if (ids.length > 2000) throw new Q.AppError('Please delete at most 2000 questions at once.', 413);

  const protectedIds = new Set();
  for (const chunk of chunked(ids, 100)) {
    const used = await Q.sbSelect('quiz_exam_questions',
      'select=question_id&question_id=in.(' + chunk.join(',') + ')');
    for (const u of used || []) protectedIds.add(u.question_id);
  }
  const removable = ids.filter(i => !protectedIds.has(i));

  let kept = [];
  if (protectedIds.size) {
    for (const chunk of chunked([...protectedIds], 100)) {
      const rows = await Q.sbSelect('quiz_questions',
        'select=id,qcode&id=in.(' + chunk.join(',') + ')');
      kept = kept.concat((rows || []).map(r => r.qcode || r.id));
    }
  }

  for (const chunk of chunked(removable, 100)) {
    await Q.sbDelete('quiz_questions', 'id=in.(' + chunk.join(',') + ')');
  }
  await Q.logAudit(req, actor, 'QUESTIONS_DELETE', 'quiz_questions', removable.join(','), null,
    { requested: ids.length, deleted: removable.length, kept: kept.length });

  return Q.ok(res, {
    deleted: removable.length, kept: kept.length, keptCodes: kept.slice(0, 50),
    message: removable.length + ' question(s) deleted.' +
      (kept.length
        ? ' ' + kept.length + ' kept: they are frozen into a published paper and a result ' +
          'refers to them. Archive those instead.'
        : ''),
  });
}

// ═══════════════════════════════════════════════════════════════════
//  EXAMINATIONS
// ═══════════════════════════════════════════════════════════════════
async function examsList(req, res) {
  const rows = await Q.sbSelect('quiz_exams', 'select=*&order=created_at_ms.desc');
  const scopeReady = await examScopeReady();
  // One read for every examination's own set, rather than two counts each.
  const tally = scopeReady ? await questionTally() : {};
  const allotReady = await allotmentReady();
  const allotTally = allotReady ? await allotmentTally() : {};
  const out = [];
  for (const ex of rows || []) {
    const frozen = await Q.sbCount('quiz_exam_questions', 'exam_id=' + Q.eq(ex.id));
    // The size of the examination's own set, so the card can say whether this
    // test has a paper of its own or is still sharing the divisional bank.
    const t = tally[ex.id] || { own: 0, active: 0 };
    out.push(Object.assign({}, ex, {
      frozenQuestionCount: frozen,
      ownQuestionCount: t.own, ownActiveCount: t.active,
      allotmentMode: allotReady ? String(ex.allotment_mode || 'ALL').toUpperCase() : 'ALL',
      allottedCount: allotTally[ex.id] || 0,
      maxMarks: Number(ex.positive_marks) * ex.question_count,
    }));
  }
  const settings = await Q.getSettings();
  return Q.ok(res, {
    exams: out, defaults: settings.exam_defaults || {},
    questionScopeReady: scopeReady, allotmentReady: allotReady,
    serverTimeMs: Date.now(),
  });
}

// How many employees are allotted to each examination, in one read.
async function allotmentTally() {
  try {
    const rows = await Q.sbSelect('quiz_exam_allotments', 'select=exam_id&limit=50000');
    const out = {};
    for (const r of rows || []) out[r.exam_id] = (out[r.exam_id] || 0) + 1;
    return out;
  } catch (e) {
    // The table arrives with migration_v24; before that there is nothing to
    // count, and the cards simply say nothing about allotment.
    if (Q.isMissingColumn(e) || (e instanceof Q.DbError && e.status === 404)) return {};
    throw e;
  }
}

async function examSave(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = body.id ? String(body.id).trim() : null;
  const now = Date.now();

  const title = String(body.title || '').trim();
  if (!title) throw new Q.AppError('Examination title is required.', 400);

  const settings = await Q.getSettings();
  const d = settings.exam_defaults || {};
  const qCount = clampInt(body.question_count, 1, 200, d.question_count || 20);
  const minQ = d.min_questions || 1, maxQ = d.max_questions || 200;
  if (qCount < minQ || qCount > maxQ) {
    throw new Q.AppError('Number of questions must be between ' + minQ + ' and ' + maxQ + '.', 400);
  }

  // The per-question timer can drive the overall limit. In AUTO the two can
  // never drift apart, which is the usual cause of a paper whose clock expires
  // before its last question is reached.
  const spq = clampInt(body.seconds_per_question, 5, 1800, d.seconds_per_question || 30);
  const durationMode = String(body.duration_mode || '').toUpperCase() === 'AUTO' ? 'AUTO' : 'FIXED';
  const totalSeconds = durationMode === 'AUTO'
    ? Math.min(24 * 3600, spq * qCount)
    : clampInt(body.total_seconds, 30, 24 * 3600, d.total_seconds || 600);

  const fields = {
    title,
    description: body.description ? String(body.description).trim() : null,
    instructions: body.instructions ? String(body.instructions).trim() : null,
    reg_open_ms: numOrNull(body.reg_open_ms),
    reg_close_ms: numOrNull(body.reg_close_ms),
    exam_open_ms: numOrNull(body.exam_open_ms),
    exam_close_ms: numOrNull(body.exam_close_ms),
    latest_start_ms: numOrNull(body.latest_start_ms),
    question_count: qCount,
    seconds_per_question: spq,
    total_seconds: totalSeconds,
    duration_mode: durationMode,
    positive_marks: numOr(body.positive_marks, d.positive_marks != null ? d.positive_marks : 4),
    negative_marks: numOr(body.negative_marks, d.negative_marks != null ? d.negative_marks : 1),
    unanswered_marks: numOr(body.unanswered_marks, 0),
    attempts_allowed: clampInt(body.attempts_allowed, 1, 10, d.attempts_allowed || 1),
    navigation: body.navigation === 'FREE' ? 'FREE' : 'ONE_WAY',
    allow_skip: body.allow_skip !== false,
    randomize_questions: body.randomize_questions !== false,
    randomize_options: body.randomize_options !== false,
    question_pool: body.question_pool || null,
    eligible_offices: Array.isArray(body.eligible_offices) ? body.eligible_offices : null,
    eligible_designations: Array.isArray(body.eligible_designations) ? body.eligible_designations : null,
    eligible_categories: Array.isArray(body.eligible_categories) ? body.eligible_categories : null,
    late_entry_allowed: body.late_entry_allowed !== false,
    on_close: body.on_close === 'GRACE' ? 'GRACE' : 'AUTO_SUBMIT',
    grace_seconds: clampInt(body.grace_seconds, 0, 3600, 0),
    result_mode: ['AUTO', 'SCHEDULED', 'MANUAL'].includes(String(body.result_mode || '').toUpperCase())
      ? String(body.result_mode).toUpperCase() : 'MANUAL',
    result_publish_ms: numOrNull(body.result_publish_ms),
    show_answer_key: body.show_answer_key === true,
    rank_enabled: body.rank_enabled !== false,
    updated_at_ms: now,
  };

  // Sanity on the window: a closing time before the opening time would make
  // the exam unopenable, which is worth catching here rather than at 10 a.m.
  if (fields.exam_open_ms && fields.exam_close_ms && fields.exam_close_ms <= fields.exam_open_ms) {
    throw new Q.AppError('The examination closing time must be after its opening time.', 400);
  }
  if (fields.latest_start_ms && fields.exam_open_ms && fields.latest_start_ms < fields.exam_open_ms) {
    throw new Q.AppError('The latest permitted start time cannot be before the examination opens.', 400);
  }

  if (id) {
    const before = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
    if (!before) throw new Q.AppError('Examination not found.', 404);
    // Changing scoring or question count after publication would desync the
    // frozen paper and already-running attempts.
    if (!['DRAFT', 'SCHEDULED'].includes(before.status)) {
      const locked = ['question_count', 'positive_marks', 'negative_marks', 'seconds_per_question', 'total_seconds'];
      for (const k of locked) {
        if (String(fields[k]) !== String(before[k])) {
          throw new Q.AppError(
            'This examination is ' + before.status + ', so its question count, timing and ' +
            'marking can no longer be changed. Duplicate it to create a revised examination.',
            409, 'EXAM_LOCKED');
        }
      }
    }
    await Q.sbUpdate('quiz_exams', 'id=' + Q.eq(id), fields, { returning: false });
    await Q.logAudit(req, actor, 'EXAM_UPDATE', 'quiz_exams', id, before, fields);
    return Q.ok(res, { saved: true, id });
  }

  const rows = await Q.sbInsert('quiz_exams', Object.assign({}, fields, {
    status: 'DRAFT', created_by: actor.username, created_at_ms: now,
  }));
  await Q.logAudit(req, actor, 'EXAM_CREATE', 'quiz_exams', rows[0].id, null, fields);
  return Q.ok(res, { saved: true, id: rows[0].id, status: 'DRAFT' });
}

// Dashes of every width collapse to a hyphen and whitespace to a single space,
// so a pasted title and a retyped one compare equal.
function confirmKey(v) {
  return String(v == null ? '' : v)
    .replace(/[‐-―−]/g, '-')
    .replace(/s+/g, ' ')
    .trim()
    .toLowerCase();
}

async function examDuplicate(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const src = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(String(body.id || '')));
  if (!src) throw new Q.AppError('Examination not found.', 404);

  const now = Date.now();
  const copy = Object.assign({}, src);
  delete copy.id;
  copy.title = src.title + ' (copy)';
  copy.status = 'DRAFT';
  copy.published_at_ms = null;
  copy.created_by = actor.username;
  copy.created_at_ms = now;
  copy.updated_at_ms = now;

  const rows = await Q.sbInsert('quiz_exams', copy);
  const newId = rows[0].id;

  // A duplicated examination gets its own copy of the question set, not a
  // share of the original's. Otherwise editing the copy's paper would silently
  // edit the paper of the examination it was duplicated from — and duplicating
  // is the normal way a new test is started from last year's.
  //
  // The frozen paper is deliberately not copied: the duplicate is a DRAFT and
  // must be published in its own right, which is what freezes it.
  let copiedQuestions = 0;
  if (await examScopeReady()) {
    const own = await Q.sbSelect('quiz_questions',
      'select=*&exam_id=' + Q.eq(src.id) + '&limit=5000');
    if ((own || []).length) {
      const base = (await nextQcodeNumber()) - 1;
      const clones = own.map((q, i) => ({
        qcode: 'Q' + String(base + i + 1).padStart(4, '0'),
        question_text: q.question_text,
        option_a: q.option_a, option_b: q.option_b, option_c: q.option_c, option_d: q.option_d,
        correct_option: q.correct_option, explanation: q.explanation, source_ref: q.source_ref,
        category: q.category, difficulty: q.difficulty,
        // Status carries over: a set that was ready to publish stays ready,
        // which is the point of duplicating rather than starting again.
        status: q.status, dedupe_hash: q.dedupe_hash,
        exam_id: newId, version: 1,
        created_by: actor.username, created_at_ms: now,
        updated_by: actor.username, updated_at_ms: now,
      }));
      for (const chunk of chunked(clones, 100)) {
        await Q.sbInsert('quiz_questions', chunk, { returning: false });
      }
      copiedQuestions = clones.length;
    }
  }

  // The candidate list comes with the copy too. A test duplicated for a
  // second batch is nearly always given to the same kind of group, and an
  // empty list on a SELECTED copy would mean nobody could sit it at all.
  let copiedAllotments = 0;
  if (await allotmentReady()) {
    const seats = await Q.sbSelect('quiz_exam_allotments',
      'select=employee_id&exam_id=' + Q.eq(src.id) + '&limit=5000');
    const ids = (seats || []).map(s => s.employee_id);
    if (ids.length) {
      copiedAllotments = ids.length - (await insertAllotments(newId, ids, actor, now)).length;
    }
  }

  await Q.logAudit(req, actor, 'EXAM_DUPLICATE', 'quiz_exams', newId,
    { from: src.id }, { questionsCopied: copiedQuestions, allotmentsCopied: copiedAllotments });

  const parts = [];
  if (copiedQuestions) parts.push('its own set of ' + copiedQuestions + ' question(s)');
  if (copiedAllotments) parts.push(copiedAllotments + ' allotted employee(s)');
  return Q.ok(res, {
    id: newId, questionsCopied: copiedQuestions, allotmentsCopied: copiedAllotments,
    message: parts.length ? 'Duplicated, along with ' + parts.join(' and ') + '.' : 'Duplicated.',
  });
}

// Re-scheduling, separated from the full editor. The window may be adjusted at
// any status — an examination that has to be pushed by an hour on the morning
// itself should not need its paper rebuilt — so this deliberately sidesteps the
// field lock in examSave, which exists to protect the frozen paper and the
// marking, not the calendar.
async function examSchedule(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.id || '').trim();
  const before = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!before) throw new Q.AppError('Examination not found.', 404);

  const openMs   = numOrNull(body.exam_open_ms);
  const closeMs  = numOrNull(body.exam_close_ms);
  const latestMs = numOrNull(body.latest_start_ms);

  if (openMs && closeMs && closeMs <= openMs) {
    throw new Q.AppError('The examination closing time must be after its opening time.', 400);
  }
  if (latestMs && openMs && latestMs < openMs) {
    throw new Q.AppError('The latest permitted start time cannot be before the examination opens.', 400);
  }
  if (latestMs && closeMs && latestMs > closeMs) {
    throw new Q.AppError('The latest permitted start time cannot be after the examination closes.', 400);
  }
  // A window shorter than the paper means nobody can ever finish inside it.
  if (openMs && closeMs && (closeMs - openMs) < before.total_seconds * 1000) {
    throw new Q.AppError(
      'The window is only ' + Math.round((closeMs - openMs) / 60000) + ' minute(s) long, but the ' +
      'paper allows ' + Math.round(before.total_seconds / 60) + ' minute(s). Widen the window or ' +
      'shorten the duration.', 400, 'WINDOW_TOO_SHORT');
  }

  // Moving the opening time once people are already inside would re-gate them
  // mid-attempt, so that particular change is refused rather than silently applied.
  const running = await Q.sbCount('quiz_attempts',
    'exam_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL') + '&status=' + Q.eq('IN_PROGRESS'));
  if (running > 0 && openMs !== before.exam_open_ms) {
    throw new Q.AppError(
      running + ' attempt(s) are in progress, so the opening time can no longer be moved. ' +
      'The closing and latest-start times may still be adjusted.', 409, 'ATTEMPTS_RUNNING');
  }

  const fields = {
    exam_open_ms: openMs,
    exam_close_ms: closeMs,
    latest_start_ms: latestMs,
    late_entry_allowed: body.late_entry_allowed !== false,
    updated_at_ms: Date.now(),
  };

  // Putting a date on a draft is the moment it becomes a scheduled examination,
  // but only once its paper is frozen — otherwise it would appear on dashboards
  // with nothing behind it.
  if (body.set_scheduled === true && before.status === 'DRAFT') {
    if (!openMs) {
      throw new Q.AppError(
        'Set an opening time before scheduling — that is the moment the examination ' +
        'opens itself.', 400, 'NO_SCHEDULE');
    }
    const frozen = await Q.sbCount('quiz_exam_questions', 'exam_id=' + Q.eq(id));
    if (frozen < before.question_count) {
      throw new Q.AppError(
        'Publish the question paper before scheduling — only ' + frozen + ' of ' +
        before.question_count + ' questions are frozen.', 409, 'PAPER_NOT_READY');
    }
    fields.status = 'SCHEDULED';
  }

  await Q.sbUpdate('quiz_exams', 'id=' + Q.eq(id), fields, { returning: false });
  await Q.logAudit(req, actor, 'EXAM_SCHEDULE', 'quiz_exams', id, {
    exam_open_ms: before.exam_open_ms, exam_close_ms: before.exam_close_ms,
    latest_start_ms: before.latest_start_ms, status: before.status,
  }, fields);

  return Q.ok(res, {
    saved: true, id, status: fields.status || before.status,
    message: fields.status === 'SCHEDULED'
      ? 'Scheduled. Participants will see the countdown, and the examination opens itself at the time set.'
      : 'Schedule updated.',
  });
}

// Deleting an examination. Every child table (frozen paper, attempts, answers,
// results) cascades from quiz_exams, so one delete takes the whole tree with it
// — which is precisely why this needs the title typed back and refuses outright
// once real candidates have sat the paper.
async function examDelete(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  // The confirmation exists to make the administrator name which examination is
  // being destroyed, not to test their typing. Titles carry an em dash and
  // non-breaking spaces that no one reproduces by hand, so dashes and runs of
  // whitespace are normalised before the comparison; the words still have to match.
  if (confirmKey(body.confirm_title) !== confirmKey(ex.title)) {
    throw new Q.AppError(
      'The title did not match. Copy it from the dialog and paste it in to confirm.',
      400, 'CONFIRM_TITLE_MISMATCH');
  }

  const running = await Q.sbCount('quiz_attempts',
    'exam_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL') + '&status=' + Q.eq('IN_PROGRESS'));
  if (running > 0) {
    throw new Q.AppError(
      running + ' attempt(s) are in progress. Cancel the examination and let them finish before ' +
      'deleting it.', 409, 'ATTEMPTS_RUNNING');
  }

  const official = await Q.sbCount('quiz_attempts',
    'exam_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL'));
  if (official > 0 && body.force !== true) {
    throw new Q.AppError(
      official + ' employee(s) have sat this examination. Deleting it would destroy their ' +
      'attempts, answers and results for good. Cancel it instead, or pass force to override ' +
      'deliberately.', 409, 'ALREADY_ATTEMPTED');
  }

  // The audit line is written first and on purpose: once the row is gone there
  // is nothing left to record what was removed.
  await Q.logAudit(req, actor, 'EXAM_DELETE', 'quiz_exams', id, ex,
    { official_attempts_destroyed: official, forced: body.force === true });

  await Q.sbDelete('quiz_exams', 'id=' + Q.eq(id));
  return Q.ok(res, {
    deleted: true, id,
    message: 'Examination deleted' + (official ? ', along with ' + official + ' attempt(s).' : '.'),
  });
}

// Pre-flight before publishing: does the bank actually hold enough valid,
// active questions to fill this paper?
// Where this examination's paper is drawn from.
//
// If the examination has questions of its own, the paper comes only from
// them — that is what "a question set per examination" means, and it is the
// whole point of being able to upload a paper against one test. If it has
// none, it falls back to the shared bank filtered by its category and
// difficulty policy, which is how every examination behaved before.
//
// Returned as a description rather than a filter string so that validate,
// publish and the UI all say the same thing about the same examination.
async function paperSource(ex) {
  const pool = ex.question_pool || {};
  const scopeReady = await examScopeReady();

  const ownCount = scopeReady
    ? await Q.sbCount('quiz_questions', 'exam_id=' + Q.eq(ex.id))
    : 0;

  if (ownCount > 0) {
    return {
      own: true, ownCount, scopeReady,
      filter: '&exam_id=' + Q.eq(ex.id),
      label: 'this examination\'s own question set',
    };
  }

  let filter = '';
  if (scopeReady) filter += '&exam_id=is.null';
  if (Array.isArray(pool.categories) && pool.categories.length) {
    filter += '&category=in.(' + pool.categories.map(csvQuote).join(',') + ')';
  }
  if (Array.isArray(pool.difficulties) && pool.difficulties.length) {
    filter += '&difficulty=in.(' + pool.difficulties.map(csvQuote).join(',') + ')';
  }
  return {
    own: false, ownCount: 0, scopeReady, filter,
    label: 'the shared question bank',
  };
}

async function examValidate(req, res) {
  const id = String((req.query || {}).id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  const source = await paperSource(ex);
  const f = 'select=id,correct_option,option_a,option_b,option_c,option_d&status=' +
    Q.eq('ACTIVE') + source.filter;
  const available = await Q.sbSelect('quiz_questions', f + '&limit=5000');

  const malformed = (available || []).filter(q =>
    !q.option_a || !q.option_b || !q.option_c || !q.option_d ||
    !['A', 'B', 'C', 'D'].includes(q.correct_option));

  const usable = (available || []).length - malformed.length;
  const frozen = await Q.sbCount('quiz_exam_questions', 'exam_id=' + Q.eq(id));

  const checks = [];
  // Which set the paper will come from, stated before anything is counted —
  // an administrator reading "12 usable of 25 required" has to know whether
  // that is the shared bank or this examination's own questions.
  checks.push({
    label: 'Paper is drawn from ' + source.label,
    ok: true, informational: true,
    detail: source.own
      ? source.ownCount + ' question(s) belong to this examination. The shared bank ' +
        'is not used, and no other examination\'s questions can reach this paper.'
      : (source.scopeReady
          ? 'This examination has no questions of its own, so it falls back to the shared ' +
            'bank. Upload a set against it in the Question Bank to give it its own paper.'
          : 'Separate sets per examination need migration_v23; until it is run every ' +
            'examination draws from the one shared bank.'),
  });
  checks.push({ label: 'Question set has enough active questions',
    ok: usable >= ex.question_count,
    detail: usable + ' usable of ' + ex.question_count + ' required, in ' + source.label +
      (usable < ex.question_count && source.own
        ? '. Add more questions to this examination, or activate the drafts already in its set.'
        : '') });
  checks.push({ label: 'Every question has four options and one key',
    ok: malformed.length === 0,
    detail: malformed.length ? malformed.length + ' malformed question(s)' : 'All valid' });
  checks.push({ label: 'Examination window is set',
    ok: !!(ex.exam_open_ms && ex.exam_close_ms),
    detail: ex.exam_open_ms ? Q.istString(ex.exam_open_ms) + ' to ' + Q.istString(ex.exam_close_ms) : 'Not set' });
  checks.push({ label: 'Overall duration fits the window',
    ok: !(ex.exam_open_ms && ex.exam_close_ms) || (ex.exam_close_ms - ex.exam_open_ms) >= ex.total_seconds * 1000,
    detail: 'Duration ' + Math.round(ex.total_seconds / 60) + ' min' });
  // The per-question budget and the overall limit are two clocks over the same
  // paper, and whichever expires first wins. If the per-question budget totals
  // more than the overall limit, the last questions cannot be reached at all by
  // anyone who uses their time — the old check compared against half the
  // duration and so passed exactly the papers that are wrong this way.
  const perQuestionTotal = ex.seconds_per_question * ex.question_count;
  const mins = sec => Math.round(sec / 60 * 10) / 10 + ' min';
  checks.push({
    label: 'Every question can be reached within the overall time',
    ok: perQuestionTotal <= ex.total_seconds,
    detail: perQuestionTotal <= ex.total_seconds
      ? ex.seconds_per_question + ' s x ' + ex.question_count + ' = ' + mins(perQuestionTotal) +
        ', within the ' + mins(ex.total_seconds) + ' allowed'
      : ex.seconds_per_question + ' s x ' + ex.question_count + ' = ' + mins(perQuestionTotal) +
        ', but only ' + mins(ex.total_seconds) + ' is allowed. A candidate using the full time per ' +
        'question runs out with about ' +
        Math.ceil((perQuestionTotal - ex.total_seconds) / ex.seconds_per_question) +
        ' question(s) unseen. Raise the duration to ' + mins(perQuestionTotal) +
        ' (or set the time limit to Automatic), or lower the per-question time to ' +
        Math.floor(ex.total_seconds / ex.question_count) + ' s.',
  });

  // The last candidate admitted should still get the whole paper. Their clock
  // is capped by the closing time, so a late cut-off quietly shortens it.
  if (ex.latest_start_ms && ex.exam_close_ms) {
    const latestFinish = ex.latest_start_ms + ex.total_seconds * 1000;
    checks.push({
      label: 'The last candidate admitted still gets the full paper',
      ok: latestFinish <= ex.exam_close_ms,
      detail: latestFinish <= ex.exam_close_ms
        ? 'Starting at ' + Q.istString(ex.latest_start_ms) + ' still finishes by close'
        : 'Starting at ' + Q.istString(ex.latest_start_ms) + ' would need until ' +
          Q.istString(latestFinish) + ', but the paper closes at ' +
          Q.istString(ex.exam_close_ms) + '. Move the closing time later, or the latest start earlier.',
    });
  }

  // Who may sit it. A restricted examination with nobody allotted is the one
  // configuration that looks finished and admits no one, so it is a gating
  // check rather than a note.
  if (await allotmentReady()) {
    const mode = String(ex.allotment_mode || 'ALL').toUpperCase();
    const seats = mode === 'SELECTED'
      ? await Q.sbCount('quiz_exam_allotments', 'exam_id=' + Q.eq(ex.id)) : 0;
    checks.push({
      label: 'Somebody can actually sit this examination',
      ok: mode !== 'SELECTED' || seats > 0,
      detail: mode === 'SELECTED'
        ? (seats
            ? 'Allotted to ' + seats + ' named employee(s); nobody else can sit it.'
            : 'This examination is restricted to selected employees, but none have been ' +
              'allotted — so nobody can sit it. Use "Who can attempt" to add them, or set ' +
              'it back to open.')
        : 'Open to everyone its office and designation rules allow.',
    });
  }

  checks.push({ label: 'Question paper frozen for this examination',
    ok: frozen >= ex.question_count,
    informational: true,
    detail: frozen ? frozen + ' questions frozen' : 'Not yet published' });

  return Q.ok(res, {
    exam: ex, checks,
    // Every gating check, however many there are — a positional slice silently
    // stopped covering them the moment one was added.
    ready: checks.filter(c => !c.informational).every(c => c.ok),
    availableQuestions: usable, frozenQuestionCount: frozen,
    paperSource: { own: source.own, ownCount: source.ownCount, label: source.label },
  });
}

// Publishing freezes an immutable copy of the paper. From this moment the
// general question bank and the examination are independent.
async function examPublish(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  const running = await Q.sbCount('quiz_attempts',
    'exam_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL') + '&status=' + Q.eq('IN_PROGRESS'));
  if (running > 0) {
    throw new Q.AppError(
      running + ' attempt(s) are in progress. Re-publishing now would change the paper ' +
      'under their feet. Wait for them to finish, or cancel the examination.', 409, 'ATTEMPTS_RUNNING');
  }
  const completed = await Q.sbCount('quiz_attempts',
    'exam_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL') + '&status=neq.IN_PROGRESS');
  if (completed > 0 && body.force !== true) {
    throw new Q.AppError(
      completed + ' employee(s) have already sat this examination. Re-publishing would ' +
      'replace the paper while their results refer to the old one. Duplicate the ' +
      'examination instead, or pass force to override deliberately.', 409, 'ALREADY_ATTEMPTED');
  }

  // The same rule validate reports: its own set if it has one, otherwise the
  // shared bank. Both read it from one place so the paper that is frozen can
  // never come from a different pool than the one just validated.
  const source = await paperSource(ex);
  const f = 'select=*&status=' + Q.eq('ACTIVE') + source.filter;
  const available = (await Q.sbSelect('quiz_questions', f + '&limit=5000')) || [];
  const usable = available.filter(q =>
    q.option_a && q.option_b && q.option_c && q.option_d &&
    ['A', 'B', 'C', 'D'].includes(q.correct_option));

  if (usable.length < ex.question_count) {
    throw new Q.AppError(
      'Only ' + usable.length + ' usable active question(s) are available in ' + source.label +
      ', but this examination needs ' + ex.question_count + '. ' +
      (source.own
        ? 'Add more questions to this examination\'s set, or activate the drafts in it.'
        : 'Add or activate more questions first.'),
      409, 'NOT_ENOUGH_QUESTIONS');
  }

  // Explicit selection wins; otherwise a random draw from the eligible pool.
  let picked;
  if (Array.isArray(body.question_ids) && body.question_ids.length) {
    const want = body.question_ids.map(String);
    picked = want.map(qid => usable.find(q => q.id === qid)).filter(Boolean);
    if (picked.length !== want.length) {
      throw new Q.AppError('One or more selected questions are not active or not valid.', 400);
    }
    if (picked.length !== ex.question_count) {
      throw new Q.AppError('Select exactly ' + ex.question_count + ' questions.', 400);
    }
  } else {
    picked = Q.secureShuffle(usable).slice(0, ex.question_count);
  }

  const now = Date.now();
  await Q.sbDelete('quiz_exam_questions', 'exam_id=' + Q.eq(id));
  await Q.sbInsert('quiz_exam_questions', picked.map((q, i) => ({
    exam_id: id, seq: i + 1, question_id: q.id,
    snapshot: {
      question_text: q.question_text,
      option_a: q.option_a, option_b: q.option_b, option_c: q.option_c, option_d: q.option_d,
      correct_option: q.correct_option,
      explanation: q.explanation, source_ref: q.source_ref,
      category: q.category, difficulty: q.difficulty,
      qcode: q.qcode, version: q.version,
    },
    frozen_at_ms: now,
  })), { returning: false });

  const status = body.status && ['SCHEDULED', 'LIVE', 'REGISTRATION_OPEN'].includes(String(body.status).toUpperCase())
    ? String(body.status).toUpperCase() : 'SCHEDULED';

  await Q.sbUpdate('quiz_exams', 'id=' + Q.eq(id),
    { status, published_at_ms: now, updated_at_ms: now }, { returning: false });

  await Q.logAudit(req, actor, 'EXAM_PUBLISH', 'quiz_exams', id,
    { status: ex.status }, { status, questions: picked.length,
      source: source.label, forced: body.force === true });

  return Q.ok(res, {
    published: true, status, questionCount: picked.length,
    paperSource: { own: source.own, label: source.label },
    message: picked.length + ' question(s) frozen from ' + source.label +
             '. The examination is ' + status +
             ' and opens at ' + Q.istString(ex.exam_open_ms) + ' IST.',
  });
}

async function examStatus(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.id || '').trim();
  const status = String(body.status || '').toUpperCase();
  const VALID = ['DRAFT', 'SCHEDULED', 'REGISTRATION_OPEN', 'REGISTRATION_CLOSED',
                 'LIVE', 'COMPLETED', 'RESULTS_PUBLISHED', 'CANCELLED'];
  if (!VALID.includes(status)) throw new Q.AppError('Invalid examination status.', 400);

  const before = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!before) throw new Q.AppError('Examination not found.', 404);

  // Scheduled means scheduled. Without an opening time there is nothing for
  // participants to wait for, and the gate would let them straight in. LIVE is
  // left alone deliberately: it is the administrator saying 'open it now'.
  if (status === 'SCHEDULED' && !before.exam_open_ms) {
    throw new Q.AppError(
      'Set the date and time first — use Schedule. An examination marked Scheduled ' +
      'with no opening time would be open to everyone immediately.', 409, 'NO_SCHEDULE');
  }

  if (status !== 'DRAFT' && status !== 'CANCELLED') {
    const frozen = await Q.sbCount('quiz_exam_questions', 'exam_id=' + Q.eq(id));
    if (frozen < before.question_count) {
      throw new Q.AppError(
        'Publish the question paper first — only ' + frozen + ' of ' + before.question_count +
        ' questions are frozen for this examination.', 409, 'PAPER_NOT_READY');
    }
  }

  await Q.sbUpdate('quiz_exams', 'id=' + Q.eq(id),
    { status, updated_at_ms: Date.now() }, { returning: false });
  await Q.logAudit(req, actor, 'EXAM_STATUS', 'quiz_exams', id, { status: before.status }, { status });
  return Q.ok(res, { updated: true, status });
}

// ═══════════════════════════════════════════════════════════════════
//  EXAMINATION ALLOTMENT — naming who may sit a paper
//
//  Two states, held on the examination itself rather than inferred:
//
//    ALL       the office and designation rules decide, as they always have
//    SELECTED  only the employees named in quiz_exam_allotments may sit it
//
//  SELECTED with an empty list means nobody, not everybody. An administrator
//  restricting an examination and an administrator who has not finished
//  choosing look identical from the data, so the mode is what is obeyed.
// ═══════════════════════════════════════════════════════════════════

// Has migration_v24 been run?
async function allotmentReady() {
  return Q.columnsReady('quiz_exams', 'allotment_mode');
}

const ALLOTMENT_PENDING =
  'Allotting an examination to named employees needs ' +
  'migration_v24_quiz_exam_allotments.sql, which has not been run on this database yet. ' +
  'Until it is, every examination stays open under its office and designation rules. ' +
  'Run the migration in the Supabase SQL editor and reload.';

async function examAllotment(req, res) {
  const qp = req.query || {};
  const id = String(qp.id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  const ready = await allotmentReady();
  if (!ready) {
    return Q.ok(res, {
      ready: false, pending: ALLOTMENT_PENDING,
      mode: 'ALL', allotted: [], candidates: [], counts: { allotted: 0, candidates: 0 },
    });
  }

  const rows = await Q.sbSelect('quiz_exam_allotments',
    'select=employee_id,allotted_by,allotted_at_ms&exam_id=' + Q.eq(id) + '&limit=5000');
  const ids = (rows || []).map(r => r.employee_id);

  // The allotted employees, with the directory details that make a list of
  // numbers readable. Fetched in chunks because an Employee ID list can be
  // longer than one filter comfortably holds.
  let detail = [];
  for (const chunk of chunked(ids, 100)) {
    const emps = await Q.sbSelect('quiz_employees',
      'select=employee_id,employee_name,designation,office,category,unit,active' +
      '&employee_id=in.(' + chunk.map(csvQuote).join(',') + ')');
    detail = detail.concat(emps || []);
  }
  const byId = {};
  for (const d of detail) byId[d.employee_id] = d;

  const allotted = (rows || []).map(r => Object.assign(
    { employee_id: r.employee_id, allotted_by: r.allotted_by, allotted_at_ms: r.allotted_at_ms },
    byId[r.employee_id] || { employee_name: '(not in the directory)', active: false }
  )).sort((a, b) => String(a.employee_name || '').localeCompare(String(b.employee_name || '')));

  // The pool to choose from, for the picker. Capped: the picker searches
  // rather than scrolling a whole division.
  const search = String(qp.q || '').trim();
  let cf = 'select=employee_id,employee_name,designation,office,category,unit,active' +
    '&active=is.true&order=employee_name.asc&limit=' + clampInt(qp.limit, 1, 1000, 300);
  if (qp.office)   cf += '&office=' + Q.eq(qp.office);
  if (qp.category) cf += '&category=' + Q.eq(String(qp.category).toUpperCase());
  if (qp.unit && await Q.columnsReady('quiz_employees')) cf += '&unit=' + Q.eq(qp.unit);
  if (search) {
    const safe = search.replace(/[,()*]/g, ' ').trim();
    cf += '&or=(employee_id.ilike.*' + encodeURIComponent(safe) + '*,' +
          'employee_name.ilike.*' + encodeURIComponent(safe) + '*,' +
          'designation.ilike.*' + encodeURIComponent(safe) + '*,' +
          'office.ilike.*' + encodeURIComponent(safe) + '*)';
  }
  const candidates = await Q.sbSelect('quiz_employees', cf);

  return Q.ok(res, {
    ready: true,
    exam: { id: ex.id, title: ex.title, status: ex.status },
    mode: String(ex.allotment_mode || 'ALL').toUpperCase(),
    allotted,
    allottedIds: ids,
    candidates: candidates || [],
    counts: { allotted: ids.length, candidates: (candidates || []).length },
  });
}

async function examAllotmentSave(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const id = String(body.id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  if (!await allotmentReady()) throw new Q.AppError(ALLOTMENT_PENDING, 409, 'MIGRATION_PENDING');

  // Changing who may sit a paper while people are sitting it would end an
  // attempt in progress. The list is therefore frozen for the duration, the
  // same way the paper itself is.
  const running = await Q.sbCount('quiz_attempts',
    'exam_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL') + '&status=' + Q.eq('IN_PROGRESS'));
  if (running > 0 && body.force !== true) {
    throw new Q.AppError(
      running + ' attempt(s) are in progress. Changing the allotment now could remove ' +
      'somebody who is part-way through the paper. Wait for them to finish, or pass force ' +
      'to override deliberately.', 409, 'ATTEMPTS_RUNNING');
  }

  const now = Date.now();
  const before = String(ex.allotment_mode || 'ALL').toUpperCase();
  const mode = body.mode !== undefined
    ? (String(body.mode).toUpperCase() === 'SELECTED' ? 'SELECTED' : 'ALL')
    : before;

  const add    = Array.isArray(body.add)    ? [...new Set(body.add.map(String).filter(Boolean))]    : [];
  const remove = Array.isArray(body.remove) ? [...new Set(body.remove.map(String).filter(Boolean))] : [];
  // `replace` sets the list outright, which is what the Select-all and
  // bulk-add buttons send.
  const replace = Array.isArray(body.replace)
    ? [...new Set(body.replace.map(String).filter(Boolean))] : null;

  let added = 0, removed = 0, unknown = [];

  if (replace) {
    const existing = await Q.sbSelect('quiz_exam_allotments',
      'select=employee_id&exam_id=' + Q.eq(id) + '&limit=5000');
    const have = new Set((existing || []).map(r => r.employee_id));
    const want = new Set(replace);
    const toAdd = replace.filter(e => !have.has(e));
    const toDrop = [...have].filter(e => !want.has(e));
    unknown = await insertAllotments(id, toAdd, actor, now);
    added = toAdd.length - unknown.length;
    removed = await deleteAllotments(id, toDrop);
  } else {
    if (add.length) {
      unknown = await insertAllotments(id, add, actor, now);
      added = add.length - unknown.length;
    }
    if (remove.length) removed = await deleteAllotments(id, remove);
  }

  if (mode !== before) {
    await Q.sbUpdate('quiz_exams', 'id=' + Q.eq(id),
      { allotment_mode: mode, updated_at_ms: now }, { returning: false });
  }

  const total = await Q.sbCount('quiz_exam_allotments', 'exam_id=' + Q.eq(id));
  await Q.logAudit(req, actor, 'EXAM_ALLOTMENT', 'quiz_exams', id,
    { allotment_mode: before }, { allotment_mode: mode, added, removed, total });

  return Q.ok(res, {
    saved: true, mode, added, removed, total,
    unknown: unknown.slice(0, 50),
    // The one state worth warning about: restricted, and nobody on the list.
    warning: mode === 'SELECTED' && total === 0
      ? 'This examination is now restricted to selected employees, but nobody has been ' +
        'allotted yet — so no one can sit it. Add the candidates, or set it back to open.'
      : (unknown.length
          ? unknown.length + ' Employee ID(s) are not in the directory and were not allotted. ' +
            'Add them under Employees first.'
          : null),
    message: mode === 'SELECTED'
      ? total + ' employee(s) may sit this examination.'
      : 'This examination is open to everyone its office and designation rules allow.',
  });
}

// Allot only employees who exist in the directory. An Employee ID that is not
// there is reported back rather than inserted — the foreign key would reject
// it anyway, and a rejection that names the ID is far more use than a 409.
async function insertAllotments(examId, ids, actor, now) {
  if (!ids.length) return [];
  let known = new Set();
  for (const chunk of chunked(ids, 100)) {
    const found = await Q.sbSelect('quiz_employees',
      'select=employee_id&employee_id=in.(' + chunk.map(csvQuote).join(',') + ')');
    for (const f of found || []) known.add(f.employee_id);
  }
  const unknown = ids.filter(i => !known.has(i));
  const rows = ids.filter(i => known.has(i)).map(employee_id => ({
    exam_id: examId, employee_id,
    allotted_by: actor.username, allotted_at_ms: now,
  }));
  for (const chunk of chunked(rows, 100)) {
    // Upsert semantics by primary key: re-adding somebody already allotted is
    // a no-op, so uploading the same list twice cannot fail.
    await Q.sbInsert('quiz_exam_allotments', chunk,
      { returning: false, onConflict: 'exam_id,employee_id' });
  }
  return unknown;
}

async function deleteAllotments(examId, ids) {
  if (!ids.length) return 0;
  let n = 0;
  for (const chunk of chunked(ids, 100)) {
    await Q.sbDelete('quiz_exam_allotments',
      'exam_id=' + Q.eq(examId) + '&employee_id=in.(' + chunk.map(csvQuote).join(',') + ')');
    n += chunk.length;
  }
  return n;
}

// Preview the frozen question paper WITH the key. QUIZ_EDITOR and above only,
// and the fetch is recorded — an answer key read is worth an audit line.
async function examPaper(req, res, actor) {
  const id = String((req.query || {}).id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  const rows = await Q.sbSelect('quiz_exam_questions',
    'select=seq,question_id,snapshot&exam_id=' + Q.eq(id) + '&order=seq.asc');
  await Q.logAudit(req, actor, 'EXAM_PAPER_VIEW', 'quiz_exams', id, null, { questions: (rows || []).length });

  return Q.ok(res, {
    exam: ex,
    questions: (rows || []).map(r => Object.assign({ seq: r.seq, question_id: r.question_id }, r.snapshot)),
  });
}

async function examMonitor(req, res) {
  const id = String((req.query || {}).id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(id));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  const attempts = await Q.sbSelect('quiz_attempts',
    'select=id,registration_id,employee_id,status,started_ms,deadline_ms,current_seq,question_count,submitted_ms' +
    '&exam_id=' + Q.eq(id) + '&mode=' + Q.eq('OFFICIAL') + '&order=started_ms.desc&limit=1000');

  const regIds = [...new Set((attempts || []).map(a => a.registration_id).filter(Boolean))];
  const regMap = {};
  for (const chunk of chunked(regIds, 100)) {
    const regs = await Q.sbSelect('quiz_registrations',
      'select=id,employee_name,employee_id,office,designation&id=in.(' + chunk.join(',') + ')');
    for (const r of regs || []) regMap[r.id] = r;
  }

  const now = Date.now();
  return Q.ok(res, {
    exam: ex, serverTimeMs: now,
    attempts: (attempts || []).map(a => Object.assign({}, a, {
      employee: regMap[a.registration_id] || null,
      remainingMs: a.status === 'IN_PROGRESS' ? Math.max(0, a.deadline_ms - now) : 0,
      progress: a.question_count ? Math.round(((a.current_seq - 1) / a.question_count) * 100) : 0,
      overdue: a.status === 'IN_PROGRESS' && a.deadline_ms <= now,
    })),
  });
}

// ═══════════════════════════════════════════════════════════════════
//  ADMIN TEST MODE
//
//  A real dry run: the attempt is created here, but it is driven through the
//  very same participant endpoints, so the timers, locking, auto-submit and
//  scoring under test are the ones employees will meet. TEST attempts carry
//  mode='TEST' and are excluded from every official count, leaderboard and
//  attempt allowance.
// ═══════════════════════════════════════════════════════════════════
async function testStart(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const examId = String(body.exam_id || '').trim();
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(examId));
  if (!ex) throw new Q.AppError('Examination not found.', 404);

  const pool = await Q.sbSelect('quiz_exam_questions',
    'select=seq,question_id&exam_id=' + Q.eq(examId) + '&order=seq.asc');
  if (!pool || pool.length < ex.question_count) {
    throw new Q.AppError(
      'Publish the question paper before running a test attempt — ' +
      (pool ? pool.length : 0) + ' of ' + ex.question_count + ' questions are frozen.',
      409, 'PAPER_NOT_READY');
  }

  // Abandon any earlier dry run by this admin, so Run Test Attempt is always
  // a clean start and never consumes anything.
  const old = await Q.sbSelect('quiz_attempts',
    'select=id&exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('TEST') +
    '&actor_label=' + Q.eq(actor.username) + '&status=' + Q.eq('IN_PROGRESS'));
  for (const o of old || []) {
    await Q.sbUpdate('quiz_attempts', 'id=' + Q.eq(o.id),
      { status: 'VOID', finalized_ms: Date.now() }, { returning: false });
  }

  const now = Date.now();
  // A dry run ignores the examination window entirely — testing before the
  // scheduled start is the whole point. It still honours the real duration.
  const chosen = (ex.randomize_questions ? Q.secureShuffle(pool) : pool).slice(0, ex.question_count);
  const rows = await Q.sbInsert('quiz_attempts', {
    exam_id: examId,
    registration_id: null,
    employee_id: null,
    actor_label: actor.username,
    mode: 'TEST',
    attempt_no: 1,
    status: 'IN_PROGRESS',
    started_ms: now,
    deadline_ms: now + ex.total_seconds * 1000,
    current_seq: 1,
    question_count: chosen.length,
    seconds_per_question: ex.seconds_per_question,
    positive_marks: ex.positive_marks,
    negative_marks: ex.negative_marks,
    unanswered_marks: ex.unanswered_marks,
    navigation: ex.navigation,
    allow_skip: ex.allow_skip,
    ip: Q.clientIp(req),
    user_agent: (req.headers['user-agent'] || '').slice(0, 300),
  });
  const attempt = rows[0];

  await Q.sbInsert('quiz_attempt_questions', chosen.map((p, i) => ({
    attempt_id: attempt.id, seq: i + 1, exam_seq: p.seq, question_id: p.question_id,
    option_order: ex.randomize_options ? Q.secureShuffle(['A', 'B', 'C', 'D']).join('') : 'ABCD',
    locked: false,
  })), { returning: false });

  await Q.logAudit(req, actor, 'TEST_ATTEMPT_START', 'quiz_attempts', attempt.id, null,
    { exam_id: examId, questions: chosen.length });

  return Q.ok(res, { attemptId: attempt.id, testMode: true, serverTimeMs: now });
}

async function testReset(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const examId = String(body.exam_id || '').trim();
  let f = 'mode=' + Q.eq('TEST') + '&actor_label=' + Q.eq(actor.username);
  if (examId) f += '&exam_id=' + Q.eq(examId);

  const mine = await Q.sbSelect('quiz_attempts', 'select=id&' + f);
  // Cascades clear the assigned questions, answers and test results.
  for (const chunk of chunked((mine || []).map(a => a.id), 50)) {
    await Q.sbDelete('quiz_attempts', 'id=in.(' + chunk.join(',') + ')');
  }
  await Q.logAudit(req, actor, 'TEST_ATTEMPT_RESET', 'quiz_attempts', examId || null, null,
    { cleared: (mine || []).length });
  return Q.ok(res, { cleared: (mine || []).length });
}

// ═══════════════════════════════════════════════════════════════════
//  RESULTS
// ═══════════════════════════════════════════════════════════════════
async function resultsList(req, res) {
  const qp = req.query || {};
  const examId = String(qp.exam_id || '').trim();
  if (!examId) throw new Q.AppError('An examination must be specified.', 400);

  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(examId));
  const ranked = await rankedResultsFor(examId);

  // Each prize category is ranked in its own right — rank 1 Departmental and
  // rank 1 GDS are two different prizes, and neither is second to the other.
  return Q.ok(res, {
    exam: ex, results: ranked, total: ranked.length,
    leaderboard: buildLeaderboard(ranked, clampInt(qp.top, 1, 50, 3)),
  });
}

// Official results for one examination, each carrying its registration, its
// category and unit, its overall rank and its rank within its own category.
// Shared by the results table and by every score report, so a figure that
// appears on screen and the same figure in the exported sheet cannot drift.
async function rankedResultsFor(examId) {
  const rows = await Q.sbSelect('quiz_results',
    'select=*&exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('OFFICIAL') +
    '&order=score.desc,elapsed_seconds.asc&limit=3000');

  // Before migration_v22 the ranking still works — there is simply one
  // category, "Unspecified", and the overall order is unchanged.
  const hasCategory = await Q.columnsReady('quiz_registrations');

  const regMap = {};
  for (const chunk of chunked([...new Set((rows || []).map(r => r.registration_id).filter(Boolean))], 100)) {
    const regs = await Q.sbSelect('quiz_registrations',
      'select=id,reg_code,employee_id,employee_name,designation,office' +
      (hasCategory ? ',category,unit' : '') + '&id=in.(' + chunk.join(',') + ')');
    for (const r of regs || []) regMap[r.id] = r;
  }

  const ranked = rankWithin((rows || []).map(r => {
    const e = regMap[r.registration_id] || null;
    return Object.assign({}, r, {
      employee: e,
      category: r.category || (e && e.category) || null,
      unit: r.unit || (e && e.unit) || null,
    });
  }));

  const eligible = ranked.filter(r => !r.withheld);
  for (const code of Q.CATEGORIES.concat(['UNSPECIFIED'])) {
    const subset = rankWithin(eligible.filter(r => (r.category || 'UNSPECIFIED') === code));
    const by = {};
    for (const r of subset) by[r.attempt_id] = r.rank;
    for (const r of ranked) {
      if ((r.category || 'UNSPECIFIED') !== code) continue;
      r.categoryRank = by[r.attempt_id] || null;
      r.categoryOutOf = subset.length;
    }
  }
  return ranked;
}

// Rank: score descending, then elapsed time ascending. Equal score AND equal
// time genuinely share a rank, and the rank after a tie skips accordingly
// (1, 2, 2, 4) — which is how a prize list has to read.
function rankWithin(list) {
  const sorted = list.slice().sort((a, b) =>
    Number(b.score) - Number(a.score) || (a.elapsed_seconds || 0) - (b.elapsed_seconds || 0));
  let lastKey = null, lastRank = 0;
  return sorted.map((r, i) => {
    const key = r.score + '|' + r.elapsed_seconds;
    const rank = key === lastKey ? lastRank : i + 1;
    lastKey = key; lastRank = rank;
    return Object.assign({}, r, { rank });
  });
}

// The two published prize lists. `top` is the number of PLACES, not rows: if
// two people tie for second, both appear and the list is four names long for a
// top-3 — dropping the tie would misstate the result.
//
// A withheld result is under administrative review and cannot win a prize, so
// it is left out of the prize list and out of its ranking. It stays visible in
// the full results table above, flagged, which is where it belongs.
function buildLeaderboard(ranked, top) {
  const out = [];
  const eligible = ranked.filter(r => !r.withheld);
  for (const code of Q.CATEGORIES.concat(['UNSPECIFIED'])) {
    const subset = rankWithin(eligible.filter(r => (r.category || 'UNSPECIFIED') === code));
    if (!subset.length) continue;
    out.push({
      category: code,
      label: code === 'UNSPECIFIED' ? 'Unspecified' : Q.categoryLabel(code),
      participants: subset.length,
      average: Math.round((subset.reduce((a, b) => a + Number(b.score), 0) / subset.length) * 100) / 100,
      highest: Math.max.apply(null, subset.map(r => Number(r.score))),
      winners: subset.filter(r => r.rank <= top).map(r => {
        const e = r.employee || {};
        return {
          rank: r.rank,
          attemptId: r.attempt_id,
          regCode: e.reg_code || '',
          employeeId: e.employee_id || r.employee_id || '',
          employeeName: e.employee_name || '',
          designation: e.designation || '',
          office: e.office || '',
          unit: r.unit || '',
          score: Number(r.score),
          maxMarks: Number(r.max_marks),
          percentage: Number(r.percentage),
          elapsedSeconds: r.elapsed_seconds,
          published: !!r.published,
          withheld: !!r.withheld,
        };
      }),
    });
  }
  return out;
}

async function resultsPublish(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const examId = String(body.exam_id || '').trim();
  const publish = body.publish !== false;
  if (!examId) throw new Q.AppError('An examination must be specified.', 400);

  const now = Date.now();
  let filter = 'exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('OFFICIAL');
  if (Array.isArray(body.attempt_ids) && body.attempt_ids.length) {
    filter += '&attempt_id=in.(' + body.attempt_ids.map(String).join(',') + ')';
  }

  await Q.sbUpdate('quiz_results', filter,
    { published: publish, published_ms: publish ? now : null }, { returning: false });

  if (publish && !body.attempt_ids) {
    await Q.sbUpdate('quiz_exams', 'id=' + Q.eq(examId),
      { status: 'RESULTS_PUBLISHED', updated_at_ms: now }, { returning: false });
  }
  await Q.logAudit(req, actor, publish ? 'RESULTS_PUBLISH' : 'RESULTS_UNPUBLISH',
    'quiz_exams', examId, null, { scoped: !!body.attempt_ids });

  return Q.ok(res, {
    updated: true,
    message: publish
      ? 'Results are now visible to participants.'
      : 'Results have been hidden from participants.',
  });
}

async function resultWithhold(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const attemptId = String(body.attempt_id || '').trim();
  const withheld = body.withheld !== false;
  if (!attemptId) throw new Q.AppError('An attempt must be specified.', 400);
  if (withheld && String(body.reason || '').trim().length < 5) {
    throw new Q.AppError('Please record a reason for withholding this result.', 400);
  }

  await Q.sbUpdate('quiz_results', 'attempt_id=' + Q.eq(attemptId),
    { withheld, recalc_note: withheld ? String(body.reason).slice(0, 300) : null }, { returning: false });
  await Q.logAudit(req, actor, withheld ? 'RESULT_WITHHOLD' : 'RESULT_RELEASE',
    'quiz_results', attemptId, null, { reason: body.reason || null });
  return Q.ok(res, { updated: true });
}

// Documented correction process: re-score from the stored answers and the
// frozen policy on the attempt. Nothing is invented, and the old figures are
// written to the audit log before being replaced.
async function resultsRecalculate(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const examId = String(body.exam_id || '').trim();
  const note = String(body.note || '').trim();
  if (!examId) throw new Q.AppError('An examination must be specified.', 400);
  if (note.length < 5) throw new Q.AppError('Please record why the results are being recalculated.', 400);

  // The frozen paper is fetched ONCE and indexed by its sequence. Looking the
  // key up per answer instead would be one database round trip per answer —
  // tens of thousands for a division-wide examination, which would simply time
  // out part-way through and leave results half re-scored.
  const paper = await Q.sbSelect('quiz_exam_questions',
    'select=seq,snapshot&exam_id=' + Q.eq(examId) + '&order=seq.asc');
  const keyBySeq = {};
  for (const p of paper || []) {
    keyBySeq[p.seq] = String((p.snapshot || {}).correct_option || '');
  }
  if (!Object.keys(keyBySeq).length) {
    throw new Q.AppError('This examination has no frozen question paper to re-score against.', 409);
  }

  const attempts = await Q.sbSelect('quiz_attempts',
    'select=*&exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('OFFICIAL') + '&status=neq.IN_PROGRESS&limit=3000');

  let changed = 0, corrections = 0;
  const now = Date.now();

  for (const a of attempts || []) {
    const answers = await Q.sbSelect('quiz_answers',
      'select=seq,chosen_original,chosen_slot,is_correct&attempt_id=' + Q.eq(a.id));
    const qs = await Q.sbSelect('quiz_attempt_questions',
      'select=seq,exam_seq&attempt_id=' + Q.eq(a.id));
    const examSeqBySeq = {};
    for (const q of qs || []) examSeqBySeq[q.seq] = q.exam_seq;

    // Re-judge each stored answer against the current key. The participant's
    // chosen option is never altered — only our verdict on it.
    const rejudged = [];
    for (const ans of answers || []) {
      if (!ans.chosen_slot) { rejudged.push(ans); continue; }
      const key = keyBySeq[examSeqBySeq[ans.seq]];
      const isCorrect = !!key && ans.chosen_original === key;
      rejudged.push(Object.assign({}, ans, { is_correct: isCorrect }));
      if (isCorrect !== !!ans.is_correct) {
        await Q.sbUpdate('quiz_answers',
          'attempt_id=' + Q.eq(a.id) + '&seq=eq.' + ans.seq,
          { is_correct: isCorrect }, { returning: false });
        corrections++;
      }
    }

    const tally = Q.computeScore(a, rejudged);
    const before = await Q.sbOne('quiz_results', 'select=*&attempt_id=' + Q.eq(a.id));

    await Q.sbInsert('quiz_results', Object.assign({
      attempt_id: a.id, exam_id: examId, registration_id: a.registration_id,
      employee_id: a.employee_id, mode: a.mode,
      // Publication state and elapsed time are preserved: a re-score corrects
      // the marks, it does not re-open or re-time the attempt.
      elapsed_seconds: before ? before.elapsed_seconds :
        Math.max(0, Math.round(((a.submitted_ms || now) - a.started_ms) / 1000)),
      published: before ? before.published : false,
      withheld: before ? before.withheld : false,
      recalc_note: note,
      computed_ms: now,
      published_ms: before ? before.published_ms : null,
    }, tally), { onConflict: 'attempt_id', returning: false });

    if (!before || Number(before.score) !== tally.score) changed++;
  }

  await Q.logAudit(req, actor, 'RESULTS_RECALCULATE', 'quiz_exams', examId, null,
    { note, attempts: (attempts || []).length, changed, answer_corrections: corrections });

  return Q.ok(res, {
    recalculated: (attempts || []).length, changed, corrections,
    message: (attempts || []).length + ' attempt(s) re-scored, ' + changed +
             ' result(s) changed, ' + corrections + ' answer verdict(s) corrected.',
  });
}

// ═══════════════════════════════════════════════════════════════════
//  REPORTS
//
//  Each report returns rows + column order; the browser turns them into
//  .xlsx with SheetJS, which this portal already loads elsewhere.
// ═══════════════════════════════════════════════════════════════════
async function report(req, res, actor) {
  const qp = req.query || {};
  const kind = String(qp.kind || '').toLowerCase();
  const examId = String(qp.exam_id || '').trim();

  const need = () => {
    if (!examId) throw new Q.AppError('An examination must be specified for this report.', 400);
  };

  let columns = [], rows = [], title = '';

  // Reports sort by unit so that a printed sheet groups itself. Before
  // migration_v22 that column does not exist, so the ordering falls back to
  // office — the report is still correct, just grouped one level coarser.
  const hasUnit = await Q.columnsReady('quiz_registrations');
  const byUnitThen = hasUnit ? 'unit.asc,office.asc,' : 'office.asc,';

  if (kind === 'registrations') {
    title = 'Registered Employees';
    columns = ['Registration ID', 'Employee ID', 'Name', 'Designation', 'Category',
               'Unit / Sub Division', 'Office', 'Status',
               'Sign-in Method', 'Email', 'Registered On (IST)'];
    let rf = 'select=*&order=' + byUnitThen + 'employee_name.asc&limit=3000';
    if (hasUnit) {
      if (qp.category === 'UNSPECIFIED') rf += '&category=is.null';
      else if (qp.category)              rf += '&category=' + Q.eq(String(qp.category).toUpperCase());
      if (qp.unit) rf += '&unit=' + Q.eq(qp.unit);
    }
    const regs = await Q.sbSelect('quiz_registrations', rf);
    const idMap = {};
    for (const chunk of chunked([...new Set((regs || []).map(r => r.identity_id).filter(Boolean))], 100)) {
      const found = await Q.sbSelect('quiz_identities', 'select=id,provider,email&id=in.(' + chunk.join(',') + ')');
      for (const i of found || []) idMap[i.id] = i;
    }
    rows = (regs || []).map(r => {
      const i = idMap[r.identity_id] || {};
      return [r.reg_code, r.employee_id, r.employee_name, r.designation,
              Q.categoryLabel(r.category), r.unit || '', r.office, r.status,
              i.provider === 'google' ? 'Google' : 'Employee ID', i.email || '', Q.istString(r.created_at_ms)];
    });

  } else if (kind === 'approved') {
    title = 'Approved Participants';
    columns = ['Registration ID', 'Employee ID', 'Name', 'Designation', 'Category',
               'Unit / Sub Division', 'Office', 'Approved By', 'Approved On (IST)'];
    const regs = await Q.sbSelect('quiz_registrations',
      'select=*&status=' + Q.eq('APPROVED') + '&order=' + byUnitThen + 'employee_name.asc&limit=3000');
    rows = (regs || []).map(r => [r.reg_code, r.employee_id, r.employee_name, r.designation,
      Q.categoryLabel(r.category), r.unit || '', r.office,
      r.approved_by || '', Q.istString(r.approved_at_ms)]);

  } else if (kind === 'directory') {
    title = 'Employee Directory';
    columns = ['Employee ID', 'Name', 'Designation', 'Category', 'Unit / Sub Division',
               'Office', 'Division', 'Active', 'Registered'];
    const emps = await Q.sbSelect('quiz_employees',
      'select=*&order=' + byUnitThen + 'employee_name.asc&limit=5000');
    const regs = await Q.sbSelect('quiz_registrations', 'select=employee_id,status&limit=5000');
    const regMap = {};
    for (const r of regs || []) regMap[r.employee_id] = r.status;
    rows = (emps || []).map(e => [e.employee_id, e.employee_name, e.designation,
      Q.categoryLabel(e.category), e.unit || '', e.office, e.division || '',
      e.active === false ? 'No' : 'Yes', regMap[e.employee_id] || 'Not registered']);

  } else if (kind === 'category-registrations' || kind === 'unit-registrations') {
    // Registration monitoring, which is a pre-examination report: it must work
    // before any examination exists, so neither variant calls need().
    const byUnitWise = kind === 'unit-registrations';
    title = byUnitWise ? 'Unit / Sub Division-wise Registration' : 'Category-wise Registration';
    if (!hasUnit) {
      throw new Q.AppError(
        'This report needs the staff category and unit columns. Run ' +
        'android-app/supabase/migration_v22_quiz_category_unit.sql in the Supabase SQL ' +
        'editor first.', 409, 'MIGRATION_PENDING');
    }
    const [emps, regs] = await Promise.all([
      Q.sbSelect('quiz_employees', 'select=unit,category,active&limit=5000'),
      Q.sbSelect('quiz_registrations', 'select=unit,category,status&limit=5000'),
    ]);

    const acc = {};
    const at = k => acc[k] || (acc[k] = { eligible: 0, registered: 0, approved: 0, pending: 0,
      departmental: 0, gds: 0, uncategorised: 0 });
    const keyOf = r => byUnitWise ? (r.unit || 'Unspecified') : Q.categoryLabel(r.category);

    for (const e of emps || []) { if (e.active !== false) at(keyOf(e)).eligible++; }
    for (const r of regs || []) {
      const b = at(keyOf(r));
      b.registered++;
      if (r.status === 'APPROVED') b.approved++;
      if (r.status === 'PENDING') b.pending++;
      if (r.category === 'DEPARTMENTAL') b.departmental++;
      else if (r.category === 'GDS') b.gds++;
      else b.uncategorised++;
    }

    columns = byUnitWise
      ? ['Unit / Sub Division', 'Employees on Roll', 'Registered', 'Departmental', 'GDS',
         'Uncategorised', 'Approved', 'Pending', 'Registration %']
      : ['Category', 'Employees on Roll', 'Registered', 'Approved', 'Pending', 'Registration %'];

    rows = Object.entries(acc).map(([k, v]) => byUnitWise
      ? [k, v.eligible, v.registered, v.departmental, v.gds, v.uncategorised, v.approved, v.pending,
         v.eligible ? Math.round((v.registered / v.eligible) * 1000) / 10 : 0]
      : [k, v.eligible, v.registered, v.approved, v.pending,
         v.eligible ? Math.round((v.registered / v.eligible) * 1000) / 10 : 0])
      .sort((a, b) => b[2] - a[2]);

  } else if (kind === 'attendance') {
    need();
    title = 'Attendance and Non-attendance';
    columns = ['Employee ID', 'Name', 'Designation', 'Category', 'Unit / Sub Division', 'Office',
               'Registration', 'Attended', 'Attempt Status', 'Started (IST)', 'Submitted (IST)'];
    const regs = await Q.sbSelect('quiz_registrations',
      'select=*&status=' + Q.eq('APPROVED') + '&order=' + byUnitThen + 'employee_name.asc&limit=3000');
    const attempts = await Q.sbSelect('quiz_attempts',
      'select=registration_id,status,started_ms,submitted_ms&exam_id=' + Q.eq(examId) +
      '&mode=' + Q.eq('OFFICIAL') + '&limit=3000');
    const aMap = {};
    for (const a of attempts || []) aMap[a.registration_id] = a;
    rows = (regs || []).map(r => {
      const a = aMap[r.id];
      return [r.employee_id, r.employee_name, r.designation, Q.categoryLabel(r.category),
        r.unit || '', r.office, r.reg_code,
        a ? 'Yes' : 'No', a ? a.status : 'ABSENT',
        a ? Q.istString(a.started_ms) : '', a && a.submitted_ms ? Q.istString(a.submitted_ms) : ''];
    });

  } else if (kind === 'scores') {
    need();
    const only = String(qp.category || '').toUpperCase();
    title = only && only !== 'ALL'
      ? 'Scores — ' + (only === 'UNSPECIFIED' ? 'Unspecified' : Q.categoryLabel(only))
      : 'Scores';
    columns = ['Overall Rank', 'Category Rank', 'Category', 'Employee ID', 'Name', 'Designation',
               'Unit / Sub Division', 'Office', 'Attempted', 'Correct',
               'Wrong', 'Unanswered', 'Positive Marks', 'Negative Marks', 'Score', 'Out Of',
               'Percentage', 'Time Taken (s)', 'Published'];
    let ranked = await rankedResultsFor(examId);
    if (only && only !== 'ALL') {
      ranked = ranked.filter(r => (r.category || 'UNSPECIFIED') === only);
    }
    rows = ranked.map(r => {
      const e = r.employee || {};
      return [r.rank, r.categoryRank || '', Q.categoryLabel(r.category),
        e.employee_id || r.employee_id || '', e.employee_name || '', e.designation || '',
        r.unit || '', e.office || '', r.attempted, r.correct, r.wrong, r.unanswered,
        Number(r.positive_earned), Number(r.negative_deducted), Number(r.score),
        Number(r.max_marks), Number(r.percentage), r.elapsed_seconds,
        r.withheld ? 'Withheld' : (r.published ? 'Yes' : 'No')];
    });

  } else if (kind === 'leaderboard') {
    need();
    // The prize list as it is meant to be read out: Departmental first, GDS
    // second, each numbered from 1, ties shown in full.
    const top = clampInt(qp.top, 1, 50, 3);
    title = 'Leaderboard — Top ' + top + ' by Category';
    columns = ['Category', 'Rank', 'Registration ID', 'Employee ID', 'Name', 'Designation',
               'Unit / Sub Division', 'Office', 'Score', 'Out Of', 'Percentage',
               'Time Taken (s)', 'Published'];
    const board = buildLeaderboard(await rankedResultsFor(examId), top);
    for (const group of board) {
      for (const w of group.winners) {
        rows.push([group.label, w.rank, w.regCode, w.employeeId, w.employeeName, w.designation,
          w.unit, w.office, w.score, w.maxMarks, w.percentage, w.elapsedSeconds,
          w.published ? 'Yes' : 'No']);
      }
    }

  } else if (kind === 'category-participation' || kind === 'category-average' ||
             kind === 'unit-participation' || kind === 'unit-average') {
    need();
    const byUnitWise = kind.startsWith('unit');
    const byAverage = kind.endsWith('average');
    const dimension = byUnitWise ? 'Unit / Sub Division' : 'Category';
    title = dimension + (byAverage ? '-wise Average Scores' : '-wise Participation');

    const regs = await Q.sbSelect('quiz_registrations',
      'select=id,category,unit&status=' + Q.eq('APPROVED') + '&limit=3000');
    const ranked = await rankedResultsFor(examId);
    const resByReg = {};
    for (const r of ranked) resByReg[r.registration_id] = r;

    const acc = {};
    const keyOf = r => byUnitWise ? (r.unit || 'Unspecified') : Q.categoryLabel(r.category);
    for (const r of regs || []) {
      const k = keyOf(r);
      const b = acc[k] || (acc[k] = { eligible: 0, attended: 0, scores: [] });
      b.eligible++;
      const got = resByReg[r.id];
      if (got) { b.attended++; b.scores.push(Number(got.score)); }
    }

    if (byAverage) {
      columns = [dimension, 'Participants', 'Average Score', 'Highest Score', 'Lowest Score'];
      rows = Object.entries(acc).filter(([, v]) => v.scores.length)
        .map(([k, v]) => [k, v.scores.length,
          Math.round((v.scores.reduce((a, b) => a + b, 0) / v.scores.length) * 100) / 100,
          Math.max.apply(null, v.scores), Math.min.apply(null, v.scores)])
        .sort((a, b) => b[2] - a[2]);
    } else {
      columns = [dimension, 'Approved Employees', 'Attended', 'Absent', 'Participation %'];
      rows = Object.entries(acc).map(([k, v]) => [k, v.eligible, v.attended,
        v.eligible - v.attended, v.eligible ? Math.round((v.attended / v.eligible) * 1000) / 10 : 0])
        .sort((a, b) => b[4] - a[4]);
    }

  } else if (kind === 'office-participation' || kind === 'office-average') {
    need();
    const byAverage = kind === 'office-average';
    title = byAverage ? 'Office-wise Average Scores' : 'Office-wise Participation';
    const regs = await Q.sbSelect('quiz_registrations',
      'select=id,office,status&status=' + Q.eq('APPROVED') + '&limit=3000');
    const results = await Q.sbSelect('quiz_results',
      'select=registration_id,score,percentage&exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('OFFICIAL') + '&limit=3000');
    const rMap = {};
    for (const r of results || []) rMap[r.registration_id] = r;

    const byOffice = {};
    for (const r of regs || []) {
      const o = byOffice[r.office] || (byOffice[r.office] = { eligible: 0, attended: 0, scores: [] });
      o.eligible++;
      const res2 = rMap[r.id];
      if (res2) { o.attended++; o.scores.push(Number(res2.score)); }
    }
    if (byAverage) {
      columns = ['Office', 'Participants', 'Average Score', 'Highest Score', 'Lowest Score'];
      rows = Object.entries(byOffice).filter(([, v]) => v.scores.length)
        .map(([office, v]) => [office, v.scores.length,
          Math.round((v.scores.reduce((a, b) => a + b, 0) / v.scores.length) * 100) / 100,
          Math.max.apply(null, v.scores), Math.min.apply(null, v.scores)])
        .sort((a, b) => b[2] - a[2]);
    } else {
      columns = ['Office', 'Approved Employees', 'Attended', 'Absent', 'Participation %'];
      rows = Object.entries(byOffice).map(([office, v]) => [office, v.eligible, v.attended,
        v.eligible - v.attended, Math.round((v.attended / v.eligible) * 1000) / 10])
        .sort((a, b) => b[4] - a[4]);
    }

  } else if (kind === 'question-stats') {
    need();
    title = 'Question-wise Statistics';
    columns = ['Seq', 'Question Code', 'Question', 'Correct Option', 'Served', 'Answered',
               'Correct', 'Wrong', 'Unanswered', 'Accuracy %'];
    const paper = await Q.sbSelect('quiz_exam_questions',
      'select=seq,question_id,snapshot&exam_id=' + Q.eq(examId) + '&order=seq.asc');
    const attempts = await Q.sbSelect('quiz_attempts',
      'select=id&exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('OFFICIAL') + '&limit=3000');
    const attemptIds = (attempts || []).map(a => a.id);

    // Map each attempt's own sequence back to the paper's sequence, because
    // every participant saw the questions in their own randomised order.
    const stats = {};
    for (const chunk of chunked(attemptIds, 50)) {
      const aqs = await Q.sbSelect('quiz_attempt_questions',
        'select=attempt_id,seq,exam_seq,served_ms&attempt_id=in.(' + chunk.join(',') + ')&limit=60000');
      const ans = await Q.sbSelect('quiz_answers',
        'select=attempt_id,seq,chosen_slot,is_correct&attempt_id=in.(' + chunk.join(',') + ')&limit=60000');
      const aKey = {};
      for (const a of ans || []) aKey[a.attempt_id + ':' + a.seq] = a;
      for (const q of aqs || []) {
        const s = stats[q.exam_seq] || (stats[q.exam_seq] = { served: 0, correct: 0, wrong: 0, unanswered: 0 });
        if (q.served_ms) s.served++;
        const a = aKey[q.attempt_id + ':' + q.seq];
        if (a && a.chosen_slot) { if (a.is_correct) s.correct++; else s.wrong++; }
        else if (q.served_ms) s.unanswered++;
      }
    }
    rows = (paper || []).map(p => {
      const s = stats[p.seq] || { served: 0, correct: 0, wrong: 0, unanswered: 0 };
      const answered = s.correct + s.wrong;
      return [p.seq, (p.snapshot || {}).qcode || '', (p.snapshot || {}).question_text || '',
        (p.snapshot || {}).correct_option || '', s.served, answered, s.correct, s.wrong,
        s.unanswered, answered ? Math.round((s.correct / answered) * 1000) / 10 : 0];
    });

  } else if (kind === 'suspicious') {
    title = 'Suspicious Activity Review';
    columns = ['When (IST)', 'Severity', 'Type', 'Employee', 'Attempt', 'Detail', 'IP'];
    let f = 'select=*&order=created_ms.desc&limit=2000&severity=in.("NOTICE","SUSPECT")';
    if (examId) f += '&exam_id=' + Q.eq(examId);
    const events = await Q.sbSelect('quiz_events', f);
    const regMap = {};
    for (const chunk of chunked([...new Set((events || []).map(e => e.registration_id).filter(Boolean))], 100)) {
      const regs = await Q.sbSelect('quiz_registrations',
        'select=id,employee_id,employee_name&id=in.(' + chunk.join(',') + ')');
      for (const r of regs || []) regMap[r.id] = r;
    }
    rows = (events || []).map(e => {
      const r = regMap[e.registration_id] || {};
      return [Q.istString(e.created_ms), e.severity, e.type,
        r.employee_name ? r.employee_name + ' (' + r.employee_id + ')' : '',
        e.attempt_id || '', JSON.stringify(e.detail || {}), e.ip || ''];
    });

  } else if (kind === 'audit') {
    title = 'Examination Audit History';
    columns = ['When (IST)', 'Administrator', 'Role', 'Action', 'Entity', 'Entity ID', 'IP'];
    const rowsA = await Q.sbSelect('quiz_audit', 'select=*&order=created_ms.desc&limit=2000');
    rows = (rowsA || []).map(a => [Q.istString(a.created_ms), a.actor, a.actor_role || '',
      a.action, a.entity || '', a.entity_id || '', a.ip || '']);

  } else if (kind === 'individual') {
    need();
    const attemptId = String(qp.attempt_id || '').trim();
    if (!attemptId) throw new Q.AppError('An attempt must be specified.', 400);
    title = 'Individual Performance';
    columns = ['Seq', 'Question', 'Correct Option', 'Chosen Option', 'Result'];
    const qs = await Q.sbSelect('quiz_attempt_questions',
      'select=seq,exam_seq&attempt_id=' + Q.eq(attemptId) + '&order=seq.asc');
    const ans = await Q.sbSelect('quiz_answers',
      'select=seq,chosen_original,is_correct,chosen_slot&attempt_id=' + Q.eq(attemptId));
    const paper = await Q.sbSelect('quiz_exam_questions',
      'select=seq,snapshot&exam_id=' + Q.eq(examId));
    const snapBySeq = {};
    for (const r of paper || []) snapBySeq[r.seq] = r.snapshot || {};
    for (const q of qs || []) {
      const snap = snapBySeq[q.exam_seq] || {};
      const a = (ans || []).find(x => x.seq === q.seq);
      rows.push([q.seq, snap.question_text || '', snap.correct_option || '',
        a && a.chosen_slot ? a.chosen_original : '—',
        !a || !a.chosen_slot ? 'Unanswered' : (a.is_correct ? 'Correct' : 'Wrong')]);
    }

  } else {
    throw new Q.AppError('Unknown report.', 404);
  }

  await Q.logAudit(req, actor, 'REPORT_EXPORT', 'report', kind, null, { exam_id: examId || null, rows: rows.length });
  return Q.ok(res, { title, kind, columns, rows, generatedMs: Date.now(), generatedIst: Q.istString(Date.now()) });
}

async function eventsList(req, res) {
  const qp = req.query || {};
  let f = 'select=*&order=created_ms.desc&limit=' + clampInt(qp.limit, 1, 500, 100);
  if (qp.severity) f += '&severity=' + Q.eq(qp.severity);
  if (qp.attempt_id) f += '&attempt_id=' + Q.eq(qp.attempt_id);
  if (qp.exam_id) f += '&exam_id=' + Q.eq(qp.exam_id);
  return Q.ok(res, { events: (await Q.sbSelect('quiz_events', f)) || [] });
}

async function auditList(req, res) {
  const qp = req.query || {};
  let f = 'select=*&order=created_ms.desc&limit=' + clampInt(qp.limit, 1, 500, 100);
  if (qp.actor) f += '&actor=' + Q.eq(qp.actor);
  if (qp.entity) f += '&entity=' + Q.eq(qp.entity);
  return Q.ok(res, { audit: (await Q.sbSelect('quiz_audit', f)) || [] });
}

// ═══════════════════════════════════════════════════════════════════
//  SETTINGS AND ROLES
// ═══════════════════════════════════════════════════════════════════
async function settingsGet(req, res) {
  const s = await Q.getSettings(true);
  return Q.ok(res, {
    settings: s,
    // Surfaced so the settings page can show what is configured without ever
    // exposing a secret value.
    environment: {
      googleClientIdSet: !!Q.GOOGLE_CLIENT_ID,
      supabaseUrl: Q.SUPABASE_URL,
    },
  });
}

async function settingsSet(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const key = String(body.key || '').trim();
  const ALLOWED = ['exam_defaults', 'registration', 'branding', 'role_map'];
  if (!ALLOWED.includes(key)) throw new Q.AppError('Unknown settings key.', 400);
  if (body.value === undefined || body.value === null || typeof body.value !== 'object') {
    throw new Q.AppError('A settings object is required.', 400);
  }

  const before = await Q.sbOne('quiz_settings', 'select=*&key=' + Q.eq(key));
  await Q.setSetting(key, body.value, actor.username);
  await Q.logAudit(req, actor, 'SETTINGS_UPDATE', 'quiz_settings', key,
    before ? before.value : null, body.value);
  return Q.ok(res, { saved: true });
}

async function rolesList(req, res) {
  const grants = await Q.sbSelect('quiz_admin_roles', 'select=*&order=username.asc');
  const users = await Q.sbSelect('app_users', 'select=username,display_name,role,active&order=username.asc');
  const settings = await Q.getSettings();
  return Q.ok(res, {
    grants: grants || [],
    portalUsers: users || [],
    roleMap: settings.role_map || {},
  });
}

async function roleGrant(req, res, actor) {
  const body = await Q.readJsonBody(req);
  const username = String(body.username || '').trim().toLowerCase();
  const role = String(body.quiz_role || '').toUpperCase();
  if (!username) throw new Q.AppError('A username is required.', 400);
  if (!['QUIZ_ADMIN', 'QUIZ_EDITOR', 'QUIZ_VIEWER', 'NONE'].includes(role)) {
    throw new Q.AppError('Invalid quiz role.', 400);
  }

  const user = await Q.sbOne('app_users', 'select=username&username=' + Q.eq(username));
  if (!user) throw new Q.AppError('That portal user does not exist.', 404);

  // Do not allow the last Quiz Administrator to demote themselves and lock
  // everyone out of the module.
  if (username === actor.username && role !== 'QUIZ_ADMIN') {
    const admins = await Q.sbCount('quiz_admin_roles', 'quiz_role=' + Q.eq('QUIZ_ADMIN'));
    if (admins <= 1) {
      throw new Q.AppError(
        'You are the only Quiz Administrator. Grant the role to someone else before ' +
        'removing your own.', 409, 'LAST_ADMIN');
    }
  }

  const before = await Q.sbOne('quiz_admin_roles', 'select=*&username=' + Q.eq(username));
  await Q.sbInsert('quiz_admin_roles', {
    username, quiz_role: role, granted_by: actor.username, created_ms: Date.now(),
  }, { onConflict: 'username', returning: false });
  await Q.logAudit(req, actor, 'ROLE_GRANT', 'quiz_admin_roles', username,
    before ? { quiz_role: before.quiz_role } : null, { quiz_role: role });

  return Q.ok(res, { saved: true });
}

// Finalises attempts whose deadline has passed but whose browser never came
// back. examStateAction already does this on each poll; this is the safety net
// for a participant who simply closed the phone.
async function sweepExpired(req, res, actor) {
  const now = Date.now();
  const stale = await Q.sbSelect('quiz_attempts',
    'select=*&status=' + Q.eq('IN_PROGRESS') + '&deadline_ms=lt.' + now + '&limit=500');

  let finalised = 0;
  for (const a of stale || []) {
    const claimed = await Q.sbUpdate('quiz_attempts',
      'id=' + Q.eq(a.id) + '&status=' + Q.eq('IN_PROGRESS'),
      { status: 'EXPIRED', submitted_ms: a.deadline_ms, finalized_ms: now });
    if (!claimed || !claimed.length) continue;

    const answers = await Q.sbSelect('quiz_answers',
      'select=is_correct,chosen_slot&attempt_id=' + Q.eq(a.id));
    const tally = Q.computeScore(a, answers);

    const ex = await Q.sbOne('quiz_exams', 'select=result_mode,result_publish_ms&id=' + Q.eq(a.exam_id));
    const autoPublish = ex && (ex.result_mode === 'AUTO' ||
      (ex.result_mode === 'SCHEDULED' && ex.result_publish_ms && now >= ex.result_publish_ms));

    await Q.sbInsert('quiz_results', Object.assign({
      attempt_id: a.id, exam_id: a.exam_id, registration_id: a.registration_id,
      employee_id: a.employee_id, mode: a.mode,
      elapsed_seconds: Math.max(0, Math.round((a.deadline_ms - a.started_ms) / 1000)),
      published: !!autoPublish, withheld: false,
      computed_ms: now, published_ms: autoPublish ? now : null,
    }, tally), { onConflict: 'attempt_id', returning: false });
    finalised++;
  }

  await Q.sbRpc('quiz_prune_expired', { now_ms: now }).catch(() => {});
  if (finalised) {
    await Q.logAudit(req, actor, 'SWEEP_EXPIRED', 'quiz_attempts', null, null, { finalised });
  }
  return Q.ok(res, { finalised, scanned: (stale || []).length });
}

// ═══════════════════════════════════════════════════════════════════
//  Small helpers
// ═══════════════════════════════════════════════════════════════════
function clampInt(v, lo, hi, dflt) {
  const n = parseInt(v, 10);
  if (!Number.isFinite(n)) return dflt;
  return Math.min(hi, Math.max(lo, n));
}
function numOr(v, dflt) {
  const n = Number(v);
  return Number.isFinite(n) ? n : dflt;
}
function numOrNull(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? Math.round(n) : null;
}
function chunked(arr, size) {
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}
// PostgREST in.() values need quoting when they may contain commas or spaces.
function csvQuote(v) {
  return '"' + String(v).replace(/"/g, '""') + '"';
}

// Exposed for the test harness in Leave Orders/quiz/selftest.js. Pure
// functions with no database access — the handler above remains the only entry
// point the HTTP layer uses.
module.exports._internals = {
  rankWithin, buildLeaderboard,
  normaliseScope, scopeFilter, scopeExamId,
};
