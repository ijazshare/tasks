# Offline task-dictation parsing (Kotlin/JVM, Android)

Provenance: first drafted from memory; fact-checked against the web on 2026-10-27 (GitHub API, Maven Central
metadata, Hugging Face API, vendor docs). Tags: [V] verified (source given), [U] unverified or contradicted-then-unresolved,
[C] corrected from the original draft. Section 2-5 design advice is the author's opinion, not web-checkable, except where tagged.

## 1. JVM date/time/recurrence parsers

| Library | Licence | Status (as of 2026-10-27) | Notes |
|---|---|---|---|
| Natty (joestelmach/natty) | MIT [C: draft said BSD-3] | Last release 0.13, 2017-01-26 (Maven Central); last commit 2017-01-26 on the release branch though the repo shows a push 2023-12-07; 169 open issues [V] | ANTLR 3.5.2 runtime + slf4j (incl. slf4j-log4j12) per its pom [V]; **no Joda-Time dependency** [C: draft was wrong]. Pure `java.util.Calendar`/`Date` output, so not Android-hostile on Joda grounds, but ANTLR runtime and log4j binding add weight; cold start is unmeasured here [U]. Returns best guess; recurrence is shallow [U]. Src: https://github.com/joestelmach/natty , https://repo1.maven.org/maven2/com/joestelmach/natty/0.13/natty-0.13.pom |
| Duckling (facebook/duckling) | BSD ("BSD License" file; GitHub reports NOASSERTION) [V] | Haskell; active: pushed 2026-03-15, 4.3k stars, last tagged release v0.2.0.0 2021-04-16 [C: "low maintenance" was too harsh, but no recent release] | Not runnable on Android. JVM-side artifacts exist (Clojure `wit/duckling`, `ongair/duckling` on Clojars) but could not confirm they are ports vs wrappers [U]. Use as spec/test-corpus source only. Src: https://github.com/facebook/duckling |
| Hawking (zoho/hawking) | **Likely GPL-3.0** [C: draft said Apache-2.0]: repo ships `licenses/gpl-3.0/` and no top-level LICENSE; GitHub shows no licence; Acknowledgement.txt says it uses Stanford CoreNLP (GPL) [U: not explicit] | Release 0.1.9 (2024-09-24, Maven Central com.zoho:hawking); last push 2025-11-04; 253 stars [V] | Java, ANTLR3 build, "inspired by Duckling" claim not checked [U]. GPL would be incompatible with a non-GPL app: treat as reading material only. Src: https://github.com/zoho/hawking |
| PrettyTime (ocpsoft/prettytime) | Apache-2.0 [V] | 5.0.9.Final on Maven Central (2024-06-28); master is 5.0.10-SNAPSHOT, last push 2025-09-29 [C: add versions] | Core module is formatting/relative-time. `prettytime-nlp` module **wraps Natty 0.13** (pom) so it inherits Natty's age [C]. README documents Android use: needs API desugaring if minSdk < 26 [V]. Src: https://github.com/ocpsoft/prettytime |
| chrono (wanasit/chrono) | MIT [V] | Very active: v2.10.3 on 2026-10-12, TypeScript [V] | Good reference for rules/refiners/tests. README points to a **Java port, wanasit/chrono-java**, but it is stale (last push 2014-10-25, MIT) [C: draft said no port known]. Other Kotlin option: `kwhen` (Kotlin Multiplatform NL date parser, io.github.pahinaa) [U: not evaluated]. QuickJS route adds an engine [U]. Src: https://github.com/wanasit/chrono , https://github.com/wanasit/chrono-java |
| Others | - | - | Stanford SUTime ships inside CoreNLP, which is GPL-3.0 (v4.6.0, 2026-09-25) [V: GitHub API]; SUTime page itself states no licence. HeidelTime GPL-3.0, last release 2.2.1 (2016-09-20), last push 2023-04-16 [V]. Python libs (parsedatetime, dateparser, recurrent) not JVM [U, not checked]. Footprint/licence unsuitable for Android. Src: https://github.com/stanfordnlp/CoreNLP , https://github.com/HeidelTime/heideltime |

Missed by the draft: `kwhen` and `chrono-java` above; Jchronic (Java port of Ruby chronic, https://clojars.org/com.mdimension/jchronic) exists but is old [U on activity].

Accuracy: none publish decline-aware accuracy. All are built to return a best guess, which is
the wrong failure mode for this app ("2 weeks from the third" -> a date, silently).

Case for a hand-written grammar:
- Scope is small and closed: dates (today, tomorrow, weekday names, "next X", "on the 5th", month+day,
  "in N units"), times (at 3, 3pm, quarter past, half past, noon), recurrence ("every day/weekday/
  Monday", "every N days/weeks", "every other week", "monthly on the 1st"). Maybe 150-300 rules.
- Whisper output is normalised English: numbers sometimes as words ("thirty minutes") or digits, so one
  number-word normaliser (zero..ninety-nine, hundred) front-loads everything.
- Declining is native: a parser that returns Parsed | Ambiguous | NoMatch can refuse on "next Friday"
  style ambiguities (offer both readings or ask) instead of silently picking.
- Zero dependencies, java.time only (core library desugaring on minSdk < 26), deterministic, unit-testable
  with a golden corpus (borrow cases from chrono/Duckling tests, which are the real value of those projects).
- Every match carries a source span, which feeds section 4's traceability check.
Cost: you own locale growth (English only for now) and edge cases; budget for a test corpus of a few hundred phrases.

## 2. Deterministic token grammar (Todoist quick-add style)

Todoist quick add: `#project`, `@label`, `p1..p4` priority, `/section`, `+assignee`, `//description`,
dates/recurrence recognised in free text, and quotes or `[ ]` to protect text from date parsing (draft said `@label`, `//description` and quote/`[ ]` protection) [C]: current Todoist help lists `%label` (`@` still works but is planned to be retired by end of 2026), `!` reminders, `p1`-`p3`, `+assignee` (shared projects only), `#project`, `/section`; `//` is not mentioned and quotes/brackets are NOT documented for blocking date parsing (you click the highlighted word to make it plain text; `{march 30}` adds a deadline). Src: https://www.todoist.com/help/todoist/features/use-task-quick-add-in-todoist-va4Lhpzz [V]. The quote-escape in this design is therefore our own invention, not Todoist behaviour.
Design for voice:
- Voice cannot say `#` or `@` reliably. Map spoken sigils: "project X", "label Y", "priority one/high", "section Z",
  and keep the symbol grammar for typed input. Both lower to the same AST.
- Pipeline: normalise (lowercase copy, number words -> digits, keep original offsets) -> tokenise with spans ->
  extract recurrence first ("every ..."), then durations/relative ("in 30 min"), then dates, then times,
  then priority/project/label tokens -> remainder is the title.
- Longest-match-first, non-overlapping spans; two candidates overlapping with different values = Ambiguous -> decline.
- Match project/label/section only against existing names (exact or small edit distance with a margin);
  never invent a project from speech. No match -> keep words in title and flag.
- Title = transcript minus consumed spans, trimmed; if empty, decline.
- Quote escape: spoken "literally ..." or typed quotes protect text (e.g. "call 'Every Day Is Monday' band").
- Result type: ParseResult(title, due, recurrence, project, labels, priority, spans, confidence flags), plus
  `unresolved: List<Span>` for words that looked structural but were not understood.

## 3. Splitting a ramble into tasks

Signals, strongest first:
1. Whisper sentence punctuation (`.`, `?`) - cheap, usually right at pauses, but Whisper adds/omits periods
   unpredictably and "base.en" is weaker than larger models.
2. Explicit cues at clause start: "and also", "also", "oh and", "then", "after that", "next", "another thing",
   "don't forget", "remind me to ... and ...", "first/second/third", "number one/two", "item", "next task".
3. List words: "first X, second Y", "X, Y and Z" (only if each part has its own verb or the app's list intent is stated).
4. Repeated imperative verb at clause start (call, buy, email, book, remind, send).
5. Long silence in audio timestamps (Whisper segment boundaries/word timestamps) as a tiebreaker, not primary.
Algorithm: split on sentence boundary -> split sentences on cue phrases -> merge fragments with no verb or that
only hold date/time into the neighbouring task (see "at 5" below) -> parse each segment independently -> show a confirm list.
Failure modes:
- Over-split: "buy milk and eggs" (one task); "call mum and tell her" (one task); "then" meaning sequence inside a task or time ("and then at 5").
- Under-split: Whisper drops punctuation on fast speech; run-ons with no cue.
- Orphan modifiers: "buy milk. Tomorrow at 5." The date belongs to the previous task; attach trailing time/date-only segments backwards (or ask).
- Distributed modifiers: "call Sam and Ali tomorrow" - one date for two tasks? Decline to distribute; ask.
- Whisper hallucination/repetition on silence ("Thank you.") - drop segments of only filler.
- Self-correction: "no wait, make that Friday" - detect "no wait / actually / scratch that / I mean" and decline or surface it rather than apply.
- Conjoined recurrence: "every Monday and Thursday" must not split at "and".
Policy: when unsure, keep as one task and show it for confirmation; never drop words.

## 4. When a small on-device model is worth it

Use deterministic grammar first. Model is only a fallback for: title cleanup, which-clause-is-which on messy rambles,
paraphrased intents the grammar can't enumerate, and project fuzzy-matching with context.
- FunctionGemma 270M (Google; Gemma terms, HF tag `license:gemma`, gated: you must accept the licence) [V]: built on Gemma 3 270M, released 2025-10-08 on HF as `google/functiongemma-270m-it`, 32k context, text only, 268M params, BF16 safetensors 0.54 GB [V]. Google says it is meant to be fine-tuned: on its Mobile Actions benchmark base scores 58% and fine-tuned 85% [V]. Uses its own chat/tool format (`<start_function_call>call:name{...}`), not JSON, so GBNF would need a custom grammar [V: chat_template]. Claim "weak on date arithmetic" is not documented anywhere I found [U]. Quantised size on phone not checked [U]. Src: https://huggingface.co/google/functiongemma-270m-it , https://blog.google/technology/developers/functiongemma/
  Good as a router/slot-filler with a closed schema, not a date calculator.
- Gemma 3 1B + llama.cpp GBNF: GBNF grammar-constrained decoding is real and documented, plus a JSON-schema-to-GBNF converter for a *subset* of JSON Schema (unsupported features such as `patternProperties`, `uniqueItems`, `prefixItems` are skipped or broken; patterns must be `^...$`) [V]. README warns grammars have performance gotchas (issue #4218) and to use `x{0,N}` rather than repeated `x?` [V]. Src: https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md . 1B Q4 size of ~0.5-0.8 GB not verified [U]. Per-summon load cost matters on-phone (the repo's own router
  bench already found 4B too slow to load on the phone; 1B tolerable). Grammar guarantees shape, not truth.
- Prefer having the model output only spans/enums, not computed values: e.g. {"title_span":[a,b],"date_span":[c,d],"recurrence_span":[e,f]}
  and let the grammar parse the spans. The model segments; deterministic code interprets.
Deterministic validation (the decline gate), applied to every model output:
1. Schema/GBNF-valid, else reject.
2. Title: must be a substring (after normalisation) or a subsequence of transcript tokens; no new content words
   (allow a small stop-word/verb-inflection whitelist).
3. Date/time/recurrence: the claimed value must be re-derived by running the deterministic parser on the cited
   transcript span; if parser output != model output, or span absent -> reject (do not "trust the model").
4. Project/label: must exist in the user's list, and the name must appear (fuzzily) in the transcript.
5. Numbers: every digit/number-word in the output appears in the transcript.
6. Priority/flags: only if a trigger word appears.
7. Coverage: unexplained transcript words must be in title or whitelisted filler; otherwise flag.
8. Any failure -> decline and show the transcript with a "didn't catch that" prompt; never auto-save a guess.
9. Run at temperature 0, fixed seed; log accepted/rejected for the golden corpus.
Verdict: model adds value mainly for splitting and title cleanup; keep dates/times 100% in the grammar.

## 5. java.time pitfalls for reminders

- Store intent, not just an instant: for "9am every day", keep (LocalTime, ZoneId, recurrence rule) and recompute the next
  ZonedDateTime; storing a UTC instant for recurring items drifts one hour across DST.
- Gap (spring forward): 02:30 doesn't exist. `ZonedDateTime.of(ldt, zone)` shifts forward by the gap length; decide policy
  (fire at the first valid instant after the gap) and test it.
- Overlap (fall back): 01:30 occurs twice. Default picks the earlier offset; use `withLaterOffsetAtOverlap()` deliberately. Don't fire twice.
- Date arithmetic: use `plus(days)` on ZonedDateTime/LocalDate, not `plus(Duration.ofHours(24))`, for "tomorrow"/"every day"; "in 30 min" is Duration, correctly an exact elapsed time.
- Zone changes: travelling or user changing zone. Choose floating (local wall-clock, "9am wherever I am") vs fixed zone; store a flag.
  Observe `ACTION_TIMEZONE_CHANGED`/`ACTION_TIME_CHANGED` and re-schedule alarms.
- Zone rules change (tzdata updates): store zone ID (e.g. Europe/London), never a fixed offset; offsets only as a cache.
- Android scheduling: use AlarmManager `setExactAndAllowWhileIdle`/`setAlarmClock`; `SCHEDULE_EXACT_ALARM` (Android 12+) is NOT pre-granted to fresh installs targeting API 33+ from Android 14 and can be revoked by user/system; `USE_EXACT_ALARM` is granted at install but only Play-permitted for calendar/alarm-clock apps [V: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms]; a to-do reminder app must handle the denied case (check `canScheduleExactAlarms()`, ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED); alarms are cleared on reboot -> reschedule on BOOT_COMPLETED; Doze/OEM killers.
- `Clock` injection for tests; never call `LocalDate.now()` inside the parser. Parse relative to a supplied "now" and zone.
- Month ends: "monthly on the 31st" -> clamp or skip (`YearMonth.atEndOfMonth`), decide and document. "Every week" across DST is fine with LocalDateTime recompute.
- Weekday semantics: "Friday" said on Friday = today or next week? "next Friday" ambiguity: classic decline case. Week start locale-dependent (`WeekFields`).
- Midnight/noon: "12am", "12pm", and "midnight tonight" (which date?); bare "at 5" -> am/pm ambiguity: decline or apply an explicit rule (e.g. nearest future) and show it.
- Minute-only precision; "in 30 min" computed from the moment of dictation end, not of save.
- minSdk < 26 needs core library desugaring (`coreLibraryDesugaring`) for java.time; otherwise ThreeTenABP.

## Verification log (2026-10-27)
URL check (HTTP): all Sources below return 200 except https://nlp.stanford.edu/software/sutime.html (no response from this host; SUTime docs are at https://stanfordnlp.github.io/CoreNLP/sutime.html) [C]; Todoist URL redirects to /help/todoist/features/...; https://ai.google.dev/gemma redirects to https://deepmind.google/models/gemma/ [C]; https://huggingface.co/google is a generic org page, better: https://huggingface.co/google/functiongemma-270m-it [C].
Not checked: java.time gap/overlap behaviour (Section 5, standard Javadoc semantics, page exists), core library desugaring wording, Whisper behaviour claims (Section 3), base.en comparison, numeric footprints. Those remain [U].

## Sources
- Natty: https://github.com/joestelmach/natty
- Duckling: https://github.com/facebook/duckling
- Hawking: https://github.com/zoho/hawking
- PrettyTime: https://github.com/ocpsoft/prettytime
- chrono: https://github.com/wanasit/chrono
- Stanford SUTime: https://stanfordnlp.github.io/CoreNLP/sutime.html ; HeidelTime: https://github.com/HeidelTime/heideltime
- Todoist quick add: https://www.todoist.com/help/todoist/features/use-task-quick-add-in-todoist-va4Lhpzz
- FunctionGemma / Gemma: https://huggingface.co/google/functiongemma-270m-it ; https://deepmind.google/models/gemma/
- llama.cpp grammars: https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md
- java.time ZonedDateTime (gap/overlap): https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/ZonedDateTime.html
- Android exact alarms: https://developer.android.com/develop/background-work/services/alarms ; https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- chrono-java: https://github.com/wanasit/chrono-java ; CoreNLP: https://github.com/stanfordnlp/CoreNLP
- Core library desugaring: https://developer.android.com/studio/write/java8-support
