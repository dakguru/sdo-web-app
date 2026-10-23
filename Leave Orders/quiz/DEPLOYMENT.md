# Employee Quiz & Assessment Module — Deployment & Operations

**National Postal Week 2026 · Online Quiz Competition**
Department of Posts · Karur Division, Karur – 639001
*"One Postal Network, Infinite Possibilities"*

Public URL: **https://krrsdo.vercel.app/Quiz**
Administration: **https://krrsdo.vercel.app/Quiz/admin**

---

## 1. What this module is, and how it fits the existing portal

The quiz is an **additive extension** of the existing KRRSDO portal. No existing
page, route, table, user record or permission was changed. The only edits to
existing files are:

| File | Change |
|---|---|
| `vercel.json` | 28 new rewrites for `/Quiz…`, inserted **before** the catch-all |
| `Leave Orders/homepage.html` | one new APPLICATIONS card + its styles + a status probe |

Everything else is new: `api/quiz-*.js`, `api/_quizlib.js`,
`Leave Orders/quiz/*`, and `android-app/supabase/migration_v20_quiz.sql`.

### The one deliberate architectural departure

Every other `app_*` table in this project is open to the Supabase **anon** key,
because those pages sit behind the client-side portal login and hold no secrets.

**An examination cannot work that way.** The question bank contains the answer
key. If `anon` could read `quiz_questions`, any participant could open the
browser console and fetch the correct answers from PostgREST directly.

So the quiz tables:

- have RLS enabled with **no permissive policy** — every browser role is denied;
- have all privileges **revoked** from `anon` and `authenticated`;
- are reachable **only** through `/api/quiz-*`, using the service-role key held
  in the Vercel environment.

> **Do not add an "anon rw" policy to any `quiz_*` table.** That single line
> would hand the answer key to every participant.

For the same reason, quiz administrators **re-authenticate on the server**.
The portal's own login verifies the PBKDF2 hash in the browser, which is fine
for pages holding no secrets but cannot guard an answer key. Administrators
enter their **existing** portal username and password once at `/Quiz/admin`;
the server verifies the hash itself (same PBKDF2-HMAC-SHA256, 256-bit, per-user
iteration count as `Leave Orders/login.html`) and issues an HttpOnly session
cookie. No new credentials are created.

---

## 2. Deployment checklist

### Step 1 — Run the database migration

In the Supabase SQL editor for project `hfzbvqpxraeoqmvhtjto`, run:

```
android-app/supabase/migration_v20_quiz.sql
```

It is idempotent (`create table if not exists` throughout), so re-running it is
safe. It creates 18 tables, two helper functions and the seeded default
settings.

Then run the follow-up migration, which adds the automatic-duration flag used
by the examination editor:

```
android-app/supabase/migration_v21_quiz_duration_mode.sql
```

It is likewise idempotent (`add column if not exists`), and existing
examinations keep their hand-entered duration.

Then run the staff category and unit migration, which is what makes the
Departmental and GDS prize lists possible:

```
android-app/supabase/migration_v22_quiz_category_unit.sql
```

**Run this before deploying the code**, not after. It adds `unit` to
`quiz_employees`, and `category` + `unit` to `quiz_registrations` and
`quiz_results`; the application writes those columns from the moment it is
deployed, so a submission that lands before the migration would fail.

It is idempotent and additive. It backfills the new columns from the records
already present and changes nothing else — every existing registration,
attempt, answer and result survives exactly as it was. The one value it
rewrites is a free-text `quiz_employees.category` that an earlier import left
as, say, `ds` or `Gramin Dak Sevak`; those are normalised onto `DEPARTMENTAL`
and `GDS`. Anything it cannot recognise is left alone and shows as
**Unspecified** for an administrator to correct by hand.

After deploying, go to **Employees → Import from Excel** and re-import
`Karur Division Employee List.xlsx` with the **Staff Category** and
**Unit/ Sub Division** columns mapped (the importer guesses both correctly from
that sheet's headers). Use mode **MERGE** — nobody is deactivated, and the
import finishes by filling in category and unit on the registrations that
already exist.

Finally, run the migration that gives every examination a question set of its
own:

```
android-app/supabase/migration_v23_quiz_exam_questions.sql
```

It adds one nullable column, `quiz_questions.exam_id`, plus its indexes. It
writes no existing row and deletes nothing, so it is safe to run at any time
— including on a database that already holds a published paper.

Unlike v22 this one is **not urgent**. The code degrades without it: the
Question Bank reports the migration as pending and names this file, the
examination cards say nothing about sets that cannot exist yet, and every
examination goes on drawing from the one shared bank exactly as before. An
upload aimed at a single examination is refused rather than quietly widened
into the shared bank. The probe re-checks every thirty seconds, so the module
picks the migration up on its own, with no redeploy.

Every question already in the bank keeps `exam_id` null, which *is* the shared
bank — so after running it, every examination that exists today still publishes
from precisely the pool it publishes from now. Nothing changes until you
deliberately upload a set against one examination.

And the migration that lets an examination name the employees who may sit it:

```
android-app/supabase/migration_v24_quiz_exam_allotments.sql
```

It adds `quiz_exams.allotment_mode` (defaulting to `ALL`) and the
`quiz_exam_allotments` table. It writes no existing row and deletes nothing,
so every examination keeps exactly the eligibility it has today.

Also **not urgent**, and it degrades the same way: until it is run, the
**Who can attempt** dialog reports the migration as pending and names the
file, the cards say nothing about allotment, and eligibility stays with the
office and designation rules.

### Step 2 — Set the environment variables in Vercel

Project → Settings → Environment Variables. All four are **required**; the
module refuses to operate rather than half-work without them.

| Variable | Where to get it | Notes |
|---|---|---|
| `SUPABASE_URL` | Supabase → Project Settings → API | `https://hfzbvqpxraeoqmvhtjto.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | Supabase → Project Settings → API → `service_role` | **Secret.** Full database access — never put this in client code |
| `QUIZ_SESSION_SECRET` | Generate one | Signs session cookies. See below |
| `GOOGLE_CLIENT_ID` | Google Cloud Console (Step 3) | The OAuth 2.0 **Web application** client ID |

Generate the session secret with any of:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
```

Changing `QUIZ_SESSION_SECRET` later signs everyone out — harmless between
examinations, disruptive during one.

### Step 3 — Create the Google OAuth client

Only you can do this; it cannot be automated.

1. <https://console.cloud.google.com/> → create or select a project.
2. **APIs & Services → OAuth consent screen**
   - User type: **External**
   - App name: `Karur Division Quiz` (or similar)
   - Support email and developer email: your address
   - Scopes: the defaults (`openid`, `email`, `profile`) are all that is needed.
     Do **not** add any further scope — the module never reads Gmail, Drive or
     contacts.
   - Publishing status: **In production** (while in *Testing*, only accounts you
     list by hand can sign in — with 782 employees that is unusable).
3. **APIs & Services → Credentials → Create credentials → OAuth client ID**
   - Application type: **Web application**
   - **Authorised JavaScript origins:**
     ```
     https://krrsdo.vercel.app
     ```
   - **Authorised redirect URIs:** none needed — the module uses Google
     Identity Services in ID-token mode, not a redirect flow.
4. Copy the **Client ID** (it ends in `.apps.googleusercontent.com`) into
   `GOOGLE_CLIENT_ID`. The **client secret is not used** and should not be
   added anywhere.

If you use a custom domain or a Vercel preview URL, add each origin to the
authorised origins list as well, or Google sign-in will fail there.

### Step 4 — Redeploy

Environment variables are read at function start, so **redeploy after setting
them**. Then confirm:

- `https://krrsdo.vercel.app/Quiz` loads and shows **Continue with Google**.
- `https://krrsdo.vercel.app/Quiz/admin` shows the administrator sign-in.
- `/Quiz/admin/settings` → **Deployment** shows `GOOGLE_CLIENT_ID: Configured`.

If something is missing, every page says exactly which variable, rather than
failing silently.

---

## 3. First-time setup, in order

### 3.1 Grant yourself the Quiz Administrator role

**Bootstrap, once per deployment.** The role-granting screen is itself
restricted to Quiz Administrators, so the *first* administrator cannot promote
themselves through the interface. Grant the role directly in the Supabase SQL
editor, replacing `your-portal-username`:

```sql
insert into public.quiz_admin_roles (username, quiz_role, granted_by, created_ms)
values ('your-portal-username', 'QUIZ_ADMIN', 'bootstrap',
        (extract(epoch from now()) * 1000)::bigint)
on conflict (username) do update
  set quiz_role = excluded.quiz_role,
      granted_by = excluded.granted_by,
      created_ms = excluded.created_ms;
```

Then **sign out of `/Quiz/admin` and sign in again** — the quiz role is stamped
onto the session at login, so an existing session keeps the old role until it
is replaced.

After that, everything is done from the interface: **Settings → Quiz
privileges** lists every portal user with a role dropdown. Grant a second
person Quiz Administrator before the examination so you are not a single point
of failure on the day.

Role resolution is:

1. an explicit grant in `quiz_admin_roles` wins; otherwise
2. the configurable `role_map` translates your portal role:
   `ADMIN → QUIZ_ADMIN`, `ASP → QUIZ_ADMIN`, `PA → QUIZ_EDITOR`, `MO → QUIZ_VIEWER`;
3. anything unmapped gets **no access**.

A portal login does **not** by itself confer quiz administration. Adjust
per-user grants at `/Quiz/admin/settings` → **Quiz privileges**.

| Role | Can do |
|---|---|
| **Quiz Administrator** | Everything: employees, examinations, publishing, results, settings, roles |
| **Quiz Editor** | Question bank, paper previews, test attempts |
| **Quiz Viewer** | Read-only reports |

### 3.2 Import the employee directory

`/Quiz/admin/employees` → **Import from Excel** → upload
`Karur Division Employee List.xlsx`.

Your file is already in the right shape — **782 employees, zero duplicates,
zero blanks, 333 offices, 32 designations** — and the column guesser picks up
`Employee_id / Employee_name / Post_desc / Office_desc / Unit/ Sub Division /
Staff Category` automatically.

**Map Staff Category and Unit/ Sub Division.** They are not decoration:

- **Staff Category** decides which of the two prize lists an employee competes
  in. The roster's `Departmental` and `GDS` are normalised to the two codes the
  module uses; so are `DS`, `Gramin Dak Sevak` and similar spellings. Anything
  unrecognised is reported row by row and stored as **Unspecified** — the
  employee can still register and sit the examination, but wins nothing until
  an administrator sets the category.
- **Unit/ Sub Division** is what the registration dashboard counts by. The
  Divisional Office appears in the roster as both `DO Karur` and `KARUR DO`;
  both are stored as **DO Karur**, so the unit is counted once, not twice.
  The current file gives **7 units**: Karur, Kulithalai, Manaparai and
  Aravakurichi Sub Divisions, Karur H.O, Kulittalai H.O and DO Karur.

- Press **Preview** first: it reports what will be inserted, updated and
  skipped, **and the category and unit breakdown of the file**, without writing
  anything. Check those two tables add up to 211 Departmental / 571 GDS before
  importing.
- Employee IDs are stored as **text**, so leading zeros survive.
- **Merge** (recommended) updates existing records and keeps everyone else.
- **Replace** marks everyone inactive first, then imports the file as the full
  list. It never deletes anybody — employees who have already registered or sat
  an examination keep their records.
- Re-importing the same file is safe: it updates, it does not duplicate.

Nobody can register until this is done. Until then every page says
*"Employee Directory Pending Import."*

#### Somebody who is not on the roster

An employee who joined after the roster was exported — or anyone otherwise
missing from it — cannot register, because registration is verified against
the directory. Registration is refused with *"Employee ID … is not in the
approved employee list."*

Add them by hand, and they can then sign in and register themselves:

**Employees → Registrations → + Add employee by ID**, or
**Employees → Employee Directory → + Add employee**.

Enter the Employee ID and it is looked up first:

- **Not in the directory** — fill in name, designation, office and (for the
  prize lists) staff category and unit. The record is created as **active**.
  The employee then signs in at `/Quiz` with their own Google account and
  registers with that Employee ID, and their registration is verified against
  this record exactly like anyone imported from Excel.
- **Already in the directory** — there is nothing to add; they can register
  now. The dialog shows who they are, and says so if the record is inactive.

> **Do not "reserve" the Employee ID for somebody who will be signing in.**
> Reserving creates a pending registration that *claims* the ID, so when the
> employee tries to register themselves they are refused with *"Employee ID …
> has just been registered from another account"*, and an administrator has to
> **Unlink** the reservation before they can proceed. Reserve an ID only to
> record somebody who will not be signing in at all. The dialog says this at
> the point of use.

A hand-added employee is a normal directory record: a later **Merge** import
updates it rather than duplicating it.

An import ends by **filling in category and unit on the registrations that
already exist**, taking them from the directory record each one was created
against. It only ever fills a blank — a category an administrator has corrected
by hand is never overwritten — and it touches nothing else: no status, no
attempt, no score. If the directory was corrected in place rather than
re-imported, the same thing is available as **Sync registrations from the
directory → Fill in the blanks** on the Import tab. **Re-sync everything** on
that card is the deliberate override that *does* overwrite hand corrections;
it asks before it runs and is written to the audit log.

### 3.2a Watch registration on the dashboard

`/Quiz/admin/employees` → **Registration Dashboard**

The landing tab of the Employees page, and the thing to watch during the
campaign. It shows:

- **Category-wise registration** — Departmental and GDS, each against its
  strength on the roll, with a coverage percentage. A third card appears only
  if somebody has registered without a category, which is a problem to fix
  before results are published.
- **Unit / Sub Division-wise registration** — one line per unit with employees
  on roll, registered, the Departmental/GDS split, approved, pending and
  coverage. This is what tells a Sub Divisional Head whether there is anybody
  left to chase.
- **Unit × category** — the same figures crossed, which is the grid prize
  distribution is planned from.
- **Office-wise registration**, collapsed behind a disclosure because there are
  over 300 offices.
- **Registrations by day (IST)** — whether the campaign is still moving.

Both breakdowns export to Excel from the buttons at the foot of the tab, and
again from the Reports page. The Registrations and Employee Directory tabs
carry matching **Category** and **Unit / Sub Division** filters, and a per-row
**Category** button for correcting one registration.

### 3.3 Load the question bank

`/Quiz/admin/questions`

- **Download Template** gives the exact column layout.
- Required columns: `question_text`, `option_a`…`option_d`, `correct_option`
  (A/B/C/D). Optional: `explanation`, `source_ref`, `category`, `difficulty`.
- **Import** validates every row: a blank option, two identical options or a
  missing key is reported rather than imported. Likely duplicates are detected
  by a normalised digest of the question text.
- Imported questions arrive as **Draft** unless you tick *mark as Active*.
  **Only Active questions can be drawn into an examination.**

No placeholder or invented questions exist anywhere in this module. Until you
load yours, the page says *"Question Bank Not Yet Uploaded."*

#### A separate question paper for each examination

The band at the top of the page — **Question set being edited** — decides
where everything below it goes. There are two kinds of set:

| Set | What it means |
|---|---|
| **Shared bank** | Questions belonging to no particular examination. Any examination that has none of its own draws its paper from here. |
| **An examination** | Questions belonging to that test alone. Its paper is drawn **only** from them, and no other examination can reach them. |

The rule the module applies when a paper is published follows from that, and
there is nothing to configure:

> **If an examination has questions of its own, its paper comes only from
> them. If it has none, it falls back to the shared bank.**

So to give one test its own paper:

1. On `/Quiz/admin/exams`, press **📝 Questions** on that examination's card.
   (Or choose it in the band at the top of the Question Bank.) The page opens
   on that test's set, and the address carries `?exam=<id>` so it can be
   bookmarked.
2. **Template** downloads a blank sheet named after that test, so three
   templates for three different tests do not all arrive as the same file.
3. **Import** uploads it. The banner above the drop zone names the test the
   questions will belong to before you choose a file; **Preview** still shows
   what will land and what needs review.
4. Activate them — only **Active** questions are drawn — and publish the
   examination as usual.

Two more set-level actions sit in the toolbar:

- **Copy from…** copies an entire set into the one on screen, as drafts for
  review. This is the usual way a new test is started: from the shared bank,
  or from last year's paper. The original is untouched, and a question already
  present is left alone rather than copied twice.
- **Clear set** (administrators only) empties the set after a typed
  confirmation — for an upload that went to the wrong test. A question already
  frozen into a published paper is **kept**, because a result refers to it;
  those are reported back by code.

A question can be moved between sets at any time through **Belongs to** in its
editor, and in the combined *All questions* view each card carries the name of
the set that owns it.

Duplicating an examination copies its question set too, so the copy has a
paper of its own rather than sharing the original's — editing one would
otherwise silently edit the other.

Until `migration_v23_quiz_exam_questions.sql` has been run, this band says so
and names the file; every question stays in the one shared bank, exactly as
before.

### 3.4 Create and publish the examination

`/Quiz/admin/exams`

1. **Create Postal Week 2026 quiz** pre-fills a draft with the recommended
   defaults and ready-written instructions. Or **+ Create examination** for full
   control.
2. Set the **schedule**. All date fields are **Indian Standard Time** —
   the form converts explicitly, so the browser's own timezone is irrelevant.
3. **Validate** runs the pre-flight checks: enough active questions, four valid
   options and one key on each, a sane window, timing that fits. Its first
   line names **which set the paper will be drawn from** — this examination's
   own questions, or the shared bank — so a count like *"12 usable of 25
   required"* can be read without guessing which pool it refers to.
4. **Publish paper** selects the questions at random and **freezes an immutable
   copy**. From that moment the examination and the question bank are
   independent — later edits to the bank cannot change this paper, or any
   attempt in progress. The dialog states the source again before you commit,
   and the card badge (**Own set: n** / **Uses shared bank**) says it at a
   glance.
5. Set the status to **Scheduled** (visible, opens at its time) or **Live**.

#### Choosing who may sit a particular test

**Examinations → 👥 Who can attempt**

An examination is in one of two states, and the state is stored explicitly —
never guessed from whether a list happens to be empty:

| | |
|---|---|
| **Open** | Eligibility is decided by the office and designation rules in **Edit**, as it always has been. This is the default, and what every existing examination is. |
| **Allotted** | **Only** the employees named in the dialog may attempt it. |

Allotment **overrides** the office and designation rules in both directions:
an allotted employee sits the paper even if their office is outside the
examination's office list, and an unallotted employee does not, even if
their office is in it. The list *is* the eligibility.

In the dialog: search the directory by name, Employee ID, designation or
office; tick the candidates, or **Add all shown** after a search that
already narrows to the group you want (one office, one designation). The
chosen employees appear as chips above, removable individually or with
**Remove all**. Nothing is written until **Save**.

What an allotted employee sees: the examination appears on their dashboard
normally. What everybody else sees: **nothing** — it is left off their
dashboard entirely rather than shown as refused. Somebody removed from the
list after sitting it still sees their own attempt and result, because that
is their record.

Two safeguards worth knowing:

- **Allotted with nobody on the list means nobody can sit it.** That is the
  honest reading of an administrator who has not finished choosing, and it is
  deliberately *not* treated as "no restriction". The card shows
  **Restricted — nobody allotted**, the dialog says so in red, and
  **Validate** fails on it.
- **The list is frozen while people are sitting the paper.** With an attempt
  in progress the save is refused, so nobody is removed part-way through.

Duplicating an examination copies its allotment as well as its question set,
so a second batch of the same test starts ready rather than empty.

Hiding is not the control: **exam-start** re-checks the allotment server-side,
so a hand-made request cannot start a paper the employee was never allotted.

Defaults, all configurable before publication:

| Setting | Default |
|---|---|
| Questions | 20 (range 15–25) |
| Options per question | exactly 4 |
| Correct answer | +4 |
| Wrong answer | −1 |
| Unanswered | 0 |
| Time per question | 30 s |
| Overall duration | 10 min |
| Official attempts | 1 |
| Navigation | one-way |
| Question / option order | randomised |

### 3.5 Rehearse before the day — Admin Test Mode

`/Quiz/admin/exams` → **Run test attempt**

This opens the examination exactly as an employee will see it — the same
question timer, overall timer, locking, auto-submission and scoring — because a
test attempt is driven through **the same participant endpoints**. A rehearsal
that used different code would prove nothing.

A test attempt:

- **ignores the scheduled window**, so you can rehearse days early;
- never appears in any report, leaderboard or participation count;
- does not consume anyone's official attempt;
- **shows the answer key** on its result page, so you can verify the marking;
- can be re-run freely — each run replaces the last;
- shows a **TEST MODE** banner throughout, so it can never be mistaken for the
  real examination.

Check the obvious things: that the per-question timer locks and advances, that
the overall timer auto-submits, that a wrong answer actually deducts, that a
refresh mid-attempt returns you to the same question with the time that
remains, and that the paper reads correctly on a phone.

---

## 4. On the day

| Task | Where |
|---|---|
| Watch attempts in real time | `/Quiz/admin/exams` → **Monitor** (refreshes every 10 s) |
| Approve any pending registrations | `/Quiz/admin/employees` → **Approve all pending** |
| Close attempts whose phone went away | `/Quiz/admin` → **Finalise expired attempts** |
| Release results | `/Quiz/admin/reports` → **Publish all results** |
| Read out the prize list | `/Quiz/admin/reports` → **Prize list — top 3 in each category** |
| Export everything | `/Quiz/admin/reports` → seventeen Excel reports |

Registration is set to **approve automatically on a roster match**, so
employees can register and sit the quiz without waiting for you. You can still
suspend, reject or release any registration. To require manual approval
instead, turn the setting off at `/Quiz/admin/settings`.

**Results are withheld by default** (`result_mode: MANUAL`). Participants see
*"Your examination has been submitted. Results will be published by the
administrator."* until you publish. You can also withhold one individual result
pending enquiry, with a recorded reason.

### Results and the leaderboard are published in two categories

Prizes go to the top three **Departmental** officials and the top three **GDS**
separately, so the Reports page draws two prize lists side by side. Neither is
ranked against the other: the first GDS participant is first in GDS, however
many Departmental scores sit above theirs overall.

- **Prizes per category** at the top of the page sets how many places each list
  shows. It defaults to **Top 3**.
- Rank is **score descending, then the faster time**. A genuine tie — identical
  score *and* identical time — shares a place and both names appear, so a
  "top 3" can legitimately be four people long. The place after a tie skips
  (1, 2, 2, 4), as a prize list must read.
- A **withheld** result cannot win a prize. It is excluded from its category
  ranking and the place passes to the next person, while the result itself
  stays visible, flagged, in the full table below.
- Anybody whose category is **Unspecified** is shown in a third list of their
  own, never folded into a prize list. If that list is not empty, fix the
  categories before announcing anything.
- The full results table shows **Category Rank** (which carries the medal) next
  to the overall rank, with the category and unit on every row, and filters by
  category.
- **Download the prize list** exports exactly what is on screen; *Leaderboard
  by Category* in the report grid does the same.

Each participant sees their own standing in their category first, with their
division-wide rank beside it, on `/Quiz/result/…`.

---

## 5. How the integrity controls actually work

Stated plainly, including the limits.

**Enforced on the server — a participant cannot affect these:**

- Whether the examination is open — judged on the server clock, so changing the
  device clock changes only the animation.
- Both deadlines. The per-question timer and the overall timer run
  independently, and **whichever expires first is enforced**. An attempt is
  finalised even if the browser never sends a final submit.
- Question order and option order, frozen per participant at the start. A
  refresh, a reconnect or a new device restores exactly the same paper.
- Correct answers. The key is read inside the API process and discarded; it is
  never part of any participant response, during or after the examination,
  unless you explicitly release the answer key.
- One-way navigation, question locking, and the attempt allowance.
- Idempotent answer saving, keyed on `(attempt, question)`: a retry after a
  dropped connection overwrites rather than scoring twice.
- Ownership. An attempt ID is not authority — it must belong to the signed-in
  participant's own registration.
- Rate limits on Employee ID lookup and every sign-in path, so the roster
  cannot be walked 782 IDs at a time. Google sign-in is limited per Google
  account rather than per IP address, because an office behind one public
  address is many people, not one; a loose per-address flood guard remains on
  the unauthenticated path, where the account is not yet knowable.

**Logged for your review, never automatic:**

Tab switches, reconnects, page exits, blocked copy attempts, failed sign-ins and
claimed-Employee-ID attempts are recorded in `quiz_events`, visible in the
*Suspicious Activity Review* report.

**A tab switch does not disqualify anybody.** It is one data point for a human
decision. Publish whatever fair-examination policy you intend to apply, and use
*Withhold result* with a recorded reason if an enquiry is needed.

**What this cannot do — do not claim otherwise:**

A browser-based examination cannot stop a participant using a second phone,
a textbook, a colleague, a search engine or an AI assistant. Copy-blocking is a
mild deterrent, nothing more. There is **no webcam, microphone or location
monitoring**, by design. If the examination must be invigilated, invigilate it
in person.

---

## 6. Correcting a mistake after the examination has started

Everything here is audited, and nothing silently rewrites history.

**A wrong answer key, found mid-examination.** Do not edit the bank and hope.
Edit the question (the edit is versioned, with your change note), then after the
examination use `/Quiz/admin/reports` → **Recalculate scores** with a recorded
reason. Every completed attempt is re-scored from the answers already stored and
the marking policy frozen on each attempt. No answer is altered and nothing is
invented; old and new figures both go to the audit log.

**An Employee ID registered by the wrong person.** `/Quiz/admin/employees` →
**Unlink**, with a reason. The ID is released so the correct official can
register. This is refused if the employee has already sat the examination —
suspend the registration instead and record the correction, rather than
orphaning an official attempt.

**A designation or office correction.** Edit the employee record. Registrations
hold their own snapshot of name, designation and office taken at registration,
so examination history is untouched.

**Re-publishing a paper** is refused while attempts are in progress, and warns
if anyone has already sat the examination. Duplicate the examination instead.

---

## 7. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| *"Quiz backend is not configured — missing …"* | Set that variable in Vercel and **redeploy**. |
| *"Google Sign-In is not yet configured."* | `GOOGLE_CLIENT_ID` is unset. Step 3. |
| Google button does not appear | `accounts.google.com` blocked by the network, or the origin is missing from the authorised JavaScript origins. |
| Google sign-in fails with *"not issued for this application"* | The client ID in Vercel does not match the one that produced the token — usually a second OAuth client. |
| Only a few accounts can sign in | The OAuth consent screen is still in **Testing**. Set it to **In production**. |
| *"The employee directory has not been imported yet"* | Step 3.2. |
| *"This examination is not ready — its question paper has not been fully published"* | Press **Publish paper**. |
| A test drew questions meant for a different test | It has none of its own, so it fell back to the shared bank. Upload its paper against it: **Examinations → 📝 Questions**. The card badge says which it is using. |
| *"Only n usable active question(s) are available in this examination's own set"* | The set is too small, or its questions are still Draft. Only **Active** questions are drawn. |
| The Question Bank says separate sets are not switched on | Run `migration_v23_quiz_exam_questions.sql`. The page picks it up within thirty seconds; no redeploy. |
| *"Employee ID … is not in the approved employee list"* | They are not in the directory. **Employees → + Add employee by ID**. |
| *"Employee ID … has just been registered from another account"* | The ID was reserved manually, which claims it. **Unlink** the reservation, then let them register. |
| An employee cannot see a test at all | It is allotted to selected employees and they are not among them. **Examinations → Who can attempt**. |
| Nobody can see a test | It is Allotted with nobody on the list. The card reads *Restricted — nobody allotted*. |
| *"Allotting an examination to named employees needs migration_v24…"* | Run `migration_v24_quiz_exam_allotments.sql`. |
| Employee says the countdown never ended | They are on a cached page. The server decides; ask them to reload. |
| An attempt is stuck *In progress* after the window closed | `/Quiz/admin` → **Finalise expired attempts**. |
| Everyone was signed out at once | `QUIZ_SESSION_SECRET` changed. |
| Admin sign-in rejected with a correct password | The account has no quiz role. `/Quiz/admin/settings` → Quiz privileges. |

---

## 8. Testing

### Automated — the marking and timing logic

```bash
node "Leave Orders/quiz/selftest.js"
```

86 tests, no database or network required. They cover the parts where a silent
mistake would change somebody's marks or let them into a closed examination:
the scoring formula (including the specification's worked example — 15 correct,
3 wrong, 2 unanswered = **57 of 80**), the option permutation across all 24
orderings, the eligibility and timing gate, the "whichever deadline falls
first" rule, the IST conversion used by the scheduling form, the countdown
formatting, the staff-category and unit normalisation, and the category-wise
ranking and prize lists (ties, withheld results, and the rule that Departmental
and GDS are never ranked against each other), the question-set scope that
keeps one examination's paper out of another's, and the allotment gate —
including the case that matters most, that an examination restricted to
selected employees with nobody yet on the list admits **nobody**, and that
anything short of an explicit allotment is not an allotment. Run it after any
change to `_quizlib.js`, the examination engine, the ranking or the gate.

### Manual — what the tests cannot cover

Automated tests do not prove that Google accepts your OAuth client, that the
service-role key reaches the functions, or that a real phone behaves on a real
network. Before the examination, walk these through on the live deployment:

- A new Google account signs in, registers, and lands on the dashboard.
- The same account returns without creating a second registration.
- An unknown Employee ID is refused with a helpful message.
- A **second** Google account cannot claim an Employee ID already registered.
- A pending (unapproved) employee cannot start the examination.
- The Start button stays disabled until the opening time, and the countdown
  ends by itself without a reload.
- Mid-examination: **refresh the page** — the same question returns with the
  time that remains, not a fresh attempt or a fresh clock.
- Mid-examination: **turn off mobile data for 30 seconds**, then back on — the
  connection banner appears, the clock does not pause, and the attempt resumes.
- Let a question timer expire without answering — it locks and advances.
- Let the overall timer expire — the attempt auto-submits.
- A wrong answer actually deducts; the arithmetic on the result page matches.
- An Admin Test Mode run shows the TEST MODE banner and never appears in the
  reports.
- iPhone Safari and Android Chrome, in both light and dark mode.

## 9. Data protection

Collected: the employee's verified Google email and Google subject identifier;
their name and picture as published by Google; their Employee ID, name,
designation and office **read from the uploaded roster, never typed in by the
participant**; their answers, timings and result; and basic technical details of
the attempt (server-side submission timestamps, IP, browser) for audit.

Not collected: passwords (Google's own password is never seen by this
application), any other Google service, webcam, microphone or location.

The privacy notice shown to employees is on `/Quiz` and is linked from the
registration form. Registration requires an explicit confirmation tick before
anything is stored.

Access: a participant can only ever read their own registration, attempt and
result — enforced server-side on every request, not by hiding a page.
Employee-level reports are restricted to users holding a quiz role.
Administrative changes and answer-key views are written to `quiz_audit`.

Retention: `quiz_sessions` and `quiz_rate_limits` are pruned by
`quiz_prune_expired()`, called whenever the expiry sweep runs. Examination
records are kept for the conduct and audit of the examination. Collect no more
than this.

---

## 10. File map

```
api/
  _quizlib.js             shared library — Supabase service-role access, Google
                          ID-token verification, sessions, rate limits, logging
  quiz-auth.js            sign-in: Google, Employee ID fallback, admin, status
  quiz-participant.js     the examination engine — eligibility, timers,
                          question serving, answer saving, scoring
  quiz-admin.js           administration — employees, questions, examinations,
                          test mode, results, reports, settings, roles

Leave Orders/quiz/
  quiz.css                design system (light + dark, mobile first)
  quiz-core.js            API client, header/footer, IST time, countdowns
  quiz-admin-core.js      admin gate, tabs, Excel import/export
  index.html              /Quiz              public landing + privacy notice
  register.html           /Quiz/register     Employee ID lookup + registration
  dashboard.html          /Quiz/dashboard    employee dashboard + countdown
  waiting.html            /Quiz/waiting      waiting room
  exam.html               /Quiz/exam/<id>    examination screen
  result.html             /Quiz/result/<id>  result + optional answer key
  admin.html              /Quiz/admin        overview
  admin-exams.html        /Quiz/admin/exams
  admin-questions.html    /Quiz/admin/questions
  admin-employees.html    /Quiz/admin/employees
  admin-reports.html      /Quiz/admin/reports
  admin-settings.html     /Quiz/admin/settings
  selftest.js             logic tests — see below
  DEPLOYMENT.md           this document

android-app/supabase/
  migration_v20_quiz.sql  18 tables, locked to the service role
  migration_v21_quiz_duration_mode.sql
                          automatic examination duration
  migration_v22_quiz_category_unit.sql
                          staff category + unit/sub division, and the backfill
                          that gives the existing records theirs
  migration_v23_quiz_exam_questions.sql
                          a question set of its own for each examination
  migration_v24_quiz_exam_allotments.sql
                          naming the employees who may sit an examination
```

Three new serverless functions, bringing the project to five — well inside
Vercel's limits. No npm dependencies were added: the API uses only Node
built-ins and `fetch`, and the browser pages reuse the SheetJS build this
portal already loads.

---

*Developed by Arun Selvaraj, Instructor, WTC Karur*
*For any queries, please call +91 7904478591 / arun18@live.in*
