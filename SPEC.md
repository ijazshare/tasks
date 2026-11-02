# Tasks: build spec for an offline-first Android task app

Status: build-ready draft, revision 5, 2026-11-01. Revision 5 records the owner's data rule (goal
7): data may go to devices the owner owns and controls, never to anyone else's server. It demotes
ramble to a "should" (the owner rarely rambles; deterministic quick to-dos are the core), moves
audio offload to Assistant only, and removes leftover references to the dropped GGUF. Revision 4 caps ramble capture at 60 s, drops
the on-device small-model fallback from Tasks (keeping Tasks pure Kotlin with no NDK or model
weights), and defers model-assisted ramble processing to a future local homelab GPU integration
(section 6.7) behind the same deterministic validation gate. Revision 3 (2026-10-28) corrected every
statement about Assistant to match its code as of 2026-10-28 (file split, reminder delivery flag,
locked decision 8, time rules, answer order) and recorded in Appendix C what Tasks takes over or
must match. Revision 2 (2026-10-27) applied the review in
`research/critique.json` (findings C1-C34, see Appendix B), the Todoist feature survey in
`research/features.md`, and the fact-checked `research/parsing.md`. Audience: the Claude Code
instance that builds this app from scratch, and the owner (GitHub `ijazshare`), who answers the open
questions in section 15.

The words MUST, SHOULD and MAY are used in their RFC 2119 sense. "Assistant" means the existing
voice assistant at `/home/ijaz/assistant-dev/themachine` (repo `ijazshare/assistant`). "Tasks" or
"the task app" means the new app this spec describes. Examples use fictional people (Amira Khan,
Bilal Rahman, Dr Okafor) and 555-01xx numbers only.

Before writing code, the builder MUST read:

- this spec end to end;
- `/home/ijaz/assistant-dev/themachine/CLAUDE.md`, which covers toolchain, style and privacy rules
  that also apply here;
- the research notes in `/home/ijaz/assistant-dev/tasks-app/research/`.

Claims marked † are not backed by any research note or by Assistant's code. Each one MUST be checked
before the milestone that depends on it (section 14, "verification gates").

---

## 1. Goals and non-goals

### Goals

1. A Todoist-like task manager that runs entirely on the phone. **Must** for v1: Inbox, flat
   projects, labels, ~~priorities~~ (priorities removed from v1 (owner, 2026-11-02)), due dates and times, recurring tasks and reminders. **Should**:
   sections, subtasks and nested projects (section 7 has the full must/should list).
2. **Tasks owns all task logic.** It parses, decides, acts and writes the reply that gets spoken.
   Assistant stays a lean, generalist router. Assistant never needs a large model for a task command:
   it forwards the user's exact words plus context and speaks back what Tasks returns.
3. **Reminders that never lie.** "In N minutes", "in N hours", specific times and recurring
   reminders all fire on time, survive reboot, time-zone changes and DST, and fire exactly once.
   Two platform exceptions are accepted and documented (8.4): after a reboot nothing fires until the
   first unlock, and after a force-stop nothing fires until Tasks next runs. In both cases a passed
   reminder is delivered once as "Missed", never silently dropped.
4. **Reliable facts or decline.** Tasks never invents a date, a time, a project, a label or task
   content. When the words do not determine an answer, it asks one short follow-up question or
   declines. The few fixed defaults it applies (8.3.1) are listed, read back in full, and need the
   owner's approval (Q4).
5. **Ramble mode** (should, lowest priority; the owner rarely uses it): a multi-task brain dump
   turned into a reviewable list of drafts, deterministic on the phone.
6. **Offline core.** Everything in goals 1 to 5 works in airplane mode, deterministically, on the
   phone. Tasks receives text only. Audio never reaches it.
7. **Owner's data rule** (owner, 2026-11-01). Task data, transcripts and audio may go only to devices
   the owner owns and controls: the phone, or the owner's own home server. They MUST never go to a
   server or computer run by anyone else (cloud APIs, Todoist, hosted models). Open-source models
   are allowed on the owner's own devices. On-device and deterministic beats off-device; off-device
   is only for optional extras (6.7).
8. Migrating today's Assistant `tasks.md` and its armed reminders loses no data.

### Non-goals (v1)

- Collaboration, sharing, assignees or comment threads. Single user, single device.
- Cloud sync, accounts, or any server not owned by the owner (goal 7). Two-way Todoist sync is out:
  it would send tasks to Todoist's servers (see 15, Q8).
- Speech recognition, TTS or audio handling inside Tasks. That is Assistant's job.
- Building alarms or timers. Assistant keeps delegating those to the Clock app.
- Location reminders, attachments, calendar view, board view, calendar sync, karma or productivity
  stats.
- Spoken time zones ("at 3 London time"). v1 declines them (8.1).
- Languages other than English for parsing. The parser is designed so a locale can be added later.
- Any network access in the core app (see section 10 for the optional connector).

---

## 2. Decisions at a glance

| Topic | Decision |
|---|---|
| Project location | `/home/ijaz/assistant-dev/tasks-app/` (Gradle root; `research/` stays as is) |
| Package / app id | `io.github.ijazshare.tasks` (debug: `.debug` suffix, like Assistant). Display name "Tasks" (Q1). Optional connector: `io.github.ijazshare.tasks.connector` (+ `.debug`) |
| Stack | Kotlin, Jetpack Compose (Material 3), single `:app` module, pure JVM/Android (no NDK/C++), Room, DataStore, kotlinx.serialization, WorkManager only where needed. `minSdk 33`, target/compile = Assistant's (`37` in `gradle/libs.versions.toml`) |
| IPC | Bound AIDL service behind a `signature` permission, one method, JSON string in and out, versioned (section 4) |
| Parsing | Hand-written deterministic grammar on `java.time`. It returns Parsed / Ambiguous / NoMatch and carries a span for every match. No third-party parser: every surveyed JVM library returns a best guess instead of declining, and several are stale or GPL (Appendix A). No on-device LLM inside Tasks; optional future ramble processing runs on the owner's homelab GPU server and passes through Tasks' deterministic validation gate (6.7) |
| Time storage | Local date + local time + optional zone ID + RRULE subset, never a bare UTC instant, except "in N minutes", which is an instant |
| Alarms | One exact "next due" alarm. Re-armed on every change, boot, time or zone change, package replace and process start |
| Network | None in the core app (no `INTERNET` permission). Email, Todoist API and future homelab GPU ramble offloading live in the optional separate connector app (Q7, 6.7). Tasks never downloads anything. Anything that leaves the phone obeys goal 7 |
| Follow-ups | At most 2 questions per command. This is Tasks' own limit, not Assistant's. Locked decision 8 (`themachine/CLAUDE.md`, as of main 37e7bb4) reads: "there is no general slot-filling loop. Three follow-ups exist. "Text Sam" with no message asks "What should I say to Sam?" and the next dictation (within 60 s) is the message; a reply that opens like an order is read back for a yes (30 s) instead of being sent. A reminder with neither a task nor a time asks "What should I remind you about?" and the answer (within 60 s) is added to the request and run as one command. A name that matches several contacts shows a contact selector. Anything else that is missing fails with a spoken reason." The task follow-up (4.7, 4.12 step 5e) replaces the reminder one and can ask twice, so decision 8 needs the owner's yes and a CLAUDE.md edit in the same Assistant change |
| Undo | Every voice mutation returns an undo token valid for 10 minutes (Q11) |
| Signing | Tasks uses Assistant's keystores: the same release key (`SIGNING_KEYSTORE_PATH` properties, as in Assistant's `app/build.gradle.kts`) and the same debug key. Keys stay under `/home/ijaz/assistant-dev`, never committed (4.2.6) |

---

## 3. Architecture

```
 ┌──────────────────────────── Assistant (existing) ─────────────────────────────┐
 │ mic → Endpointer → Whisper → transcript                                        │
 │   → TaskRouting.claims(transcript)? ──yes──┐   (keyword pre-route, no model)    │
 │   → else 1B router → tool "tasks" ─────────┤                                    │
 │                                            ▼                                    │
 │                  TaskAppClient (bind, call, timeout, retry on dead binder)      │
 │   speak(response.speech) ◀── needs_input? re-listen, send follow_up (≤2)        │
 └────────────────────────────────────────────┬───────────────────────────────────┘
                     Binder (AIDL), signature permission, JSON v1
 ┌────────────────────────────────────────────▼───────────────── Tasks (new) ─────┐
 │ CommandService (exported, permission-guarded)                                   │
 │   → CallerCheck (uid → single package → allowlist + signature match)            │
 │   → CommandHandler (single-threaded mutation dispatcher, request_id dedupe)     │
 │       → Normalizer → IntentGrammar → SlotGrammars (date/time/recur/…)           │
 │       → EntityResolver (projects/labels/tasks from Room)                        │
 │       → Decider (done | needs_input | declined | not_mine)                      │
 │       → Repository (Room) → ReminderScheduler (AlarmManager) → UndoJournal      │
 │       → SpeechWriter (reply text)                                               │
 │ UI (Compose): Today, Upcoming, Inbox, Projects, Search, Ramble review, Settings │
 │ Receivers: ReminderFire, Boot, TimeChanged, TimezoneChanged, PackageReplaced    │
 └─────────────────────────────────────────────────────────────────────────────────┘
        optional, separate APK, has INTERNET: "Tasks Connector" (email, Todoist API, owner's home-server ramble)
        → talks to Tasks through the same signature-guarded service
```

### Package layout (`io.github.ijazshare.tasks`)

```
contract/   request/response data classes (kotlinx.serialization), ContractVersion, error codes
ipc/        CommandService, ITaskCommand.aidl, CallerCheck, RequestDedupe
command/    CommandHandler, Conversation store, Referents, Decider, SpeechWriter, UndoJournal
parse/      Normalizer, Tokens+Spans, IntentGrammar, DateGrammar, TimeGrammar, RecurrenceGrammar,
            DurationGrammar, PriorityGrammar, EntityResolver, TaskMatcher, RambleSegmenter,
            ValidationGate (for future external ramble drafts, 6.7), ParseResult,
            lexicon/ (verbs.txt, filler.txt, stopwords.txt)
time/       DueSpec, RecurrenceRule (RRULE subset), NextOccurrence, Clock injection
data/       Room entities, DAOs, migrations, Repository, export/import (JSON)
reminders/  ReminderScheduler, ReminderReceiver, BootReceiver, TimeChangeReceiver, Notifications
importers/  AssistantMarkdownImporter, TodoistBackupImporter (zip/CSV), TodoistSyncMapper
ui/         theme (Halo, shared look with Assistant), screens, quick-add bar, ramble review
settings/   DataStore-backed settings
```

Rules the builder MUST follow:

- The parser and time code are pure Kotlin with no Android imports. They take an injected `Clock`
  and `ZoneId` and never call `now()` themselves, so they unit-test on the JVM.
- All mutations go through `Repository`, inside a Room transaction. Each mutation writes its own
  undo-journal row in that transaction and then calls `ReminderScheduler.rearm()`.
- No analytics, crash SDKs or network libraries in the core app, the same privacy invariants as
  Assistant.

---

## 4. The Assistant <-> Tasks contract

### 4.1 IPC mechanism and why

**Choice:** an exported bound service, `io.github.ijazshare.tasks.ipc.CommandService`, guarded by a
custom permission with `protectionLevel="signature"`. It exposes one AIDL interface that takes a
JSON request string and returns a JSON response string.

| Option | Verdict |
|---|---|
| **Bound service + AIDL + signature permission** | **Chosen.** Synchronous request/response; the platform enforces the permission at bind time; Assistant can pre-bind while the user is still speaking, which hides process start; one interface method keeps versioning in the JSON, not the AIDL |
| `ContentProvider.call()` | Viable and simpler (no connection lifecycle), but `call()` is not covered by the provider's read/write permissions; the provider must enforce manually, and there is no natural pre-warm. Keep as a fallback if binding proves flaky on One UI |
| `Messenger` | Async only, needs reply plumbing, no benefit over AIDL here |
| Broadcasts / `startService` intents | No reply, so no confirmation. The same failure as Todoist's deep links (`research/api-and-android.md` §6: "no confirmation that a task was created") |
| Activity for result / deep link | Needs UI and focus, breaks the overlay flow |

Why the signature level: the owner signs both apps with one key, so only apps from that key can
bind. No user-visible permission prompt, and no other app on the phone can drive Tasks.

### 4.2 Security details (MUST)

1. **Both** apps declare the permission identically, so whichever installs first defines it:
   ```xml
   <permission android:name="io.github.ijazshare.permission.TASK_COMMAND"
               android:protectionLevel="signature"
               android:label="Send task commands" />
   ```
   Assistant adds `<uses-permission android:name="io.github.ijazshare.permission.TASK_COMMAND"/>`.
   † That a same-key duplicate declaration installs cleanly in either order is platform behaviour,
   not something the research notes cover. The M3 instrumented test MUST install in both orders.
2. The service is `exported="true"` with `android:permission="io.github.ijazshare.permission.TASK_COMMAND"`.
3. Defence in depth: every `handle()` call resolves `Binder.getCallingUid()` with
   `packageManager.getPackagesForUid(uid)`. Neither app uses `sharedUserId`, so the result MUST be
   exactly one package. That package MUST be in the allowlist {`io.github.ijazshare.assistant`,
   `io.github.ijazshare.assistant.debug`, `io.github.ijazshare.tasks.connector`,
   `io.github.ijazshare.tasks.connector.debug`}, and `checkSignatures(myPackage, thatPackage)` MUST
   equal `SIGNATURE_MATCH`. On any failure, return error `caller_not_trusted` without touching the
   DB.
4. Assistant declares package visibility (Android 11+), or bind silently fails:
   ```xml
   <queries>
     <package android:name="io.github.ijazshare.tasks" />
     <package android:name="io.github.ijazshare.tasks.debug" />
   </queries>
   ```
5. Build variants: a debug Assistant talks to a debug Tasks, and a release one to a release Tasks.
   Assistant's `TaskAppClient` picks the target id from its own `BuildConfig.DEBUG`. A key mismatch
   shows up as a bind `SecurityException`, which Assistant reports as "The task app is signed with a
   different key."
6. **Signing.**
   - Release: Tasks reads the same signing properties Assistant reads (`SIGNING_KEYSTORE_PATH` and
     the rest, see `themachine/app/build.gradle.kts`), so both apps share one release key. The
     keystore and its backup stay under `/home/ijaz/assistant-dev` and are never committed.
   - Debug: Tasks' debug build MUST be signed with the same debug keystore as Assistant's debug
     build. In M3, confirm with `apksigner verify --print-certs` on both APKs.
   - CI builds only run tests. A CI-built APK is never installed on the phone, because its debug key
     differs.
   - Q15 (debug/release mixing) MUST be answered before M3 starts.
7. Tasks never logs utterance text at INFO in release builds. Its own history tables are local,
   retention-limited and user-clearable (12, "Privacy").

### 4.3 AIDL

```aidl
// io/github/ijazshare/tasks/ipc/ITaskCommand.aidl
package io.github.ijazshare.tasks.ipc;
interface ITaskCommand {
    /** Highest contract major version this build speaks, e.g. 1. */
    int contractVersion();
    /** Request JSON (section 4.4) in, response JSON (4.5) out. Never throws for bad input. */
    String handle(String requestJson);
}
```

`handle` runs on a binder thread. The implementation hands the work to a single-threaded coroutine
dispatcher that serializes all mutations, and waits for the result.

- **Deadline.** The handler has a hard deadline of 600 ms from receipt. The deadline is checked on
  the dispatcher immediately **before** the Room transaction starts. If it has passed, nothing is
  written and the response is error `busy`. Once a transaction has started it is allowed to finish,
  and the response is the real outcome even if late. So `busy` always means "nothing was saved",
  and a late commit is never reported as a failure.
- **Dedupe.** `request_id` is looked up first. A completed request returns its stored response. A
  request that is still in flight makes the duplicate wait for the same result (one shared
  `Deferred` per `request_id`). Only `done`, `needs_input` and `declined` responses are stored.
  `error` responses are never stored, so a retry runs fresh.
- **Size.** Responses are capped at 64 KiB (binder transactions fail near 1 MB). Only `listed`
  effects can grow. They carry at most 50 items and set `truncated: true` on that effect.
  Requests over 256 KiB return `bad_request`. That limit is why migration is chunked (4.13).

### 4.4 Request schema (v1)

```json
{
  "v": 1,
  "request_id": "6f1c2a0e-1b7d-4a53-9d0e-3f6b1f0f2a11",
  "type": "utterance",
  "utterance": "remind me to call Amira at 5",
  "conversation_id": null,
  "option_id": null,
  "undo_token": null,
  "context": {
    "now_epoch_ms": 1793109780000,
    "speech_end_epoch_ms": 1793109779400,
    "zone": "Europe/London",
    "locale": "en-GB",
    "hour_cycle": "h12",
    "source": "voice",
    "round": 0,
    "routed_by": "keyword"
  },
  "client": { "package": "io.github.ijazshare.assistant", "version": "0.1.0" }
}
```

| Field | Rules |
|---|---|
| `v` | Contract major version. Required |
| `request_id` | UUID v4 from the client. Tasks dedupes by it for 10 minutes (4.3), so a retry is safe |
| `type` | `utterance` (a fresh command), `follow_up` (answer to a question; needs `conversation_id`), `cancel` (abandon a conversation; needs `conversation_id`), `undo` (needs `undo_token`), `ramble` (multi-task dump; `utterance` holds the whole transcript; `conversation_id` set when it answers an `expects: "ramble"` question), `migrate` (4.13; needs `lines` and `chunk`), `complete_by_legacy_id` (4.13 step 5; needs `legacy_id`), `status` (health check, 4.5.3) |
| `utterance` | The **exact** text, untrimmed and unedited: Whisper's transcript for voice, the typed text for `typed`, the subject line for `email`, the shared text for `share`, the notification title and text for `notification`. For `follow_up` it is the answer as heard. Max 8,000 chars (ramble). Absent for `undo`, `cancel`, `migrate`, `complete_by_legacy_id`, `status` |
| `description` | Optional. Text stored as the task description and **never parsed** for dates or entities: an email body, the rest of a shared text, a notification's expanded text. Max 16,000 chars. Only accepted with `source` `email`, `share` or `notification` |
| `external_id` | Optional. A stable id from the source (the email `Message-ID`). Recorded in `import_map(source = context.source)`, so the same email sent twice creates one draft |
| `option_id` | For `follow_up` when the user tapped an option chip instead of speaking |
| `conversation_id` | For `follow_up`, `cancel`, and a `ramble` that answers an `expects: "ramble"` question |
| `undo_token` | For `undo` |
| `lines`, `chunk` | For `migrate`: `lines` is an array of raw `tasks.md` lines; `chunk` is `{"index": 0, "count": 3, "file_sha256": "…"}` |
| `legacy_id` | For `complete_by_legacy_id` |
| `context.now_epoch_ms` | Client wall clock at send time |
| `context.speech_end_epoch_ms` | When the user stopped speaking. "In 20 minutes" is computed from this, not from save time. For non-voice sources it equals `now_epoch_ms` |
| `context.zone` | IANA zone ID from `ZoneId.systemDefault()`. Required |
| `context.locale` | BCP 47. v1 parses English only. A non-`en` locale still parses English, but dates are formatted for the locale |
| `context.hour_cycle` | `h12` or `h23`, for how replies say times |
| `context.source` | `voice`, `typed` (Assistant's `submitText`), `share`, `notification` (10, option B), `email` (connector) |
| `context.round` | Number of questions already asked in this conversation: 0 for a fresh command, 1 or 2 for follow-ups |
| `context.routed_by` | `keyword`, `model`, `follow_up` (Assistant sent it straight back to Tasks), `connector`. Affects `not_mine` handling (4.5) |

Unknown fields MUST be ignored by both sides. A field that the `type` does not use is ignored.

### 4.5 Response schema (v1)

```json
{
  "v": 1,
  "request_id": "6f1c2a0e-1b7d-4a53-9d0e-3f6b1f0f2a11",
  "status": "done",
  "speech": "Okay. I'll remind you to call Amira today at 5 pm.",
  "display": { "title": "Call Amira", "lines": ["Today 5:00 pm · Inbox · reminder on"] },
  "effects": [
    { "op": "created", "task_id": "t_8Q2c", "title": "Call Amira", "project": "Inbox",
      "due": { "date": "2026-10-27", "time": "17:00", "zone": null }, "recurrence": null,
      "reminder_at_epoch_ms": 1793120400000 }
  ],
  "warnings": [],
  "conversation": null,
  "undo": { "token": "u_41fe", "expires_epoch_ms": 1793110380000 },
  "open_uri": "tasks://task/t_8Q2c",
  "error": null
}
```

| `status` | Meaning | What Assistant does |
|---|---|---|
| `done` | Action committed (or query answered) | Speak `speech` (nothing if empty), CONFIRM cue, keep `undo` |
| `needs_input` | Nothing committed; a question is pending in `conversation` | Speak `speech` (the question), show `conversation.options` as chips, re-listen, send `follow_up` (or, for `expects: "ramble"`, a long capture sent as `ramble`) |
| `declined` | Understood it is a task command, but the words do not determine a safe action, or the user cancelled | Speak `speech`, REJECT cue, no re-listen |
| `not_mine` | Not a task command at all | If `routed_by` is `keyword` or `follow_up`, run the transcript through Assistant's normal path as a fresh command; otherwise speak "I cannot do that yet." |
| `error` | Internal or contract failure; `error.code` set | Speak `speech` (always filled with a plain sentence), REJECT cue |

#### 4.5.1 Field definitions

| Field | Shape and rules |
|---|---|
| `speech` | String, may be empty only for `cancel`, `status` and `complete_by_legacy_id`. Rules below |
| `display` | `{ "title": string, "lines": [string] }` or null. At most 6 lines of at most 80 chars. Shown on Assistant's overlay; never spoken |
| `effects` | Array of effect objects (table below), in the order applied. Empty for `needs_input`, `declined`, `not_mine`, `error` |
| `warnings` | Array of `{ "code": string, "fix": string }`. Codes: `notifications_off`, `exact_alarm_denied` (the reminder fires "at about" the time), `channel_muted`, `battery_restricted`. `fix` is one sentence for the overlay. `speech` already includes the spoken caveat, so Assistant never composes text itself. Follows Assistant's honest-reminder behaviour from `ReminderStore.create` |
| `conversation` | Only with `needs_input` (4.5.2) |
| `undo` | `{ "token", "expires_epoch_ms" }` on `done` for every mutation from `utterance` or `follow_up`; null for queries and for `undo`, `status`, `cancel`, `migrate`, `ramble` and `complete_by_legacy_id` requests |
| `open_uri` | Set on `done` when the result has one natural screen: one task → `tasks://task/{id}`; a ramble → `tasks://ramble/{id}`; a query → `tasks://today` (or `tasks://upcoming` for a future-day query). Null otherwise |
| `error` | Only with `status: error`: `{ "code": string, "detail": string }`. `detail` is for logs, never spoken, and never contains utterance text |

Effect objects. Every effect has `op`. A task effect also has `task_id`, `title`, `project`, `due`
(`{date, time|null, zone|null}` or null), `recurrence` (`{ "rrule": "FREQ=…", "anchor":
"schedule"|"completion", "text": "every day at 9 am" }` or null) and `reminder_at_epoch_ms` (the
next fire time, or null).

| `op` | Extra fields | Produced by |
|---|---|---|
| `created` | task fields | create |
| `updated` | task fields, `changed`: list of field names (`priority`, `project`, `labels`, `title`) | move project, add label (priority: priorities removed from v1 (owner, 2026-11-02)) |
| `rescheduled` | task fields, `previous_due` | reschedule, snooze-by-voice |
| `completed` | task fields; for a recurring task `due` is the **next** occurrence and `completed_occurrence` the one ticked | complete, `complete_by_legacy_id` |
| `deleted` | task fields (as they were) | delete |
| `undone` | `undone_op`, `task_ids` | undo |
| `listed` | `query` (`today`, `tomorrow`, `overdue`, `date:2026-10-28`, `project:Shopping`), `items`: [`{task_id, title, due, project, priority, overdue}`], `total`, `truncated` | query |
| `ramble_drafted` | `ramble_id`, `count`, `flagged` | ramble |
| `migrated` | `chunk_index`, `imported`, `already_imported`, `needs_review`, `non_task_lines`, `done_all` (true when every chunk of `file_sha256` has arrived) | migrate |
| `drafted` | `draft_id`, `list` (`from_email`, `from_notification`) | `email`, `notification` sources (10) |
| `project_created` / `label_created` | `id`, `name` | explicit create, or "Create it" answer (4.7) |

#### 4.5.2 `conversation`

```json
{ "id": "c_19ab", "expects": "date", "expires_epoch_ms": 1793109870000, "rounds_left": 1,
  "options": [ { "id": "o1", "label": "Friday 30 October" },
               { "id": "o2", "label": "Friday 6 November" } ] }
```

`expects` is one of `date`, `time`, `date_time`, `title`, `project`, `label`, `task`, `choice`,
`confirm`, `ramble`. It tells Tasks how to parse the answer, and lets Assistant pick a UI (chips for
`choice`, `confirm` and `task`; long capture for `ramble`). Clients treat an unknown `expects` as
`choice`. `options` has at most 3 entries. `expires_epoch_ms` is `now + 90 s` and is reset by every
new question.

#### 4.5.3 Responses by request `type`

| `type` | Possible `status` | Notes |
|---|---|---|
| `utterance`, `follow_up` | any | |
| `ramble` | `done` (`ramble_drafted`), `declined` (nothing left after the junk gate: "I didn't catch any tasks in that."), `error` | |
| `cancel` | `done` | `speech` empty, no effects. Assistant speaks nothing |
| `undo` | `done` (`undone`), `declined` | 4.8 |
| `migrate` | `done` (`migrated`), `error` | 4.13 |
| `complete_by_legacy_id` | `done` (`completed`), `declined` (unknown id) | `speech` empty; Assistant shows nothing (it came from a notification tap) |
| `status` | `done` | `speech` empty. Extra top-level object `"status": {"contract_version": 1, "counts": {"open": n, "overdue": n, "due_today": n}, "permissions": {"notifications": bool, "exact_alarm": bool, "battery_unrestricted": bool}, "migrated": ["assistant_md"]}` |

#### 4.5.4 Speech rules (enforced by `SpeechWriter`, unit-tested)

- One or two short sentences, plain text, no markdown, no emoji, no IDs.
- Every committed date or time is said back in full: day word, plus the date when it is more than a
  week away, plus am/pm in `h12`. Every applied default (8.3.1) is said as its clock time. The user
  can only catch a misparse if they hear it.
- A task saved without a reminder after a "remind me" says so ("no alert").
- Titles are said back as stored. Queries read at most 5 items, then "and N more".
- Questions end with a question mark and offer at most 3 choices.

### 4.6 Versioning

- `v` is the major version. Additive changes (new optional fields, new `warnings` codes, new
  `effects` ops, new `expects` values that clients may treat as `choice`, new request `type`s) do
  **not** bump it. Removing or re-meaning a field does.
- Tasks supports the current major and the previous one. `contractVersion()` returns the highest.
  Assistant calls it once per bind. If Assistant's major is newer than Tasks', Assistant says "Please
  update the task app." If older and unsupported, Tasks returns error `unsupported_version` with
  speech "Please update Assistant."
- Golden request/response fixtures live in `tasks-app/contract/v1/` (13.4). They MUST cover every
  `type`, `status`, `op` and `expects` value above, and they are written before M3 starts.

### 4.7 Multi-turn follow-ups

- **State.** Tasks owns conversation state. It stores the draft (parsed slots, unresolved slot,
  original utterance, the original `speech_end_epoch_ms`, every instant already computed, round) in
  the `conversation` table, keyed by `conversation.id`. Instants are **frozen**: "in 20 minutes"
  with the title supplied later still fires 20 minutes after the first utterance ended.
- **TTL.** 90 s from the moment each question is returned. Every new question resets it.
- **One live conversation per client.** A new `utterance` from the same client cancels any open
  conversation first.
- **Assistant's side.** Assistant only carries the id. After speaking a `needs_input` question, it
  re-listens once (normal endpointing) and sends `{"type":"follow_up","conversation_id":…,
  "utterance":<new transcript>}` with `routed_by: "follow_up"` **directly to Tasks, skipping its
  router**. A tapped chip sends `option_id` instead. If the user says nothing, Assistant sends
  `cancel` and speaks nothing extra.
- **Parsing an answer**, in order:
  1. Escape words ("cancel", "never mind", "stop", "forget it", "no thanks") cancel the conversation
     and return `declined` with "Okay, I've left it."
  2. The answer is parsed against `expects`: a `date` question accepts "Friday", "the 6th" or "next
     week Friday"; a `confirm` question accepts yes/no variants; a `task` question accepts any words
     that match one of the offered tasks.
  3. If that fails but the answer fills **another** missing slot of the same draft ("Friday" when a
     time was asked and the date is also missing), the slot is accepted and the remaining one is
     asked if a round is left.
  4. If the answer itself starts with an intent frame (6.1 step 3) and is not a valid answer, Tasks
     cancels the conversation and handles the answer as a fresh `utterance`. If no frame matches and
     it is not an answer, Tasks cancels the conversation and returns `not_mine`; Assistant then
     routes the transcript as a fresh command ("what's the weather" is not eaten).
  5. An answer that is itself ambiguous ("next Friday") or unparseable is re-asked once with a
     narrower prompt ("Say a day, like Friday or the 6th."), if a round is left.
- **Limits.** At most 2 questions per command. When the second question's answer still does not
  complete the draft, the result is `declined`: "I still didn't get a day, so I haven't saved it.
  You can add it in Tasks." **Nothing is saved until the conversation completes.** A
  half-specified reminder is never stored as a guess.

Questions Tasks may ask. This is the complete list for v1; a new question needs a new row here and a
seed case.

| Trigger | Question | `expects` |
|---|---|---|
| "remind me to …" with no date and no time | "When should I remind you?" | `date_time` |
| "remind me …" with a date but no time (8.3) | "What time on Friday the 30th?" Options: the morning named time ("9 am"), "No alert" | `time` |
| A time with no title | "What should I remind you about?" | `title` |
| "next Friday", "this weekend", "next week" with no day, a same-day weekday or month-day, "every other week" with no day | Both or all readings ("This Friday the 30th, or Friday the 6th?") | `date` |
| Bare hour in the night window, bare hour in a recurrence, "at 12" (8.3) | "5 in the morning or the evening?", "Noon or midnight?" | `time` |
| An explicit time today that has passed (8.3) | "1 pm today has passed. Tomorrow at 1 pm?" | `confirm` |
| A past explicit date (8.3) | "3 October has passed. Do you mean next year?" | `confirm` |
| Self-correction inside one utterance (6.1 step 7) | Both readings ("Tomorrow at 5 pm, or Friday at 5 pm?") | `choice` |
| Unknown project or list named | "I don't have a Garden list. Create it, or add it without a list?" | `choice` |
| Unknown label named | "I don't have an 'errands' label. Create it, or leave it off?" | `choice` |
| Unknown section in a known project | "Home has no 'Garden' section. Put it in Home without a section?" | `confirm` |
| 2 or 3 tasks match complete/reschedule/delete (6.1 step 5) | "Which one: call Amira about the party, or call Amira's dentist?" | `task` |
| 4 or more tasks match | "I found 5 tasks with Amira. Which one?" with the top 3 as options; the user may also say more of the name | `task` |
| One task matches only fuzzily (6.1 step 5) | "Do you mean 'Pay the gas bill'?" | `confirm` |
| "it" or "that" with no live referent (4.11), or after a query that listed more than one task | "Which task?" (after a query: the first 3 listed, as options) | `task` |
| "start a brain dump" with no content (6.5) | "Go ahead, I'm listening." | `ramble` |

Not questions (fixed behaviour, never asked):

- "Add X to my list" with no date or time saves undated.
- Delete of a recurring task deletes the whole series (8.2), undoable.
- An open-task set with no match declines: "I couldn't find a task like that."

"Create it" after an unknown project or label question is the **one** case where speech creates a
project or label without an explicit "create a list called …" command, because the user just
confirmed it. The created name is the words the user said, title-cased.

### 4.8 Undo

- Every mutation writes an `undo_journal` row in the same transaction: the inverse operations, plus
  an alarm re-arm. Voice mutations return `undo.token` (opaque, single use, expires after 10
  minutes).
- Assistant keeps **only the last** token, in memory, process-wide, until it expires. A second
  command replaces it, so the earlier action can then be undone only in the Tasks app. If
  Assistant's process dies, the token is lost. Both limits are accepted.
- Two paths:
  - When a transcript matches its deterministic undo pattern (`^(undo|undo that|scratch that|take
    that back)\b`, after the leading-filler strip in 4.12 step 3) and a token is held, Assistant
    sends `{"type":"undo","undo_token":…}`. This check runs before `TaskRouting` and before
    Assistant's own commands. "Cancel that" is **not** an undo phrase, because it collides with
    Assistant's own cancel flows.
  - The Done overlay shows an "Undo" chip while the token is valid.
- Tasks replies with `done` and "Undone. I've removed 'Call Amira'." An expired or used token
  returns `declined` with "That can't be undone any more. You can change it in Tasks." Without a
  token there is no undo by voice. No guessing at which action the user meant.
- **Conflicts.** The journal row records each touched row's `updated_at`. If any of those rows
  changed since (edited in the UI, re-imported, purged), undo is refused with `declined`: "That task
  has changed since, so I've left it. You can change it in Tasks."
- In-app, every mutation shows a 5-second snackbar Undo backed by the same journal row. Whichever is
  used first marks the row used; the other then gets the "can't be undone" answer.
- Undo of completing a recurring task restores the previous due date and removes the completion
  log row.
- Imports (4.13, 11) produce no voice token. They are undone from their summary screen.

### 4.9 Errors

| `error.code` | When | `speech` |
|---|---|---|
| `bad_request` | JSON invalid, a required field is missing, or the request is over 256 KiB | "Something went wrong talking to the task app." |
| `unsupported_version` | Major `v` not supported | "Please update Assistant." / "Please update the task app." |
| `caller_not_trusted` | Uid/signature check failed | "The task app is signed with a different key." |
| `busy` | Deadline passed before the transaction started (4.3) | "The task app took too long. Nothing was saved." (true by construction) |
| `storage` | Room or disk failure | "I couldn't save that; storage failed." |
| `conversation_expired` | Follow-up after the TTL | "I lost track of that. Please say it again." |
| `internal` | Anything else (caught at the service boundary) | "Something went wrong in the task app." |

Client-side failures (Assistant): Tasks not installed, bind refused, `DeadObjectException`, timeout.
Each maps to one fixed sentence in `TaskAppClient`:

- Not installed: "Install Tasks to keep lists and reminders." (There is no legacy fallback, see
  4.12 step 7.)
- Timeout: "The task app didn't answer. Check Tasks before trying again." It does **not** say that
  nothing was saved, because the client cannot know.

### 4.10 Latency budget

Assistant's budget is under 3 s from end of speech to start of the spoken reply, warm, on the
reference device (`themachine/CLAUDE.md`: "< 3 s … (tiny.en + 1B)"; locked decision 1 names the
owner's Samsung foldable, Snapdragon SM8850, Android 17, as the primary target). Tasks' share:

| Stage | Budget | Notes |
|---|---|---|
| Keyword pre-route (`TaskRouting.claims`) | < 5 ms | Claimed commands skip Assistant's model. The saving (assumed 0.5-1 s of routing time) is an assumption to measure in M3 |
| Bind | 0 ms on the critical path | `TaskAppClient.prewarm()` binds when listening starts; cold process start (assumed 200-400 ms, measure in M3) overlaps speech |
| Binder transport | < 10 ms | |
| `handle()`, grammar path, incl. Room write + alarm arm | p50 ≤ 50 ms, p95 ≤ 150 ms on the reference phone | Design targets, not measurements. Measured in a debug timing log and asserted by an instrumented benchmark in M3 |
| `handle()` hard deadline | 600 ms | Checked before commit (4.3) |
| Assistant client timeout | 800 ms per attempt | One retry with the same `request_id`, **only** after `DeadObjectException` or a lost binding, never after a timeout. Total Tasks wall time on any path ≤ 1,600 ms; the success path stays inside the 3 s budget |
| On-device model in Tasks | None | Tasks runs pure deterministic Kotlin; optional heavy ramble processing is deferred to the homelab GPU integration (6.7) |
| Ramble | Return `ramble_drafted` within 1,000 ms (grammar only). Not part of the 3 s budget; see 6.5 for the capture budget | |

### 4.11 What the task app does with each utterance (v1 intents)

| Intent | Example | Must/should |
|---|---|---|
| create (with optional due, time, recurrence, reminder, project, labels, priority, deadline) | "remind me to call Amira at 5", "add oat milk to my shopping list", "priority one call the insurer tomorrow morning" | must |
| query | "what's on my list today", "what do I have tomorrow", "anything overdue", "read my tasks" | must |
| complete | "mark call Amira as done", "tick off the gas bill", "I've paid the gas bill" | must |
| reschedule / snooze | "move the dentist to Thursday at 10", "push that to tomorrow", "snooze that for an hour" | must |
| delete | "delete the gym task", "remove the gym reminder" | must |
| ramble | "brain dump …", "start a brain dump" (or `type: ramble`) | should (M4) |
| undo | via token | must |
| change an existing task: move project / add label (priority: priorities removed from v1 (owner, 2026-11-02)) | "make the insurer task priority one" | should |
| create project, list or label (explicit only) | "create a list called Garden", "create a label called errands" | should |
| reopen (uncomplete) | "un-tick call Amira" | later |

Priority, project and labels said **while creating** a task are part of create (must). Changing them
on an existing task is the "should" row.

**Referents.** "That" or "it" refers to the task in the most recent single-task effect for the same
client package. Tasks stores it in a small Room table `referent(client, task_id, expires_at)`, kept
10 minutes, so a Tasks process death does not lose it. Debug and release Assistants are different
packages and never share a referent. With no live referent, or when the last effect was a `listed`
with more than one item, Tasks asks "Which task?" (4.7). A `not_mine` or `declined` response does
not change the referent.

### 4.12 Exactly what Assistant must change

Assistant gains IPC plumbing, a regex pre-route and UI for questions. It gains **no** task grammar,
no date parsing, no task DB and no bigger model. Migration sends raw `tasks.md` lines; Tasks parses
them.

Honest size: about 800 production lines (tests extra), capped at **850** in the files listed below.
If the builder needs more, or needs a file not listed, that is a scope change for the owner. Paths
are relative to `themachine/app/`. `VoiceSession.kt` was split on 2026-10-27, so the session code is spread over
`VoiceSession` (the conductor), `SpeechCapture.kt`, `CallChecks.kt`, `FollowUps.kt` and
`PendingChoice.kt`, all in `assistant/`.

| # | File | Change | Est. lines |
|---|---|---|---|
| 1 | `src/main/AndroidManifest.xml` | `<permission>`, `<uses-permission>` (4.2.1), `<queries>` (4.2.4) | 15 |
| 2 | `build.gradle.kts` | `buildFeatures { aidl = true }` | 1 |
| 3 | `src/main/aidl/io/github/ijazshare/tasks/ipc/ITaskCommand.aidl` | Byte-identical copy of Tasks' file, same package name (13.4 keeps it in sync) | 10 |
| 4 | `src/main/java/.../tasks/TaskAppClient.kt` | Bind, send, timeout and retry, last undo token, unbind | 220 |
| 5 | `src/main/java/.../tasks/TaskContract.kt` | kotlinx.serialization classes mirroring 4.4 and 4.5 (Assistant already uses kotlinx.serialization or whatever it uses for tool JSON: reuse it) | 150 |
| 6 | `src/main/java/.../tasks/TaskRouting.kt` | Regex pre-route, below | 80 |
| 7 | `src/main/java/.../tasks/TaskMigration.kt` | Read raw lines, chunk, send, cancel own alarms, rename file (4.13) | 80 |
| 8 | `tools/AssistantTools.kt`, `tools/ToolExecutor.kt`, `llm/PromptBuilder.kt` | Replace `create_reminder` with `tasks` | 15 |
| 9a | `assistant/VoiceSession.kt` | `prewarm()` call in `start()`, pre-route in `act()`, `carryOut` branch, `SessionState.FollowUp` | 70 |
| 9b | `assistant/FollowUps.kt` | Task follow-up loop (beside `answeredPendingSend`, `listenForAnswer`, `chooseContact`) | 40 |
| 9c | `assistant/SpeechCapture.kt` | Ramble long capture (a `listen()` parameter) | 20 |
| 10 | `ui/` overlay | Undo chip, option chips, Settings "Run migration" button | 80 |
| 11 | `audio/Endpointer.kt`, `audio/VoiceRecorder.kt` | `long` profile parameter (today `DEFAULT_MAX_DURATION_MILLIS = 20_000L`) | 20 |
| 12 | `context/AssistantFiles.kt` | Drop `TASKS` from `contextForPrompt()` after migration | 5 |

Steps:

1. **Manifest and Gradle** (rows 1-3).
2. **`TaskAppClient.kt`:**
   - Target: explicit `ComponentName(pkg, "io.github.ijazshare.tasks.ipc.CommandService")`, where
     `pkg` is `io.github.ijazshare.tasks` or `.debug` per `BuildConfig.DEBUG`. The class name is the
     same in both variants.
   - `prewarm()` calls `bindService(BIND_AUTO_CREATE)` when listening starts. If already bound it
     does nothing.
   - **Unbind:** 30 s after the voice session ends (an idle timer reset by every new session), and
     immediately when Assistant's process is going away. Tasks' process is then free to be
     reclaimed.
   - `suspend fun send(request): TaskResponse?` on `Dispatchers.IO`, with the timeout and retry from
     4.10.
   - `isInstalled()`, and the last undo token (4.8).
3. **`TaskRouting.kt`**, a deterministic pre-route. Over-claiming is cheap and safe, because a
   `not_mine` answer to a keyword-routed request falls back to Assistant's normal path (4.5).
   Under-claiming costs a model call and risks the router not picking `tasks`.
   - First, strip leading fillers repeatedly: `^(okay|ok|hey|so|right|please|um|uh)[,.!]?\s+`.
   - Never claim if the transcript contains `\b(timer|alarm|wake me)\b`. Those stay with the Clock
     tools.
   - Claim when the stripped transcript matches any of (case-insensitive):
     - `^remind me\b`
     - `^(add|put) .+ (to|on|in) (my|the) .*\b(list|tasks?|to-?dos?)\b`
     - `^(add|create|new) (a )?(task|to-?do)\b`
     - `^(mark|tick|check) .+ (as )?(done|complete|completed|off)\b`, `^tick off\b`
     - `^(i've|i have) (done|finished|completed|paid|sent|called|booked|bought|posted|emailed)\b`
     - `^(what's|what is|what do i have|anything|is there anything)\b.*\b(on my (list|tasks)|my tasks|to-?do|due|overdue)\b`, `^what do i have (today|tomorrow|on)\b`
     - `^(read|show|open) my (tasks|list|to-?do( list)?)\b`, `^my (tasks|to-?do( list)?)\b`, `^to-?do\b`
     - `^(move|push|reschedule|postpone|snooze) .+ (to|until|for|by)\b`, `^(snooze|push) (that|it)\b`
     - `^(delete|remove) .+ (task|reminder|from my list)\b`
     - (priorities removed from v1 (owner, 2026-11-02)) `^(priority|p) ?(one|two|three|four|[1-4])\b`, `^(make|set) .+ priority\b`
     - `^(create|make|add) (a )?(new )?(list|project|label) (called|named)\b`
     - `\b(brain ?dump|ramble)\b` within the first 5 words
   - The undo pattern (4.8) is checked before this, and only when a token is held.
   - The phrase list is not hand-copied: both repos test against the shared fixture
     `contract/v1/routing.jsonl` (13.4). Tasks asserts every `claim: true` line parses to an intent
     other than `not_mine`; Assistant asserts `TaskRouting.claims` matches every line's `claim`
     flag.
4. **Tools:**
   - `tools/AssistantTools.kt`: replace `create_reminder` with `tasks`. It takes **no parameters**, so
     the router has nothing to invent. Description: "Tasks, to-do lists and reminders: remind me,
     add to or read my list, what is due or overdue, mark done, move, snooze, delete, brain dump.
     Not alarms or timers." The Clock tools are unchanged.
   - `tools/ToolExecutor.kt`: remove the `create_reminder` handler. The `tasks` handler calls
     `sendToTasks(transcript, "model")`.
   - `llm/PromptBuilder.kt`: change the reminder example to `remind me to take the bins out at 8pm ->
     {"tool":"tasks"}`, in exactly whatever shape the grammar emits for a parameterless tool such as
     `take_screenshot`.
   - Re-run `bench/` routing on the real-log slice before shipping.
5. **`assistant/VoiceSession.kt`, `assistant/FollowUps.kt`, `assistant/SpeechCapture.kt`:**
   - (a) in `VoiceSession.start()`, call `taskClient.prewarm()`;
   - (b) in `VoiceSession.act()`, after the two pending-follow-up checks (`answeredPendingSend`, so
     "Text Sam" then "pick up milk" is still a message; Assistant's own `answeredPendingReminder` goes
     with the legacy path, step 7) and before `answeredWithoutTheModel` (today: `LocalAnswers`, then
     `Arithmetic`, then the command cache) and the model: the undo check (4.8), then `if
     (TaskRouting.claims(t)) return sendToTasks(t, "keyword")`;
   - (c) in `VoiceSession.carryOut()`, when the tool is `tasks`, call `sendToTasks(transcript,
     "model")` with the **current transcript**, never a cached argument. `CallChecks.reconciled`
     runs first and leaves a `tasks` call untouched (its `else` branch);
   - (d) map the response to the existing `SessionState.Done(…)` with `ToolResult(spoken = speech,
     success = status == done)`;
   - (e) add `SessionState.FollowUp(question, options)`. After the question is spoken, call
     `listenForAnswer()` (`FollowUps.kt`; it re-opens the microphone only for a voice session that
     was not stopped or dismissed since the command began) (max 2 rounds) and route that transcript
     to `follow_up` with `routed_by: "follow_up"`. Option chips reuse the `ChooseContact` pattern
     (`SessionState.ChooseContact`, `PendingChoice.kt`, `chooseContact()` in `FollowUps.kt`);
   - (f) on `not_mine` with `routed_by` `keyword` or `follow_up`, continue into the existing model
     path with the same transcript;
   - (g) on `needs_input` with `expects: "ramble"`, listen (`SpeechCapture.listen()`) with the `long` profile (step 8) and send
     the transcript as `type: "ramble"` with the `conversation_id`.
6. **`ui` overlay:** an Undo chip on Done while a token is valid; option chips on FollowUp; in
   Settings, the Tasks link status and a "Run migration" button.
7. **Legacy path.**
   - `create_reminder` and `ReminderStore.create` stop being called the moment the `tasks` tool
     ships. There is **no** fallback that creates reminders in Assistant: if Tasks is not installed,
     the `tasks` tool speaks "Install Tasks to keep lists and reminders."
   - `ReminderReceiver` and `ReminderBootReceiver` stay only to fire, and re-arm after boot,
     reminders that Assistant had already armed, until migration (4.13) has cancelled them. They are
     deleted in the first Assistant release after the owner's phone reports `migrated`. The same
     release deletes what only the reminder tool used: `PendingReminder` and
     `answeredPendingReminder`, `ToolExecutor.ASK_TASK`, `ReminderStore.create`, and the
     `CREATE_REMINDER` branches of `CallChecks` (`withSpokenTime` stays for `set_alarm`).
     That deletion is not counted against the 850-line cap.
   - The Assistant change ships together with Tasks M3, never before.
8. **Ramble capture** (M4): a `long` endpointing profile, used only after an `expects: "ramble"`
   question (4.7):
   - cap 60 s instead of 20 s;
   - trailing quiet 2.5 s;
   - the transcript is sent as `type: ramble`.

   This is one parameter on the existing recorder, not a new pipeline. A one-shot dump ("brain dump.
   Call the garage …", seed 26) needs no long capture: it arrives as a normal utterance whose
   content follows the frame, and Tasks treats it as a ramble.
9. **`context/AssistantFiles.kt`:** after migration, drop `TASKS` from `contextForPrompt()`. Routing
   does not use it already, and a stale copy would give `AnswerPrompt` wrong facts. Only the
   on-device answer model ever sees that context: the opt-in remote answer model is sent the
   question, fixed instructions and the current time, never tasks, notes or the owner's name
   (`llm/RemoteAnswerer.kt`), so Tasks data never leaves the phone through Assistant, and Q14 must
   keep it that way.

### 4.13 Migration of `tasks.md` and armed reminders

Source format (from `AssistantFiles.kt`): `- [ ] title — due Tue 2 Sep, 7:00 PM  <!-- id:ab12cd34
due:2026-09-02T19:00 added 2026-09-01 18:22 -->`. A reminder that has fired reads `- [ ] title — due
Tue 2 Sep, 7:00 PM  <!-- id:ab12cd34 delivered added 2026-09-01 18:22 -->`. An untitled reminder is
stored with the title `Reminder`.

- Hand-written lines have no comment, but may still carry visible `— due …` text.
- Once a reminder fires, `markDelivered` turns its `due:` field into a bare `delivered` flag in the
  trailing comment (the visible `— due …` text stays). Swiping the notification or opening it
  (`markSeen`) removes the flag. After `BOOT_COMPLETED` a flagged open task is shown once more, and
  that showing drops the flag. So a remaining `due:` in the past means a reminder that never fired;
  `delivered` means it fired and was never answered; a line with an `id:` but neither means it fired
  and was seen (or never had a time).
- The file may also hold lines that are not tasks (headings, notes, blank lines).

Procedure. It runs once at the first successful `status` handshake, and also from the Settings
button.

1. **Assistant** reads the file as raw lines. It does not parse them. It computes the file's
   SHA-256.
2. **Assistant** sends the lines in chunks of at most 200 lines and 128 KiB:
   `{"type":"migrate","lines":[…],"chunk":{"index":i,"count":n,"file_sha256":"…"}}`.
3. **Tasks** (`AssistantMarkdownImporter`) handles each chunk in one transaction:
   - Each task line gets a key: the comment's `id:` if present, else `"md:" + sha256(raw line)`.
     The key is unique in `import_map(source = "assistant_md")`, so re-sending a chunk adds nothing
     twice.
   - Items go to Inbox with `source = assistant_md`. The raw line is stored in `source_utterance`.
   - Title: the line minus the checkbox, the `— due …` text and the comment.
   - A line whose comment has an `id:` and no `due:` already fired (or never had a time). Its visible
     `— due …` text is imported as the task's due date for display only, with **no** reminder, so
     a reminder the owner already saw does not come back as "Missed". With the `delivered` flag it
     gets exactly one immediate "missed while you were away" delivery (8.4), the same single
     re-show Assistant gives it after a reboot. Done items ignore the flag.
   - Due: from the comment's `due:` value if present. Otherwise (a hand-written line, no `id:`) the visible `— due …` text is run
     through Tasks' own date grammar against the `added` stamp (or migration time). If that gives a
     single `Parsed` value it is used, else the task is imported undated, with the original text,
     onto the "Import needs review" list. Due is **floating** (date and time, no zone).
   - Each open item with a due gets an at-due reminder. One in the past gets one immediate "missed
     while you were away" delivery (8.4).
   - Done items are imported as completed, with completion time = migration time and a flag.
   - Lines that are not tasks are counted in `non_task_lines`. They are not imported; they survive
     in the renamed backup file, and the import summary says so.
   - Tasks arms its alarms and **then** responds `done` with a `migrated` effect (counts only, so
     no list can be truncated).
4. When a response has `done_all: true` for the file's SHA-256, **Assistant**:
   - cancels its own alarm for every id its existing `readTasks()` returns, using the existing
     `pendingIntentFor(id)` (existing code, no new parsing);
   - renames `tasks.md` to `tasks.migrated-2026MMDD.md`, kept as a backup and never re-read;
   - sets `tasksMigrated = true` in DataStore.

   A failure at any step leaves the file and alarms untouched, and the whole procedure retries next
   session; already-imported keys are skipped. The worst case is a duplicate notification within
   one second of migration, never a lost one.
5. Notifications Assistant already posted keep working: its `ReminderReceiver` Done/Snooze actions
   stay until the legacy code is deleted (4.12 step 7). A Done tap on a migrated id forwards
   `{"type":"complete_by_legacy_id","legacy_id":…}` to Tasks.

---

## 5. Data model (Room)

All ids are random strings (`t_` + 10 base62 chars, etc.). Timestamps are `Instant`, stored as epoch
ms. Everything soft-deletes (`deleted_at`) so undo and import mapping stay simple. A purge job
(run on app start, at most daily) removes rows deleted more than 30 days ago and applies the
retention rules in section 12. `order` is a reserved SQL word, so the column is `sort_order`.

```kotlin
@Entity project(
  id, name, name_key: String /* normalised lowercase, for lookup */, color: String?,
  parent_id: String?, sort_order: Int,
  is_inbox: Boolean,                // exactly one row; cannot be deleted or renamed
  is_archived: Boolean, view: String /* list|board(later) */,
  created_at, updated_at, deleted_at?)
  // index on (parent_id, name_key). Uniqueness among non-deleted siblings is enforced by
  // Repository inside the transaction (Room annotations cannot express a partial unique index).

@Entity section(id, project_id, name, name_key, sort_order, is_archived,
                created_at, updated_at, deleted_at?)       // same uniqueness rule per project

@Entity label(id, name, name_key, color?, sort_order, created_at, updated_at, deleted_at?,
              schedule_json: String? /* 8.6; null = Regular. Added in schema v2 */)
  // name_key unique among non-deleted labels, enforced by Repository

@Entity task(
  id, title, description: String /* markdown, may be empty */,
  project_id, section_id?, parent_id? /* subtask, max depth 4 */, sort_order: Int,
  priority: Int /* priorities removed from v1 (owner, 2026-11-02): always 4, ignored; was 1 = highest (spoken "priority one" / p1) … 4 = none */,
  // due (all null = no due)
  due_date: LocalDate?, due_time: LocalTime? /* null = all-day; minute precision */,
  due_zone: String? /* IANA id; null = floating, interpreted in the device's current zone */,
  recurrence: String? /* RRULE subset, see 8.2 */, recurrence_anchor: String? /* schedule|completion */,
  recurrence_text: String? /* as the user said it, for display */,
  deadline: LocalDate? /* not allowed on recurring tasks */, duration_min: Int?,
  completed_at: Instant?, created_at, updated_at, deleted_at?,
  missed_at: Instant? /* an alert was missed, task not opened since (8.6 rule 8). Schema v2 */,
  source: String /* voice|typed|ramble|share|notification|email|assistant_md|todoist */,
  source_utterance: String? /* exact words that created it; cleared by "Clear voice history" */)

@Entity task_label(task_id, label_id)  // PK both

@Entity reminder(
  id, task_id,
  kind: String /* AT_DUE | BEFORE_DUE | AT_LOCAL | AT_INSTANT | NAG (8.6 uses AT_DUE, BEFORE_DUE, NAG;
                  at_instant holds each alert's fire time) */,
  offset_min: Int? /* BEFORE_DUE */,
  at_date: LocalDate?, at_time: LocalTime?, at_zone: String? /* AT_LOCAL */,
  at_instant: Instant? /* AT_INSTANT, "in 20 minutes" */,
  next_fire_at: Instant? /* derived cache; recomputed, never authoritative */,
  last_fired_at: Instant?, snoozed_until: Instant?, created_at, updated_at, deleted_at?)

@Entity completion_log(
  id, task_id, completed_at: Instant,
  due_date_at_completion: LocalDate?, due_time_at_completion: LocalTime?,
  due_zone_at_completion: String?, was_recurring: Boolean, imported: Boolean)

@Entity undo_journal(token, created_at, expires_at, used: Boolean, client: String,
                     summary: String, inverse_ops_json: String,
                     touched_json: String /* [{table, id, updated_at}] for the conflict check */)

@Entity conversation(id, client, created_at, expires_at, round: Int, expects: String,
                     draft_json: String /* slots, frozen instants, speech_end */,
                     original_utterance: String)

@Entity referent(client PK, task_id, expires_at)            // 4.11

@Entity request_log(request_id PK, received_at, response_json)  // dedupe, 10 min TTL

@Entity import_map(source: String /* assistant_md|todoist|email|notification */,
                   external_id: String, local_id: String,
                   kind: String /* task|project|section|label|draft */,
                   import_run_id: String, imported_at)  // PK (source, external_id)

@Entity import_run(id, source, started_at, finished_at?, summary_json, removed_at?)

@Entity ramble(id, created_at, source /* voice|typed|email|notification */, transcript,
               status /* open|saved|discarded */, closed_at?)
@Entity ramble_draft(id, ramble_id, sort_order, span_start, span_end, parsed_json, flags_json,
                     description: String?, state /* keep|edit|drop */, confirmed: Boolean)

@Entity utterance_log(id, at, utterance, response_status, intent?, parse_json)
  // 30-day retention, user-clearable; feeds the golden corpus only through the scrub step (13.1)
```

Indexes:

- `task(project_id, completed_at, deleted_at)`;
- `task(due_date)`;
- `reminder(next_fire_at)`;
- `project(parent_id, name_key)`, `section(project_id, name_key)`, `label(name_key)`;
- `task.title` FTS4 table (`task_fts`) for search and task matching.

Export: Settings → Export writes one JSON file (schema version + all tables, minus logs,
conversations, referents and `source_utterance`) through the Storage Access Framework. An "Include
voice history" switch, off by default, adds `source_utterance` and ramble transcripts. Import of
that file restores it. This is the backup story, and it needs no network.

---

## 6. Parsing pipeline

All of it is in `parse/`. It is pure Kotlin and deterministic. The input is the utterance plus
`ParseContext(now: ZonedDateTime, speechEnd: Instant, zone, hourCycle, projects, labels,
openTasksIndex, settings)`.

### 6.1 Stages

1. **Junk gate.** These return `not_mine` without parsing:
   - empty or filler-only text (`thank you`, `you`, `bye`, a lone `okay`);
   - text that is all punctuation;
   - Whisper's bracket tokens (`[BLANK_AUDIO]`, `(music)`).

   Reuse the phrase list from Assistant's `stt/JunkTranscript.kt`. That file carries an AGPL header
   (section 12, "Licence"), so either keep the header or rewrite the list. Its doc comment explains
   why: base.en turns music, TV or silence into "you" and "Thank you.", and the router turned those
   into alarms. A task app that saves "Thank you." as a task is broken.
2. **Normalizer.** Produces a lowercase working copy with an **offset map back to the original**:
   - leading fillers (`okay`, `ok`, `hey`, `so`, `right`, `please`, `um`, `uh`) are skipped, as in
     4.12 step 3;
   - number words become digits ("twenty five" → 25, "a quarter past" kept as a phrase token,
     "half" kept). Implemented as: the working copy is the same length as the utterance and the slot
     grammars read number words themselves, so the offset map is the identity;
   - unicode dashes and quotes are normalized;
   - **protected spans** are marked and are never parsed as dates or times: phone numbers
     (`\d{3}-\d{4}`, `555-0142`, `+44 …`), money (`£40`, `$12.50`), version-like tokens, quoted text,
     and anything after the spoken escape "literally". (Quote protection is this app's own design;
     Todoist's help does not document it. Todoist's escape is clicking the highlighted word, which
     6.3 copies.)
3. **Intent grammar.** Classifies into the 4.11 intents by the leading verb frame. Frames (each has
   corpus cases):
   - create: `remind me (to|about|that)?`, `add X (to|on) (my|the) Y list`, `put X on my list`,
     `add (a )?task`, `new task`, a leading priority (`priority one …`, `p1 …`), `create a (list|
     project|label) called`;
   - query: `what('s| is) (on|due|left|overdue)`, `what do i have`, `anything (due|overdue|on my
     list)`, `(read|show) my (tasks|list|to-?do)`, `my tasks`, `to-?do list`;
   - complete: `mark … (as )?(done|complete)`, `tick off …`, `tick … off`, `… is done`, `i('ve| have)
     (done|finished|paid|sent|called|booked|bought|posted|emailed) …`, `complete …`;
   - reschedule: `(move|push|reschedule|postpone) … (to|until)`, `snooze (that|it|…) (for)? <duration>`;
   - delete: `(delete|remove) … (task|reminder|from my list)?`;
   - ramble: `(start a |okay )?(brain ?dump|ramble)`.

   No frame matched → `not_mine`. The frame list and `TaskRouting` (4.12 step 3) are tested against
   the same fixture.
4. **Slot grammars.** Run in this order. Each consumes non-overlapping spans, longest match first:
   1. recurrence (`every …`, `daily`, `weekly`, `each …`), including its own time ("every day at 9");
   2. relative instants and durations (`in 20 minutes`, `in an hour and a half`, `for 30 minutes` =
      duration);
   3. dates (`today`, `tomorrow`, `tonight`, weekday names, `this/next <weekday>`, `on the 5th`,
      `5 November`, `November 5th`, `the day after tomorrow`, `in 3 days`, `next week`,
      `end of the month` = the last day of the current month);
   4. times (`at 5`, `5pm`, `17:00`, `half past six`, `quarter to 9`, `noon`, `midnight`, `in the
      morning/afternoon/evening`);
   5. deadline (`deadline Friday`, typed `{Friday}`);
   6. priority (priorities removed from v1 (owner, 2026-11-02): not parsed, the words stay in the title) (`priority one…four`, `p1…p4`, `high priority` = p2; `urgent` is a tag, 8.6 rule 6);
   7. project, section and label (spoken `in my X list`, `to the X project`, `label Y`, `tagged Y`;
      typed `#X`, `/S`, `%Y` and `@Y`).

   Two candidate spans that overlap with **different** values make the result `Ambiguous`.

   Phrases that sound structural but carry no exact value are recognised so they are never silently
   dropped: vague quantities (`in a few minutes`, `in a couple of hours`, `in a bit`, `later`,
   `soon`, `sometime`) decline with "I need an exact time, like in 20 minutes."; spoken zones
   (`London time`, `GMT`, `Eastern`, `UTC`) decline with "I can't set times in other time zones
   yet." (8.1).
5. **EntityResolver and TaskMatcher.**
   - Project, section and label names match **existing** names only: case-insensitive exact match
     on `name_key`, then plural/singular, then Damerau-Levenshtein ≤ 1 for names of 5+ characters,
     with a margin (the second-best must be ≥ 2 away). An unknown name produces the matching
     question in 4.7.
   - "my list", "my to-do list" and "the list" without a name map to Inbox. "Shopping list" is a
     named list ("Shopping") like any other.
   - **Task matching** for complete, reschedule, delete and change. Defined exactly:
     - Tokenise: normalise (Normalizer rules), split on anything that is not a letter or digit,
       strip a possessive `'s` (`amira's` → `amira`), drop stop words from
       `lexicon/stopwords.txt` (at least: a, an, the, my, to, at, on, in, for, of, about, with, task,
       reminder, list). Two tokens are equal if identical, or if both are 5+ characters and within
       Damerau-Levenshtein 1. Q is the token set of the spoken task reference (the utterance minus
       the intent frame and any date/time spans). T is a candidate title's token set.
     - Candidates are open, non-deleted tasks from the FTS index.
     - Step 1, exact: if exactly one candidate has `T == Q`, it is the match.
     - Step 2, containment: candidates with `Q ⊆ T`. One → the match. Two or three → ask "Which
       one" (4.7). Four or more → ask with the top 3 by score (below).
     - Step 3, fuzzy: with no containment, score = Dice = `2·|Q∩T| / (|Q| + |T|)`. If the best is
       ≥ 0.5, ask "Do you mean '…'?" (`confirm`). Otherwise decline: "I couldn't find a task like
       that."
     - A fuzzy match is never acted on without a yes.
6. **Title extraction.**
   - Title = original text minus consumed spans and minus the intent frame (`remind me to`, `add`,
     `to my … list`), trimmed of dangling prepositions (`at`, `on`, `by`, `for`). The first letter is
     upper-cased and nothing else changes: no paraphrase, no spelling fixes.
   - Empty title → ask "What should I remind you about?"
   - Titles longer than 120 characters are kept whole. On the voice path the speech reads back only
     the first 8 words and "…"; in a ramble the draft is flagged `long_title`.
7. **Decider.** Combines the slot results and policy (section 8.3) into `done`, `needs_input` or
   `declined`.
   - Any `unresolved` span is a word that looked structural but was not understood ("on the
     thirty-second", "at 25 o'clock"). It produces a decline or a question, never silent dropping.
   - **Self-correction** cues (`no wait`, `actually`, `I mean`, `scratch that`, `sorry`, `no make
     that`) count only when a slot value of some type comes before the cue **and** a value of the
     same type comes after it ("at 5 tomorrow no wait Friday"). Then the result is a question
     offering both readings; the correction is never applied silently. Anywhere else the cue is
     ordinary title text ("say sorry to Amira", "actually call the dentist").

### 6.2 `ParseResult` (Kotlin)

```kotlin
sealed interface SlotValue<out T> { data class Parsed<T>(val v: T, val span: IntRange) : SlotValue<T>
  data class Ambiguous<T>(val options: List<T>, val span: IntRange) : SlotValue<T>
  data object Absent : SlotValue<Nothing> }
data class ParseResult(
  val intent: Intent, val title: String?, val titleSpans: List<IntRange>,
  val due: SlotValue<DueSpec>, val recurrence: SlotValue<RecurrenceRule>,
  val reminder: SlotValue<ReminderSpec>, val deadline: SlotValue<LocalDate>,
  val priority: SlotValue<Int>, val project: SlotValue<ProjectRef>, val labels: List<LabelRef>,
  val unresolved: List<IntRange>, val selfCorrection: Boolean, val appliedDefaults: List<DefaultUsed>)
```

Every span is an offset into the **original** utterance, so the UI can highlight it (quick-add
chips) and the validation gate can re-derive it. `appliedDefaults` lists every 8.3.1 default used,
so `SpeechWriter` can read it back.

### 6.3 Typed quick-add

The in-app quick-add bar runs the same pipeline with `source = typed`. Its intent is always create,
so no verb frame is needed ("every day at 7 take the bins out" is valid). Typed symbols are additional
terminals that lower to the same AST. They follow Todoist's current Quick Add syntax
(`research/features.md` §3):

| Typed | Meaning | Note |
|---|---|---|
| `#Work` | project | |
| `#Work /Admin` | section (after a project) | |
| `%errands` | label | Todoist's current label symbol |
| `@errands` | label | Also accepted. Todoist plans to retire `@` by the end of 2026; Tasks keeps it because it has no other meaning here |
| `p1`-`p4` | priority (priorities removed from v1 (owner, 2026-11-02)) | Todoist's help lists `p1`-`p3` (p4 = no priority); its API docs accept `p1`-`p4` |
| `{Friday}` | deadline | |
| `!14:00`, `!30 min before` | extra reminder (at a time, or before due) | should. Todoist's help shows exactly these two forms (checked 2026-11-02); this spec's shorthand `!30mb` and `!2h` is dropped. M2 parses the symbol away but stores nothing, because alerts come from tags (8.6) |
| `for 2h` | duration | later (parsed and stored) |
| ` // text` at the end | description | From Todoist's Quick Add **API** docs (`research/api-and-android.md` §3); the help page does not mention it |
| `every!` | completion-anchored recurrence | should |

`+assignee` is not supported (no collaboration). Parsed spans show live as coloured chips. Tapping a
chip un-parses it back into title text, which is Todoist's own escape (its help: click the
highlighted word to make it plain text; `research/parsing.md` §2).

### 6.4 Coverage target for v1 grammar

About 200 rules are expected (`research/parsing.md` §1 estimates 150-300). The golden corpus (13.1)
defines "done": at least 300 cases, of which at least 60 must decline or ask. Rules are added only
with a corpus case.

### 6.5 Ramble segmentation

**Entry.** A ramble starts in one of three ways:

- a one-shot utterance whose frame is followed by content ("Okay brain dump. Call the garage …"):
  Tasks segments it straight away;
- a frame with no content ("start a brain dump"): Tasks returns `needs_input` with
  `expects: "ramble"` and "Go ahead, I'm listening." Assistant captures with the `long` profile
  (4.12 step 8) and sends the result as `type: ramble`;
- the in-app Ramble button (typed or pasted text; Tasks never records audio).

**Capture budget.** Continuous rambles are capped at 60 s (at most two 30-second Whisper windows on
the phone, avoiding long-form Whisper drift). M4 MUST measure transcription time and quality on up
to 60 s dumps on the phone. Target, to be confirmed by that measurement: transcript ready within
10 s of the end of a 60 s dump. Assistant shows its normal thinking state meanwhile.

Input: one long transcript. Output: an ordered list of drafts, each with a span and its own
`ParseResult`, plus flags.

1. Mark the leading frame ("okay brain dump", "start a ramble") as a `frame` span.
2. Split on Whisper sentence punctuation (`.`, `?`, `!`).
3. Within each sentence, split at cue phrases at clause start: `and also`, `also`, `oh and`,
   `another thing`, `then` (only when followed by a verb from `lexicon/verbs.txt`), `after that`,
   `don't forget to`, `first/second/third`, `next`, `number one/two`.
4. **Merge back:**
   - a fragment with no verb (per `lexicon/verbs.txt`) and no title words, or one that is only a
     date or time ("Tomorrow at 5."), attaches to the **previous** draft and is flagged
     `attached_modifier`;
   - `every Monday and Thursday`, and `X and Y` with no second verb ("buy milk and eggs"), never
     split.
5. Mark filler-only fragments (junk gate list) as `filler` spans.
6. Flag, and never fix:
   - `self_correction`;
   - `distributed_modifier` ("call Amira and Bilal tomorrow": one date, two people, kept as **one**
     draft and flagged);
   - `attached_modifier`;
   - `no_verb`;
   - `long_title` (over 120 characters, typically an unpunctuated run-on);
   - `ambiguous_slot`.
7. **When unsure, one draft, flagged** (`research/parsing.md` §3 policy): a run with no punctuation
   and no cue stays one draft with `no_split_signal`, rather than being cut at a guess.
8. Never drop words. Every character of the transcript is in exactly one of: a draft span, a
   `frame` span, or a `filler` span. The review screen can show the frame and filler spans.

`lexicon/verbs.txt` is shipped with the app: about 300 imperative verbs common in tasks (call, buy,
email, book, send, pay, pick up, …), built from the golden corpus and grown only with a corpus case.

Ramble result over IPC: `status: done`, with effect `{"op":"ramble_drafted","ramble_id":…,"count":4,
"flagged":1}` and speech "I've got 4 tasks ready to review, 1 needs a look. Open Tasks to save them."
Drafts are **not** saved as tasks until reviewed (Q6). A heads-up notification opens the review
screen. If notifications are off, the response carries the `notifications_off` warning and the
speech says "Open Tasks to review them; notifications are off." Today always shows a banner while
any ramble is open, so a dump is never stranded.

**Retention.** Open rambles stay until reviewed or discarded; they are never purged automatically.
Saved and discarded rambles, with their drafts and transcripts, are purged 30 days after closing.

### 6.6 Review screen (ramble)

- A list of draft cards: title, chips for slots, flags in amber, and the original span underneath.
- Each card: Keep / Edit / Drop. Plus "Save all kept" and "Discard ramble".
- "Save all kept" saves only unflagged kept drafts, plus flagged drafts the user has confirmed one by
  one (a tick on the card). It says how many flagged drafts it left.
- Edits use the quick-add bar on the draft's text.

Todoist's Ramble, for contrast (`research/features.md` §1): a live preview where the user says
"Actually, I meant …" or "Remove that" and finishes with "that's all". Tasks has no live preview
because Assistant transcribes after the user stops; corrections are flagged for review instead.

### 6.7 Future off-device homelab GPU ramble processing (deferred to M6, optional)

Tasks embeds **no** on-device LLM and no native `llama.cpp` build. For M0–M5, ramble segmentation is
100% deterministic (`RambleSegmenter`, 6.5) on the transcript Assistant sends.

When the owner sets up their own home GPU server (owner-owned hardware only, goal 7; e.g. Intel
Arc running open-source models through `llama.cpp` / `whisper.cpp` with Vulkan or SYCL †), heavier
ramble processing can be added without changing Tasks' offline core. Ramble is low priority, so M6
may never be needed.

- **Endpoint rule:** the only allowed endpoint is the owner's own server, reached over the home LAN
  or the owner's tailnet. Tailscale's coordination servers see keys and metadata, not traffic †; if
  the owner wants none of that, use LAN only or a self-hosted Headscale. Any other host is refused.
  The core Tasks APK never gains `INTERNET`.
- **Two optional upgrades:**
  1. **Server ASR (Assistant only).** Tasks and the Connector never hold audio, so only Assistant
     can send the 60 s ramble audio to an open-source ASR model on the owner's server (e.g.
     `whisper.cpp` with `large-v3-turbo` †). Tasks still receives text only. This needs the owner to
     amend Assistant's `CLAUDE.md` privacy invariant (today the network is allowed only for model
     downloads and the opt-in remote answer model) to allow "the owner's own server".
  2. **Server span segmentation (Connector).** The Connector sends the ramble **transcript** to an
     open-source instruct model on the owner's server, constrained by a JSON schema / GBNF grammar,
     to propose draft boundaries and slot spans.
- **Output schema** (spans only—the remote model never invents text or dates):
  ```json
  {"drafts":[{"title_span":[12,40],"date_span":[41,49]|null,"time_span":null,"recurrence_span":null,
             "project_span":null}]}
  ```
- **Deterministic `ValidationGate` inside Tasks** (`parse/ValidationGate.kt`, pure Kotlin; any
  failure rejects that draft and keeps the deterministic grammar-only draft from 6.5, flagged):
  1. Schema-valid, spans within bounds of the original `utterance`, and spans non-overlapping.
  2. The title is sliced **directly from the transcript span**, so it contains no new content words.
     Cleanup may only drop filler words from an allowlist ("um", "like", "you know", "I need to").
  3. Every `date_span`, `time_span` and `recurrence_span` is re-parsed by Tasks' own `java.time`
     grammar. The span MUST parse as `Parsed`, or the slot is dropped. **The model never supplies
     date/time values.**
  4. A `project_span` must resolve through `EntityResolver` to an existing project.
  5. Every number in the output appears in the transcript.
  6. Coverage: every transcript word is in some span or in a frame or filler span.

---

## 7. Features mapped from Todoist

Sources: `research/features.md` (Todoist help pages, read 2026-10-27; its tags [C] confirmed, [S]
search summary only, [U] unconfirmed are kept below), `research/api-and-android.md` (API v1), and
`research/todoist-android-surface.txt` (installed app v12324). Rows still marked † are covered by no
research note and must be checked before being treated as parity claims.

| Todoist feature | Tasks | Notes and source |
|---|---|---|
| Inbox | **must** | Undeletable project. Todoist puts tasks in Inbox unless a project is given (features §1, §7) |
| Projects, nested | **must** (flat), should (nesting) | Todoist has sub-projects (features §4 [S]); depth limit unconfirmed |
| Sections | should | Todoist `/section` (features §3 [C]) |
| Subtasks | should (depth ≤ 4) | Voice can't create them in v1. Todoist's Ramble can't either (features §1 [C]) |
| Labels | **must** | Matched by name, never created from speech except as in 4.7. Typed `%label` (Todoist's current symbol) and `@label` (features §3 [C]) |
| Priorities p1-p4 | ~~must~~ priorities removed from v1 (owner, 2026-11-02) | Stored 1 = highest; p4 = none (features §4 [S]). Todoist API is inverted, 4 = urgent (api §2); map on import |
| Due date, due time | **must** | Todoist also takes relative "in 2 hours", "in 5 days" (features §3 [C]) |
| Recurring due dates | **must** | Grammar in 8.2. Todoist: `every` keeps the cycle, `every!` repeats from the completion date, `after 10 days` = `every! 10 days` (features §3 [C]). `every!` is should here. Todoist's `starting` / `ending` / `for` ranges and `every last workday`: later |
| Deadlines `{…}` | should | Separate from due. Todoist: a hard cutoff, not allowed on recurring tasks, Pro/Business only (features §4 [C]); Tasks copies the recurring-task rule |
| Durations | later | Parsed and stored (`for 30 minutes`). Todoist: paid, max 24 h, needs date and time (features §3 [C]) |
| Reminders: at time, relative, "in N minutes" | **must** | Todoist: an automatic relative reminder when a task gets a time, custom absolute reminders, `!` shortcuts and multiple reminders on paid plans only (features §5 [C]). Here all are free and local. Typed `!` shortcuts: should |
| Reminders on recurring tasks | **must** | Todoist: a custom reminder on a recurring task fires once (features §5 [C]). Tasks does the same for `AT_LOCAL`/`AT_INSTANT` (8.4) |
| Location reminders | later | Todoist: Android and iOS only; plan requirement unconfirmed (features §5 [C/U]) |
| Today, Upcoming views | **must** | Todoist deep links `today`, `upcoming` (api §5) |
| Search | **must** | FTS. Todoist `search:` filter (features §4 [C]) |
| Filters (saved queries) | should | Todoist's query language is confirmed (features §4 [C]). Tasks supports the subset `today`, `tomorrow`, `overdue`, `no date`, `p1`-`p4`, `%label` / `@label`, `#project`, `##project`, `&`, `\|`, `!`, `( )` |
| Natural-language quick add (typed) | **must** | Same parser as voice; syntax in 6.3 |
| Quick-add tile, launcher shortcut | should | Todoist ships an "Add task" Quick Settings tile and a quick-add shortcut (features §7 [C]; surface dump `CreateItemTileService`, `CreateQuickAddDialogShortcutActivity`) |
| Share target (text) | **must** | `ACTION_SEND text/plain` → quick-add sheet prefilled, the user confirms. Todoist: `QuickAddItemAsTask` (surface dump) |
| Ramble (voice brain dump) | should (owner rarely uses it) | Todoist's needs internet and uses LLMs for transcription and extraction, so it is cloud in effect; its help never says "cloud" outright (features §1 [C/U]). Free plans get 10 sessions a month; 40 languages. Ours is on-device text from Assistant, English only |
| Ramble entry points | should | Todoist: waveform in Quick Add, Quick Settings tile, app shortcut, 1x1 widget (features §1 [C]). Tasks: Assistant "brain dump" and an in-app button are must; tile and shortcut should |
| Task description | **must** | |
| Undo | **must** | Todoist: an Undo toast for a few seconds (features §6 [C]). Tasks: 5 s snackbar plus a 10-minute voice token (4.8) |
| Comments, attachments | no | Todoist: 15,000-char comments, one attachment per comment, size cap by plan (features §6, moderately confident) |
| Completed history, activity log | should | Todoist: a Completed tasks view and a read-only activity log, one week of history on the free plan (features §6 [S]). Tasks: `completion_log`, a Completed screen. Restoring deleted tasks in Todoist: unconfirmed |
| Home-screen widget | should | Todoist ships a task list widget, an "Add task" widget and a Ramble 1x1 widget (features §7 [C]). Tasks: a Glance Today list with tick boxes |
| Board view, calendar view † | later | |
| Calendar sync | no | Todoist: Google or Outlook, one at a time, partly two-way; `every!` becomes one-off events (features §7 [C]). Needs network |
| Templates, import/export CSV | should (import), later (export CSV) | Todoist: project CSV import/export, UTF-8, max 300 tasks, no undo after import (features §6 [S]). JSON export is must |
| Backups | **must** | Local JSON export (5). Todoist: backups on paid plans (features §6) |
| Collaboration, assignees | no | Non-goal |
| Karma, productivity | no | Todoist ships a productivity widget (surface dump `ProductivityAppWidgetSettingsActivity`); karma † |
| Themes | must (one) | Halo look shared with Assistant |
| Email-to-task | optional | Section 10. Todoist: a per-project address (features §2 [C]) |
| Voice assistant integration | **must** | Assistant IPC (4). Todoist: Google Assistant "Add X to my Y list on Todoist", with a known "Which list?" bug (features §7 [S]) |
| Todoist import | should | Section 11 |

---

## 8. Reminder and recurrence semantics

### 8.1 Time model

- A **floating** time (`due_zone = null`) means the wall clock wherever the phone is: "9 am" stays
  9 am after flying from London to New York. This is the **default** for anything spoken as a clock
  time (Q3).
- A **fixed** time (`due_zone = "Europe/London"`) keeps its instant across zone changes. It is used
  for "in N minutes/hours" (the reminder is `AT_INSTANT` and the due is fixed in the creating zone),
  and when the user sets "keep this time when travelling" in the detail sheet. Spoken zones ("at 3
  London time") are declined in v1 (6.1 step 4).
- "In N minutes/hours" = `speech_end + Duration`, **not rounded**. The alarm fires at that exact
  instant. The task's `due_time` is that instant in the creating zone, kept to the millisecond so the
  alert fires on the instant; the display truncates it to the minute. The task counts as overdue from the reminder instant, not from the
  truncated minute.
- "In N days/weeks" is calendar arithmetic (`LocalDate.plusDays`). The task gets the spoken time if
  one is said; otherwise it is all-day.
- "Tomorrow" = `today.plusDays(1)`, never `+24h`.

### 8.2 Recurrence

The rule is stored as an RRULE subset string. Supported:

- `FREQ=DAILY|WEEKLY|MONTHLY|YEARLY`;
- `INTERVAL=n`;
- `BYDAY=MO,TH` (with an ordinal for MONTHLY: `BYDAY=1MO`, `-1FR`);
- `BYMONTHDAY=n|-1`;
- `BYMONTH=n`.

The time of day is `due_time`. The anchor is `recurrence_anchor`.

| Phrase | Rule |
|---|---|
| every day / daily | `FREQ=DAILY` |
| every weekday | `FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR` |
| every weekend | `FREQ=WEEKLY;BYDAY=SA,SU` |
| every Monday and Thursday | `FREQ=WEEKLY;BYDAY=MO,TH` |
| every other week on <day> / every 2 weeks on <day> | `FREQ=WEEKLY;INTERVAL=2;BYDAY=…`. With no day and no start date, ask "Which day?" (4.7) |
| every 3 days | `FREQ=DAILY;INTERVAL=3` |
| every month on the 15th / monthly on the 15th | `FREQ=MONTHLY;BYMONTHDAY=15` |
| every month on the last day | `FREQ=MONTHLY;BYMONTHDAY=-1` |
| first Monday of every month | `FREQ=MONTHLY;BYDAY=1MO` |
| every year on 5 November / every 5 November | `FREQ=YEARLY;BYMONTH=11;BYMONTHDAY=5` |
| every 3 days after I finish it (typed: `every! 3 days`, `after 3 days`) | `FREQ=DAILY;INTERVAL=3` + anchor `completion` |
| every hour | declined in v1: "I can only repeat daily or less often." |

**First occurrence:** the earliest occurrence ≥ now. If the spoken time today has already passed,
the next one counts (a recurrence always has a next occurrence, so this is not the past-time
question in 8.3). A specific start ("starting Monday") overrides that.

**Completing a recurring task** advances it by **one** occurrence; the series continues:

- A `completion_log` row is written.
- Schedule anchor: the next due is the first occurrence strictly after **max(current due, now)**, so
  ticking an overdue daily task does not leave it overdue.
- Completion anchor: the next due is `completed_at` in local time + interval, keeping `due_time`.
- `recurrence_text` stays as it was.
- Ending the series is "Complete forever" in the task menu (Todoist has the same menu item,
  `research/features.md` §6), or delete.

**Deleting a recurring task** deletes the whole series (soft delete, undoable). Skipping one
occurrence is "push to tomorrow" on that occurrence (8.4, outcome table).

**Month-end:** `BYMONTHDAY=31` in a 30-day month **clamps** to the last day, and creation speech
says so: "on the 31st, or the last day in shorter months" (Q5). `BYMONTHDAY=29/30` clamps the same
way in February.

### 8.3 Ambiguity rules (decline or ask, never guess)

| Input | Rule |
|---|---|
| "next Friday" | Always ask, with both dates ("this Friday the 30th, or Friday the 6th?") |
| "this Friday" or bare "Friday" | The soonest Friday strictly after today |
| A same-day reference: bare weekday or "this <weekday>" said on that weekday ("Tuesday" on a Tuesday), or "the 5th" said on the 5th | Ask, with both readings: "Today, or Tuesday 3 November?" The same rule for weekdays and month-days |
| "the 5th" on any other day | The soonest 5th after today; speech says the month |
| Bare hour in a **one-off** ("at 5", "at 1:30", "half past 6") | The nearer future of the am and pm readings (default D1). **Night rule:** if that reading falls between 00:00 and 06:59, ask "5 in the morning or the evening?" |
| Bare hour in a **recurrence** ("every day at 7", "every day at 1:30") | Always ask am or pm. "Nearest future" has no meaning for a time that repeats |
| How the am/pm question is worded | The pm reading is "in the afternoon" for 12:01-4:59 and "in the evening" for 5:00-11:59; the am reading is "in the morning" |
| Not bare (no question) | `5pm`, `5 am`, `17:00`, a leading-zero `05:00`, hours 13-23, or a qualifier: `in the morning/afternoon/evening`, `tonight` (pm), `this morning` (am) |
| "at 12" | Ask "Noon or midnight?", unless "noon", "midday" or "midnight" is said |
| "midnight" / "midnight tonight" | 00:00 at the **start of the next day**. Speech: "at midnight tonight, the start of Wednesday" |
| "tonight", "this morning/afternoon/evening", "tomorrow morning" with no time | Named-time defaults (D2). Speech states the clock time. If the named time has already passed today ("this morning" at 14:03), ask |
| An explicit time today that has already passed ("remind me at 1pm" at 14:03, "at 9am" at 14:03) in a one-off | Ask "1 pm today has passed. Tomorrow at 1 pm?" (`confirm`). Yes → tomorrow; no → declined |
| "remind me" with a date but no time ("remind me on Friday to …") | Ask "What time on Friday the 30th?" with options "9 am" (the morning named time) and "No alert". "No alert" saves it all-day with no clock time. Under 8.6 the tag schedule still gives an all-day task its 09:00 alerts, so speech says "no time alert", not "no alert". Without "remind me" ("add X on Friday"), it is saved all-day, no question |
| "remind me to X" with no date and no time | Ask "When should I remind you?" (Q10) |
| A date that doesn't exist ("31st of November") | Decline: "November has no 31st." |
| A time that doesn't exist ("at 25 o'clock") | Decline: "That isn't a time I can use." |
| A past explicit date ("remind me on 3 October") | Ask: "3 October has passed. Do you mean next year?" |
| Distributed or conflicting slots, self-corrections | Ask, offering both readings |
| "this weekend", "next week" with no day | Ask "Which day?" with options (Sat/Sun; Mon) |
| Vague quantities ("in a few minutes", "later", "soon") | Decline: "I need an exact time, like in 20 minutes." |
| Spoken zones ("at 3 London time") | Decline: "I can't set times in other time zones yet." |
| Week start (for "next week") | Monday by default (setting, Q12) |

#### 8.3.1 Defaults (owner approval required, Q4)

These are the only places Tasks fills in something the user did not literally say. Each one is
always read back as a clock time, and each is a single policy constant so the owner can switch it to
"always ask".

| Id | Default | Read back as | Alternative if the owner rejects it |
|---|---|---|---|
| D1 | Bare one-off hour → the nearer future of am/pm, outside the night window. "Nearer" is read off the clock now (the next time that wall time occurs), then applied to the spoken date, so "tomorrow at 5" said at 14:03 is 5 pm | "today at 5 pm" | Ask am/pm every time |
| D2 | Named times: morning 09:00, afternoon 14:00, evening 19:00, tonight 20:00 (Settings). For comparison, Todoist uses morning 9 am, afternoon 12 pm, evening 7 pm (`research/features.md` §3) | "tomorrow at 9 am" | Ask "What time?" with the named time as an option |
| D3 | A timed task gets one at-due reminder (Settings: "default reminder for timed tasks") | "reminder on" in `display`; speech says "I'll remind you" | No reminder unless "remind me" is said |

### 8.4 Scheduling and delivery

- **One alarm.** `ReminderScheduler.rearm()` recomputes `next_fire_at` for every live reminder
  whose task is not completed, picks the minimum, and arms one alarm:
  `setExactAndAllowWhileIdle(RTC_WAKEUP, t, pi)`. The PendingIntent has a fixed request code and
  identity. If `canScheduleExactAlarms()` is false, it uses `setAndAllowWhileIdle` and adds the
  `exact_alarm_denied` warning. Settings offers "alarm-clock delivery" (`setAlarmClock`), which is
  exempt from Doze limits and shows the alarm icon.
- **On fire:** select every reminder with `next_fire_at ≤ now + 1 s` and `last_fired_at <
  next_fire_at`. The 1 s tolerance only absorbs clock reads within one wake-up; AlarmManager never
  fires an exact alarm early, so no reminder fires before its time. In one transaction, set
  `last_fired_at = next_fire_at` **before** posting the notification. Post one notification per
  task (grouped if there are more than 3), then `rearm()`. Setting `last_fired_at` first is the
  guard against the five-second refire loop Assistant hit (`AssistantFiles.markDelivered` comment).
  Assistant adds a second guard that Tasks MUST match: a firing for a task whose notification is
  still on screen is ignored, not shown again and not re-marked (`ReminderReceiver`). A notification
  swiped away or opened counts as seen; one left unanswered is shown once more after
  `BOOT_COMPLETED`, even if it was not "missed" (Appendix C, item 3).
- **Re-arm triggers:**
  - `BOOT_COMPLETED`;
  - `MY_PACKAGE_REPLACED`;
  - `TIME_SET`;
  - `TIMEZONE_CHANGED`;
  - `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`;
  - `Application.onCreate`;
  - every mutation.
- **Missed reminders** (the phone was off, locked since reboot, or the app force-stopped past
  `next_fire_at`): delivered once on re-arm as "Missed: <title> (was due 5:00 pm)". Never refired.
- **Known gaps (accepted, documented in Settings → About reminders):**
  - **Before first unlock.** Tasks is not direct-boot aware in v1: Room lives in
    credential-encrypted storage, which is unreadable until the first unlock after a reboot. Nothing
    fires until then; at `BOOT_COMPLETED` (after unlock), passed reminders are delivered as Missed.
  - **Force-stop** clears alarms. They are restored on the next app open or the next Assistant
    summon (which binds Tasks, running `Application.onCreate`). A reminder due in between is late
    and delivered as Missed.
  - **OEM battery management.** One UI may put an unused app to sleep (`research/parsing.md` §5
    names Doze/OEM killers as a risk). Onboarding has a step that opens
    `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` and asks the user to set Tasks to "Unrestricted".
    Its live state is in the permissions checklist and the `battery_restricted` warning.
- **Notification:**
  - channel `reminders`, high importance;
  - actions **Done**, **Snooze 10 min**, **Snooze 1 h**, **Tomorrow** (same time tomorrow, using
    the floating/fixed rules);
  - tap opens the task;
  - Done on a recurring task advances it (8.2).
- **Permissions:** `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`.
  `USE_EXACT_ALARM` is granted at install and cannot be revoked by the user; Google Play only
  permits it for alarm-clock and calendar apps, which does not bind a sideloaded app
  (`research/parsing.md` §5, verified against the Android 14 exact-alarm page). Tasks also declares
  `SCHEDULE_EXACT_ALARM` and keeps the `canScheduleExactAlarms()` check and the inexact fallback as
  cheap defence in case a future Play release or OS change removes the install-time grant. The
  onboarding checklist shows the live state of each, as Assistant's does.

**Outcomes by task shape.** Rescheduling never changes the recurrence rule.

| Action | Undated | All-day | Timed one-off | Recurring |
|---|---|---|---|---|
| Complete | Completed | Completed | Completed; its reminders stop | `completion_log` row, due advances one occurrence (8.2); `AT_DUE`/`BEFORE_DUE` follow it |
| Move to a day, no time said ("push to tomorrow", "move to Thursday") | Gets that date, all-day | Date changes | Date changes, time kept | This occurrence moves, time kept; the series continues from it under schedule-anchor rules |
| Move with a time said | Date and time set | Becomes timed | Both set | This occurrence moves |
| Snooze (notification button, "snooze that for an hour") | n/a | n/a | `snoozed_until = now + d`; due unchanged | Same, this occurrence only |
| Delete | Deleted | Deleted | Deleted | Whole series deleted |

Reminder rules that go with it:

- `AT_DUE` and `BEFORE_DUE` follow the due, through moves and recurrence.
- `AT_LOCAL` and `AT_INSTANT` are one-shot. On a recurring task they fire once (as Todoist does,
  `research/features.md` §5). When a timed task is moved, they are replaced by one `AT_DUE`
  reminder at the new time, and the speech says "I'll remind you then."
- A snooze sets `snoozed_until`, and `next_fire_at = snoozed_until`. That is always later than
  `last_fired_at`, so the guard lets it fire. `snoozed_until` is cleared when it fires or when the
  due changes.
- "Add X to my list at 5" creates a timed task, so it gets the D3 reminder.

### 8.5 DST and zone rules

`NextOccurrence` computes in `LocalDateTime` and converts to an instant last, with these policies:

- **Gap (spring forward).** A wall time inside the gap fires at the first valid instant after the
  gap, shifted by the gap length (java.time's default, `ZonedDateTime.of`). Example: Europe/London
  on 2027-03-28 has no 01:00-01:59, so "every day at 1:30 am" fires at 02:30 BST that day and at
  01:30 on the 29th.
- **Overlap (fall back).** A wall time inside the overlap fires **once**, at the **earlier**
  offset. Example: Europe/London on 2026-10-25 has 01:30 twice. The reminder fires at 01:30 BST,
  never again at 01:30 GMT. Enforced by the `last_fired_at` check plus computing the next occurrence
  from the *local* date-time after the one that fired.
- **Instants are immune.** "In 30 minutes" at 01:45 BST on 2026-10-25 fires at 01:15 GMT (30 elapsed
  minutes). The display says "1:15 am".
- **Zone change.** Floating times re-resolve in the new zone on `TIMEZONE_CHANGED`. Fixed times keep
  their instant. A floating reminder whose new instant has already passed on the zone-change day
  counts as missed and is delivered once.
- tzdata updates need no migration, because zone IDs are stored and offsets never are.

---

### 8.6 Tag reminder schedules (owner, 2026-11-02; replaces D3)

Reminders come from tags, not from "remind me". Every dated task gets the schedule of its most
urgent tag; untagged tasks get **Regular**.

| Schedule | Tags | Fires before due | At due | After due | Snooze buttons |
|---|---|---|---|---|---|
| Regular | none, `work`, `personal`, `family`, `client` | 3 d, 1 d, 1 h, 10 min | no | no | 15 min, Next day |
| Urgent | `urgent` | 3 d, 1 d, 1 h, 10 min | yes | every 10 min until Done, day and night | 15 min, 1 h |

Rules:

1. **Short notice:** alerts already past at creation are skipped silently. If none are left, the
   task fires once at due.
2. **Date only:** alerts at 09:00 three days and one day before, and 09:00 on the day.
3. **Repeating tasks:** only the 1 h and 10 min alerts (plus urgent's at-due and nag).
4. **Urgent nags all night.** No quiet hours.
5. **Snooze only at the last moment (owner, 2026-11-02).** The 3 d, 1 d and 1 h alerts show only
   Done. The snooze buttons appear only on the 10 min alert and the at-due alert (urgent), plus
   urgent's nags that follow it and the 09:00 alert of a date-only task (its "task time"). A snooze that lands on or after the next scheduled alert
   is replaced by that alert, so two alerts never stack (e.g. urgent: Snooze 1 h on the 1 h alert
   becomes the at-due alert).
6. **Voice and typed tags:** "for <tag>" counts only directly after "remind me" ("remind me for work
   to send the invoice Friday at 3"); "urgent" counts before "remind me" or as "remind me urgently".
   Anywhere else the words stay in the title ("buy a gift for family").
7. **Lists:** tasks are listed by due date in Today / Tomorrow / Upcoming, each with tag filter
   chips; each row shows its next alert.
8. **Missed:** alerts missed while the phone was off collapse to one "Missed" notification per task
   (the latest). Tapping it opens a **Missed** list of tasks whose alerts were missed and not yet
   opened.

Schedules are stored on the label (`label.schedule_json`: offsets, at_due, nag_min, snooze options);
reminder rows are derived from them whenever a task's due, tags or recurrence change.

## 9. UI essentials

The visual language follows Assistant's Halo theme: Cortana cyan accent, chamfered panels, subtle
scanlines, an opaque background. Copy Assistant's theme tokens into `ui/theme` rather than sharing a
module (two repos), observing the licence note in section 12. The look is "modern, minimal but
still informative."

Screens, in build order:

1. **Today**: a banner for open rambles and needs-review imports; overdue (collapsible, with
   "Reschedule all to today"), then today grouped by time and all-day. Each row has a checkbox,
   title, time, project chip, priority colour, and a recurrence icon. Swipe right completes, swipe
   left reschedules (date sheet).
2. **Quick-add bar**: a bottom sheet from a FAB, the tile, the shortcut or a share. Typed parse with
   live chips (6.3). Enter saves, and an undo snackbar follows.
3. **Task detail sheet**: title, description, due (date/time/zone toggle "keep this time when
   travelling"), recurrence (picker + text), reminders list, project/section, labels, priority,
   deadline, subtasks, history, and `source_utterance` shown small ("Created by voice: '…'").
   Menu: Complete forever (recurring), Delete.
4. **Upcoming**: the next 14 days, grouped by day.
5. **Inbox** and **Projects** (list → project with sections).
6. **Search**: FTS plus the filter query subset. Saved filters (should).
7. **Ramble review** (6.6), and the "From email" / "From notification" review lists (10).
8. **Settings**:
   - named times;
   - default reminder for timed tasks (on, at due);
   - week start;
   - default floating/fixed;
   - alarm-clock delivery;
   - Assistant link status (installed, signature OK, contract version, migration state);
   - export/import, with "Include voice history";
   - Todoist import, with past import runs and "Remove this import";
   - permissions checklist (notifications, exact alarms, battery unrestricted);
   - About reminders (the known gaps in 8.4);
   - Clear voice history (section 12).
9. **Completed** history (should).

Other UI requirements:

- **Accessibility:** TalkBack labels on every control; minimum touch 48 dp; respects font scaling.
- **Deep links** (internal, also usable by Assistant's `open_uri`): `tasks://today`,
  `tasks://upcoming`, `tasks://inbox`, `tasks://task/{id}`, `tasks://project/{id}`,
  `tasks://ramble/{id}`. Not exported to other apps except through the signature-guarded activity
  alias.

---

## 10. Email ingestion (optional; trade-off is an owner decision)

Constraint: the core app holds no `INTERNET` permission, and that is the simplest provable offline
guarantee (a manifest anyone can read).

For reference, Todoist gives every project its own address: the subject becomes the task name, the
body becomes a comment, and `date tomorrow`, `@label`, `p1`-`p3` in the mail set details. Gmail and
Outlook add-ins exist, and their links back to the email work only in the web apps
(`research/features.md` §2). Option D below is that model; it needs a server.

Rules for every email- or notification-derived task:

- The subject (or notification title) is sent as `utterance`, so it is parsed as quick-add text; the
  body is sent as `description` and is **never** parsed for dates.
- It always lands as a draft in a review list ("From email" / "From notification", effect
  `drafted`), never directly as a task. The user keeps, edits or drops it as in 6.6.
- `external_id` (the `Message-ID`, or the notification key) makes it idempotent.

| Option | Network in Tasks? | Effort | Notes |
|---|---|---|---|
| A. Share from the mail app | No | Tiny (already a must: share target) | The user taps Share on an email → quick-add sheet with subject as title and the rest as description. Works with any mail app; the user confirms in the sheet, so no review list is needed |
| B. "Make a task from that email" via Assistant | No | Small, **but changes Assistant: needs the owner's approval (Q7)** | Assistant already holds notification access. It reads the newest mail notification's title and text and forwards them with `source: "notification"` (title as `utterance`, text as `description`). Only what the notification shows; stays on device. It is third-party text, not the user's words, so it goes to the review list |
| C. IMAP polling of a dedicated mailbox | Yes, **in the separate "Tasks Connector" APK** | Medium | Below |
| D. Todoist-style unique inbound address | Needs a server | Large | Out: needs a third-party mail host (goal 7) |

Option C, the connector (`io.github.ijazshare.tasks.connector`):

- Signed with the same key as Tasks (4.2.6); it declares the same permission (4.2.1). Install order
  does not matter (verify as in 4.2.1).
- WorkManager every 15-60 min. The app password is stored in Android Keystore-encrypted DataStore.
- It pushes each new mail over the same IPC: `type: "utterance"`, `source: "email"`,
  `routed_by: "connector"`, `utterance` = subject, `description` = plain-text body (truncated to
  16,000 chars), `external_id` = `Message-ID`.
- Failure cases: authentication failure or a changed password stops polling and posts one
  connector notification ("Email sign-in failed"); mailbox unreachable or offline retries at the
  next poll; a duplicate `external_id` is skipped by Tasks; Tasks not installed or `caller_not_trusted`
  stops polling and notifies. Mail is marked read or moved only after Tasks returns `done`.

Recommendation: ship A in M5. B only with the owner's approval. C only if the owner wants it (Q7),
in the separate connector so the core app stays network-free.

---

## 11. Import from Todoist

Two paths. Each run is an `import_run`. Re-imports go through `import_map(source = "todoist")`.

**Re-import conflict rule.** A re-imported item updates the local task only if the local task is
untouched since its last import (`task.updated_at ≤ import_map.imported_at`). Locally edited,
completed or deleted tasks are skipped and counted as "kept your changes".

**Undo.** An import produces no voice token. The summary screen, and later Settings → Todoist import,
offer "Remove this import": it removes the rows that run created and that are still untouched, and
reports what it kept.

### 11.1 File import (should, offline)

- Source files. **† Verify both before coding (gate before M5):**
  - the per-project CSV the user exports from a project's menu (Todoist: export/import as CSV,
    UTF-8, max 300 tasks, `research/features.md` §6; API `GET /api/v1/templates/file`,
    `research/api-and-android.md` §4);
  - the backup zip (Settings → Backups, on paid plans, `research/features.md` §6; or
    `GET /api/v1/backups` and `/backups/download`). That the zip holds per-project CSV in the same
    template format is an assumption.
- The column set is from memory and unverified: `TYPE, CONTENT, DESCRIPTION, PRIORITY, INDENT,
  AUTHOR, RESPONSIBLE, DATE, DATE_LANG, TIMEZONE, DURATION, DURATION_UNIT, DEADLINE, DEADLINE_LANG`.
  Check it against a real export first, and adjust the table below.
- The CSV has no ids, so each row gets a synthetic key: `csv:` + sha256 of (project name, section
  name, the CONTENT of each parent up the INDENT chain, CONTENT, n), where n counts earlier rows with
  the same tuple. Reordering rows does not change keys; editing a task's text in Todoist makes it a
  new task.

| CSV column | Tasks |
|---|---|
| `TYPE=section` | a section |
| `TYPE=task`, `CONTENT` | a task; `CONTENT` is the title. † whether labels appear inline in `CONTENT` (`@x`) must be checked; if so, they are extracted as labels |
| `DESCRIPTION` | `description` |
| `PRIORITY` | † whether it uses UI numbering (1 = p1) or API numbering (4 = p1): check with a known p1 task |
| `INDENT` | subtask depth (`parent_id` = the nearest earlier row with a smaller indent) |
| `DATE`, `DATE_LANG` | `DATE` is a date string ("every day", "Oct 30"). Run **our** grammar on it when `DATE_LANG` is `en`. Not a single `Parsed` → undated, on "Import needs review" with the original string |
| `TIMEZONE` | a set zone makes the due fixed; empty is floating |
| `DURATION`, `DURATION_UNIT` | `duration_min` (unit `day` → ×1440) |
| `DEADLINE` | `deadline` (dropped with a review entry if the task is recurring) |
| `AUTHOR`, `RESPONSIBLE`, `DEADLINE_LANG` | ignored |

### 11.2 API import (later, connector only)

- Full sync with `POST /api/v1/sync`, `sync_token=*`, `resource_types=["all"]`, using a personal
  token pasted once (`research/api-and-android.md` §1, §4). Read-only: needs `data:read`.
- The token is used for the import and then discarded, never stored.
- Keys are Todoist's own ids.

| Todoist API | Tasks |
|---|---|
| priority (4 = urgent) | `priority = 5 - api_priority` |
| `due.date` `YYYY-MM-DD` | `due_date`, all-day |
| `due.date` `…T12:00:00` (no Z) | floating date + time |
| `due.date` `…Z` + `timezone` | fixed: convert the UTC instant to local in `timezone`, store date, time and zone |
| `due.is_recurring` + `due.string` | Run **our** recurrence grammar on `due.string` (English). `every!` / `after N days` → anchor `completion` (`research/features.md` §3). If it is not `Parsed`, import as non-recurring and add the task to "Import needs review" with the original string. Never guess |
| `deadline.date` | `deadline` |
| `duration {amount, unit}` | `duration_min` (unit `day` → ×1440) |
| labels (names) | find or create a label by name (an import is an explicit user action, so creation is allowed) |
| `parent_id`, `section_id`, `project.parent_id` | same structure, via `import_map` |
| reminders `relative minute_offset` / `absolute due` | `BEFORE_DUE` / `AT_LOCAL` or `AT_INSTANT` per the due shape |
| comments, attachments | skipped with a count in the summary |
| completed tasks | not imported in v1. † Whether `/sync` returns completed tasks at all is unverified; v1 imports only open tasks either way |

The import summary screen shows counts per entity, "kept your changes", and the needs-review list.

---

## 12. Non-functional requirements

- **Offline:** the core APK declares no `INTERNET`. A CI check fails the build if the merged
  manifest contains it.
- **Performance:** cold start to Today ≤ 600 ms. `handle()` budgets as in 4.10. The DB stays fast at
  10,000 tasks (seed test).
- **Battery:** no periodic work in the core app beyond the daily purge on app start. One alarm at a
  time.
- **Robustness:**
  - every receiver and binder entry point catches everything and logs locally;
  - a crash in `handle()` returns `internal`, never a dead binder;
  - `CrashLog` mirrors Assistant's local-only file.
- **Privacy:**
  - `utterance_log` keeps 30 days; `request_log` 10 minutes; `conversation` rows are deleted when
    the conversation ends or expires; `referent` rows after 10 minutes.
  - Settings → Clear voice history deletes `utterance_log`, `request_log`, `conversation`,
    `referent`, the transcripts of closed rambles, and sets `task.source_utterance` to null.
  - Export follows 5 ("Include voice history" off by default).
  - The golden-corpus scrub rule is in 13.1.
- **Licence:** Assistant is AGPL-3.0 (a fork). Tasks is a new work owned by the owner, so its
  licence is their choice (Q2). Do not copy Assistant code with Hasan's copyright header without
  keeping that header (AGPL). Phrase lists and theme tokens should be rewritten, or keep the header.
  Do not copy code or test data from GPL projects (Hawking, SUTime/CoreNLP, HeidelTime; Appendix A)
  unless Q2 picks a GPL-compatible licence.

---

## 13. Testing

### 13.1 Golden utterance corpus

- **Files:** `app/src/test/resources/golden/*.jsonl`. One case per line:
  ```json
  {"id":"rem-at-bare-5pm","utterance":"remind me to call Amira at 5",
   "now":"2026-10-27T14:03:00","zone":"Europe/London","speech_end_offset_ms":-600,
   "projects":["Inbox","Home","Work","Shopping"],"labels":["errands","calls"],
   "open_tasks":[],
   "expect":{"status":"done","intent":"create","title":"Call Amira",
             "due":{"date":"2026-10-27","time":"17:00","zone":null},"recurrence":null,
             "reminder":"SCHEDULE","project":"Inbox","priority":4,
             "speech":"Okay. I'll remind you to call Amira today at 5 pm."}}
  ```
  Follow-up cases add `"after":"<id of the case it answers>"`; the runner replays that case first.
- **Runner:** builds a `ParseContext` from the case, runs the **full** handler against an in-memory
  Room DB and a fake scheduler, and compares fields. `speech` is compared exactly, because speech
  is part of the contract.
- **Goals:** at least 300 cases by M2, with at least 60 `needs_input` or `declined`.
- **Sources:**
  - this table;
  - chrono (MIT) and Duckling (BSD) test phrases, rewritten as tasks (licences verified in
    `research/parsing.md` §1). Not Hawking, SUTime or HeidelTime (GPL);
  - Assistant's real query log (`/home/ijaz/assistant-dev/bench/cases/`). `bench/data/` is private
    and MUST NOT be committed. **Scrub rule:** before any real-log case is committed, replace every
    name, phone number, address, email and other personal detail with the fictional set in this
    spec's header, and the owner reviews the batch. The owner's own number is never committed in any
    form;
  - junk transcripts (expect `not_mine`).

Seed cases. All have now = **Tue 27 Oct 2026 14:03, Europe/London (GMT), h12**, unless noted.

| # | Utterance | Expected |
|---|---|---|
| 1 | remind me to call Amira at 5 | done; "Call Amira", today 17:00 floating (D1), reminder at due |
| 2 | remind me in 20 minutes to take the bread out | done; "Take the bread out", AT_INSTANT 14:22:59.4 (speech end 14:02:59.4 + 20 min), due 14:22 fixed Europe/London |
| 3 | add oat milk and eggs to my shopping list | done; **one** task "Oat milk and eggs" in Shopping, no due |
| 4 | remind me to take my vitamins every day at 9am | done; DAILY 09:00, first due **2026-10-28** (9 am passed; a recurrence takes the next occurrence) |
| 5 | remind me next Friday to renew the parking permit | needs_input expects=date; options Fri 30 Oct / Fri 6 Nov |
| 6 | (now 03:10) remind me at 5 to feed the cat | needs_input expects=time; "5 in the morning or the evening?" (night rule) |
| 7 | add prune the roses to the garden list | needs_input expects=choice; "I don't have a Garden list. Create it, or add it without a list?" |
| 8 | remind me to water the plants | needs_input expects=date_time; "When should I remind you?" |
| 9 | add water the plants to my list | done; Inbox, no due, no question |
| 10 | remind me to call Bilal on 555-0142 tomorrow at noon | done; "Call Bilal on 555-0142", 2026-10-28 12:00. The number is not a time |
| 11 | remind me to call the garage at 5 tomorrow no wait Friday | needs_input expects=choice; "Tomorrow at 5 pm, or Friday at 5 pm?" |
| 12 | remind me every Monday and Thursday at 7pm to put the bins out | done; WEEKLY MO,TH 19:00, first due Thu 29 Oct |
| 13 | remind me on the 31st of every month at 9am to pay rent | done; MONTHLY BYMONTHDAY=31 09:00, first 2026-10-31; speech mentions the clamp |
| 14 | remind me on the 31st of November to file the claim | declined; "November has no 31st." |
| 15 | remind me in an hour and a half | needs_input expects=title; "What should I remind you about?" |
| 16 | what's on my list today | done; `listed`; reads ≤ 5 items, then "and N more" |
| 17 | mark call Amira as done (open: "Call Amira", "Call Amira's dentist") | done; completes "Call Amira" (exact match wins, 6.1 step 5) |
| 18 | delete the gym task (open: "Gym at 6") | done + undo token; "Deleted 'Gym at 6'. Say undo to bring it back." (unique containment) |
| 19 | (follow-up to 5) "the sixth" | needs_input expects=time; "What time on Friday 6 November?" options "9 am" / "No alert" (second and last question) |
| 20 | Thank you. | not_mine |
| 21 | remind me tonight to lock the shed | done; today 20:00 (D2), speech "today at 8 pm" |
| 22 | add call the insurer tomorrow morning | done; 2026-10-28 09:00 (D2), reminder at due (D3) (was "priority one call…"; priorities removed from v1 (owner, 2026-11-02)) |
| 23 | remind me at half past six in the evening to start dinner | done; today 18:30 |
| 24 | remind me at midnight tonight to send the form to Dr Okafor | done; 2026-10-28 00:00; speech "at midnight tonight, the start of Wednesday" |
| 25 | set a timer for 10 minutes | not_mine (Assistant's Clock tool) |
| 26 | ramble: "Okay brain dump. Call the garage about the MOT. Buy printer paper and also book a haircut for Saturday. Oh and email Dr Okafor the invoice. Tomorrow morning." | done; 4 drafts; "Okay brain dump" is a frame span; the last draft has "tomorrow morning" attached and flagged `attached_modifier`; "Buy printer paper" and "Book a haircut" (Sat 31 Oct) are separate |
| 27 | (now Sat 24 Oct 2026 23:50, Europe/London) remind me every day at 1:30 am to check the boiler | done; first due 2026-10-25 01:30, fires once at 01:30 BST |
| 28 | remind me at 25 o'clock | declined; "That isn't a time I can use." |
| 29 | (follow-up to 19) "9 am" | done; 2026-11-06 09:00, reminder at due |
| 30 | (now Sat 24 Oct 2026 23:50) remind me every day at 1:30 to check the boiler | needs_input expects=time; "1:30 in the morning or the afternoon?" (bare hour in a recurrence) |
| 31 | remind me at 1pm to ring the school | needs_input expects=confirm; "1 pm today has passed. Tomorrow at 1 pm?" |
| 32 | (follow-up to 31) "yes" | done; 2026-10-28 13:00 |
| 33 | remind me on Friday to post the parcel | needs_input expects=time; "What time on Friday the 30th?" options "9 am" / "No alert" |
| 34 | (follow-up to 33) "no alert" | done; Fri 30 Oct all-day, no reminder; speech says "no alert" |
| 35 | add renew the passport on Friday | done; Fri 30 Oct all-day, no reminder, no question |
| 36 | mark call Amira as done (open: "Call Amira about the party", "Call Amira's dentist") | needs_input expects=task; both titles as options |
| 37 | remind me on Tuesday at 6pm to put the recycling out | needs_input expects=date; "Today, or Tuesday 3 November?" (same-day weekday) |
| 38 | remind me in a few minutes to check the oven | declined; "I need an exact time, like in 20 minutes." |
| 39 | remind me to call Amira at 3 London time | declined; "I can't set times in other time zones yet." |
| 40 | start a brain dump | needs_input expects=ramble; "Go ahead, I'm listening." |
| 41 | remind me to say sorry to Amira tomorrow at 10am | done; title "Say sorry to Amira", 2026-10-28 10:00 (no self-correction) |
| 42 | okay remind me to stretch at 4pm | done; "Stretch", today 16:00 (leading filler stripped) |
| 43 | I've paid the gas bill (open: "Pay the gas bill") | done; completes "Pay the gas bill" |
| 44 | (after 1, same client) push that to tomorrow | done; "Call Amira" moved to 2026-10-28 17:00 (time kept, referent) |
| 45 | (in-app quick-add bar) every day at 7 take the bins out | needs_input expects=time; "7 in the morning or the evening?" |

D3 is replaced by 8.6: "reminder at due" in rows 1, 22 and 29 means the task gets its tag schedule (`reminder`
is `SCHEDULE` in the corpus, `AT_INSTANT` for "in N minutes").

### 13.2 Property tests (time math)

Use `kotest-property` (verified 2026-11-02: 6.2.6 resolves and compiles with Kotlin 2.4.10 and JVM 17). Generators cover:

- instants 2000-2040;
- zones: Europe/London, America/New_York, Australia/Lord_Howe (30-minute DST), Asia/Kolkata (no
  DST), Pacific/Apia (skipped a day in 2011), America/Sao_Paulo (DST abolished);
- local times including gap and overlap minutes;
- every supported RRULE shape.

Properties:

1. `next(rule, after = t) > t` always, and `next` is monotone: `next(next(t)) > next(t)`.
2. Every computed occurrence's **local** date matches the rule (weekday ∈ BYDAY, day-of-month = BYMONTHDAY
   or clamped last day, interval spacing in local dates).
3. The local time of an occurrence equals `due_time`, except inside a gap, where it equals `due_time`
   + gap length.
4. Within any 48-hour window around an overlap, a daily reminder fires **exactly once** per local
   date (simulate with a fake clock and the real scheduler logic).
5. "Tomorrow" from any instant in any zone = `localDate + 1`, including across DST and Apia's skipped day.
6. "In N minutes" = exactly `N × 60 s` of elapsed time, regardless of DST.
7. Floating reminder after a zone change keeps its local wall time. Fixed keeps its instant.
8. `RecurrenceRule` → RRULE string → parse round-trips. `DueSpec` JSON round-trips.
9. The parser is total: random unicode strings never throw, and return `not_mine`, `declined` or a
   result whose spans are within bounds and non-overlapping.
10. Title soundness: every word in a produced title occurs in the utterance (normalised), for random
    corpus mutations.
11. Weekday rules never land on excluded days. `every weekday` never yields Sat or Sun.
12. No reminder ever fires before its `next_fire_at` (fake clock, random alarm delivery delays).
13. Ramble accounting: for random transcripts, frame + filler + draft spans cover every character
    exactly once.

### 13.3 Other tests

- Room DAO and migration tests (Room `MigrationTestHelper`) from schema v1 on.
- `ReminderScheduler` with a fake `AlarmManager` wrapper: one armed alarm; re-arm on each trigger;
  missed delivery happens once; no refire loop; snooze fires after a prior fire.
- `UndoJournal`: for each mutation type, apply then undo restores the DB byte-for-byte (excluding
  `updated_at`); undo after a conflicting edit is refused.
- `CommandHandler`: in-flight dedupe (two concurrent calls with one `request_id` commit once);
  `busy` never leaves a row written; `error` responses are not cached.
- `TaskMatcher`: table tests for the exact, containment and fuzzy steps, including possessives.
- `CommandService` instrumented test: a test APK signed with the same debug key binds and
  round-trips fixtures. A differently-signed test APK gets `SecurityException` on bind. Both install
  orders of Tasks and the test APK work (4.2.1).
- An instrumented benchmark of `handle()` p50/p95 on the phone against 4.10 (via adb; the phone
  setup is in the owner's notes).
- Assistant-side: `TaskAppClient` JSON mapping against `contract/v1` fixtures; `TaskRouting.claims`
  against `contract/v1/routing.jsonl`; the `FollowUps.kt` / `VoiceSession` follow-up state machine with a fake client,
  including `not_mine` on a follow-up.
- End to end on the phone: both debug builds installed; drive with Assistant's typed input
  (`submitText`) through every seed case (ramble seeds in M4); check the speech and DB state through
  the Tasks debug "contract console" screen (debug build only).

### 13.4 Contract fixtures

- `tasks-app/contract/v1/` holds `request-*.json` and `response-*.json` pairs (one per `type`,
  `status`, effect `op` and `expects` value), `routing.jsonl` (phrases with `claim: true|false`
  and the expected intent), a copy of `ITaskCommand.aidl`, and `MANIFEST.sha256` listing every
  file's hash.
- Tasks' tests assert the handler produces each response from each request with a fixed clock, and
  that `MANIFEST.sha256` is current.
- Assistant vendors the directory under `app/src/test/resources/tasks-contract/v1/` (the AIDL file
  also goes to `src/main/aidl/…`). Its tests assert it parses each response and maps it to the right
  `SessionState`.
- **Drift check.** `tasks-app/contract/sync-to-assistant.sh` copies the directory and the AIDL file
  into `themachine`. Assistant's `TaskContractDriftTest` compares its vendored hashes with
  `/home/ijaz/assistant-dev/tasks-app/contract/v1/MANIFEST.sha256` when that path exists (local
  builds) and is skipped when it does not (CI). Whoever changes the contract runs the script and
  commits both sides in the same piece of work.

---

## 14. Milestones for the builder

Each milestone ends with: builds, `testDebugUnitTest` green, spotless and detekt clean (same setup as
Assistant), installed on the phone, and a short note of what was verified. Run `source
/home/ijaz/assistant-dev/env.sh` before Gradle. Install nothing outside `/home/ijaz/assistant-dev`.

Verification gates († claims):

- Before M2: confirm the Todoist quick-add details 6.3 relies on against the live help page, and
  that `kotest-property`'s current version works with the toolchain.
- Before M3: Q15 answered; same-key duplicate permission installs in both orders (4.2.1); debug
  certs match (4.2.6).
- Before M5: a real Todoist CSV export and backup zip are inspected, and 11.1 is corrected to match.

| M | Scope | Done when |
|---|---|---|
| M0 | Gradle project at `tasks-app/`, version catalog mirrored from Assistant, Halo theme, Room schema v1 + export JSON, CI workflow (copy Assistant's `ci.yml` shape), manifest check "no INTERNET" | Empty app launches with Today/Inbox. Export of an empty DB works |
| M1 | Repository, Today/Upcoming/Inbox/Projects/detail screens, `time/` package, ReminderScheduler + receivers + notifications, battery onboarding step, property tests 13.2 | A typed task with a manual date/time fires on time. Reboot, zone change and DST property tests pass |
| M2 | Parser (6.1-6.4) + quick-add bar + golden corpus ≥ 300, CommandHandler with all v1 intents and referents, behind a debug "contract console" screen | Corpus green. Seed table 13.1 green except the ramble seeds (26, 40). Typed quick add shows chips |
| M3 | IPC: AIDL service, caller check, dedupe, conversation store, undo journal, contract fixtures + `routing.jsonl`; **Assistant changes 4.12 steps 1-7 and 9** on a branch of `themachine`; migration 4.13 | Every seed except 26 and 40 passes by voice or typed through Assistant on the phone, `handle()` p95 ≤ 150 ms, `tasks.md` migrated on the phone without a lost or doubled reminder, Assistant change ≤ 850 production lines |
| M4 (should; may follow M5) | Ramble (on-device deterministic): segmenter, review screen, typed ramble in-app, ramble IPC incl. `expects: "ramble"`, Assistant 60 s capture (4.12 step 8), Whisper 60 s measurement (6.5) | Seeds 26 and 40 green. A 60 s spoken dump produces drafts with every character accounted for. Capture time measured and reported to the owner |
| M5 | Share target, quick-add tile + shortcut, search + filter subset, Todoist CSV/backup import, completed history | Import of a real export matches counts. Re-import changes nothing. Needs-review list correct |
| M6 (later, optional) | Tasks Connector APK + owner's home-server ramble segmentation (owner-owned hosts only, goal 7) + `ValidationGate` (6.7), IMAP (10) and/or Todoist API import (11.2) | Gate rejects 100% of a synthetic bad-output set (no date ever comes from the model). Owner has answered Q7/Q8/Q9 and homelab endpoint is live |

**M0–M5 are self-contained and ready to build now** with zero LLM/NDK dependencies in Tasks. Do not
start M6 or option B in section 10 without the owner's go-ahead.

Commit conventions follow Assistant (conventional commits). Create the repo locally only. Pushing or
creating a GitHub repo needs the owner's say-so (Q1).

---

## 15. Open questions for the owner

1. **Name and ids.** Is "Tasks" / `io.github.ijazshare.tasks` right? Is it a separate GitHub repo
   (public, like Assistant?), or a folder in `ijazshare/assistant`?
   **Answered 2026-11-01:** "Tasks", `io.github.ijazshare.tasks`, its own repo `ijazshare/tasks`
   (created locally; pushed only on the owner's say-so).
2. **Licence** for the new app: AGPL-3.0 to match Assistant, or something else? This affects how much
   Assistant code can be copied, and whether GPL sources can be used.
   **Answered 2026-11-01:** AGPL-3.0, matching Assistant.
3. **Floating by default?** Should spoken clock times follow the phone's zone when travelling
   (floating, proposed), or stay fixed to the zone where they were set?
4. **Defaults D1-D3 (8.3.1).** Approve each, or switch it to "always ask":
   - D1: a bare one-off hour takes the nearer future am/pm, and asks only between midnight and
     7 am;
   - D2: morning 09:00, afternoon 14:00, evening 19:00, tonight 20:00 (Todoist uses afternoon 12 pm);
   - D3: replaced by tag schedules (8.6, owner 2026-11-02).
5. **The 31st in short months.** Clamp to the last day (proposed), or skip that month?
6. **Ramble saving.** Always review before saving (proposed), or auto-save drafts that parsed with no
   flags?
7. **Email.** Share-only (A) keeps everything offline and needs no Assistant change. Do you want
   Assistant notification capture (B), which adds Assistant code? Do you want IMAP (C)? If so, is a
   separate "Tasks Connector" app with `INTERNET` acceptable, or must nothing in this family touch
   the network? Note for goal 7: IMAP reads a mailbox hosted by the mail provider. The Connector
   sends only the login and fetches mail already stored there; no task data goes out.
8. **Todoist.** Is a one-time import enough (file import proposed; API import optional), or do you
   want ongoing two-way sync? Sync sends tasks to Todoist's servers, so goal 7 rules it out. A
   read-only API import sends nothing but the token.
9. **Home-server ramble (M6, optional).** Answered in part (2026-11-01): owner-owned devices only,
   open-source models allowed (goal 7). Audio offload can only live in Assistant (6.7, item 1).
   Still open: is M6 wanted at all, given ramble is rarely used? If yes, LAN only, or LAN plus the
   owner's tailnet?
10. **"Remind me to X" with no time.** Ask "When?" (proposed), or save undated and say so? And with
    a date but no time: ask "What time?" with a "No alert" option (proposed), or save all-day with no
    alert and say so?
11. **Undo window.** Is 10 minutes right?
12. **Week start**: Monday (proposed) or Sunday?
13. **Should Assistant speak reminders** when they fire (Tasks would ask Assistant to TTS them), or
    are notifications enough? Proposed: notifications only in v1.
14. **Assistant's answer context.** After migration, should Assistant's `AnswerPrompt` get a short
    "due today" summary from Tasks (a `status` call) so general questions can mention tasks, or keep
    them fully separate (proposed)?
15. **Debug/release mixing (answered 2026-11-02: same build type only).** Is "same build type only" acceptable? The
    alternative is signing debug builds with the release key on this machine.
16. **Recurring delete.** Delete removes the whole series (proposed, as Todoist does). Should voice
    also support "skip this one" as its own command, beyond "push to tomorrow"?
17. **Spoken time zones.** v1 declines "at 3 London time" (proposed). Is that acceptable, or should
    a small closed list of zones be supported?

---

## Appendix A. Sources

Research notes (this project):

- `/home/ijaz/assistant-dev/tasks-app/research/api-and-android.md`: Todoist API v1, models, the due
  object, Quick Add (including ` // description`), webhooks, auth, limits, export, deep links, and
  what the installed app exposes.
- `/home/ijaz/assistant-dev/tasks-app/research/features.md`: Todoist's user-facing features from
  its help pages (2026-10-27): Ramble, email, Quick Add syntax, organisation and filters, reminders,
  completion and undo, Android surfaces, Google Assistant, calendar sync. Its pages were summarised
  by a small model, so quoted syntax is faithful to the summary, not a raw copy.
- `/home/ijaz/assistant-dev/tasks-app/research/parsing.md`: the JVM parser survey, grammar design,
  ramble splitting, the small-model gate and java.time pitfalls. Fact-checked on 2026-10-27 with a
  verification log; claims are tagged [V] verified, [C] corrected or [U] unverified. Sections 2-5
  (Whisper behaviour, java.time semantics, model footprints) are mostly [U].
- `/home/ijaz/assistant-dev/tasks-app/research/todoist-android-surface.txt`: the package-manager
  dump of Todoist v12324.
- `/home/ijaz/assistant-dev/tasks-app/research/critique.json`: the review applied in revision 2.

Parser survey results that this spec relies on (from `parsing.md` §1, verified 2026-10-27). None is
used as a dependency; the corpus may borrow test phrases from the MIT/BSD ones.

| Library | Licence | Status |
|---|---|---|
| Natty | MIT | Last release 0.13, 2017-01-26. ANTLR 3.5.2 runtime + slf4j; no Joda-Time dependency |
| Duckling | BSD | Haskell, active repo, last release v0.2.0.0 (2021). Not runnable on Android; whether the JVM artifacts are real ports is unconfirmed |
| Hawking | Likely GPL-3.0 (no top-level LICENSE; ships `licenses/gpl-3.0`, credits CoreNLP) | 0.1.9, 2024-09-24. Reading material only |
| PrettyTime | Apache-2.0 | 5.0.9.Final. `prettytime-nlp` wraps Natty 0.13 |
| chrono | MIT | TypeScript, v2.10.3 (2026-10-12). Java port `chrono-java` exists but is stale (2014). `kwhen` (Kotlin Multiplatform) not evaluated |
| SUTime (in CoreNLP), HeidelTime | GPL-3.0 | Unsuitable for Android and licence |

Todoist:

- API v1 and migration: https://developer.todoist.com/api/v1/ ,
  https://developer.todoist.com/api/v1/#tag/Migrating-from-v9
- Due dates: https://developer.todoist.com/api/v1/#tag/Due-dates
- Quick Add endpoint:
  https://developer.todoist.com/api/v1/#tag/Tasks/operation/quick_add_api_v1_tasks_quick_post
- Webhooks: https://developer.todoist.com/api/v1/#tag/Webhooks
- Authorization: https://developer.todoist.com/api/v1/#tag/Authorization
- Request limits: https://developer.todoist.com/api/v1/#tag/Request-limits
- URL schemes: https://developer.todoist.com/api/v1/#tag/Url-schemes
- Quick Add help: https://www.todoist.com/help/todoist/features/use-task-quick-add-in-todoist-va4Lhpzz
- Dates and recurrence: https://todoist.com/help/articles/205325931 ,
  https://todoist.com/help/articles/360000636289
- Durations: https://todoist.com/help/articles/9281236249628
- Ramble: https://www.todoist.com/help/articles/dictate-and-add-tasks-with-ramble-P1Raq7vVF
- Email: https://todoist.com/help/articles/205749772 ; Gmail:
  https://www.todoist.com/help/todoist/integrations/use-gmail-with-todoist-YMVdMcAx ; Outlook:
  https://todoist.com/help/articles/360013412679
- Filters: https://www.todoist.com/help/articles/introduction-to-filters-V98wIH
- Deadlines: https://www.todoist.com/help/todoist/features/introduction-to-deadlines-in-todoist-uMqbSLM6U
- Reminders: https://todoist.com/help/articles/205348301 , https://todoist.com/help/articles/8993625039132
- Recurring completion: https://todoist.com/help/articles/complete-a-task-with-a-recurring-date-dmI6SVqdP
- Templates / CSV: https://todoist.com/help/articles/208821185 ; backups:
  https://todoist.com/help/articles/115001799989 ; usage limits:
  https://todoist.com/help/articles/usage-limits-in-todoist-e5rcSY
- Android tile: https://todoist.com/help/articles/213282965 ; widgets:
  https://todoist.com/help/articles/206408179
- Google Assistant: https://todoist.com/help/articles/115000056165
- Calendar sync: https://todoist.com/help/articles/115003128085

Parsing, models and time:

- Natty: https://github.com/joestelmach/natty
- Duckling: https://github.com/facebook/duckling
- Hawking: https://github.com/zoho/hawking
- PrettyTime: https://github.com/ocpsoft/prettytime
- chrono: https://github.com/wanasit/chrono ; chrono-java: https://github.com/wanasit/chrono-java
- SUTime: https://stanfordnlp.github.io/CoreNLP/sutime.html ; CoreNLP:
  https://github.com/stanfordnlp/CoreNLP
- HeidelTime: https://github.com/HeidelTime/heideltime
- Gemma: https://deepmind.google/models/gemma/ ; FunctionGemma:
  https://huggingface.co/google/functiongemma-270m-it
- llama.cpp grammars: https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md
- java.time `ZonedDateTime` gap/overlap:
  https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/ZonedDateTime.html
- Android exact alarms: https://developer.android.com/develop/background-work/services/alarms ,
  https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- Desugaring (not needed at minSdk 33): https://developer.android.com/studio/write/java8-support

Assistant code read for this spec (paths under
`/home/ijaz/assistant-dev/themachine/app/src/main/java/io/github/ijazshare/assistant/` unless
noted):

- `tools/AssistantTools.kt`: the tool list, including `create_reminder`.
- `tools/ToolExecutor.kt`: the handler table and honest-failure speech.
- `tools/ReminderStore.kt`: exact/inexact alarms, per-id PendingIntent, notification checks,
  `rescheduleAll`.
- `context/AssistantFiles.kt`: the `tasks.md` line format, `markDelivered`, the file lock.
- `llm/PromptBuilder.kt`: why routing stays minimal and the examples are few.
- `assistant/VoiceSession.kt` (`act`, `answeredWithoutTheModel`, `carryOut`), `assistant/CallChecks.kt`
  (`reconciled`, `withSpokenTime`), `assistant/FollowUps.kt` and `assistant/PendingSend.kt`
  (`PendingSend`, `PendingReminder`, the `ChooseContact` pattern reused for follow-ups),
  `assistant/SpeechCapture.kt`, `assistant/CommandCache.kt`; split from one file on 2026-10-27.
- `tools/StatedTime.kt`, `tools/TimeExpressions.kt`: the stated-time rules (Appendix C item 1).
- `llm/RemoteAnswerer.kt`, `llm/ModelRouter.kt`: what the remote answer model is sent.
- `stt/JunkTranscript.kt`: the junk phrase list and why it exists (AGPL header).
- `themachine/app/build.gradle.kts`: release signing from `SIGNING_KEYSTORE_PATH` and related
  properties.
- `/home/ijaz/assistant-dev/themachine/CLAUDE.md`: locked decisions (1: the owner's Samsung foldable, SM8850; 8: no general slot-filling loop, two named
  follow-ups), the < 3 s latency budget, permissions and privacy invariants.
- DST transition dates used in examples were checked with tzdata on this machine:
  - Europe/London gap: 2027-03-28 01:00-02:00.
  - Europe/London overlap: 2026-10-25 01:00-02:00.

Assumptions still to measure (not sourced): the 0.5-1 s routing time a keyword claim saves, the
200-400 ms cold start of Tasks, the `handle()` p50/p95 targets, and Whisper's time on up to 60 s of
audio. Each has a milestone that measures it (4.10, 6.5).

---

## Appendix B. Review notes (revision 2)

The 34 findings in `research/critique.json` are numbered C1-C34 in file order. All were applied
except where noted below.

Applied with a different fix than proposed, or only in part:

- **C1 (night rule vs seed 27).** One rule for all bare hours, split by kind: one-offs use the
  nearest future plus the night rule; recurrences always ask, because "nearest future" means nothing
  for a repeating time. Seed 27 now says "1:30 am"; seed 30 is the bare version and asks.
- **C2 (task-match metric).** Chose "an exact normalised title wins", then containment, then a fuzzy
  Dice score that can only ask, never act. Seed 17 is now `done`; seed 36 keeps the ambiguous case.
- **C4 (model download / FD sharing).** Superseded in revision 4: Tasks has no model. Previously, both options removed: the owner copies the GGUF and picks it
  through SAF. Adding a second AIDL method and model-serving code to Assistant would break the
  single-method contract and the lean-Assistant decision.
- **C12 (defaults that guess).** Kept D1-D3 as listed, read-back, owner-approved defaults (Q4)
  rather than asking every time. Asking on every bare hour would double the questions on the most
  common command, and the owner should make that trade. Same-day weekday and month-day, and "every
  other week" with no day, now ask.
- **C13 (separate title field).** Added `description`, `external_id`, `lines`, `legacy_id` and the
  enums, but **not** a separate `title` field: the title is always derived by Tasks' parser from
  `utterance`, so no client can pass a pre-made title around the grammar.
- **C22 (zone slot).** Instead of a closed zone list, v1 declines spoken zones (seed 39, Q17). It
  removes a rule set and a mapping table that nothing else needs; fixed times are still available
  from the detail sheet.
- **C24 (direct boot, force-stop).** Documented as accepted gaps with Missed delivery, not
  implemented. Direct-boot support needs a device-protected copy of the schedule, which is more than
  v1 warrants. `SCHEDULE_EXACT_ALARM` and the inexact fallback stay as cheap defence.
- **C27 (permission claim).** Kept the dual declaration, marked † with an install-order test in M3,
  instead of removing it.

Not applied: none.

---

## Appendix C. Assistant changes since revision 2

State of Assistant on 2026-10-28, main 37e7bb4, read from `/home/ijaz/assistant-dev/worktrees/assistant-fixes`
(paths under `app/src/main/java/io/github/ijazshare/assistant/`). Everything in this appendix is
behaviour of the *existing* reminder path that Tasks replaces (4.12 step 7), so Tasks takes it over.
Where 8.3 asks instead of acting, 8.3 wins: Tasks is allowed to be more careful, never less.

1. **Reminder time handling in code** (`tools/StatedTime.kt`, `tools/TimeExpressions.kt`,
   `assistant/CallChecks.kt` `withSpokenTime`, `tools/ReminderStore.kt` `create`).
   - Exactly one stated time replaces the model's. With none or several, the model's numbers stand.
   - With no day named, tomorrow only if the word "tomorrow" is said; otherwise today, and
     `ReminderStore.create` rolls a time that has passed to the next day.
   - A named weekday, month or date keeps the model's day.
   - "The day after tomorrow" is declined: "I can only set that for today or tomorrow."
   - "A reminder for/of 5 minutes" (after "remind", "reminder" or "alarm", before any "to") is a
     length, so the time is now + 5 minutes, not a title. "Remind me to study for 2 hours" is a title.
   - Tasks must: treat the same phrases as times, not titles (golden cases: "set a reminder for 5
     minutes" at 09:04 freezes 09:09 and asks for the title (`expects=title`, as seed 15); "remind me to study for 2 hours" keeps that title and
     asks when; "remind me at 4.15 pm" at 12:24 is today 16:15, never tomorrow; "in 30 minutes" is
     an instant, per 8.1); never infer "tomorrow" from anything but the word; support or
     explicitly decline "the day after tomorrow" (Tasks supports it, 6.1); and ask where 8.3 says
     so for a time that has passed ("1 pm today has passed") instead of silently rolling.

2. **Untitled reminder** (`tools/ToolExecutor.kt` `createReminder` and `ASK_TASK`,
   `assistant/PendingSend.kt` `PendingReminder`, `assistant/FollowUps.kt` `answeredPendingReminder`,
   `tools/StatedTime.kt` `isUntitled`, `tools/ReminderStore.kt` `UNTITLED`).
   - A reminder that is only the asking and a time ("set a reminder", "a reminder for 5 minutes") is
     untitled (`isUntitled`). With no stated time it asks "What should I remind you about?",
     `PendingReminder` holds the request for 60 s, and the answer is appended to the request and run
     again as one command ("Set a reminder" + "to book tickets at 6 pm"). "Cancel" says "Okay, no
     reminder." With a time it is stored with the title "Reminder".
   - Tasks must: ask "What should I remind you about?" when the title is missing, as 4.7, 6.1 and
     seed 15 already say. That also covers the case Assistant does not ask about (untitled with a
     time, which it stores as "Reminder"). Add a golden case for a bare "set a reminder" (asks, 90 s
     TTL), and one where the answer arrives as "to book tickets at 6 pm" (title and time from one
     answer). Imported lines titled "Reminder" stay as they are. A cancel word answers as `cancel`.

3. **`tasks.md` delivery** (`context/AssistantFiles.kt` `markDelivered`, `markSeen`, `parseTask`,
   `contextForPrompt`; `tools/ReminderReceiver.kt`; `tools/ReminderStore.kt` `rescheduleAll`;
   `ui/MainActivity.kt`).
   - `markDelivered` turns `due:` into a `delivered` flag in the trailing comment. Swiping or
     opening the notification clears it. After `BOOT_COMPLETED` a flagged open task is shown once
     more (and drops the flag). A firing whose notification is still active is ignored. Ticked
     `- [x]` tasks are left out of the answer model's context.
   - Tasks must: migrate flagged and unflagged lines as in 4.13 (no lost, doubled or falsely
     "missed" reminder); match the still-showing guard and the one-re-show-after-boot rule in 8.4,
     or tell the owner it differs. Test cases for the importer: a line with `due:` in the past (one
     Missed), `delivered` open (one Missed), an `id:` with neither flag nor `due:` but visible
     `— due …` text (imported dated, no reminder), a ticked line (completed, no reminder).

4. **Locked decision 8 rewritten** (`CLAUDE.md`). Quoted in full in section 2, "Follow-ups". There is
   no general slot-filling loop, and the old "max 2 clarification rounds per command" no longer
   exists. Three follow-ups exist; the reminder one ("What should I remind you about?", item 2) is
   the one Tasks replaces. Tasks keeps its own limit of 2 questions; adding it to Assistant needs the owner's yes
   and a CLAUDE.md edit. Tasks must not rely on Assistant counting rounds: `context.round` and the
   limit are enforced by Tasks (4.7).

5. **`VoiceSession.kt` split** (`assistant/VoiceSession.kt` 708 lines, `SpeechCapture.kt` 168,
   `CallChecks.kt` 178, `FollowUps.kt` 146, `PendingChoice.kt` 21; the first three plus
   `PendingSend.kt` hold the pieces 4.12 names). 4.12 now points at the right files. The estimate
   of about 800 production lines and the 850 cap are unchanged: the rows add up to 806, and
   nothing in the split makes any row clearly wrong. Tasks must: re-check the file list in 4.12
   against the tree before M3 and treat a change in any other file as a scope change.

6. **Rename** to `io.github.ijazshare.assistant`; no older package or project name remained in the
   spec, so only the checkout path `/home/ijaz/assistant-dev/themachine` is left, as intended.
   Tasks must: use the allowlist in 4.2.3 as written; the debug variant is `.debug`.

7. **Remote answer model** (`llm/RemoteAnswerer.kt` `systemPrompt`, `requestBody`;
   `llm/ModelRouter.kt` `answer`; `CLAUDE.md` decision 5). The opt-in remote model is sent only the
   question text, fixed instructions and the current time: no tasks, notes or name. The on-device
   answer model still gets `files.contextForPrompt()` and the name. Tasks must: never hand task
   content to Assistant for the answer path. If Q14 is approved, a "due today" summary may feed the
   on-device model only, and the remote path stays question-only.

8. **Other Assistant facts the spec had stale.**
   - `answeredWithoutTheModel` order (`assistant/VoiceSession.kt`): `LocalAnswers`, then `Arithmetic`,
     then the command cache, then the model. The pre-route of 4.12 step 5b sits before all of them
     and after the pending-send check.
   - The latency reference device is the owner's Samsung foldable (SM8850), not "Galaxy S26 Ultra"
     (`CLAUDE.md` decision 1).
   - `contextForPrompt()` now drops ticked-off `- [x]` tasks, so migrating them changes nothing for
     the answer model.
   - Tasks must: keep the keyword pre-route off pending-send replies, and re-run `bench/` routing
     (4.12 step 4) after the Assistant branch is rebased on this tree.
