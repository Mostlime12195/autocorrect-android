# Data-sharing upload schema (v1)

Sharing is **off by default** ("Share typing data to improve autocorrect" in the app). When it is on, a WorkManager job
(every ~6 h, Wi-Fi only unless that setting is off, battery not low) POSTs the events logged **since sharing was turned
on** to the configured URL. The built-in URL is a placeholder (`https://example.invalid/autocorrect/v1/events`): with it,
nothing is sent. Set the real URL in Settings → Upload server URL once the endpoint exists.

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

Respond with any 2xx to acknowledge the batch; the client then moves its cursor past those events. Any other status
(or a network error) is retried with WorkManager backoff, so the endpoint must tolerate duplicates: deduplicate on
`events[].id` (correction events) or `(install_id, ts, type)` (perf / session events).

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
| `outcome` | string | `accepted` (kept), `undone` (backspace right after reverted it to `typed`), `strip_pick` (user tapped a suggestion; `final` is the pick) |
| `recheck` | object? | `{from, to}` when the re-check of the previous word changed it |
| `style` | string | casual / standard / formal |
| `model` | string | manifest model id (m2_int4, m2_int8, block4, none) |
| `lm_ms` | number | time for the word's correction (candidates + LM), ms |
| `n_scored` / `n_cands` | int | candidates LM-scored / generated |
| `skipped` | bool | LM skipped (confident exact hit) |
| `cheap` | bool | no model loaded: spatial + unigram only |
| `app_version` | string | |

### `strip_pick`: a suggestion tapped while typing (before the word was finished)

`id, typed, touches, candidates` (`[{w}]`, the three shown), `chosen`, `final`, `outcome: "strip_pick"`,
`while_typing: true`, `style, model, app_version`.

### `perf`: a finished word that was not changed (no text)

`model, lm_ms, n_scored, n_cands, skipped, cheap`.

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
