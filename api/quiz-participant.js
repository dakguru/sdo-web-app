// api/quiz-participant.js — the employee-facing examination engine.
//
// Everything that decides an outcome happens here, on the server:
//   • eligibility against the uploaded roster and the exam's own rules
//   • whether the examination is open — judged on the server clock only
//   • which questions this participant gets, and in which option order
//   • whether an answer is correct — the key never leaves this process
//   • when an attempt expires, and what it scores
//
// The browser countdown is a picture of the server's deadline, not the
// deadline itself. A participant who freezes their clock, replays a request,
// or never sends a final submit still gets the same result.
//
//   POST action=lookup        Employee ID -> name / designation / office
//   POST action=register      create the registration
//   GET  action=dashboard     registration, exams, attempts, results
//   POST action=exam-start    begin or resume an attempt
//   GET  action=exam-state    current question + authoritative remaining time
//   POST action=answer        save one answer (idempotent)
//   POST action=skip          leave the current question unanswered
//   POST action=submit        finalise the attempt and score it
//   GET  action=result        the participant's own result
//   POST action=event         client-side integrity signal (advisory)

'use strict';

const Q = require('./_quizlib');

module.exports = async function handler(req, res) {
  try {
    const cfgErr = Q.configError();
    if (cfgErr) throw new Q.AppError(cfgErr, 503, 'NOT_CONFIGURED');
    Q.requireJsonRequest(req);

    const action = String((req.query && req.query.action) || '').toLowerCase();
    switch (action) {
      case 'lookup':     return await lookupAction(req, res);
      case 'register':   return await registerAction(req, res);
      case 'dashboard':  return await dashboardAction(req, res);
      case 'exam-start': return await examStartAction(req, res);
      case 'exam-state': return await examStateAction(req, res);
      case 'answer':     return await answerAction(req, res);
      case 'skip':       return await skipAction(req, res);
      case 'submit':     return await submitAction(req, res);
      case 'result':     return await resultAction(req, res);
      case 'event':      return await eventAction(req, res);
      default:
        throw new Q.AppError('Unknown action.', 404, 'UNKNOWN_ACTION');
    }
  } catch (err) {
    return Q.fail(res, err);
  }
};

// ═══════════════════════════════════════════════════════════════════
//  REGISTRATION
// ═══════════════════════════════════════════════════════════════════

// Employee ID lookup. Requires a signed-in participant and is rate limited,
// so the roster cannot be dumped by walking Employee IDs. It returns ONE
// record at a time and never accepts a wildcard or a partial id.
async function lookupAction(req, res) {
  const { identity, registration } = await Q.requireParticipant(req);
  if (registration) {
    throw new Q.AppError('You have already completed registration.', 409, 'ALREADY_REGISTERED');
  }

  const body = await Q.readJsonBody(req);
  const empId = String(body.employee_id || '').trim();
  if (!/^[0-9A-Za-z/-]{3,24}$/.test(empId)) {
    throw new Q.AppError(
      'Employee ID looks wrong. Please enter the Employee ID exactly as it appears ' +
      'in your pay slip or service record.', 400, 'BAD_EMP_ID');
  }

  await Q.rateLimit('lookup:' + identity.id, 20, 60 * 1000);
  await Q.rateLimit('lookup-ip:' + (Q.clientIp(req) || 'unknown'), 40, 60 * 1000);

  const total = await Q.sbCount('quiz_employees', 'active=is.true');
  if (!total) {
    throw new Q.AppError(
      'The employee directory has not been imported yet. Please try again later, ' +
      'or contact the administrator.', 503, 'DIRECTORY_EMPTY');
  }

  const emp = await Q.sbOne('quiz_employees',
    'select=employee_id,employee_name,designation,office,category,active&employee_id=' + Q.eq(empId));

  if (!emp || !emp.active) {
    await Q.logEvent(req, {
      identity_id: identity.id, type: 'LOOKUP_MISS',
      detail: { employee_id: empId },
    });
    throw new Q.AppError(
      'Employee ID ' + empId + ' was not found in the Karur Division employee list. ' +
      'Please check the ID, or contact the administrator on +91 7904478591 ' +
      'if you believe this is an error.', 404, 'EMP_NOT_FOUND');
  }

  // Say plainly that the ID is taken, without revealing who holds it.
  const claimed = await Q.sbOne('quiz_registrations',
    'select=id,status&employee_id=' + Q.eq(empId));
  if (claimed) {
    await Q.logEvent(req, {
      identity_id: identity.id, type: 'LOOKUP_CLAIMED', severity: 'NOTICE',
      detail: { employee_id: empId },
    });
    throw new Q.AppError(
      'Employee ID ' + empId + ' has already been registered for this quiz from another account. ' +
      'If this is your Employee ID, please contact the administrator on +91 7904478591 ' +
      'or arun18@live.in to have it re-assigned to your account.', 409, 'EMP_CLAIMED');
  }

  return Q.ok(res, {
    employee: {
      employee_id: emp.employee_id,
      employee_name: emp.employee_name,
      designation: emp.designation,
      office: emp.office,
    },
  });
}

async function registerAction(req, res) {
  const { identity, registration } = await Q.requireParticipant(req);
  if (registration) {
    return Q.ok(res, { registration: shapeRegistration(registration), alreadyRegistered: true });
  }

  const body = await Q.readJsonBody(req);
  const empId = String(body.employee_id || '').trim();
  if (!/^[0-9A-Za-z/-]{3,24}$/.test(empId)) {
    throw new Q.AppError('Please enter a valid Employee ID.', 400, 'BAD_EMP_ID');
  }
  if (body.consent !== true) {
    throw new Q.AppError(
      'Please confirm that the details shown are yours and that you accept the privacy notice.',
      400, 'CONSENT_REQUIRED');
  }

  await Q.rateLimit('register:' + identity.id, 6, 10 * 60 * 1000);

  // Name, designation and office are taken from the roster, never from the
  // request body. A participant cannot type their way into a different office.
  const emp = await Q.sbOne('quiz_employees',
    'select=*&employee_id=' + Q.eq(empId) + '&active=is.true');
  if (!emp) {
    throw new Q.AppError(
      'Employee ID ' + empId + ' is not in the approved employee list, so registration ' +
      'cannot be completed. Please contact the administrator.', 404, 'EMP_NOT_FOUND');
  }

  const settings = await Q.getSettings();
  const regCfg = settings.registration || {};
  const autoApprove = regCfg.auto_approve_on_roster_match === true;

  // The roster match is the identity evidence we actually have. A Google
  // account alone proves nothing about employment, so auto-approval is
  // conditioned on the match and on the Employee ID being unclaimed.
  const status = autoApprove ? 'APPROVED' : 'PENDING';
  const now = Date.now();
  const regCode = await nextRegCode(regCfg.reg_code_prefix || 'NPW2026');

  const newRegistration = {
    reg_code: regCode,
    identity_id: identity.id,
    employee_id: emp.employee_id,
    employee_name: emp.employee_name,
    designation: emp.designation,
    office: emp.office,
    // Snapshotted for the same reason as designation and office: the prize
    // category a participant competed in must not change under them later.
    category: emp.category || null,
    unit: emp.unit || null,
    status,
    roster_verified: true,
    manual_entry: false,
    approved_by: autoApprove ? 'auto:roster-match' : null,
    approved_at_ms: autoApprove ? now : null,
    created_at_ms: now,
    updated_at_ms: now,
  };

  // Until migration_v22 has run the two new columns are dropped, and the
  // backfill gives these registrations their category afterwards. Nobody is
  // turned away from registering over a column that does not exist yet.
  const payload = await Q.columnsReady('quiz_registrations')
    ? newRegistration
    : Q.withoutCategory(newRegistration);

  let rows;
  try {
    rows = await Q.sbInsert('quiz_registrations', payload);
  } catch (e) {
    // The two UNIQUE constraints are the real duplicate guard — a race between
    // two tabs lands here rather than creating a second registration.
    if (e instanceof Q.DbError && e.status === 409) {
      const mine = await Q.sbOne('quiz_registrations', 'select=*&identity_id=' + Q.eq(identity.id));
      if (mine) {
        return Q.ok(res, { registration: shapeRegistration(mine), alreadyRegistered: true });
      }
      throw new Q.AppError(
        'Employee ID ' + empId + ' has just been registered from another account. ' +
        'Please contact the administrator on +91 7904478591.', 409, 'EMP_CLAIMED');
    }
    throw e;
  }

  const reg = rows[0];
  await Q.logEvent(req, {
    identity_id: identity.id, registration_id: reg.id,
    type: 'REGISTERED', detail: { employee_id: emp.employee_id, status },
  });

  return Q.ok(res, { registration: shapeRegistration(reg), autoApproved: autoApprove });
}

// Sequential, human-readable registration code: NPW2026-0001.
async function nextRegCode(prefix) {
  const n = await Q.sbCount('quiz_registrations');
  for (let bump = 1; bump <= 40; bump++) {
    const code = prefix + '-' + String(n + bump).padStart(4, '0');
    const clash = await Q.sbOne('quiz_registrations', 'select=id&reg_code=' + Q.eq(code));
    if (!clash) return code;
  }
  return prefix + '-' + Date.now().toString(36).toUpperCase();
}

// ═══════════════════════════════════════════════════════════════════
//  DASHBOARD
// ═══════════════════════════════════════════════════════════════════
async function dashboardAction(req, res) {
  const { identity, registration } = await Q.requireParticipant(req);
  const now = Date.now();

  if (!registration) {
    return Q.ok(res, {
      needsRegistration: true, serverTimeMs: now,
      identity: { email: identity.email, displayName: identity.display_name, provider: identity.provider },
    });
  }

  // Draft and cancelled examinations are invisible to participants.
  const exams = await Q.sbSelect('quiz_exams',
    'select=*&status=in.(SCHEDULED,REGISTRATION_OPEN,REGISTRATION_CLOSED,LIVE,COMPLETED,RESULTS_PUBLISHED)' +
    '&order=exam_open_ms.asc');

  const attempts = await Q.sbSelect('quiz_attempts',
    'select=id,exam_id,status,started_ms,deadline_ms,current_seq,question_count,submitted_ms,attempt_no' +
    '&registration_id=' + Q.eq(registration.id) + '&mode=' + Q.eq('OFFICIAL'));

  const results = await Q.sbSelect('quiz_results',
    'select=*&registration_id=' + Q.eq(registration.id) + '&mode=' + Q.eq('OFFICIAL'));

  const allotted = await myAllotments(registration.employee_id);

  const examViews = [];
  for (const raw of exams || []) {
    const ex = withAllotment(raw, allotted);
    const mine = (attempts || []).filter(a => a.exam_id === ex.id);

    // An examination allotted to a named group is not merely closed to
    // everybody else — it is none of their business. It is left off the
    // dashboard entirely rather than listed as refused.
    //
    // Unless they have sat it: somebody removed from the list afterwards must
    // still be able to reach their own attempt and result, which is their
    // record, not the examination's.
    if (String(ex.allotment_mode || 'ALL').toUpperCase() === 'SELECTED' &&
        ex.allottedToMe !== true && !mine.length) {
      continue;
    }

    const done = mine.filter(a => a.status !== 'IN_PROGRESS');
    const live = mine.find(a => a.status === 'IN_PROGRESS');
    const result = (results || []).find(r => r.exam_id === ex.id && r.published && !r.withheld);
    const gate = examGate(ex, registration, mine, now);

    examViews.push({
      id: ex.id,
      title: ex.title,
      description: ex.description,
      instructions: ex.instructions,
      examOpenMs: ex.exam_open_ms,
      examCloseMs: ex.exam_close_ms,
      latestStartMs: ex.latest_start_ms,
      questionCount: ex.question_count,
      secondsPerQuestion: ex.seconds_per_question,
      totalSeconds: ex.total_seconds,
      positiveMarks: Number(ex.positive_marks),
      negativeMarks: Number(ex.negative_marks),
      maxMarks: Number(ex.positive_marks) * ex.question_count,
      navigation: ex.navigation,
      allowSkip: ex.allow_skip,
      attemptsAllowed: ex.attempts_allowed,
      attemptsUsed: done.length,
      status: ex.status,
      // Everything the UI needs to decide what to show, decided on the server.
      canStart: gate.canStart,
      reason: gate.reason,
      phase: gate.phase,
      inProgressAttemptId: live ? live.id : null,
      // So the dashboard can show the attempt's own clock, not just the window's.
      inProgressDeadlineMs: live ? live.deadline_ms : null,
      completed: done.length > 0,
      result: result ? shapeResult(result, ex) : null,
      resultPending: done.length > 0 && !result,
    });
  }

  return Q.ok(res, {
    serverTimeMs: now,
    identity: { email: identity.email, displayName: identity.display_name, provider: identity.provider },
    registration: shapeRegistration(registration),
    exams: examViews,
  });
}

// The examinations this employee has been allotted by name, as a Set of exam
// ids. One indexed read; the dashboard needs it once for every examination it
// shows, and exam-start needs it for one.
//
// Until migration_v24 has run the table does not exist. That is reported as
// "no allotments", which is safe in both directions: without the column that
// turns the restriction on, no examination is in SELECTED mode either, so
// nothing is wrongly closed and nothing is wrongly opened.
async function myAllotments(employeeId) {
  if (!employeeId) return new Set();
  try {
    const rows = await Q.sbSelect('quiz_exam_allotments',
      'select=exam_id&employee_id=' + Q.eq(employeeId) + '&limit=2000');
    return new Set((rows || []).map(r => r.exam_id));
  } catch (e) {
    if (Q.isMissingColumn(e) || (e instanceof Q.DbError && e.status === 404)) return new Set();
    throw e;
  }
}

// Decorate an examination with this employee's allotment, which is what
// `eligible` reads. Kept beside the gate so the two cannot drift apart.
function withAllotment(ex, allotted) {
  return Object.assign({}, ex, { allottedToMe: allotted.has(ex.id) });
}

// The single place that decides whether an employee may begin. Called from the
// dashboard (to render) AND from exam-start (to authorise) — the button being
// disabled is cosmetic; this function is the control.
function examGate(ex, registration, myAttempts, now) {
  if (registration.status !== 'APPROVED') {
    return {
      canStart: false, phase: 'NOT_APPROVED',
      reason: registration.status === 'PENDING'
        ? 'Your registration is awaiting administrator approval.'
        : 'Your registration is ' + registration.status.toLowerCase() + '. Please contact the administrator.',
    };
  }
  if (!eligible(ex, registration)) {
    return {
      canStart: false, phase: 'NOT_ELIGIBLE',
      reason: String(ex.allotment_mode || 'ALL').toUpperCase() === 'SELECTED'
        ? 'This examination is open only to the employees allotted to it. ' +
          'Please contact the administrator if you believe you should be included.'
        : 'This examination is not open to your office or designation.',
    };
  }
  if (ex.status === 'CANCELLED') return { canStart: false, phase: 'CANCELLED', reason: 'This examination has been cancelled.' };

  const live = myAttempts.find(a => a.status === 'IN_PROGRESS');
  if (live) {
    if (live.deadline_ms > now) {
      return { canStart: true, phase: 'RESUME', reason: 'You have an attempt in progress — resume it.' };
    }
    return { canStart: false, phase: 'EXPIRED', reason: 'Your attempt has ended and is being finalised.' };
  }

  const used = myAttempts.filter(a => a.status !== 'IN_PROGRESS').length;
  if (used >= (ex.attempts_allowed || 1)) {
    return { canStart: false, phase: 'ATTEMPTED', reason: 'You have already completed this examination.' };
  }

  // An examination with no opening time has not been scheduled, whatever its
  // status says, and a missing date must mean 'not yet' rather than 'no
  // restriction' — otherwise marking a paper SCHEDULED before setting its date
  // opens it to everyone immediately. LIVE is the deliberate exception: an
  // administrator sets that by hand and means it.
  if (!ex.exam_open_ms && ex.status !== 'LIVE') {
    return {
      canStart: false, phase: 'NOT_SCHEDULED',
      reason: 'The date and time of this examination have not been announced yet.',
    };
  }
  if (ex.exam_open_ms && now < ex.exam_open_ms) {
    return { canStart: false, phase: 'NOT_STARTED', reason: 'Examination Not Yet Started' };
  }
  if (ex.exam_close_ms && now > ex.exam_close_ms) {
    return { canStart: false, phase: 'CLOSED', reason: 'This examination has closed.' };
  }
  // Late entry: latest_start_ms bars a NEW attempt while the exam itself may
  // still be running for those already inside.
  if (ex.latest_start_ms && now > ex.latest_start_ms) {
    if (!ex.late_entry_allowed) {
      return { canStart: false, phase: 'LATE', reason: 'The latest permitted start time has passed.' };
    }
    return { canStart: false, phase: 'LATE', reason: 'The latest permitted start time for this examination has passed.' };
  }
  if (ex.status !== 'LIVE' && ex.status !== 'SCHEDULED' && ex.status !== 'REGISTRATION_CLOSED' && ex.status !== 'REGISTRATION_OPEN') {
    return { canStart: false, phase: ex.status, reason: 'This examination is not currently open.' };
  }
  return { canStart: true, phase: 'OPEN', reason: '' };
}

function eligible(ex, registration) {
  // An examination allotted to named candidates is open to them and to nobody
  // else. This is checked before the office and designation rules and
  // overrides them: the list IS the eligibility, so an allotted employee
  // whose office is outside the examination's office list still sits it, and
  // an unallotted employee inside that list does not.
  //
  // `allottedToMe` is attached by the caller, which knows this employee's
  // allotments. It is read strictly: anything other than an explicit true
  // means not allotted, so a failure to load them shuts the examination
  // rather than opening it.
  if (String(ex.allotment_mode || 'ALL').toUpperCase() === 'SELECTED') {
    return ex.allottedToMe === true;
  }

  const check = (list, value) => {
    if (!Array.isArray(list) || !list.length) return true;   // empty = everyone
    return list.map(x => String(x).trim().toLowerCase())
      .includes(String(value || '').trim().toLowerCase());
  };
  return check(ex.eligible_offices, registration.office)
    && check(ex.eligible_designations, registration.designation);
}

// ═══════════════════════════════════════════════════════════════════
//  ATTEMPT LIFECYCLE
// ═══════════════════════════════════════════════════════════════════
async function examStartAction(req, res) {
  const { registration } = await Q.requireParticipant(req);
  if (!registration) throw new Q.AppError('Please complete registration first.', 403, 'NOT_REGISTERED');

  const body = await Q.readJsonBody(req);
  const examId = String(body.exam_id || '').trim();
  if (!examId) throw new Q.AppError('No examination was specified.', 400);

  const raw = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(examId));
  if (!raw) throw new Q.AppError('Examination not found.', 404);
  // The dashboard hides an unallotted examination, but hiding is not a
  // control. The same allotment is loaded again here, where starting is
  // actually authorised, so a hand-made request cannot walk into a paper this
  // employee was never allotted.
  const ex = withAllotment(raw, await myAllotments(registration.employee_id));

  const now = Date.now();
  const mine = await Q.sbSelect('quiz_attempts',
    'select=*&registration_id=' + Q.eq(registration.id) +
    '&exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('OFFICIAL'));

  // Resume takes priority: a refresh, a dropped connection or an incoming call
  // must land the participant back in the same attempt, never a new one.
  const live = (mine || []).find(a => a.status === 'IN_PROGRESS');
  if (live) {
    if (live.deadline_ms <= now) {
      await finaliseAttempt(req, live, 'EXPIRED');
      throw new Q.AppError('Your examination time has ended. Your answers have been submitted.', 409, 'ATTEMPT_EXPIRED');
    }
    await Q.logEvent(req, {
      attempt_id: live.id, registration_id: registration.id, exam_id: examId,
      type: 'RESUME', detail: { current_seq: live.current_seq },
    });
    return Q.ok(res, { attemptId: live.id, resumed: true, serverTimeMs: now });
  }

  const gate = examGate(ex, registration, mine || [], now);
  if (!gate.canStart) {
    throw new Q.AppError(gate.reason || 'You cannot start this examination now.', 403, gate.phase);
  }

  // The published, immutable question set. Editing the general question bank
  // after publication cannot reach this.
  const pool = await Q.sbSelect('quiz_exam_questions',
    'select=seq,question_id,snapshot&exam_id=' + Q.eq(examId) + '&order=seq.asc');
  if (!pool || pool.length < ex.question_count) {
    throw new Q.AppError(
      'This examination is not ready — its question paper has not been fully published. ' +
      'Please contact the administrator.', 503, 'PAPER_NOT_READY');
  }

  const chosen = (ex.randomize_questions ? Q.secureShuffle(pool) : pool).slice(0, ex.question_count);

  // Whichever deadline falls first governs: the overall duration, or the
  // examination's own closing time (plus any configured grace).
  const byDuration = now + ex.total_seconds * 1000;
  const closeCap = ex.exam_close_ms
    ? ex.exam_close_ms + (ex.on_close === 'GRACE' ? (ex.grace_seconds || 0) * 1000 : 0)
    : null;
  const deadline = closeCap ? Math.min(byDuration, closeCap) : byDuration;
  if (deadline <= now + 5000) {
    throw new Q.AppError('There is not enough time left to begin this examination.', 403, 'NO_TIME_LEFT');
  }

  const attemptNo = (mine || []).length + 1;
  let attempt;
  try {
    const rows = await Q.sbInsert('quiz_attempts', {
      exam_id: examId,
      registration_id: registration.id,
      employee_id: registration.employee_id,
      mode: 'OFFICIAL',
      attempt_no: attemptNo,
      status: 'IN_PROGRESS',
      started_ms: now,
      deadline_ms: deadline,
      current_seq: 1,
      // Policy is frozen onto the attempt: a settings change mid-examination
      // cannot retroactively rescore anybody.
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
    attempt = rows[0];
  } catch (e) {
    // The partial unique index on OFFICIAL attempts caught a double-tap.
    if (e instanceof Q.DbError && e.status === 409) {
      const again = await Q.sbOne('quiz_attempts',
        'select=*&registration_id=' + Q.eq(registration.id) +
        '&exam_id=' + Q.eq(examId) + '&mode=' + Q.eq('OFFICIAL') + '&status=' + Q.eq('IN_PROGRESS'));
      if (again) return Q.ok(res, { attemptId: again.id, resumed: true, serverTimeMs: now });
    }
    throw e;
  }

  // Freeze this participant's question order and option permutation.
  const aqRows = chosen.map((p, i) => ({
    attempt_id: attempt.id,
    seq: i + 1,
    exam_seq: p.seq,
    question_id: p.question_id,
    option_order: ex.randomize_options ? Q.secureShuffle(['A', 'B', 'C', 'D']).join('') : 'ABCD',
    locked: false,
  }));
  await Q.sbInsert('quiz_attempt_questions', aqRows, { returning: false });

  await Q.logEvent(req, {
    attempt_id: attempt.id, registration_id: registration.id, exam_id: examId,
    type: 'ATTEMPT_STARTED', detail: { question_count: chosen.length, deadline_ms: deadline },
  });

  return Q.ok(res, { attemptId: attempt.id, resumed: false, serverTimeMs: now });
}

// The participant's view of an in-progress attempt: one question, the option
// texts in their frozen display order, and the two remaining times. The
// correct answer is never part of this payload.
async function examStateAction(req, res) {
  const ctx = await actorFor(req);
  const attemptId = String((req.query && req.query.attempt_id) || '').trim();
  if (!attemptId) throw new Q.AppError('No attempt was specified.', 400);

  const attempt = await loadOwnAttempt(attemptId, ctx);
  return Q.ok(res, await buildExamState(req, ctx, attempt));
}

// The same payload, built rather than sent, so that answering a question can
// return the next one in the same response instead of costing a second
// request. Every database call here crosses the network one at a time, so the
// round trip saved is the one the candidate was waiting through.
async function buildExamState(req, ctx, attempt) {
  const registration = ctx.registration;
  const attemptId = attempt.id;
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(attempt.exam_id));
  const now = Date.now();

  // Server-side enforcement of the overall timer. This runs on every poll, so
  // an attempt finalises even if the participant's browser never comes back.
  if (attempt.status === 'IN_PROGRESS' && attempt.deadline_ms <= now) {
    const result = await finaliseAttempt(req, attempt, 'EXPIRED');
    return {
      finished: true, reason: 'TIME_UP', attemptId,
      resultAvailable: !!result, serverTimeMs: Date.now(),
      examTitle: ex ? ex.title : null,
    };
  }
  if (attempt.status !== 'IN_PROGRESS') {
    return {
      finished: true, reason: attempt.status, attemptId,
      serverTimeMs: now, examTitle: ex ? ex.title : null,
    };
  }

  const qs = await Q.sbSelect('quiz_attempt_questions',
    'select=*&attempt_id=' + Q.eq(attemptId) + '&order=seq.asc');
  const answers = await Q.sbSelect('quiz_answers',
    'select=seq,chosen_slot&attempt_id=' + Q.eq(attemptId));
  const answered = new Set((answers || []).filter(a => a.chosen_slot).map(a => a.seq));

  // Walk forward over any question whose per-question timer elapsed while the
  // participant was away. Each one locks unanswered — we do not hand back time
  // that has already been spent, and we do not punish beyond the question the
  // participant was actually on.
  let seq = attempt.current_seq;
  let advanced = false;
  while (seq <= attempt.question_count) {
    const row = (qs || []).find(x => x.seq === seq);
    if (!row) break;
    if (row.locked) { seq++; advanced = true; continue; }
    if (row.q_deadline_ms && row.q_deadline_ms <= now) {
      await Q.sbUpdate('quiz_attempt_questions',
        'attempt_id=' + Q.eq(attemptId) + '&seq=eq.' + seq,
        { locked: true }, { returning: false });
      await Q.logEvent(req, {
        attempt_id: attemptId, registration_id: registration ? registration.id : null,
        type: 'QUESTION_TIMEOUT', detail: { seq },
      });
      seq++; advanced = true; continue;
    }
    break;
  }

  if (seq > attempt.question_count) {
    const result = await finaliseAttempt(req, attempt, 'AUTO_SUBMITTED');
    return {
      finished: true, reason: 'COMPLETED', attemptId,
      resultAvailable: !!result, serverTimeMs: Date.now(),
      examTitle: ex ? ex.title : null,
    };
  }
  if (advanced && seq !== attempt.current_seq) {
    await Q.sbUpdate('quiz_attempts', 'id=' + Q.eq(attemptId),
      { current_seq: seq }, { returning: false });
    attempt.current_seq = seq;
  }

  const row = (qs || []).find(x => x.seq === seq);
  const examQ = await Q.sbOne('quiz_exam_questions',
    'select=snapshot&exam_id=' + Q.eq(attempt.exam_id) + '&seq=eq.' + row.exam_seq);
  if (!examQ) throw new Q.AppError('This question could not be loaded. Please contact the administrator.', 500);

  // First time this question is served, its own deadline is set — and capped
  // by the overall deadline, so the per-question clock can never outlive it.
  let qDeadline = row.q_deadline_ms;
  if (!row.served_ms || !qDeadline) {
    qDeadline = Math.min(now + attempt.seconds_per_question * 1000, attempt.deadline_ms);
    await Q.sbUpdate('quiz_attempt_questions',
      'attempt_id=' + Q.eq(attemptId) + '&seq=eq.' + seq,
      { served_ms: now, q_deadline_ms: qDeadline }, { returning: false });
  }

  const snap = examQ.snapshot || {};
  const order = row.option_order || 'ABCD';
  const options = order.split('').map((orig, idx) => ({
    slot: 'ABCD'[idx],
    text: snap['option_' + orig.toLowerCase()],
  }));

  const prior = (answers || []).find(a => a.seq === seq);

  return {
    finished: false,
    attemptId,
    examTitle: ex ? ex.title : '',
    seq,
    total: attempt.question_count,
    questionText: snap.question_text,
    options,                                 // option texts only — no key
    selectedSlot: prior ? prior.chosen_slot : null,
    answeredCount: answered.size,
    navigation: attempt.navigation,
    allowSkip: attempt.allow_skip,
    secondsPerQuestion: attempt.seconds_per_question,
    testMode: attempt.mode === 'TEST',
    isLast: seq >= attempt.question_count,
    // Authoritative clocks. The client renders a countdown from these; it does
    // not decide when time is up.
    serverTimeMs: now,
    questionDeadlineMs: qDeadline,
    attemptDeadlineMs: attempt.deadline_ms,
    questionRemainingMs: Math.max(0, qDeadline - now),
    attemptRemainingMs: Math.max(0, attempt.deadline_ms - now),
  };
}

async function answerAction(req, res) {
  const ctx = await actorFor(req);
  const body = await Q.readJsonBody(req);
  const attemptId = String(body.attempt_id || '').trim();
  const seq = parseInt(body.seq, 10);
  const slot = String(body.slot || '').toUpperCase();

  if (!attemptId || !Number.isInteger(seq)) throw new Q.AppError('Malformed answer.', 400);
  if (!['A', 'B', 'C', 'D'].includes(slot)) throw new Q.AppError('Please select one of the four options.', 400);

  const attempt = await loadOwnAttempt(attemptId, ctx);
  const now = Date.now();

  if (attempt.status !== 'IN_PROGRESS') {
    throw new Q.AppError('This attempt has already been submitted.', 409, 'ATTEMPT_CLOSED');
  }
  if (attempt.deadline_ms <= now) {
    await finaliseAttempt(req, attempt, 'EXPIRED');
    throw new Q.AppError('Your examination time has ended.', 409, 'ATTEMPT_EXPIRED');
  }
  // One-way navigation is enforced here, not by hiding a button.
  if (attempt.navigation === 'ONE_WAY' && seq !== attempt.current_seq) {
    throw new Q.AppError('You cannot return to a previous question in this examination.', 409, 'LOCKED');
  }
  if (seq < 1 || seq > attempt.question_count) throw new Q.AppError('Unknown question.', 400);

  const row = await Q.sbOne('quiz_attempt_questions',
    'select=*&attempt_id=' + Q.eq(attemptId) + '&seq=eq.' + seq);
  if (!row) throw new Q.AppError('Unknown question.', 404);
  if (row.locked) throw new Q.AppError('This question is locked.', 409, 'LOCKED');
  if (!row.served_ms) throw new Q.AppError('This question has not been served yet.', 409, 'NOT_SERVED');
  if (row.q_deadline_ms && row.q_deadline_ms <= now) {
    await Q.sbUpdate('quiz_attempt_questions',
      'attempt_id=' + Q.eq(attemptId) + '&seq=eq.' + seq, { locked: true }, { returning: false });
    throw new Q.AppError('Time for this question has run out.', 409, 'QUESTION_EXPIRED');
  }

  // De-permute the tapped display slot back to the stored option, then compare
  // against the frozen snapshot. The key is read here and discarded.
  const order = row.option_order || 'ABCD';
  const original = order['ABCD'.indexOf(slot)];
  const examQ = await Q.sbOne('quiz_exam_questions',
    'select=snapshot&exam_id=' + Q.eq(attempt.exam_id) + '&seq=eq.' + row.exam_seq);
  const correct = examQ && examQ.snapshot ? String(examQ.snapshot.correct_option || '') : '';
  const isCorrect = !!correct && original === correct;

  // Keyed on (attempt_id, seq): a retried request overwrites rather than
  // scoring twice, so a flaky connection cannot double-count or double-deduct.
  await Q.sbInsert('quiz_answers', {
    attempt_id: attemptId,
    seq,
    chosen_slot: slot,
    chosen_original: original,
    is_correct: isCorrect,
    answered_ms: now,                       // server receipt time
    client_token: body.client_token ? String(body.client_token).slice(0, 64) : null,
  }, { onConflict: 'attempt_id,seq', returning: false });

  const advance = body.advance !== false;
  let nextSeq = attempt.current_seq;
  if (advance) {
    await Q.sbUpdate('quiz_attempt_questions',
      'attempt_id=' + Q.eq(attemptId) + '&seq=eq.' + seq, { locked: true }, { returning: false });
    nextSeq = Math.min(seq + 1, attempt.question_count + 1);
    await Q.sbUpdate('quiz_attempts', 'id=' + Q.eq(attemptId),
      { current_seq: nextSeq }, { returning: false });
  }

  // Deliberately no `correct` field in the response: the participant must not
  // learn the answer during the examination.
  const next = advance ? await nextStateOrNull(req, ctx, attempt, nextSeq) : null;

  return Q.ok(res, {
    saved: true, seq, advanced: advance, nextSeq,
    finished: nextSeq > attempt.question_count,
    serverTimeMs: now,
    next,
  });
}

// The next question, attached to the response that advanced to it. If building
// it fails for any reason the field is simply absent and the client fetches the
// state as it always did, so this can only ever save a round trip, never cost
// correctness.
async function nextStateOrNull(req, ctx, attempt, nextSeq) {
  if (nextSeq > attempt.question_count) return null;
  try {
    attempt.current_seq = nextSeq;
    return await buildExamState(req, ctx, attempt);
  } catch (_) {
    return null;
  }
}


async function skipAction(req, res) {
  const ctx = await actorFor(req);
  const body = await Q.readJsonBody(req);
  const attemptId = String(body.attempt_id || '').trim();
  const seq = parseInt(body.seq, 10);

  const attempt = await loadOwnAttempt(attemptId, ctx);
  const now = Date.now();
  if (attempt.status !== 'IN_PROGRESS') throw new Q.AppError('This attempt has already been submitted.', 409);
  if (!attempt.allow_skip) throw new Q.AppError('Skipping is not permitted in this examination.', 403, 'NO_SKIP');
  if (attempt.deadline_ms <= now) {
    await finaliseAttempt(req, attempt, 'EXPIRED');
    throw new Q.AppError('Your examination time has ended.', 409, 'ATTEMPT_EXPIRED');
  }
  if (attempt.navigation === 'ONE_WAY' && seq !== attempt.current_seq) {
    throw new Q.AppError('You cannot return to a previous question.', 409, 'LOCKED');
  }

  await Q.sbUpdate('quiz_attempt_questions',
    'attempt_id=' + Q.eq(attemptId) + '&seq=eq.' + seq, { locked: true }, { returning: false });
  const nextSeq = Math.min(seq + 1, attempt.question_count + 1);
  await Q.sbUpdate('quiz_attempts', 'id=' + Q.eq(attemptId),
    { current_seq: nextSeq }, { returning: false });

  const next = await nextStateOrNull(req, ctx, attempt, nextSeq);
  return Q.ok(res, {
    skipped: true, nextSeq, finished: nextSeq > attempt.question_count,
    serverTimeMs: now, next,
  });
}

async function submitAction(req, res) {
  const ctx = await actorFor(req);
  const body = await Q.readJsonBody(req);
  const attemptId = String(body.attempt_id || '').trim();
  const attempt = await loadOwnAttempt(attemptId, ctx);

  if (attempt.status !== 'IN_PROGRESS') {
    const existing = await Q.sbOne('quiz_results', 'select=*&attempt_id=' + Q.eq(attemptId));
    return Q.ok(res, { submitted: true, alreadySubmitted: true, resultAvailable: !!existing });
  }

  const expired = attempt.deadline_ms <= Date.now();
  const result = await finaliseAttempt(req, attempt, expired ? 'EXPIRED' : 'SUBMITTED');
  return Q.ok(res, { submitted: true, resultAvailable: !!result, serverTimeMs: Date.now() });
}

// ═══════════════════════════════════════════════════════════════════
//  FINALISATION AND SCORING
//
//  Total = (correct x positive) - (wrong x negative); unanswered score the
//  configured unanswered_marks, 0 by default. Computed from the stored answers
//  and the frozen policy on the attempt, so it is reproducible and auditable.
// ═══════════════════════════════════════════════════════════════════
async function finaliseAttempt(req, attempt, status) {
  const now = Date.now();

  // Claim the attempt first. The status filter makes this a compare-and-set:
  // two concurrent finalisers cannot both proceed to write a result.
  const claimed = await Q.sbUpdate('quiz_attempts',
    'id=' + Q.eq(attempt.id) + '&status=' + Q.eq('IN_PROGRESS'),
    { status, submitted_ms: now, finalized_ms: now });
  if (!claimed || !claimed.length) {
    return await Q.sbOne('quiz_results', 'select=*&attempt_id=' + Q.eq(attempt.id));
  }

  const answers = await Q.sbSelect('quiz_answers',
    'select=seq,is_correct,chosen_slot&attempt_id=' + Q.eq(attempt.id));

  const tally = Q.computeScore(attempt, answers);

  const ex = await Q.sbOne('quiz_exams', 'select=result_mode,result_publish_ms&id=' + Q.eq(attempt.exam_id));
  // A result exists immediately; whether the participant may SEE it is a
  // separate decision, held in `published`.
  const autoPublish = ex && (ex.result_mode === 'AUTO' ||
    (ex.result_mode === 'SCHEDULED' && ex.result_publish_ms && now >= ex.result_publish_ms));

  // The prize category travels with the result, taken from the registration as
  // it stood when the examination was sat. A later correction to the directory
  // cannot move somebody between prize lists after the event.
  //
  // If migration_v22 has not been run yet, the columns are simply skipped. A
  // result without a category can be given one later by the backfill; a result
  // that was never written because the insert failed cannot be recovered at
  // all, so the marks always take priority over the category.
  const ready = attempt.registration_id && await Q.columnsReady('quiz_registrations');
  const reg = ready
    ? await Q.sbOne('quiz_registrations',
        'select=category,unit&id=' + Q.eq(attempt.registration_id))
    : null;

  const row = Object.assign({
    attempt_id: attempt.id,
    exam_id: attempt.exam_id,
    registration_id: attempt.registration_id,
    employee_id: attempt.employee_id,
    mode: attempt.mode,
    category: reg ? reg.category : null,
    unit: reg ? reg.unit : null,
    elapsed_seconds: Math.max(0, Math.round((now - attempt.started_ms) / 1000)),
    published: !!autoPublish,
    withheld: false,
    computed_ms: now,
    published_ms: autoPublish ? now : null,
  }, tally);
  // Belt and braces. The probe above is cached, so it can in principle be
  // stale by a few seconds against a migration running right now; losing a
  // result to that race is not acceptable, so a rejection on the two new
  // columns is retried without them rather than propagated.
  try {
    await Q.sbInsert('quiz_results', row, { onConflict: 'attempt_id', returning: false });
  } catch (e) {
    if (!Q.isMissingColumn(e)) throw e;
    await Q.sbInsert('quiz_results', Q.withoutCategory(row),
      { onConflict: 'attempt_id', returning: false });
  }

  await Q.logEvent(req, {
    attempt_id: attempt.id, registration_id: attempt.registration_id,
    exam_id: attempt.exam_id, type: 'FINALISED',
    detail: Object.assign({ status }, tally),
  });

  return row;
}

// ═══════════════════════════════════════════════════════════════════
//  RESULTS
// ═══════════════════════════════════════════════════════════════════
async function resultAction(req, res) {
  const ctx = await actorFor(req);
  const registration = ctx.registration;
  const attemptId = String((req.query && req.query.attempt_id) || '').trim();
  if (!attemptId) throw new Q.AppError('No attempt was specified.', 400);

  // loadOwnAttempt is what stops one employee reading another's result by id.
  const attempt = await loadOwnAttempt(attemptId, ctx);
  const ex = await Q.sbOne('quiz_exams', 'select=*&id=' + Q.eq(attempt.exam_id));
  const result = await Q.sbOne('quiz_results', 'select=*&attempt_id=' + Q.eq(attemptId));

  if (!result) {
    return Q.ok(res, { available: false, reason: 'PENDING', examTitle: ex ? ex.title : '' });
  }
  // A dry run is for the administrator's own eyes and shows immediately; it is
  // never published, never ranked, never counted.
  const isTest = attempt.mode === 'TEST';
  if (!isTest && (!result.published || result.withheld)) {
    return Q.ok(res, {
      available: false,
      reason: result.withheld ? 'WITHHELD' : 'NOT_PUBLISHED',
      examTitle: ex ? ex.title : '',
      publishAtMs: ex ? ex.result_publish_ms : null,
      message: result.withheld
        ? 'Your result is under administrative review.'
        : 'Your examination has been submitted. Results will be published by the administrator.',
    });
  }

  // Rank is computed live over published, non-withheld official results:
  // score descending, then elapsed time ascending.
  //
  // Two ranks are reported. The overall one places the participant in the
  // whole division; the category one places them among their own — Departmental
  // or GDS — which is the rank the prizes are actually awarded on, so it is the
  // one that matters to them.
  let rank = null, outOf = null, categoryRank = null, categoryOutOf = null;
  if (ex && ex.rank_enabled && !isTest) {
    // Before migration_v22 the overall rank still works; only the category
    // standing is unavailable, and it is simply not shown.
    const haveCategory = await Q.columnsReady('quiz_results');
    const peers = await Q.sbSelect('quiz_results',
      'select=attempt_id,score,elapsed_seconds' + (haveCategory ? ',category' : '') +
      '&exam_id=' + Q.eq(attempt.exam_id) +
      '&mode=' + Q.eq('OFFICIAL') + '&published=is.true&withheld=is.false' +
      '&order=score.desc,elapsed_seconds.asc');
    outOf = (peers || []).length;
    const idx = (peers || []).findIndex(p => p.attempt_id === attemptId);
    if (idx >= 0) rank = idx + 1;

    const myCategory = result.category || (registration && registration.category) || null;
    if (myCategory) {
      const same = (peers || []).filter(p => p.category === myCategory);
      categoryOutOf = same.length;
      const ci = same.findIndex(p => p.attempt_id === attemptId);
      if (ci >= 0) categoryRank = ci + 1;
    }
  }

  let answerKey = null;
  // A dry run always shows the key — verifying the key is the point of it.
  if (isTest || (ex && ex.show_answer_key)) answerKey = await buildAnswerKey(attempt);

  return Q.ok(res, {
    available: true,
    testMode: isTest,
    examTitle: ex ? ex.title : '',
    participant: registration ? {
      employeeName: registration.employee_name,
      employeeId: registration.employee_id,
      designation: registration.designation,
      office: registration.office,
      category: registration.category || null,
      categoryLabel: Q.categoryLabel(registration.category),
      unit: registration.unit || null,
      regCode: registration.reg_code,
    } : {
      // A TEST attempt has no registration — it is an administrator dry run.
      employeeName: attempt.actor_label || 'Administrator test',
      employeeId: '—', designation: 'TEST MODE', office: '—', regCode: 'TEST',
      category: null, categoryLabel: 'Unspecified', unit: null,
    },
    result: shapeResult(result, ex),
    rank, outOf,
    categoryRank, categoryOutOf,
    categoryLabel: Q.categoryLabel(result.category || (registration && registration.category)),
    answerKey,
  });
}

// Only ever called when the exam explicitly permits it. Until then the key
// stays on the server, even after submission.
async function buildAnswerKey(attempt) {
  const qs = await Q.sbSelect('quiz_attempt_questions',
    'select=seq,exam_seq,option_order&attempt_id=' + Q.eq(attempt.id) + '&order=seq.asc');
  const answers = await Q.sbSelect('quiz_answers',
    'select=seq,chosen_original,is_correct&attempt_id=' + Q.eq(attempt.id));
  // The whole paper in one query, indexed by sequence — not one query per
  // question, which would be 20 round trips to render a single result page.
  const paper = await Q.sbSelect('quiz_exam_questions',
    'select=seq,snapshot&exam_id=' + Q.eq(attempt.exam_id));
  const snapBySeq = {};
  for (const r of paper || []) snapBySeq[r.seq] = r.snapshot || {};

  const out = [];
  for (const row of qs || []) {
    const snap = snapBySeq[row.exam_seq] || {};
    const mine = (answers || []).find(a => a.seq === row.seq);
    out.push({
      seq: row.seq,
      questionText: snap.question_text,
      options: ['a', 'b', 'c', 'd'].map(k => ({ key: k.toUpperCase(), text: snap['option_' + k] })),
      correctOption: snap.correct_option,
      yourOption: mine ? mine.chosen_original : null,
      isCorrect: mine ? !!mine.is_correct : null,
      explanation: snap.explanation || null,
      reference: snap.source_ref || null,
    });
  }
  return out;
}

// ═══════════════════════════════════════════════════════════════════
//  INTEGRITY SIGNALS
//
//  Advisory only. A tab switch is logged for administrator review; it never
//  ends an examination or disqualifies anybody on its own.
// ═══════════════════════════════════════════════════════════════════
async function eventAction(req, res) {
  const ctx = await actorFor(req);
  const identity = ctx.identity || null;
  const registration = ctx.registration;
  const body = await Q.readJsonBody(req);
  const type = String(body.type || '').toUpperCase().slice(0, 40);

  const ALLOWED = ['TAB_HIDDEN', 'TAB_VISIBLE', 'OFFLINE', 'ONLINE', 'PAGE_EXIT', 'COPY_BLOCKED',
    'CONTEXT_MENU_BLOCKED', 'SCREENSHOT_KEY', 'RESUME'];
  if (!ALLOWED.includes(type)) return Q.ok(res, { logged: false });

  await Q.logEvent(req, {
    attempt_id: body.attempt_id ? String(body.attempt_id) : null,
    registration_id: registration ? registration.id : null,
    identity_id: identity ? identity.id : null,
    type,
    severity: ['TAB_HIDDEN', 'COPY_BLOCKED', 'CONTEXT_MENU_BLOCKED', 'SCREENSHOT_KEY']
      .includes(type) ? 'NOTICE' : 'INFO',
    detail: { at: Date.now() },
  });
  return Q.ok(res, { logged: true });
}

// ═══════════════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════════════

// Who is driving this attempt?
//
// Normally a signed-in employee. But Admin Test Mode runs a real dry run
// through these very endpoints — same timers, same locking, same scoring —
// which is the only way a rehearsal proves anything. So an ADMIN session is
// also accepted, and `attemptActor` then restricts it to TEST attempts it
// started itself. An admin can never touch an OFFICIAL attempt here.
async function actorFor(req) {
  const ps = await Q.readSession(req, 'PARTICIPANT');
  if (ps) {
    const ctx = await Q.requireParticipant(req);
    return { kind: 'PARTICIPANT', identity: ctx.identity, registration: ctx.registration };
  }
  const as = await Q.readSession(req, 'ADMIN');
  if (as) {
    if (!Q.roleAtLeast(as.quiz_role, 'QUIZ_EDITOR')) {
      throw new Q.AuthError('Your quiz role does not permit test attempts.', 403);
    }
    return { kind: 'ADMIN', username: as.username, role: as.quiz_role, registration: null };
  }
  throw new Q.AuthError('Please sign in to continue.', 401);
}

// Every attempt-scoped endpoint goes through this. An attempt id alone is not
// authority.
async function loadOwnAttempt(attemptId, ctx) {
  const attempt = await Q.sbOne('quiz_attempts', 'select=*&id=' + Q.eq(attemptId));
  if (!attempt) throw new Q.AppError('Attempt not found.', 404);

  if (ctx.kind === 'ADMIN') {
    if (attempt.mode !== 'TEST') {
      throw new Q.AuthError('Official attempts cannot be opened from the administration module.', 403);
    }
    if (attempt.actor_label && attempt.actor_label !== ctx.username) {
      throw new Q.AuthError('This test attempt belongs to another administrator.', 403);
    }
    return attempt;
  }

  if (!ctx.registration) throw new Q.AppError('Please complete registration first.', 403, 'NOT_REGISTERED');
  if (attempt.registration_id !== ctx.registration.id) {
    throw new Q.AuthError('This attempt does not belong to you.', 403);
  }
  return attempt;
}

function shapeRegistration(r) {
  if (!r) return null;
  return {
    regCode: r.reg_code,
    employeeId: r.employee_id,
    employeeName: r.employee_name,
    designation: r.designation,
    office: r.office,
    category: r.category || null,
    categoryLabel: Q.categoryLabel(r.category),
    unit: r.unit || null,
    status: r.status,
    rosterVerified: r.roster_verified,
    remarks: r.remarks,
    createdAtMs: r.created_at_ms,
  };
}

function shapeResult(r, ex) {
  if (!r) return null;
  return {
    attemptId: r.attempt_id,
    examTitle: ex ? ex.title : null,
    attempted: r.attempted,
    correct: r.correct,
    wrong: r.wrong,
    unanswered: r.unanswered,
    positiveEarned: Number(r.positive_earned),
    negativeDeducted: Number(r.negative_deducted),
    score: Number(r.score),
    maxMarks: Number(r.max_marks),
    percentage: Number(r.percentage),
    elapsedSeconds: r.elapsed_seconds,
    publishedMs: r.published_ms,
  };
}

// Exposed for the test harness in Leave Orders/quiz/selftest.js. These are pure
// functions with no database access; the handler above is the only entry point
// the HTTP layer uses.
module.exports._internals = { examGate, eligible };
