# Keylime keyboard (Android)

**Install:** grab the APK from the [latest release](https://github.com/Mostlime12195/autocorrect-android/releases/latest)
(arm64, Android 8+), open it, then enable Keylime in Settings → System → Keyboard. It updates itself from then on.

A QWERTY keyboard (Android `InputMethodService`) whose autocorrect is the tiny on-device language model from the
`autocorrect` project. It is a Kotlin port of the browser demo's pipeline (candidate generation → LM re-ranking →
re-check of the previous word) on onnxruntime-android, fed with **real touch coordinates** instead of key centres.

- Keyboard: shift / caps lock (double-tap shift; lock icon + accent key), accelerating backspace repeat, numbers + two
  symbol layers, space, comma, period, enter whose icon follows the field's action (send / search / go / next / done /
  newline). Long-press: digits on the top row, accents (é, ñ, ü, ...), ? ! … on the period. Long-press ?123 opens the
  keyboard switcher; the gear in an empty strip opens settings.
- Strip toolbar (settings, clipboard, emoji) when there's nothing to suggest. Clipboard: a paste chip for text copied in
  the last 90 s and a history panel (last 10 + pinned, pin / delete, kept only on the phone, never sensitive clips or
  copies made in password / incognito fields; can be turned off). Emoji: recents + Unicode categories (long-press comma
  or the strip button). Optional number row; keyboard height 80-130%.
- Confusion-set rewind: to/too/two, there/their/they're, your/you're, its/it's, then/than, lose/loose, were/where/we're,
  of/off, a/an are re-checked up to 3 words back once later words are typed, and fixed only when the model is very sure
  (margin 6, `Rewind.MARGIN`), in one edit with an undo chip.
- Look and feel: Material Symbols icons (Apache 2.0), Material You dynamic colors (light / dark), key preview bubbles,
  press animation, strip fade-in, a flash when a correction lands and an undo chip with the word as typed.
- Autocorrect on space / punctuation / enter, with the text before the cursor as the LM's left context (up to 400
  characters, clipped to the last 64 tokens like the eval). Backspace right after a correction restores what you typed
  (and keeps it).
- Never waits for the model: the word is committed as typed and the correction is applied when it's ready, in one batch
  edit, only if the text before the cursor is still exactly what was typed (plus the word you're composing now);
  otherwise it's dropped. Enter / Send waits up to 400 ms for a pending correction. The state machine is
  `ime/KeyboardController.kt`, tested with a fake editor and a slow engine (`KeyboardControllerTest`).
- Candidate generation v2 (splits "inaminute" → "in a minute", space-bar misses, merges "some thing" → "something",
  apostrophes "im" → "i'm", g-drops), with the per-kind penalties from `web/site/params_v2.json`. camelCase tokens are
  never split or merged. The **formal** style also adds sentence capitals, i → I and punctuation after the previous
  word (end-of-message punctuation is not done yet).
- Correction mode: **Auto-correct** (default) or **Suggest only (never edit my text)**. Suggest-only never changes
  committed text (no late corrections, re-checks, rewinds, capitals or punctuation): a likely typo (model choice !=
  typed word and the fix beats it by `KeyboardController.FLAG_MARGIN`, 1.5) is committed with a `SuggestionSpan`
  (misspelled + easy-correct, top fixes) so apps that support it show the red underline and their tap-to-fix menu, and the
  strip shows "typo?" with the top 3 fixes for the most recent flagged word (tap = replace) plus a **not a typo** chip
  (spelling flags) or **ignore** (other flags). **Only auto-correct when confident** (auto mode, off by default):
  corrections whose margin over every other candidate is below `CONFIDENT_MARGIN` (3.0) are underlined and suggested
  instead of applied.
- Personal dictionary (`files/dict/words.txt`, settings → Personal dictionary: search, add, delete, clear all, import
  Android's personal dictionary where the system allows it). Dictionary words are never corrected, flagged or rewound,
  and are offered as candidates for near-miss typos (spatial cost + a small prior). Words get in via "not a typo",
  long-press on the word as typed in the strip, or by undoing the same spelling fix twice (toast + an undo chip).
  Grammar guard: only spelling fixes teach the dictionary (typed word not in the lexicon, one word, not just capitals /
  punctuation / apostrophes). Undoing anything else (confusable swaps like their/there, capitals, punctuation,
  rewinds, multi-word edits) only adds a suppression: the same edit needs 3 more nats for 7 days (`files/dict/learn.tsv`).
  "Delete my uploaded data" wipes both files.
- No word window for grammar: after a typing pause (400 ms) suggest-only re-checks every sentence of the field it can
  read (2000 chars before and after the cursor, all words, the rest of the sentence as right context, cached per
  sentence) and adds / removes underlines; auto mode rewinds confusables across the whole sentence (up to 20 words,
  +0.5 nats of margin per word beyond 3) and, in the formal style, adds commas and full stops scored on everything
  typed after them (a full stop needs at least 3 words of right context; per word only commas). The next key cancels it.
- In-app updates: `latest.json` on the Nest mirror (`scripts/publish_apk.sh <tag>` after every release) is checked when
  settings open and every 6 h; a notification and a settings banner offer the update, the APK is downloaded (auto on
  Wi-Fi unless turned off), SHA-256-checked and installed with a PackageInstaller session.
- Settings in sub-screens: Typing, Correction, Dictionary, Appearance, Model, Privacy and data, Updates, About (the
  setup card stays on top until setup is done). Appearance: theme (system / light / dark), theme color (Material You
  on Android 12+ by default, the original green before, 10 swatches or a custom HSV / hex color; text on the accent is
  black or white by contrast), a background image (Photo Picker, stored at screen width, cropped to the keyboard,
  darken / blur / key-opacity sliders, removed by delete-my-data), keyboard height (108% default), key gap, corner
  radius, hint symbols on / off and size. Layout: QWERTY / QWERTZ / AZERTY / Dvorak / Colemak (the spatial model's
  letter centres and the swipe templates follow the layout), number row, comma and emoji keys, cursor-key row,
  one-handed mode (80% width, tap the empty side to switch), split keys in landscape, custom long-press entries
  ("e: € :)" per line, multi-character entries go in as text). Model: the Lite (34M) model is the default on low-RAM
  phones (< 4.5 GB or flagged low-RAM) and on phones with only small cores (A53 / A55 class).
- Suggest-only keeps every flagged word underlined (SuggestionSpan); in apps that ignore it, putting the cursor on or
  next to a flagged word shows its fixes in the strip (offsets are tracked per field and re-found when text moves).
- Suggestion strip: top 3. While typing it shows cheap suggestions (spatial + word frequency); after a correction it
  shows the alternatives, and tapping one replaces the corrected word.
- Models (downloaded on first run, see below): m2 106M int4 (default; trained with a style control token: casual /
  standard / formal, default standard, right after the leading EOS like the web demo, `web/STYLE_TOKENS.md`) and m1 34M
  int8 (Lite / battery saver). A saved pick the manifest no longer lists is cleared and the default is used.
- Settings app (Material 3): a setup card, a model card with size / status / download progress, battery saver, style (casual / standard / formal),
  autocorrect on/off, debug overlay, local log export / clear, opt-in data sharing.

## Install

1. On the phone, open the repo's **Releases** page (signed in to GitHub) and download the latest `autocorrect-*.apk`
   (every push to `main` also leaves the APK as a workflow artifact).
2. Open it. Android asks to allow installs from your browser / file manager ("Install unknown apps"): allow, install.
   Later builds install over earlier ones (same signing key).
3. Open **Autocorrect**. It downloads the model (m2 int4, about 80 MB; use Wi-Fi) and shows the progress under "3. Model".

## Enable the keyboard

In the Autocorrect app:

1. **Enable the keyboard** → turn on "Keylime" (Android warns that keyboards can see what you type; this
   one keeps its log on the phone unless you turn on sharing).
2. **Switch to it** → pick "Keylime". You can also long-press ?123 (or use the keyboard icon in
   the navigation bar) to switch.
3. Type in the "Try the keyboard here" box at the top.

## Models and download

Models are not in the APK or in this repo. They are release assets of the public repo
[Mostlime12195/autocorrect-models](https://github.com/Mostlime12195/autocorrect-models) (tag `models-v1`). On first run the app fetches that release's `manifest.json`
(fallback: `https://autocorrect.mostlime.hackclub.app/autocorrect-android/manifest.json`), which lists each model's files
with size and SHA-256 and the mirrors to download from, and downloads the active model's files into app-private storage
with WorkManager (progress in settings). A file is used only after its SHA-256 matches. A copy of the manifest is
bundled (`app/src/main/assets/manifest.json`) as the fallback. A new model only needs new assets + manifest, no new APK:
`gh release upload models-v1 --clobber -R Mostlime12195/autocorrect-models <files> manifest.json`.

Model files are **pre-optimized ORT format** (`.ort`), made on an arm64 host by `android_export/to_ort.py` in the
autocorrect repo (all graph optimizations applied at conversion, int4 `DequantizeLinear+MatMul` fused to `MatMulNBits`
with int8-activation kernels). The app loads them with graph optimization off and uses the memory-mapped file's bytes in
place for the weights, so session creation is mostly mmap. `android_export/int4_head.py` also turns the int4 graph's tied
int8 embedding head into a `MatMulNBits` (bits=8) node shared with the embedding lookup: without it onnxruntime
dequantises the whole 32k×640 table on every call (~40% of the step time on ARM).

Publishing a model: in the autocorrect repo, `to_ort.py` → `make_manifest.py --app <this repo>/app/src/main/assets/manifest.json`
→ upload to the models release (`android_export/publish.sh` also mirrors to `autocorrect.mostlime.hackclub.app/autocorrect-android/` on Nest). For a style-token model, list it in `make_manifest.py --style_models <id>` (sets
`supports_style`) and add per-style params under `style_params` when the style evals land.

### Session policy, latency, battery

- The ORT session is preloaded when the keyboard service starts (and again when it's shown, if needed), kept for the
  life of the process, and released only on critical memory pressure (`onTrimMemory` RUNNING_CRITICAL / COMPLETE,
  `onLowMemory`). Until it's ready, word-end correction uses the cheap spatial + unigram path.
- The LM runs only at word end (space / punctuation / enter / strip pick). While typing, the strip uses the cheap path
  (debounced).
- Early exit: with a clear cheap leader (4 nats) only the top 2 + typed are LM-scored; the re-check is skipped after a
  clear first-pass win (6 nats) unless it's a confusion-set word. Up to 4 ORT threads.
- The LM is skipped when the typed word is one of the 20k most frequent words, an exact lexicon hit, its spatial fit is within `SKIP_FIT_PER_LETTER` (0.6
  nats/letter) of a perfect hit, it beats every other candidate by `SKIP_MARGIN` (3 nats) on spatial + prior, and it
  has no confusion-set / contraction / split alternatives (`engine/Corrector.kt`). The skip rate is in the debug overlay
  and the Stats row.
- Only the top `lmTopK` (6) cheap-ranked candidates are LM-scored, plus the typed string and any confusion /
  contraction / split candidates.
- The shared left context's KV + conv state is cached and *extended* between words, so only the new word's tokens run.
- **Lite model** (battery saver) switches to m1 34M.
- Debug overlay (settings) shows: model, per-word ms and scored/total candidates, skip rate, cold load (process start →
  session ready) and first-inference time. The same numbers go to the local log (`perf` / `session` events).

## Touch coordinates

The letter layer has exactly the geometry of `keyboard.py` / `keyboard.js`: key width 1, rows offset 0 / 0.5 / 1.5,
row pitch 1.4. A touch at pixel (x, y) in the key area becomes `(x / keyWidth, y / rowHeight × 1.4)`, so the spatial
model (σ = 0.3 key widths) sees real touch positions. The bottom row's space bar spans x 2.5–7.5 (the eval layout uses
3.5–8.5); the v2 candidate generator's space-miss model is given the real extent.

## Privacy and data

- Always logged locally (app-private JSONL, `files/events/events.jsonl`, rotated at 8 MB): every correction with typed
  word, touches, candidates + scores, chosen / final word, outcome (accepted / undone / strip pick), style, model,
  timestamp, and up to 120 characters of left context; timing-only events for unchanged words. Settings has **Export**
  (share sheet) and **Clear local data**.
- Never logged, learned from or uploaded: anything typed in password, incognito, email, URI, number, phone or date
  fields. Autocorrect is off in password / email / URI / number / phone fields and when the field asks for no
  suggestions.
- **Share typing data to improve autocorrect** is off by default. When on, events logged from then on are uploaded in
  batches (WorkManager, about every 6 h, Wi-Fi only by default) to `https://autocorrect.mostlime.hackclub.app/api/v1/upload`
  under a random install ID. 2xx advances; 429 / 5xx / network errors retry (honoring `Retry-After`); 413 halves the
  batch; other 4xx skip the batch. **Delete my uploaded data** calls `DELETE /api/v1/install/{id}`, clears the local log
  and starts a new install ID (a failed call is retried by the worker before any upload). Request format:
  [docs/upload-schema.md](docs/upload-schema.md); server API: `collect/API.md` in the autocorrect repo.
- App data is excluded from Android backups.

## Build and CI

GitHub Actions (`.github/workflows/build.yml`, ubuntu-latest) on every push / PR:

1. Engine unit tests: SentencePiece tokenizer vs Python sentencepiece (m1 + m2 vocab), candidate generation v1 + v2
   (splits, contractions, merges) on key-centre and jittered real-valued touches vs `keyboard.py`, a replay of the Python
   `corrector.decode()` (every LM call answered from the recorded log), the keyboard's word-at-a-time API vs `decode()`,
   and the real `lm_block4` ORT graph through onnxruntime vs the Python decode on 50 messages (fetched from the model
   host; skipped if it's down). Fixtures come from `android_export/make_fixtures.py` in the autocorrect repo.
2. App unit tests (field privacy policy).
3. Signed release APK → workflow artifact. Tags `v*` and manual runs (Actions → build → Run workflow) also create a
   GitHub Release with the APK.

**Signing:** one release keystore, generated once, stored as repository secrets `AC_KEYSTORE_B64` (base64 PKCS12),
`AC_KEYSTORE_PASSWORD`, `AC_KEY_ALIAS`, `AC_KEY_PASSWORD`. CI decodes it into the runner's temp dir and Gradle signs with
it (`app/build.gradle.kts`), so every build has the same certificate and installs over the previous one. Without those
variables (local builds) the debug key is used. Backup of the keystore: `~/.config/autocorrect-android/` on the dev VPS
(mode 600). Losing it means the next build can't update an installed app (uninstall first).

Local builds: `./gradlew :app:assembleDebug` with an Android SDK. On the arm64 dev VPS the x86 aapt2 runs under
qemu-user: `-Pandroid.aapt2FromMavenOverride=$HOME/.cache/aapt2x86/bin/aapt2` (a wrapper around
`qemu-x86_64 -L /usr/x86_64-linux-gnu aapt2`). The engine is plain Kotlin/JVM and builds without
one: `ENGINE_ONLY=1 ./gradlew :engine:test` (set `AC_MODEL_DIR` to a folder with `lm_block4.step.int8.ort` for the
ORT tests).

## Layout

- `engine/`: pure Kotlin port (tokenizer, lexicon, spatial model, candidate generators v1 / v2, decode, word-level
  corrector, ORT step-graph scorer with prefix caching). Compile-only dependency on the ORT Java API.
- `app/`: the IME (`ime/`: service glue, `KeyboardController` text state machine, view, layout, field policy,
  engine holder), model download (`model/`), local log + uploader (`log/`), settings (`ui/`). The keyboard service runs
  in its own process (`:ime`), so the settings screen's test box doesn't share a main thread with it.

## License

Code: MIT (see `LICENSE`). Model weights in [autocorrect-models](https://github.com/Mostlime12195/autocorrect-models): CC BY 4.0. Icons: Material Symbols (Apache 2.0).
