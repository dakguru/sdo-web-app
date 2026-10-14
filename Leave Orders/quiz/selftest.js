/*  selftest.js — logic tests for the Quiz module
 *  ─────────────────────────────────────────────────────────────────────────
 *  Run from the repository root:   node "Leave Orders/quiz/selftest.js"
 *
 *  Covers the parts where a silent mistake would change somebody's marks or
 *  let them into a closed examination: the scoring formula, the option
 *  permutation, the eligibility and timing gate, the IST conversion used by
 *  the scheduling form, and the countdown formatting.
 *
 *  No database, no network, no environment variables needed. These are the
 *  pure functions; everything stateful is exercised against the live
 *  deployment using the acceptance list in DEPLOYMENT.md.
 */

'use strict';

const assert = require('assert');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const Q = require(path.join(ROOT, 'api', '_quizlib.js'));
const participant = require(path.join(ROOT, 'api', 'quiz-participant.js'));
const { examGate, eligible } = participant._internals;
const admin = require(path.join(ROOT, 'api', 'quiz-admin.js'));
const { rankWithin, buildLeaderboard } = admin._internals;

let pass = 0, fail = 0;
function test(name, fn) {
  try { fn(); pass++; console.log('  ✓ ' + name); }
  catch (e) { fail++; console.log('  ✗ ' + name + '\n      ' + e.message); }
}
function group(name) { console.log('\n' + name); }

const POLICY = {
  question_count: 20, positive_marks: 4, negative_marks: 1, unanswered_marks: 0,
};
const answers = (correct, wrong, skippedServed) => {
  const out = [];
  for (let i = 0; i < correct; i++) out.push({ chosen_slot: 'A', is_correct: true });
  for (let i = 0; i < wrong; i++) out.push({ chosen_slot: 'B', is_correct: false });
  // A served-but-skipped question has a row with no chosen option; it must
  // count as unanswered, not as wrong.
  for (let i = 0; i < (skippedServed || 0); i++) out.push({ chosen_slot: null, is_correct: null });
  return out;
};

/* ═══════════════════════════════════════════════════════════════════
   SCORING
   ═══════════════════════════════════════════════════════════════════ */
group('Scoring — Q.computeScore');

test('the worked example from the specification: 15 correct, 3 wrong, 2 unanswered = 57 of 80', () => {
  const r = Q.computeScore(POLICY, answers(15, 3));
  assert.strictEqual(r.correct, 15);
  assert.strictEqual(r.wrong, 3);
  assert.strictEqual(r.unanswered, 2);
  assert.strictEqual(r.positive_earned, 60);
  assert.strictEqual(r.negative_deducted, 3);
  assert.strictEqual(r.score, 57);
  assert.strictEqual(r.max_marks, 80);
  assert.strictEqual(r.percentage, 71.25);
});

test('a skipped question that was served still scores zero, not minus one', () => {
  const r = Q.computeScore(POLICY, answers(10, 0, 5));
  assert.strictEqual(r.attempted, 10);
  assert.strictEqual(r.wrong, 0);
  assert.strictEqual(r.unanswered, 10, 'five skipped plus five never served');
  assert.strictEqual(r.score, 40);
});

test('a full paper of wrong answers goes negative, and is not clamped at zero', () => {
  const r = Q.computeScore(POLICY, answers(0, 20));
  assert.strictEqual(r.score, -20);
  assert.strictEqual(r.percentage, -25);
});

test('an untouched paper scores zero of the maximum', () => {
  const r = Q.computeScore(POLICY, []);
  assert.strictEqual(r.attempted, 0);
  assert.strictEqual(r.unanswered, 20);
  assert.strictEqual(r.score, 0);
  assert.strictEqual(r.max_marks, 80);
  assert.strictEqual(r.percentage, 0);
});

test('a perfect paper scores the maximum and exactly 100 per cent', () => {
  const r = Q.computeScore(POLICY, answers(20, 0));
  assert.strictEqual(r.score, 80);
  assert.strictEqual(r.percentage, 100);
});

test('negative marks are read as a magnitude, so a configured 0.25 deducts 0.25', () => {
  const r = Q.computeScore(
    { question_count: 10, positive_marks: 1, negative_marks: 0.25, unanswered_marks: 0 },
    answers(6, 4));
  assert.strictEqual(r.score, 5);
});

test('a non-zero unanswered policy is honoured when the administrator sets one', () => {
  const r = Q.computeScore(
    { question_count: 10, positive_marks: 4, negative_marks: 1, unanswered_marks: 1 },
    answers(5, 2));
  // 5x4 - 2x1 + 3x1
  assert.strictEqual(r.unanswered, 3);
  assert.strictEqual(r.score, 21);
});

test('the policy comes off the attempt, so two attempts of the same paper can score differently', () => {
  const a = Q.computeScore({ question_count: 20, positive_marks: 4, negative_marks: 1 }, answers(10, 10));
  const b = Q.computeScore({ question_count: 20, positive_marks: 2, negative_marks: 0 }, answers(10, 10));
  assert.strictEqual(a.score, 30);
  assert.strictEqual(b.score, 20);
});

test('more answer rows than questions cannot drive unanswered below zero', () => {
  const r = Q.computeScore({ question_count: 5, positive_marks: 4, negative_marks: 1 }, answers(5, 3));
  assert.strictEqual(r.unanswered, 0);
});

test('percentage is rounded to two decimals, not left as a long float', () => {
  const r = Q.computeScore({ question_count: 3, positive_marks: 1, negative_marks: 0 }, answers(1, 0));
  assert.strictEqual(r.percentage, 33.33);
});

/* ═══════════════════════════════════════════════════════════════════
   OPTION PERMUTATION
   ═══════════════════════════════════════════════════════════════════ */
group('Option permutation — display order and de-permutation');

// Mirrors the serving code in examStateAction and the judging code in
// answerAction. If these two ever disagree, every randomised paper is marked
// against the wrong option, so the round trip is worth asserting directly.
function displayOptions(snapshot, order) {
  return order.split('').map((orig, idx) => ({
    slot: 'ABCD'[idx], text: snapshot['option_' + orig.toLowerCase()],
  }));
}
function dePermute(order, slot) {
  return order['ABCD'.indexOf(slot)];
}

const SNAP = {
  option_a: 'alpha', option_b: 'bravo', option_c: 'charlie', option_d: 'delta',
  correct_option: 'C',
};

test('with no randomisation the display order is the stored order', () => {
  const shown = displayOptions(SNAP, 'ABCD');
  assert.deepStrictEqual(shown.map(o => o.text), ['alpha', 'bravo', 'charlie', 'delta']);
  assert.strictEqual(dePermute('ABCD', 'C'), 'C');
});

test('tapping the slot showing the correct text is judged correct under every permutation', () => {
  const perms = allPermutations('ABCD');
  assert.strictEqual(perms.length, 24, 'all 24 permutations are covered');
  for (const order of perms) {
    const shown = displayOptions(SNAP, order);
    // Find the slot whose visible text is the genuinely correct option.
    const correctText = SNAP['option_' + SNAP.correct_option.toLowerCase()];
    const slot = shown.find(o => o.text === correctText).slot;
    assert.strictEqual(dePermute(order, slot), SNAP.correct_option,
      'order ' + order + ': slot ' + slot + ' should map to C');
  }
});

test('tapping any other slot is judged wrong under every permutation', () => {
  for (const order of allPermutations('ABCD')) {
    const shown = displayOptions(SNAP, order);
    const correctText = SNAP['option_' + SNAP.correct_option.toLowerCase()];
    for (const o of shown) {
      if (o.text === correctText) continue;
      assert.notStrictEqual(dePermute(order, o.slot), SNAP.correct_option,
        'order ' + order + ': slot ' + o.slot + ' must not map to C');
    }
  }
});

test('every permutation shows all four options exactly once — none lost or repeated', () => {
  for (const order of allPermutations('ABCD')) {
    const texts = displayOptions(SNAP, order).map(o => o.text).sort();
    assert.deepStrictEqual(texts, ['alpha', 'bravo', 'charlie', 'delta'],
      'order ' + order);
  }
});

test('the shuffler only ever produces valid ABCD permutations', () => {
  for (let i = 0; i < 400; i++) {
    const order = Q.secureShuffle(['A', 'B', 'C', 'D']).join('');
    assert.ok(/^[ABCD]{4}$/.test(order), order + ' is not four ABCD letters');
    assert.strictEqual(new Set(order).size, 4, order + ' repeats a letter');
  }
});

function allPermutations(s) {
  if (s.length <= 1) return [s];
  const out = [];
  for (let i = 0; i < s.length; i++) {
    for (const rest of allPermutations(s.slice(0, i) + s.slice(i + 1))) {
      out.push(s[i] + rest);
    }
  }
  return out;
}

/* ═══════════════════════════════════════════════════════════════════
   SHUFFLE QUALITY
   ═══════════════════════════════════════════════════════════════════ */
group('Randomisation — Q.secureShuffle');

test('each of 10 questions reaches first position roughly equally over 6000 draws', () => {
  const pool = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  const first = {};
  const N = 6000;
  for (let i = 0; i < N; i++) {
    const f = Q.secureShuffle(pool)[0];
    first[f] = (first[f] || 0) + 1;
  }
  const expected = N / pool.length;
  for (const k of pool) {
    const got = first[k] || 0;
    // Generous band: this is a smoke test for bias, not a statistics exam.
    assert.ok(Math.abs(got - expected) < expected * 0.35,
      'value ' + k + ' appeared first ' + got + ' times, expected about ' + expected);
  }
});

test('the shuffle returns a new array and leaves the original untouched', () => {
  const pool = [1, 2, 3, 4, 5];
  const copy = pool.slice();
  Q.secureShuffle(pool);
  assert.deepStrictEqual(pool, copy);
});

test('randomInt stays inside its bound and handles the degenerate cases', () => {
  assert.strictEqual(Q.randomInt(1), 0);
  assert.strictEqual(Q.randomInt(0), 0);
  for (let i = 0; i < 2000; i++) {
    const n = Q.randomInt(7);
    assert.ok(n >= 0 && n < 7, 'randomInt(7) returned ' + n);
  }
});

/* ═══════════════════════════════════════════════════════════════════
   DUPLICATE DETECTION
   ═══════════════════════════════════════════════════════════════════ */
group('Duplicate detection — Q.questionHash');

test('wording identical but for case, spacing and punctuation hashes the same', () => {
  const a = Q.questionHash('In which year did the Post Office Act come into force?');
  const b = Q.questionHash('in which year did the post office act come into force');
  const c = Q.questionHash('In  which   year did the Post Office Act come into force ?');
  assert.strictEqual(a, b);
  assert.strictEqual(a, c);
});

test('a genuinely different question hashes differently', () => {
  assert.notStrictEqual(
    Q.questionHash('Who is the Director General of Postal Services?'),
    Q.questionHash('Who is the Secretary, Department of Posts?'));
});

test('an empty question still produces a stable hash rather than throwing', () => {
  assert.strictEqual(Q.questionHash(''), Q.questionHash(null));
});

/* ═══════════════════════════════════════════════════════════════════
   ELIGIBILITY AND THE EXAMINATION GATE
   ═══════════════════════════════════════════════════════════════════ */
group('Eligibility — examGate and eligible');

const NOW = Date.UTC(2026, 9, 14, 4, 30);      // 14 Oct 2026, 10:00 IST
const HOUR = 3600 * 1000;

const EX = (over) => Object.assign({
  id: 'e1', status: 'LIVE',
  exam_open_ms: NOW - HOUR, exam_close_ms: NOW + HOUR,
  latest_start_ms: null, attempts_allowed: 1, late_entry_allowed: true,
  total_seconds: 600, question_count: 20,
  eligible_offices: null, eligible_designations: null,
}, over || {});

const REG = (over) => Object.assign({
  id: 'r1', status: 'APPROVED', office: 'Karur H.O', designation: 'Postal Assistant',
}, over || {});

test('an approved employee inside the window may start', () => {
  const g = examGate(EX(), REG(), [], NOW);
  assert.strictEqual(g.canStart, true);
  assert.strictEqual(g.phase, 'OPEN');
});

test('a pending registration is refused, and told why', () => {
  const g = examGate(EX(), REG({ status: 'PENDING' }), [], NOW);
  assert.strictEqual(g.canStart, false);
  assert.strictEqual(g.phase, 'NOT_APPROVED');
  assert.match(g.reason, /awaiting administrator approval/i);
});

['REJECTED', 'SUSPENDED', 'CANCELLED'].forEach(s => {
  test('a ' + s.toLowerCase() + ' registration cannot start', () => {
    assert.strictEqual(examGate(EX(), REG({ status: s }), [], NOW).canStart, false);
  });
});

test('before the opening time the phase is NOT_STARTED, which is what drives the countdown', () => {
  const g = examGate(EX({ exam_open_ms: NOW + HOUR }), REG(), [], NOW);
  assert.strictEqual(g.canStart, false);
  assert.strictEqual(g.phase, 'NOT_STARTED');
  assert.strictEqual(g.reason, 'Examination Not Yet Started');
});

test('after the closing time the examination is closed', () => {
  const g = examGate(EX({ exam_close_ms: NOW - 1 }), REG(), [], NOW);
  assert.strictEqual(g.canStart, false);
  assert.strictEqual(g.phase, 'CLOSED');
});

test('past the latest permitted start time a NEW attempt is refused', () => {
  const g = examGate(EX({ latest_start_ms: NOW - 1 }), REG(), [], NOW);
  assert.strictEqual(g.canStart, false);
  assert.strictEqual(g.phase, 'LATE');
});

test('an attempt already used blocks a second one when only one is allowed', () => {
  const g = examGate(EX(), REG(), [{ status: 'SUBMITTED' }], NOW);
  assert.strictEqual(g.canStart, false);
  assert.strictEqual(g.phase, 'ATTEMPTED');
});

test('a second attempt is allowed when the examination permits two', () => {
  const g = examGate(EX({ attempts_allowed: 2 }), REG(), [{ status: 'SUBMITTED' }], NOW);
  assert.strictEqual(g.canStart, true);
});

test('an attempt in progress with time left resumes rather than starting afresh', () => {
  const g = examGate(EX(), REG(), [{ status: 'IN_PROGRESS', deadline_ms: NOW + 60000 }], NOW);
  assert.strictEqual(g.canStart, true);
  assert.strictEqual(g.phase, 'RESUME');
});

test('an attempt in progress whose deadline passed is not resumable', () => {
  const g = examGate(EX(), REG(), [{ status: 'IN_PROGRESS', deadline_ms: NOW - 1 }], NOW);
  assert.strictEqual(g.canStart, false);
  assert.strictEqual(g.phase, 'EXPIRED');
});

test('resuming takes priority over an exhausted attempt allowance', () => {
  const g = examGate(EX(), REG(),
    [{ status: 'SUBMITTED' }, { status: 'IN_PROGRESS', deadline_ms: NOW + 1000 }], NOW);
  assert.strictEqual(g.phase, 'RESUME');
});

test('a cancelled examination cannot be started by anybody', () => {
  assert.strictEqual(examGate(EX({ status: 'CANCELLED' }), REG(), [], NOW).canStart, false);
});

test('a draft examination is not startable even inside its window', () => {
  const g = examGate(EX({ status: 'DRAFT' }), REG(), [], NOW);
  assert.strictEqual(g.canStart, false);
});

test('an empty eligibility list means the whole division', () => {
  assert.strictEqual(eligible(EX({ eligible_offices: [] }), REG()), true);
  assert.strictEqual(eligible(EX({ eligible_offices: null }), REG()), true);
});

test('an office restriction admits the listed office and refuses the rest', () => {
  assert.strictEqual(eligible(EX({ eligible_offices: ['Karur H.O'] }), REG()), true);
  assert.strictEqual(
    eligible(EX({ eligible_offices: ['Kulittalai H.O'] }), REG()), false);
});

test('office matching ignores case and stray spacing, as typed by an administrator', () => {
  assert.strictEqual(
    eligible(EX({ eligible_offices: ['  karur h.o  '] }), REG()), true);
});

test('a designation restriction is applied as well as the office restriction', () => {
  assert.strictEqual(
    eligible(EX({ eligible_designations: ['Postal Assistant'] }), REG()), true);
  assert.strictEqual(
    eligible(EX({ eligible_designations: ['Sub Postmaster'] }), REG()), false);
  // Both lists must pass, not either.
  assert.strictEqual(
    eligible(EX({ eligible_offices: ['Karur H.O'], eligible_designations: ['Driver'] }), REG()),
    false);
});

test('an ineligible employee is refused with the eligibility reason, not a timing one', () => {
  const g = examGate(EX({ eligible_offices: ['Somewhere Else'] }), REG(), [], NOW);
  assert.strictEqual(g.phase, 'NOT_ELIGIBLE');
});

/* ═══════════════════════════════════════════════════════════════════
   DEADLINES
   ═══════════════════════════════════════════════════════════════════ */
group('Deadlines — whichever applicable limit falls first');

// Mirrors the deadline computation in examStartAction.
function attemptDeadline(ex, startMs) {
  const byDuration = startMs + ex.total_seconds * 1000;
  const closeCap = ex.exam_close_ms
    ? ex.exam_close_ms + (ex.on_close === 'GRACE' ? (ex.grace_seconds || 0) * 1000 : 0)
    : null;
  return closeCap ? Math.min(byDuration, closeCap) : byDuration;
}

test('starting well inside the window, the overall duration governs', () => {
  const ex = { total_seconds: 600, exam_close_ms: NOW + HOUR, on_close: 'AUTO_SUBMIT' };
  assert.strictEqual(attemptDeadline(ex, NOW), NOW + 600000);
});

test('starting near the close, the examination closing time governs instead', () => {
  const ex = { total_seconds: 600, exam_close_ms: NOW + 120000, on_close: 'AUTO_SUBMIT' };
  assert.strictEqual(attemptDeadline(ex, NOW), NOW + 120000,
    'a late starter does not get to run past the closing time');
});

test('a configured grace period extends the cap by exactly that much', () => {
  const ex = { total_seconds: 600, exam_close_ms: NOW + 120000, on_close: 'GRACE', grace_seconds: 90 };
  assert.strictEqual(attemptDeadline(ex, NOW), NOW + 120000 + 90000);
});

test('grace never extends beyond the overall duration', () => {
  const ex = { total_seconds: 600, exam_close_ms: NOW + HOUR, on_close: 'GRACE', grace_seconds: 600 };
  assert.strictEqual(attemptDeadline(ex, NOW), NOW + 600000);
});

test('with no closing time set, the duration alone governs', () => {
  assert.strictEqual(
    attemptDeadline({ total_seconds: 300, exam_close_ms: null }, NOW), NOW + 300000);
});

test('a per-question deadline is capped by the overall deadline', () => {
  // Mirrors examStateAction: Math.min(now + spq, attempt.deadline_ms)
  const attempt = { seconds_per_question: 30, deadline_ms: NOW + 10000 };
  const qDeadline = Math.min(NOW + attempt.seconds_per_question * 1000, attempt.deadline_ms);
  assert.strictEqual(qDeadline, NOW + 10000,
    'the question clock cannot outlive the paper clock');
});

/* ═══════════════════════════════════════════════════════════════════
   INDIAN STANDARD TIME
   ═══════════════════════════════════════════════════════════════════ */
group('Indian Standard Time');

// Mirrors QuizAdmin.localInputToMs / msToLocalInput from quiz-admin-core.js.
// The scheduling form must mean IST regardless of the administrator's own
// machine timezone, so this conversion is explicit rather than left to Date.
function localInputToMs(v) {
  const m = String(v).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/);
  if (!m) return null;
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) - (330 * 60000);
}
function msToLocalInput(ms) {
  const d = new Date(ms);
  const ist = new Date(d.getTime() + (330 + d.getTimezoneOffset()) * 60000);
  const p = n => (n < 10 ? '0' + n : '' + n);
  return ist.getFullYear() + '-' + p(ist.getMonth() + 1) + '-' + p(ist.getDate()) +
    'T' + p(ist.getHours()) + ':' + p(ist.getMinutes());
}

test('11:00 IST on 15 Oct 2026 is 05:30 UTC', () => {
  const ms = localInputToMs('2026-10-15T11:00');
  assert.strictEqual(new Date(ms).toISOString(), '2026-10-15T05:30:00.000Z');
});

test('midnight IST maps to 18:30 UTC the previous day', () => {
  const ms = localInputToMs('2026-10-15T00:00');
  assert.strictEqual(new Date(ms).toISOString(), '2026-10-14T18:30:00.000Z');
});

test('the conversion round-trips, so an edited examination keeps its time', () => {
  for (const v of ['2026-10-09T10:00', '2026-10-15T23:45', '2026-01-01T00:00', '2026-12-31T12:30']) {
    assert.strictEqual(msToLocalInput(localInputToMs(v)), v, v + ' did not round-trip');
  }
});

test('istString renders a readable IST stamp regardless of the server timezone', () => {
  const s = Q.istString(localInputToMs('2026-10-15T11:00'));
  assert.match(s, /15 Oct 2026/);
  assert.match(s, /11:00/);
});

test('istString returns an empty string for a missing timestamp rather than 1970', () => {
  assert.strictEqual(Q.istString(null), '');
  assert.strictEqual(Q.istString(0), '');
});

/* ═══════════════════════════════════════════════════════════════════
   COUNTDOWN FORMATTING
   ═══════════════════════════════════════════════════════════════════ */
group('Countdown formatting');

// Mirrors QuizTime.clock and QuizTime.split from quiz-core.js.
function clock(msLeft) {
  const s = Math.max(0, Math.floor(msLeft / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const p2 = n => (n < 10 ? '0' + n : '' + n);
  return h > 0 ? h + ':' + p2(m) + ':' + p2(sec) : p2(m) + ':' + p2(sec);
}

test('under an hour the clock reads mm:ss', () => {
  assert.strictEqual(clock(600000), '10:00');
  assert.strictEqual(clock(59000), '00:59');
  assert.strictEqual(clock(1000), '00:01');
});

test('over an hour the clock reads h:mm:ss', () => {
  assert.strictEqual(clock(3600000), '1:00:00');
  assert.strictEqual(clock(3661000), '1:01:01');
});

test('a negative remainder shows zero, never a minus sign', () => {
  assert.strictEqual(clock(-5000), '00:00');
  assert.strictEqual(clock(0), '00:00');
});

/* ═══════════════════════════════════════════════════════════════════
   STAFF CATEGORY AND UNIT

   The category decides which of the two prize lists an employee competes
   in, so a spelling the roster happens to use must never quietly become
   the wrong category — or no category at all when it is a valid one.
   ═══════════════════════════════════════════════════════════════════ */
group('Staff category and unit');

test('every spelling of GDS the roster uses lands on GDS', () => {
  for (const v of ['GDS', 'gds', 'G.D.S.', ' Gds ', 'Gramin Dak Sevak', 'Extra Departmental']) {
    assert.strictEqual(Q.normCategory(v), 'GDS', v);
  }
});

test('every spelling of Departmental lands on DEPARTMENTAL', () => {
  for (const v of ['Departmental', 'departmental', 'DS', 'Dept', 'Regular']) {
    assert.strictEqual(Q.normCategory(v), 'DEPARTMENTAL', v);
  }
});

test('an unrecognised category becomes null rather than a guess', () => {
  // Landing in the wrong prize list is worse than landing in none: null shows
  // as "Unspecified", which an administrator can see and correct.
  assert.strictEqual(Q.normCategory('Casual Labour'), null);
  assert.strictEqual(Q.normCategory(''), null);
  assert.strictEqual(Q.normCategory(null), null);
  assert.strictEqual(Q.categoryLabel(null), 'Unspecified');
});

test('the Divisional Office is one unit, however the roster spells it', () => {
  // The Karur roster carries both "DO Karur" and "KARUR DO"; left alone they
  // would appear as two separate units on every unit-wise count.
  assert.strictEqual(Q.normUnit('DO Karur'), 'DO Karur');
  assert.strictEqual(Q.normUnit('KARUR DO'), 'DO Karur');
  assert.strictEqual(Q.normUnit('karur do'), 'DO Karur');
});

test('an unknown unit keeps the words the roster used', () => {
  assert.strictEqual(Q.normUnit('  Some New   Sub Division '), 'Some New Sub Division');
  assert.strictEqual(Q.normUnit(''), null);
});

/* ═══════════════════════════════════════════════════════════════════
   CATEGORY-WISE RANKING AND PRIZE LISTS
   ═══════════════════════════════════════════════════════════════════ */
group('Category-wise ranking and prize lists');

const competitor = (id, category, score, secs, extra) => Object.assign({
  attempt_id: id, category, score, elapsed_seconds: secs,
  max_marks: 80, percentage: score, withheld: false, published: true,
  employee: { employee_id: id, employee_name: 'Employee ' + id, designation: 'Postal Assistant',
              office: 'Karur H.O', reg_code: 'NPW2026-' + id },
}, extra || {});

test('rank is by score, then by the faster time', () => {
  const r = rankWithin([
    competitor('a', 'GDS', 40, 300),
    competitor('b', 'GDS', 60, 400),
    competitor('c', 'GDS', 60, 200),
  ]);
  assert.deepStrictEqual(r.map(x => x.attempt_id), ['c', 'b', 'a']);
});

test('an exact tie shares a rank and the next place skips', () => {
  // Two people on the same score AND the same time are genuinely level, so
  // the list must read 1, 2, 2, 4 — not 1, 2, 3, 4.
  const r = rankWithin([
    competitor('a', 'GDS', 60, 200),
    competitor('b', 'GDS', 50, 100),
    competitor('c', 'GDS', 50, 100),
    competitor('d', 'GDS', 10, 100),
  ]);
  assert.deepStrictEqual(r.map(x => x.rank), [1, 2, 2, 4]);
});

const field = [
  competitor('d1', 'DEPARTMENTAL', 70, 200), competitor('d2', 'DEPARTMENTAL', 65, 300),
  competitor('d3', 'DEPARTMENTAL', 60, 250), competitor('d4', 'DEPARTMENTAL', 55, 250),
  competitor('g1', 'GDS', 50, 100), competitor('g2', 'GDS', 48, 120),
  competitor('g3', 'GDS', 40, 500), competitor('g4', 'GDS', 39, 500),
  competitor('u1', null, 75, 100),
];

test('Departmental and GDS are ranked separately, each from first place', () => {
  const board = buildLeaderboard(rankWithin(field), 3);
  assert.deepStrictEqual(board.map(b => b.category), ['DEPARTMENTAL', 'GDS', 'UNSPECIFIED']);
  assert.deepStrictEqual(board[0].winners.map(w => w.employeeId), ['d1', 'd2', 'd3']);
  assert.deepStrictEqual(board[1].winners.map(w => w.employeeId), ['g1', 'g2', 'g3']);
  assert.deepStrictEqual(board[1].winners.map(w => w.rank), [1, 2, 3],
    'the top GDS participant is first in GDS, not fifth overall');
});

test('an employee with no category is listed apart, not folded into a prize list', () => {
  const board = buildLeaderboard(rankWithin(field), 3);
  const unspecified = board.filter(b => b.category === 'UNSPECIFIED')[0];
  assert.ok(unspecified, 'the uncategorised participant is still shown');
  assert.strictEqual(unspecified.winners.length, 1);
  assert.ok(!board[0].winners.some(w => w.employeeId === 'u1'),
    'the highest score in the examination does not win a prize it has no category for');
});

test('a withheld result cannot win a prize, and the place passes to the next', () => {
  const board = buildLeaderboard(rankWithin(
    field.map(r => r.attempt_id === 'd1' ? Object.assign({}, r, { withheld: true }) : r)), 3);
  assert.deepStrictEqual(board[0].winners.map(w => w.employeeId), ['d2', 'd3', 'd4']);
});

test('a tie for third shows both names — a top three can be four people long', () => {
  const board = buildLeaderboard(rankWithin([
    competitor('a', 'GDS', 70, 100), competitor('b', 'GDS', 60, 100),
    competitor('c', 'GDS', 50, 100), competitor('d', 'GDS', 50, 100),
    competitor('e', 'GDS', 10, 100),
  ]), 3);
  assert.deepStrictEqual(board[0].winners.map(w => w.employeeId), ['a', 'b', 'c', 'd']);
  assert.deepStrictEqual(board[0].winners.map(w => w.rank), [1, 2, 3, 3]);
});

test('each category reports its own participant count and average', () => {
  const board = buildLeaderboard(rankWithin(field), 3);
  assert.deepStrictEqual(board.map(b => b.participants), [4, 4, 1]);
  assert.strictEqual(board[0].average, 62.5);
  assert.strictEqual(board[0].highest, 70);
});

/* ═══════════════════════════════════════════════════════════════════
   CONFIGURATION GUARD
   ═══════════════════════════════════════════════════════════════════ */
group('Configuration guard');

test('a missing service key or session secret is reported by name, not swallowed', () => {
  // This harness runs with no environment set, which is exactly the state a
  // fresh deployment is in before Step 2 of DEPLOYMENT.md.
  const err = Q.configError();
  if (process.env.SUPABASE_SERVICE_ROLE_KEY && process.env.QUIZ_SESSION_SECRET) {
    assert.strictEqual(err, null, 'a fully configured environment reports no error');
  } else {
    assert.ok(err, 'an unconfigured environment must report an error');
    assert.match(err, /SUPABASE_SERVICE_ROLE_KEY|QUIZ_SESSION_SECRET/);
  }
});

/* ═══════════════════════════════════════════════════════════════════ */
console.log('\n' + '─'.repeat(58));
console.log(pass + ' passed, ' + fail + ' failed');
console.log('─'.repeat(58));
process.exit(fail ? 1 : 0);
