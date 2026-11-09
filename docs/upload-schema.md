# Data-sharing upload schema (v2)

Sharing is **off by default** ("Share typing data to improve autocorrect" in Settings → Privacy and data). When it is
on, the keyboard queues one scrubbed, word-level event per correction decision in `filesDir/upload/corrections.jsonl`
(`log/Corrections.kt` builds them, `App.corrections` is the queue) and a WorkManager job (every ~6 h, Wi-Fi only unless
that setting is off, battery not low) POSTs new queue lines to
`https://autocorrect.mostlime.hackclub.app/api/v1/upload`. **Upload preview** in the same screen shows the last 20
queued events verbatim. Turning sharing off clears the queue. **Delete my uploaded data** sends
`DELETE /api/v1/install/{install_id}`, clears the queue and local log and switches to a new random `install_id`; if the
call fails it stays pending and the worker retries it before any upload.

The local event log (`filesDir/events/events.jsonl`, used for Settings → Stats and "Export local data") stays on the
phone and is never uploaded.

Client handling of responses (`log/UploadClient.kt`): 2xx advances; 408 / 429 / 5xx / network error retries later
(honoring `Retry-After`); 413 halves the batch; any other 4xx skips the batch.

The rest of this file is the platform-neutral spec, shared with the Mac app and the server (`collect/DATA.md` in the
autocorrect repo).

What the Keylime keyboards (Android and Mac) upload when a user turns on sharing, how it's scrubbed, how long it's
kept, and how it's exported for training. This file is the contract for both apps and the server
(`server.py` enforces it; anything outside it is rejected). HTTP details (status codes, limits, delete) are in
`API.md`.

## Principles

- **Opt-in, off by default.** Nothing is queued or sent until the user turns sharing on. Only decisions made after
  that are sent (no backfill). Turning it off stops queueing and uploading.
- **One event per correction decision, one word at a time.** No sentences, no field contents, no keystrokes or touch
  points, no app or package names, no timestamps finer than a day.
- **Scrubbed on the device**, then checked again by the server with the same rules.
- **Visible.** Settings has an "Upload preview" that lists the last ~20 queued events exactly as they will be sent.
- **Deletable.** "Delete my uploaded data" removes everything stored for the install's random ID, clears the local
  queue and switches to a new random ID.

## Envelope

`POST /api/v1/upload`, `Content-Type: application/json`

```json
{"schema": "autocorrect-events/2", "install_id": "<random UUID>", "app_version": "0.1.90", "events": [ ... ]}
```

Exactly these four keys. `install_id` is the random per-install ID the app already makes (`UUID.randomUUID()` on
Android, `UUID()` on Mac); it is not tied to the device, account or OS. Up to 500 events per request.

## Event

```json
{"v": 2, "id": "3f1c2b9e8a474d2e9b1a0c5e6f7a8b9c", "day": "2026-11-09", "type": "correction",
 "platform": "android", "app_version": "0.1.90", "model": "m2s_int4",
 "kind": "spelling", "typed": "teh", "suggestion": "the", "alternatives": [{"w": "tea", "rank": 1}],
 "outcome": "auto_applied_kept", "margin": "4-8", "before": ["see", "you"], "after": ["soon"]}
```

| field | required | rule |
|---|---|---|
| `v` | yes | `2` |
| `id` | yes | random per event, `[A-Za-z0-9_-]{8,64}` (a UUID). Only used so a retried batch isn't stored twice |
| `day` | yes | `YYYY-MM-DD`, UTC date of the decision. Nothing finer |
| `type` | yes | `"correction"` |
| `platform` | yes | `android` or `mac` |
| `app_version` | yes | `[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}` |
| `model` | yes | model id from the manifest (`m2s_int4`, `block4`, `none`, ...), `[a-z0-9][a-z0-9_.-]{0,31}` |
| `kind` | yes | `spelling`, `real_word`, `grammar`, `punctuation` (below) |
| `typed` | yes | the word as typed (case kept). One token |
| `suggestion` | yes, except `missed` | the top fix offered or applied (rank 0). One or two tokens (`alot` → `a lot`). Never equal to `typed` |
| `alternatives` | no | up to 3 more fixes that were offered, `[{"w": word, "rank": 1..9}]`, ranks increasing, words distinct. Ranks are positions in the app's ranked list (gaps mean a scrubbed word was left out) |
| `outcome` | yes | below |
| `picked_rank` | only for `suggestion_picked` | rank of the word the user picked: 0 = `suggestion`, otherwise one of the `alternatives` ranks |
| `final` | only for `manual_fix` and `missed` | what the user changed the word to. Never equal to `typed` or `suggestion` |
| `margin` | no | how much the model preferred the suggestion over the word as typed, bucketed: `0-1`, `1-2`, `2-4`, `4-8`, `8+` (nats; `8+` also when the typed word wasn't a candidate at all) |
| `before` | no | at most 2 words right before the word, lowercased, oldest first |
| `after` | no | at most 1 word right after the word, lowercased |

No other field is allowed. An event with an unknown field is rejected (`unknown_field`), so a client can't send more
than this by accident.

### Outcomes

| outcome | meaning |
|---|---|
| `auto_applied_kept` | the keyboard replaced the word and the user kept it |
| `auto_applied_undone` | the keyboard replaced the word and the user reverted it to exactly what they typed (backspace undo, picking the original, Mac undo) |
| `suggestion_picked` | the user picked one of the offered fixes (`picked_rank`): from the strip / pill / underline menu, or an alternative after an auto fix |
| `suggestion_ignored` | a fix was offered (underline / chip) and the user left the word as typed until it went away, or tapped "Ignore" on a non-spelling flag |
| `not_a_typo` | the user said the flagged word is right ("Not a typo"; it joins the personal dictionary) |
| `manual_fix` | after an auto fix was undone, the user retyped that same word slot into something else (`final`) |
| `missed` | the user fixed a word the keyboard never flagged (`final`). Optional; only sent where it's cheap to detect for sure. Neither app sends it yet |

### Kinds

Decided on the device from `typed` and `suggestion`:

- `grammar`: a confusable swap between real words chosen from the following words (to/too, their/there, your/you're,
  its/it's, then/than, a/an, ...): the "rewind" fixes.
- `punctuation`: the letters are the same and only apostrophes, hyphens, accents or capitals differ (`dont` → `don't`,
  `im` → `I'm`, `cafe` → `café`).
- `real_word`: the typed word is in the lexicon or personal dictionary but was changed to a different word
  (`form` → `from`).
- `spelling`: everything else (the typed word isn't a known word).

## Scrubbing (on the device, before an event is queued)

A **token** is allowed only if it is 1-20 characters, has at least one letter, and contains only letters, combining
marks, `'`, `’`, `-` and `.`. So anything with a digit, `@`, `://`, a space, a slash or other symbols is not a token.

1. **Field check.** Nothing is recorded in password (all variants), email, URL, number, phone, date/time, raw or
   incognito (`IME_FLAG_NO_PERSONALIZED_LEARNING`) fields on Android. On Mac: nothing in secure (password) fields,
   while secure input is on, or in excluded apps (terminals, editors and password managers by default); the Mac
   can't tell email / URL / number fields apart, but the token rule below still drops emails, links and numbers.
   Such a decision never becomes an event.
2. **The word itself.** If `typed` isn't a single allowed token, or `suggestion` / `final` aren't one or two allowed
   tokens, the whole event is dropped.
3. **Alternatives.** Each alternative that fails the token rule is left out (its rank is kept, so ranks may skip).
   If `picked_rank` points at a left-out word, the event is dropped.
4. **Context.** Take the 2 whitespace-separated tokens right before the word and the 1 right after it, from the same
   line. Trim punctuation from their ends, lowercase them, then drop every one that fails the token rule (checked on
   the untrimmed token too, so `https://x.y` and `me@x.com` are dropped, not trimmed into something allowed). Dropped
   tokens are not replaced by words further away.
5. **Quality guard** (the same one the personal dictionary uses):
   - accidental backspace: an auto fix reverted with backspace and then retyped as the same fix is noise; no event.
   - probation words: if `typed` (or `final`) is an auto-learned word still on probation, no event.
   - a revert whose result isn't known (the user moved the cursor away before finishing the word): no event.

The server re-checks rules 2-4 (tokens, lowercase context, lengths, counts) and rejects violations per event.

## Storage and retention

- Server: SQLite on lime's Hack Club Nest container (`/var/lib/autocorrect-collect/collect.db`). Each event is stored
  once per `(install_id, id)` with the validated JSON body. For v2 uploads the server's own times (`received_at`,
  install `first_seen` / `last_seen`) are stored as the start of the UTC day.
- IP addresses are used only for in-memory rate limits; never stored or logged. No read endpoint; exports are made
  over ssh with `admin.py`.
- Kept until the install deletes it ("Delete my uploaded data" → `DELETE /api/v1/install/{id}`) or the maintainer
  deletes it. There is no automatic expiry yet.
- On the device: the queue (`filesDir/upload/corrections.jsonl` on Android, `~/Library/Application Support/Autocorrect/upload/corrections.jsonl`
  on Mac) holds only scrubbed v2 events, is trimmed after upload, capped in size, and cleared by "Delete my uploaded
  data" or by turning sharing off.
- The old v1 schema (`autocorrect-events/1`: 120 characters of context, touch points, candidate scores) is retired:
  the server answers 410 `schema_retired` (set `COLLECT_ACCEPT_V1=1` to accept it again). Rows stored before v2 stay in
  the database until deleted; `admin.py export --type autocorrect` etc. still reads them, the pair exports don't.

## Export formats (`admin.py`, run on the Nest container)

`autocorrect-collect export-sft -o sft.jsonl [--since YYYY-MM-DD]`: one line per usable event:

```json
{"typed": "recieve", "target": "receive", "before": ["to"], "after": [], "kind": "spelling", "changed": true,
 "source": "suggestion_picked", "platform": "android", "model": "m2s_int4"}
```

| source outcome | target |
|---|---|
| `auto_applied_kept` | `suggestion` |
| `suggestion_picked` | the picked word |
| `manual_fix`, `missed` | `final` |
| `auto_applied_undone`, `not_a_typo` | `typed` itself (`changed: false`: "leave this word alone" examples) |
| `suggestion_ignored` | no pair (not a clear verdict) |

`autocorrect-collect export-pref -o pref.jsonl [--since YYYY-MM-DD]`: one chosen / rejected pair per usable event:

```json
{"typed": "teh", "chosen": "tea", "rejected": "the", "before": ["a", "cup"], "after": ["please"],
 "kind": "spelling", "source": "suggestion_picked", "platform": "mac", "model": "m2s_int4"}
```

| source outcome | chosen | rejected |
|---|---|---|
| `suggestion_picked`, rank 0 | `suggestion` | `typed` |
| `suggestion_picked`, rank k > 0 | the picked alternative | `suggestion` |
| `auto_applied_undone`, `not_a_typo` | `typed` | `suggestion` |
| `manual_fix` | `final` | `suggestion` |
| `auto_applied_kept` | `suggestion` | `typed` (weak: weight by `source`) |
| `suggestion_ignored`, `missed` | no pair | |

`autocorrect-collect export --type correction` dumps the raw v2 events (plus `install_id`, `received_at`) for anything
else. `autocorrect-collect stats` shows totals, including v2 counts by kind, platform and outcome.
