# Todoist user-facing features (public sources only)

Researched 2026-10-27 from todoist.com help pages via web fetch/search. No app code inspected.
Legend: [C] confirmed on a Todoist page, [S] from search summary only, [U] unconfirmed / not found.
Fetched pages were summarised by a small model, so quoted syntax is faithful to that summary, not a raw page copy.

## 1. Ramble (voice brain dump)
Source: https://www.todoist.com/help/articles/dictate-and-add-tasks-with-ramble-P1Raq7vVF ; https://www.todoist.com/help/todoist/product-updates/turn-your-scattered-thoughts-into-clear-tasks-ramble-jan-21-HhmP8ue8R
- Start [C]: waveform icon in Quick Add or sidebar (web/desktop; Shift+Q in app; global Option+Shift+R mac, Alt+Shift+R Windows; menu bar/tray). Mobile: waveform in Quick Add, home/lock-screen widget, iOS control/Action button, Siri shortcut, Android quick setting tile, Android app shortcut and 1x1 widget (widget appears in picker only after one completed session; source: https://www.todoist.com/help/todoist/features/use-a-todoist-widget-on-your-android-device-632pZA via search). Wear OS beta: Ramble button.
- Extracts [C]: task name, date/time, project, section, priority, label, description; deadlines and durations on paid plans. Not supported: sub-tasks, custom reminders, urgent reminders. Tasks go to Inbox unless started inside a project/label or a project/section is spoken.
- Edit/confirm [C]: tasks appear in a live preview. Say "Actually, I meant..." to edit, "Remove that" to delete a suggestion. Finish with "that's all" / "that's it", the Add tasks button, or checkmark.
- Cloud vs device [C/U]: needs internet, does not work offline, uses LLMs for transcription and extraction; usage data may be stored temporarily on servers. Doist states audio is not stored or used for training [S]. A press release names Google Gemini on Vertex AI [S, not on help pages]. Explicit "cloud, not on device" wording not found; behaviour implies cloud.
- Languages [C]: 40 listed: Arabic, Bengali, Bulgarian, Chinese (simp/trad), Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hebrew, Hindi, Hungarian, Icelandic, Indonesian, Italian, Japanese, Korean, Latvian, Lithuanian, Norwegian, Polish, Portuguese, Romanian, Russian, Serbian, Slovak, Slovenian, Spanish, Swahili, Swedish, Thai, Turkish, Ukrainian, Vietnamese. Earlier posts said 38.
- Limits [C]: Free/Beginner/Pro Legacy 10 sessions/month (shared with Task Capture Assist; counts only if a task is found); Pro/Business unlimited. No off switch. Not usable from browser extensions. 300 active tasks per project cap.

## 2. Email
- Forward to project [C]: project menu > Manage data (mobile: "Email tasks to this project") gives a per-project address. Subject = task name, body attached as a comment. Source: https://todoist.com/help/articles/205749772
  - Details above signature: `date tomorrow` (the word "date" is required), `@label` (must exist), `p1`-`p3`, `+Firstname\ Lastname` (shared projects). Cannot forward to a section.
  - Per-task address ("Add comments via email"): forwarded mail becomes a comment; typed text before it is the comment.
  - Email Assist (Pro/Business): extracts date/time, rewrites task name, adds meeting links. Attachment size limits: 5 MB Beginner, 25 MB Pro personal (https://todoist.com/help/articles/usage-limits-in-todoist-e5rcSY).
- Gmail [C]: Chrome extension (Todoist for Chrome; standalone Gmail extension retired 30 Jun 2026) and Google Workspace add-on ("Todoist for Gmail": open email > Todoist icon > Add task; editable project/name/due/label/priority). Not iPadOS/Android tablets. Task links back to the email in browser Gmail only. Source: https://www.todoist.com/help/todoist/integrations/use-gmail-with-todoist-YMVdMcAx
- Outlook [C]: add-in via Get add-ins. "Add to Todoist Inbox" one click, or "Add to Todoist" with project/name/due/labels/priority. Pro/Business: email summary in description, subtasks from action items, duration. Needs Outlook.com/Live/Hotmail/O365/cloud Exchange, not IMAP/POP3. Link opens in Outlook web only. Source: https://todoist.com/help/articles/360013412679

## 3. Quick Add syntax
Source: https://www.todoist.com/help/articles/use-task-quick-add-in-todoist-va4Lhpzz ; dates https://todoist.com/help/articles/205325931
| Element | Syntax / example | Status |
|---|---|---|
| Project | `#Work` | C |
| Section | `#Work /Admin` (after project) | C |
| Label | `%email` ; `@email` still works but to be retired by end of 2026 | C |
| Priority | `p1` `p2` `p3` (p4 is default/no priority; Quick Add page does not list p4 explicitly) | C/U for p4 token |
| Assignee | `+Lucile` (shared project only, put `#project` first) | C |
| Deadline | `{march 30}`, `{next Friday}` (Pro/Business) | C |
| Reminder | `!14:00`, `!30 min before`, `!3pm`, `!2h`, `!30mb`, `!tomorrow` (Pro/Business) | C |
| Date/time | `tomorrow at 4 PM`, `in 5 days`, `+5 days`, `in 2 hours`, `28 days after 21 July`; "in the morning"=9am, "afternoon"=12pm, "evening"=7pm; times need colon `13:00` | C |
| Recurrence | `every day`, `every weekday`, `every other fri`, `every mon, fri at 20:00`, `every 1st wed jan, 3rd thu jul`, `every last workday at 3pm`, `every 2, 15, 27`, `every 3rd Tuesday starting Aug 29 ending in 6 months`, `everyday for 3 weeks`, `every 14 days 7am starting 1 Nov` | C |
| `every` vs `every!` | `every 3 months` keeps original cycle; `every! 3 hours` reschedules from completion date; `after 10 days` becomes `every! 10 days` | C (https://todoist.com/help/articles/360000636289) |
| Duration | `Write paper for 1h`, `Today 21:00 for 2h`, `Every Monday at 16:00 for 1h15m`, range `9:30-10:00`; Pro/Business, max 24 h, needs date+time | C (https://todoist.com/help/articles/9281236249628) |
Only one `every` per recurring phrase.

## 4. Organization
- Projects, sub-projects, sections, labels, filters [S]: glossary https://www.todoist.com/help/articles/todoist-glossary-cA60laWMH describes sub-projects as nested projects, sections as groups within a project, labels as cross-project tags, filters as saved searches. Limits [C]: 300 active tasks/project; labels 500/account, 100/task; filters 3 Beginner, 150 Pro; Beginner 5 active personal projects (https://todoist.com/help/articles/usage-limits-in-todoist-e5rcSY).
- Priorities [S]: p1 highest to p4 lowest/none. Dedicated "Introduction to priorities" article not read: U for detail.
- Filter query language [C], https://www.todoist.com/help/articles/introduction-to-filters-V98wIH : `|` OR, `&` AND, `!` NOT, `%label`, `#project`, `##project` incl. sub-projects, `/section`, `()` grouping, `,` separate lists, `\` escape, `*` wildcard. Examples: `(today | overdue) & #Work`, `today & p1`, `p1 & overdue, p4 & today`, `#Homework & tomorrow & !%languages`. Also `date before: May 5`, `due after: sunday`, `no date`, `no time`, `search: meeting`, `assigned to: me`, `assigned by: others`, `shared`, `created after: -365 days`, `workspace: Doist`, `deadline: December 31`, `deadline before`, `deadline after`, `no deadline`.
- Deadlines vs due dates [C]: date = when you plan to work; deadline = hard cutoff, not for recurring tasks, Pro/Business. https://www.todoist.com/help/todoist/features/introduction-to-deadlines-in-todoist-uMqbSLM6U
- Durations: see section 3.

## 5. Reminders
Source: https://todoist.com/help/articles/205348301 ; https://todoist.com/help/articles/8993625039132
- Relative/automatic [C]: added when a task gets date+time; lead time set in Settings > Reminders. Custom "Before task" tab or `!30mb`.
- Absolute [C]: custom reminder at a specific date/time; recurring reminder e.g. `every Tuesday 7:00` (a custom reminder on a recurring task fires once).
- Location [C/U]: Android and iOS only; choose arrive or leave, radius on map; created on web but fires on mobile. Plan requirement not confirmed from a Todoist page (third party says paid): U.
- Multiple custom reminders and `!` shortcuts need Pro/Business. Snooze only on mobile.

## 6. Completion, undo, comments, templates, activity log
- Completion/undo [C]: tick circle; Undo toast appears bottom-left for a few seconds and restores the prior date; after that no revert via the toast. Recurring: "Complete forever" in task menu. Completed tasks viewable via the Completed tasks display option and un-completable in project [S]. https://todoist.com/help/articles/complete-a-task-with-a-recurring-date-dmI6SVqdP
- Comments/attachments [C]: task comment 15,000 chars; 1 attachment per comment; size caps by plan (5 MB Beginner team, 100 MB Business) per usage-limits page. A Chinese-locale copy supplied the 5/100 MB numbers; treat as moderately confident.
- Templates [S]: export/import project as CSV (three-dot menu > Import from CSV / Export, UTF-8, max 300 tasks, no undo after import); 60+ premade templates at todoist.com/templates; 100 templates limit. https://todoist.com/help/articles/208821185
- Activity log [S]: avatar > Activity log; completed tasks shown there cannot be edited or uncompleted there; history 1 week Beginner, full on paid. Restore deleted tasks: not found (U). Backups exist on paid plans (https://todoist.com/help/articles/115001799989).

## 7. Android surfaces, assistant, calendar
- Quick Settings tile [C]: "Add task" tile, drag in from tile editor (some steps Android 13+). https://todoist.com/help/articles/213282965
- Widgets [C]: task list widget (plus icon), "Add task" shortcut widget, Ramble 1x1 widget. https://todoist.com/help/articles/206408179
- Google Assistant [S]: https://todoist.com/help/articles/115000056165 : "Add [task] to my list on Todoist" (Inbox); "Add [task] to my [project] list on Todoist"; with a date. Unknown project goes to Inbox. Known issue: Assistant may only ask "Which list do you want to add?". Not on iOS, smart devices, Wear OS. "App Actions" by that name and Gemini replacement: U, not found.
- Calendar sync [C]: https://todoist.com/help/articles/115003128085 : Settings > Calendars, connect Google or Outlook (one provider). Events show read-only in Today/Upcoming. "Sync tasks to calendar" creates a "Todoist" calendar; task name/date/time/duration/completion/deletion go to calendar; name/date/time edits come back; new calendar events do not become tasks. Needs date, time and duration unless "Sync all-day tasks". Unsupported recurrences (incl. `every!`) become one-off events. Calendar feed (iCal) exists but not read: U (article "Add a Todoist calendar feed").

## Not confirmed
Exact p4 Quick Add token; "Introduction to priorities" article; location-reminder plan; Ramble cloud wording; App Actions; deleted-task restore; iCal feed details; sub-project depth limit.
