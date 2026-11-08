# Data-sharing upload schema (v1)

Sharing is **off by default** ("Share typing data to improve autocorrect" in the app). When it is on, a WorkManager job
(every ~6 h, Wi-Fi only unless that setting is off, battery not low) POSTs the events logged **since sharing was turned
on** to `https://autocorrect.mostlime.hackclub.app/api/v1/upload` (Settings → Upload server URL can override it). The
server side is `collect/` in the autocorrect repo (`collect/API.md`). **Delete my uploaded data** sends
`DELETE /api/v1/install/{install_id}`, clears the local log and switches to a new random `install_id`; if the call fails
it stays pending and the worker retries it before any upload.

Nothing typed in these fields is ever logged, so it can never be uploaded: passwords (text / visible / web / number
password), incognito fields (`IME_FLAG_NO_PERSONALIZED_LEARNING`), email, URI, number, phone, date/time fields and raw
(`TYPE_NULL`) fields. Autocorrect itself is off in password, email, URI, number and phone fields.

## Request

`POST <url>` with `Content-Type: application/json; charset=utf-8`

```json
{
  "schema": "autocorrect-events/1",
  "install_id": "random UUID made on first use (not tied to the device or account)",
  "app_version": "0.1.12",
  "sent_at": 1762621200000,
  "events": [ /* up to 500 events / 512 KB per request, oldest first */ ]
}
```

Client handling of the response (`log/UploadClient.kt`):

- 2xx: the batch is done, the cursor moves past it.
- 408 / 429 / 5xx / network error: stop and retry later (a `Retry-After` header schedules the next run after that many
  seconds, otherwise WorkManager backoff). Retries resend the same lines, so the server deduplicates.
- 413: halve the batch and resend (a single event that still gets 413 is skipped).
- any other 4xx: permanent, the batch is skipped so it can't block later uploads.

## Event types

Every event has `v` (event schema, 1), `ts` (ms since epoch, phone clock) and `type`.

### `autocorrect`: one finished word that the keyboard changed (or whose re-check changed the previous word)

| field | type | meaning |
|---|---|---|
| `id` | string | UUID |
| `typed` | string | the word as typed (with case) |
| `touches` | array | one `[x, y]` per typed character, in keyboard.py key units (key width 1, row pitch 1.4, see README); `null` for characters typed off the letter layer (apostrophe) |
| `left_context` | string | up to 120 characters of text before the word |
| `sep` | string | separator between the previous word and this one |
| `candidates` | array | up to 8, best first: `{w, kind, sp, lm, score}`; `kind` is word / split / space / contr / contr_rw / gdrop, `sp` spatial log-lik, `lm` LM log-prob (null when the LM didn't run), `score` final score |
| `chosen` | string | what autocorrect picked |
| `final` | string | what ended up in the text |
| `outcome` | string | `accepted` (kept), `undone` (backspace or the undo chip reverted it to `typed`), `strip_pick` (user tapped a suggestion; `final` is the pick), `dropped_stale` (the text changed before the result landed) |
| `recheck` | object? | `{from, to}` when the re-check of the previous word changed it |
| `style` | string | casual / standard / formal |
| `model` | string | manifest model id (m2s_int4, m2s_int8, m2_int4, m2_int8, block4, none) |
| `lm_ms` | number | time for the word's correction (candidates + LM), ms |
| `n_scored` / `n_cands` | int | candidates LM-scored / generated |
| `skipped` | bool | LM skipped (confident exact hit) |
| `cheap` | bool | no model loaded: spatial + unigram only |
| `app_version` | string | |

### `strip_pick`: a suggestion tapped while typing (before the word was finished)

`id, typed, touches, candidates` (`[{w}]`, the three shown), `chosen`, `final`, `outcome: "strip_pick"`,
`while_typing: true`, `style, model, app_version`.

### `perf`: a finished word that was not changed (no text)

`model, app_version, lm_ms, n_scored, n_cands, skipped, cheap, ctx_tokens`.

### `rewind`: a confusable word fixed from later words (to/too/two, there/their/they're, your/you're, its/it's, then/than, lose/loose, were/where/we're, of/off, a/an)

`id, from, to, margin` (LM log-prob margin × lam_r, at least 6), `words_back` (1-3), `left_context` (≤120 chars),
`right_context` (the words typed after it), `outcome` (`accepted`, or a second line with the same `id` and `undone` when
the undo chip put it back), `style, model, app_version`.

### `session`: model load timings (no text), once per session load, after its first LM inference

`model, session_ms` (ORT session creation), `cold_load_ms` (process start → session ready), `lexicon_ms`,
`first_inference_ms`, `app_version`.

## Example

```json
{"v":1,"id":"5b0d…","ts":1762621000123,"type":"autocorrect","typed":"teh","touches":[[4.43,0.61],[2.61,0.83],[5.42,2.31]],
 "style":"standard","model":"m2_int4","app_version":"0.1.12","left_context":"see you at","sep":" ",
 "candidates":[{"w":"the","kind":"word","sp":-6.12,"lm":-1.9,"score":-8.5},{"w":"teh","kind":"word","sp":1.62,"lm":-14.2,"score":-16.1}],
 "chosen":"the","final":"the","outcome":"accepted","lm_ms":84.2,"n_scored":7,"n_cands":18,"skipped":false,"cheap":false}
```
