# Data-sharing upload schema (v3)

Sharing is **off by default** ("Share typing data to improve autocorrect" in Settings → Privacy and data). When it is
on, the keyboard queues one scrubbed, word-level event per correction decision in `filesDir/upload/corrections.jsonl`
(`log/Corrections.kt` builds them, `App.corrections` is the queue) and a WorkManager job (every ~6 h, Wi-Fi only unless
that setting is off, battery not low) POSTs new queue lines to
`https://autocorrect.mostlime.hackclub.app/api/v1/upload`. **Upload preview** in the same screen shows the last 20
queued events exactly as they will be sent. Turning sharing off clears the queue. **Delete my uploaded data** sends
`DELETE /api/v1/install/{install_id}`, clears the queue and local log and switches to a new random `install_id`; if the
call fails it stays pending and the worker retries it before any upload.

**Palantir Mode** (same screen, a separate switch that is off by default and greyed out while sharing is
off) adds `ctx_before` / `ctx_after` to new events: the rest of the sentence around the corrected word, clipped and
scrubbed by `Ctx` in `log/Corrections.kt` (rules below, "Sentence context"). `CorrectionLog` reads the switch for every
event, `UploadWorker` strips context from queued events if it is off when a batch is sent, the preview shows the same
stripped lines, and switching it off rewrites the queue without context. Switching sharing off switches it off too.
`FieldPolicy` decides per field: `noUpload` (sensitive fields and the built-in list of password managers,
authenticators and terminals, `FieldPolicy.EXCLUDED_APPS`) means no event at all; `noContext` (also name, address,
filter, phonetic and no-suggestion fields) means events without sentence context.

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
- **One event per correction decision, one word at a time.** No field contents, no keystrokes or touch points, no app
  or package names, no timestamps finer than a day. No sentences either, unless the user also turns on the second
  switch below.
- **Sentence context is a second, separate opt-in** ("Palantir Mode"), off by default and only available
  while sharing is on. With it, an event also carries the rest of the sentence the word was in, clipped and scrubbed
  on the device (see "Sentence context"). Turning it off removes the context from everything still queued; turning
  sharing off turns it off too.
- **Scrubbed on the device**, then checked again by the server with the same rules.
- **Visible.** Settings has an "Upload preview" that lists the last ~20 queued events exactly as they will be sent
  (with the sentence context if, and only if, that switch is on).
- **Deletable.** "Delete my uploaded data" removes everything stored for the install's random ID (sentence context
  included: it is part of the event), clears the local queue and switches to a new random ID.
- **Expiring.** The server deletes every correction event 90 days after it was received.

## Envelope

`POST /api/v1/upload`, `Content-Type: application/json`

```json
{"schema": "autocorrect-events/3", "install_id": "<random UUID>", "app_version": "0.1.90", "events": [ ... ]}
```

`autocorrect-events/3` is v2 plus the two optional sentence-context fields; the server still accepts
`autocorrect-events/2` from older apps (same events, no context allowed). Exactly these four keys. `install_id` is the random per-install ID the app already makes (`UUID.randomUUID()` on
Android, `UUID()` on Mac); it is not tied to the device, account or OS. Up to 500 events per request.

## Event

```json
{"v": 3, "id": "3f1c2b9e8a474d2e9b1a0c5e6f7a8b9c", "day": "2026-11-09", "type": "correction",
 "platform": "android", "app_version": "0.1.90", "model": "m2s_int4",
 "kind": "spelling", "typed": "teh", "suggestion": "the", "alternatives": [{"w": "tea", "rank": 1}],
 "outcome": "auto_applied_kept", "margin": "4-8", "before": ["see", "you"], "after": ["soon"],
 "ctx_before": "I think we should see you", "ctx_after": "soon, around <num> ok?"}
```

The last line (`ctx_before` / `ctx_after`) is only there when the user turned on "Palantir Mode".

| field | required | rule |
|---|---|---|
| `v` | yes | `3` (apps since v3), or `2` (older apps, and events queued before an update). A `2` event can't carry `ctx_*`; an `autocorrect-events/2` upload can only hold `2` events |
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
| `ctx_before` | no, `v` 3 only, second opt-in only | the part of the word's sentence before it: at most 24 tokens joined by single spaces, scrubbed ("Sentence context") |
| `ctx_after` | no, `v` 3 only, second opt-in only | the part of the word's sentence after it: at most 12 tokens, same rules |

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
   incognito (`IME_FLAG_NO_PERSONALIZED_LEARNING`) fields on Android, or in any field of a password manager,
   authenticator or terminal app (a built-in package list, `FieldPolicy.EXCLUDED_APPS`). On Mac: nothing in secure
   (password) fields, while secure input is on, in excluded apps (terminals, editors and password managers by
   default), or in password managers, keychains, authenticators and terminals even if the user took them off that
   list (`CorrectionEvents.neverSharedApps`); the Mac can't tell email / URL / number fields apart, but the token
   rules still drop emails, links and numbers. Such a decision never becomes an event, with or without sentence
   context.
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

## Sentence context (`ctx_before` / `ctx_after`, second opt-in)

Only when "Palantir Mode" is on (it can't be on without sharing, and is off by default). The setting's
description says what it does: it also sends the sentence around each correction. Everything below happens on the
device before the event is queued; the opt-in is checked when the decision is made, again when the event is queued,
and again when a batch is sent (context is stripped from queued events if it is off by then).

1. **Fields.** Never from a field that gives no event at all (rule 1 above). In addition, no sentence context (the
   event stays word-level) from name, postal-address, filter and phonetic fields and fields that ask for no
   suggestions on Android, and from search fields on Mac.
2. **The current sentence only, on the same line.** Take the text before the word back to the last line break, and
   the text after it up to the next line break; context never crosses a newline. Split it at whitespace. A token ends
   a sentence if, ignoring closing quotes and brackets, it ends in `.` `!` `?` `…` (or `。` `！` `？`). `ctx_before` is
   the tokens after the last sentence-ending token; `ctx_after` is the tokens up to and including the first one.
   Something stuck to the word itself (an opening quote, a comma, the `.` that ends the sentence) is not context; if
   it ends the sentence, `ctx_after` is empty.
3. **Caps.** At most the 24 tokens nearest the word before it and the 12 nearest after it. If the text came from a
   window that may have been cut in the middle of a word, the token at the cut is left out.
4. **Scrub every token**, first match wins:

   | placeholder | replaces |
   |---|---|
   | `<email>` | a token with an `@` that has text on both sides |
   | `<url>` | a token with `://`, starting with `www.`, or (before any `/`) with dots between letter/digit runs and 2+ letters after the last dot (`example.com/x`, `file.txt`) |
   | `<handle>` | `@name`, `#tag` |
   | `<num>` | a token with a digit and no letter: amounts, times, dates, phone numbers. A run of them becomes one `<num>` |
   | `<tok>` | letters mixed with digits (`a1b2c3`, `2nd`); more than 20 characters; any character that isn't a letter, accent or plain punctuation (`_ / = + $ %`, emoji, ...); or letters that look like a code or key: 3+ lower-to-upper case changes (`dGhpcyBpcyBh`), `UPPER-CASE-GROUPS` with 2+ hyphens, 6+ ASCII letters with no vowel |

   Everything else is kept as typed (case and the punctuation `' ’ ‘ " “ ” - – — . , ! ? ; : ( ) … & ¿ ¡ « »` stay).
   Ordinary words, including names of people and places, are not replaced: this is what the second opt-in shares.
5. **Nothing but placeholders is nothing.** If no real word is left on a side, that field is left out.

The server runs the same token function on every token (`ctx_token` in `server.py`) and rejects the event (`bad_ctx`)
if any token would have been replaced, if there are more than 24 / 12 tokens, a line break, a double space, two
`<num>` in a row or no real word. `ctx_ref.py` is the reference for steps 2-5 and writes `ctx_vectors.json`, which
the Android and Mac test suites replay against their own implementations.

On Android the text before the word is what was in the field when the word was finished (up to 240 characters). The
text after it is what was there if the fix happened in the middle of text (re-check fixes); for a word fixed as it
was typed, it is what the user typed next in that field, up to the end of that sentence (the event waits for it and
goes out when the sentence ends, the cursor jumps or the field is left). On Mac both sides are read from the field
when the outcome is known (240 / 120 UTF-16 units; the last token after the word is always left out since it may be
half typed).

## Storage and retention

- Server: SQLite on lime's Hack Club Nest container (`/var/lib/autocorrect-collect/collect.db`). Each event is stored
  once per `(install_id, id)` with the validated JSON body. For v2 uploads the server's own times (`received_at`,
  install `first_seen` / `last_seen`) are stored as the start of the UTC day.
- IP addresses are used only for in-memory rate limits; never stored or logged. No read endpoint; exports are made
  over ssh with `admin.py`.
- **Retention: 90 days.** The service deletes every correction event (v2 and v3, with or without sentence context)
  whose `received_at` is more than `COLLECT_RETENTION_DAYS` days old (default 90; set it in the systemd unit's
  `Environment=`; `0` turns expiry off). It runs when the service starts and then once every 24 hours for as long as
  it runs (`retention_loop` in `server.py`); `autocorrect-collect purge [--days N]` runs it by hand. Install records
  left with no events and not seen for that long go too. Exports made before then are files on the maintainer's
  machine and are not reached by this.
- Before that, an event is gone as soon as the install deletes it ("Delete my uploaded data" →
  `DELETE /api/v1/install/{id}`) or the maintainer does.
- On the device: the queue (`filesDir/upload/corrections.jsonl` on Android, `~/Library/Application Support/Autocorrect/upload/corrections.jsonl`
  on Mac) holds only scrubbed events, is trimmed after upload, capped in size, and cleared by "Delete my uploaded
  data" or by turning sharing off. Turning "Palantir Mode" off rewrites it without `ctx_before` /
  `ctx_after`.
- The old v1 schema (`autocorrect-events/1`: 120 characters of context, touch points, candidate scores) is retired:
  the server answers 410 `schema_retired` (set `COLLECT_ACCEPT_V1=1` to accept it again). Rows stored before v2 stay in
  the database until deleted by hand: the retention job only deletes `correction` events and never touches them.
  `admin.py export --type autocorrect` etc. still reads them, the pair exports don't.

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

Both exports read v2 and v3 events. When an event has sentence context, the pair also carries `ctx_before` /
`ctx_after` as stored (strings with placeholders); the sentence is `ctx_before + " " + typed + " " + ctx_after`, give
or take punctuation that was stuck to the word. `before` / `after` are always there, as lists.

```json
{"typed": "teh", "target": "the", "before": ["see", "you"], "after": ["soon"], "ctx_before": "I think we should see you",
 "ctx_after": "soon, around <num> ok?", "kind": "spelling", "changed": true, "source": "auto_applied_kept",
 "platform": "android", "model": "m2s_int4"}
```

`autocorrect-collect export --type correction` dumps the raw events (plus `install_id`, `received_at`) for anything
else. `autocorrect-collect stats` shows totals, including counts by kind, platform and outcome and how many events
carry sentence context.
