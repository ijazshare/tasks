# Todoist developer surface and Android integration notes

Researched 2026-10-27. Primary source: https://developer.todoist.com/api/v1/ (single-page docs; I parsed the embedded OpenAPI 3.1 spec plus the rendered text). Android facts from /home/ijaz/assistant-dev/tasks-app/research/todoist-android-surface.txt (Todoist v12324, package-manager dump, app not decompiled).

## 1. Which API is current

- Todoist API v1 (base `https://api.todoist.com/api/v1/`) is the current API. Its "Migrating from v9" section says: "The Todoist API v1 is a new API that unifies the Sync API v9 and the REST API v2." The old Sync v9 and REST v2 docs "are still available for reference". The page I read gives no sunset date for either. Source: https://developer.todoist.com/api/v1/#tag/Migrating-from-v9
- Changes that matter: IDs are opaque strings (old numeric IDs are rejected for tasks, projects, sections, comments, reminders); endpoints are lowercase only; only the `api.todoist.com` host; cursor pagination on list endpoints (`results` plus `next_cursor`); the task `url` field was removed (format is `https://app.todoist.com/app/task/<id>`); errors are JSON. `/api/v1/ids_mapping/...` translates old to new IDs, up to 100 per call. The spec itself lists `/api/v1/id_mappings/{obj_name}/{obj_ids}`, so the migration prose and the path differ.
- `/api/v1/sync` still exists (incremental sync with `sync_token`, up to 100 commands per request). Filters can only be created or updated through `/sync`. REST only has delete for filters (`DELETE /api/v1/filters/{filter_id}`).
- Official SDKs: Python and TypeScript.

## 2. Data models (API v1)

Task (`GET /api/v1/tasks/{task_id}`): id, user_id, project_id, section_id (null), parent_id (null), added_by_uid, assigned_by_uid, responsible_uid, labels (array of label NAMES), deadline (object or null), duration (object or null, example `{amount:30, unit:"minute"}`), is_collapsed, checked, is_deleted, added_at, completed_at, completed_by_uid, updated_at, due (object or null), priority (1 normal to 4 urgent; the UI's p1 equals API 4), child_order, order_key, content, description, note_count (deprecated, always 0), day_order, completed_count, postponed_count.

Due object (spec types it as a free-form object; the properties below come from the "Due dates" section):
- `date`: RFC 3339. Three shapes. Full-day: `"2016-12-01"`. Floating with time: `"2016-12-06T12:00:00.000000"` (no Z, timezone null). Fixed timezone: `"2016-12-06T13:00:00.000000Z"` (stored in UTC) with `timezone` set. For recurring tasks it is the current iteration.
- `datetime`: NOT a field in v1. The datetime lives inside `date`. (The old v2 REST API had a separate `datetime`; the v1 docs and task example show none.) Tell the parser to branch on the length of `date`.
- `string`: human-readable form in the user's timezone, e.g. "every day at 12". Also the input for server-side parsing: send `{"string":"tomorrow"}` and the server fills `date`, `lang`, `is_recurring`.
- `timezone`: IANA name (e.g. `Europe/Madrid`) or null for floating and full-day. Used to compute the next recurrence.
- `is_recurring`: boolean.
- `lang`: language used to parse `string` (en, da, pl, zh, ko, de, pt, ja, it, fr, sv, ru, es, nl, fi, nb, tw ...).
- Example from the spec: `{"date":"2025-02-12","is_recurring":false,"lang":"en","string":"tomorrow"}`.
- Deadline (separate from due): `{date, lang}`. Source: https://developer.todoist.com/api/v1/#tag/Due-dates

Project: id, name, description, color, icon, parent_id, child_order, order_key, is_collapsed, is_archived, is_deleted, is_favorite, is_frozen, is_shared, inbox_project, view_style, created_at, updated_at, creator_uid, role, access, can_assign_tasks, can_comment, public_key, default_order.

Section: id, user_id, project_id, name, description, section_order, order_key, is_collapsed, is_archived, is_deleted, added_at, updated_at, archived_at.

Label: id, name, color, order, is_favorite. Tasks reference labels by name.

Filter (sync object): id, name, query (the filter query string, e.g. "priority 1"), description, color, item_order, order_key, is_deleted, is_favorite, is_frozen. Availability and count depend on plan.

Reminder (REST `/api/v1/reminders`): id, item_id (the task), notify_uid, is_deleted, is_urgent, type ("relative" or "absolute"), minute_offset (relative), due (absolute; fields date, string, lang, timezone, is_recurring). Create body: task_id, reminder_type, minute_offset, due, service ("email" or "push"), is_urgent, notify_uid. Reminders need a due date WITH time and depend on plan. Location reminders are a separate endpoint (`/api/v1/location_reminders`).

## 3. Quick Add (server-side natural language)

- `POST https://api.todoist.com/api/v1/tasks/quick`, JSON body. Source: https://developer.todoist.com/api/v1/#tag/Tasks/operation/quick_add_api_v1_tasks_quick_post
- Body: `text` (required, Quick Add syntax), `note` (added as a comment, ignored if plan lacks comments), `reminder` (natural-language, silently ignored if unparseable), `auto_reminder` (bool, default false), `meta` (bool, default false; adds a `meta` object showing how the text was parsed).
- Syntax: dates in natural language; `#Project` (`#My\ Project`); `/Section` (needs a project); `@label`; `p1`-`p4` or `!!1`-`!!4`; `+Assignee` (needs a project); `{deadline}`; reminders `!30m`, `!1h`, `!0mb`, `!14:00`; description after ` // ` at the end. Date parsing follows the account language.
- Returns: 200 with a JSON object. The docs only say "additionalProperties: true", and the response sample is empty. The prose implies a created task (plus `meta` when requested). I could not confirm the exact shape from the docs, so verify with one live call before relying on it. If a part fails to parse, the task is still created and the unparsed text stays in `content`.
- Needs the `task:add` scope or higher.

## 4. Webhooks, auth, limits, export

Webhooks (https://developer.todoist.com/api/v1/#tag/Webhooks): configured in the App Management Console. HTTPS only, no port in the URL. Events: item:added/updated/deleted/completed/uncompleted; note:added/updated/deleted; project:added/updated/deleted/archived/unarchived; section: same five; label and filter added/updated/deleted; reminder:fired. Payload: event_name, user_id, event_data, version, initiator, triggered_at, optional event_data_extra (item:updated carries old_item and update_intent). Headers: `X-Todoist-Hmac-SHA256` (base64 HMAC-SHA256 of the body keyed with client_secret), `X-Todoist-Delivery-ID`, User-Agent `Todoist-Webhooks`. The endpoint must return HTTP 200. Retry: after 15 minutes, at most 3 times. Delivery can be late, out of order or missing. Webhooks fire only for users who completed your app's OAuth flow, including the developer's own account. Implication: webhooks need a public HTTPS endpoint, which this offline-first assistant does not have.

Auth (https://developer.todoist.com/api/v1/#tag/Authorization):
- Personal API token from Settings > Integrations, sent as `Authorization: Bearer <token>`.
- OAuth: authorize at `https://app.todoist.com/oauth/authorize` (client_id, scope, state), exchange at `POST https://api.todoist.com/oauth/access_token`. New apps get 3600 s access tokens and rotating refresh tokens (`grant_type=refresh_token`). Also dynamic client registration (`/oauth/register`), client ID metadata documents with PKCE, `POST /api/v1/revoke`, `DELETE /api/v1/access_tokens`, `POST /api/v1/access_tokens/migrate_personal_token`.
- Scopes: `task:add` (add only, no reading), `data:read`, `data:read_write` (includes task:add and data:read), `data:delete`, `project:delete`, `backups:read`.

Limits (https://developer.todoist.com/api/v1/#tag/Request-limits): 1000 partial sync requests and 100 full sync requests per user per 15 minutes; up to 100 commands per sync request; 1 MiB POST body; 65 KiB headers; 15 s timeout for standard requests, 5 min for uploads. A 429 response exists, with `retry_after`/`Retry-After`. REST per-endpoint quotas are not stated in what I read. Plan limits (reminders, filters, comments) come from `user_plan_limits`.

Export and backup: `GET /api/v1/backups` lists daily backup archives (scope `backups:read`, `data:read_write` or a personal token; MFA token needed for the latter two on MFA accounts). `GET /api/v1/backups/download?file=...` redirects to a signed URL that lasts one minute (needs `data:read_write`). Per-project export as CSV: `GET /api/v1/templates/file?project_id=...&use_relative_dates=true`; import via `/api/v1/templates/import_into_project_from_file` and `create_project_from_file`. Full migration: pull everything with `/sync` (`sync_token=*`, `resource_types=["all"]`) or download the latest backup zip. Backups are also available in Settings > Backups.

## 5. Android deep links (public docs) and what the installed app exposes

Public scheme docs: https://developer.todoist.com/api/v1/#tag/Url-schemes (mobile and desktop sections).

Mobile (Android and iOS):
- Views: `todoist://`, `todoist://today`, `upcoming`, `profile`, `inbox`, `teaminbox`, `notifications`.
- Tasks: `todoist://task?id={id}` opens a task. `todoist://addtask?content=...&date=...&priority=1..4` (percent-encoded) opens the add-task view PRE-FILLED. The docs say it "will not automatically submit the task". Priority 4 is the most urgent.
- Also `todoist://projects`, `project?id=`, `labels`, `label?name=`, `filters`, `filter?id=`, and `todoist://search?query=` (Android only).
- Desktop adds `todoist://openquickadd?content=&description=` and `todoist://filters-labels`.

Installed Todoist v12324 (from todoist-android-surface.txt):
- `RouterActivity` handles `todoist` and `https` links (the deep-link entry, consistent with the docs above).
- `QuickAddItemAsTask` accepts `ACTION_SEND` `text/plain`. This is a share target that, by name, creates a task from shared text. I did not test whether it submits silently or opens a UI.
- `AddAsNoteActivity` (SEND text, image, audio, application): attach to a comment. `AddFileAsItemProxyActivity`: file as a task.
- `QuickAddItemActivity` (MAIN) plus `CreateItemTileService` (quick-settings tile) and `CreateQuickAddDialogShortcutActivity`. `RambleTileService` and `CreateRambleShortcutActivity`: Ramble voice capture (cloud-based, needs internet, no disable switch per help centre: https://www.todoist.com/help/articles/dictate-to-add-tasks-with-ramble-P1Raq7vVF).
- Content providers: `AppSliceProvider` (Android Slices, mostly read-only surface for the launcher/Assistant), `FileProvider` (share files), no exported data provider for tasks.
- Whether the activities are exported and require permissions is not shown in this dump; the dump only lists intent filters.

## 6. What an assistant app can do today (interim integration)

Can do:
1. Open the pre-filled add-task screen: `todoist://addtask?content=<encoded>&date=<encoded>&priority=N`. The user taps save. No network credential needed.
2. Send text to the share target (`ACTION_SEND text/plain`, package `com.todoist`) so the Todoist share flow handles capture.
3. Open views and objects by deep link (today, inbox, a project, a filter, search).
4. With a personal API token or OAuth `task:add`: `POST /api/v1/tasks/quick` creates a task from natural language on the server, in the user's language and timezone, and the response includes the created object (shape unverified). `data:read` allows reading tasks for "what is due today" answers (`GET /api/v1/tasks/filter`).
5. Closing, moving and rescheduling tasks via REST with `data:read_write`. Reminders via `/api/v1/reminders` (plan permitting).
6. Poll `/sync` incrementally to notice changes without a public endpoint.

Cannot do or caveats:
- Through intents or deep links there is no confirmation that a task was created. `addtask` only pre-fills; the share target returns no result to the sender; no callback or result code is documented.
- No documented intent for reading tasks, so reading needs the REST API (network, token).
- Offline: REST and Quick Add need internet. Natural-language parsing is server-side, so the offline-only voice constraint holds only for the speech-to-text step; the Todoist call itself is online. The assistant would need to hold a token, which touches the user's "reliable facts or decline" and token-storage rules.
- Webhooks need a public HTTPS endpoint (not feasible on-device). Polling `/sync` replaces them.
- Ramble and Slices expose no documented third-party API.
- Quick Add response shape and the share target's silent-vs-UI behaviour are untested. The user's own account on the Todoist plan decides reminders, filters and comments.
- Quick Add never fails on bad syntax: it creates the task with leftover text in `content`, so the assistant must pass `meta=true` and inspect the parse if it wants correctness.

Recommendation: start with the deep link `addtask` (zero credentials, user confirms) as the interim, and add `POST /api/v1/tasks/quick` with a personal token later if confirmed creation is wanted (that is the only route that returns a created-task object to verify).
