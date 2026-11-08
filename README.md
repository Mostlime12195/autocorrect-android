# Autocorrect keyboard (Android)

A QWERTY keyboard (Android `InputMethodService`) whose autocorrect is the tiny on-device language model from the
`autocorrect` project. It is a Kotlin port of the browser demo's pipeline (candidate generation → LM re-ranking →
re-check of the previous word) on onnxruntime-android, fed with **real touch coordinates** instead of key centres.

- Keyboard: shift / caps lock (double-tap shift), backspace with repeat, numbers + two symbol layers, space, comma,
  period, enter that follows the field's action (Go / Search / Send / Next / Done / newline). Long-press ?123 opens the
  keyboard switcher.
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
- Suggestion strip: top 3. While typing it shows cheap suggestions (spatial + word frequency); after a correction it
  shows the alternatives, and tapping one replaces the corrected word.
- Models (downloaded on first run, see below): m2 106M int4 (default), m2 106M int8, Block AttnRes 34M (battery saver).
- Settings app: setup steps, model choice + download progress, battery saver, style (casual / standard / formal),
  autocorrect on/off, debug overlay, local log export / clear, opt-in data sharing.

## Install

1. On the phone, open the repo's **Releases** page (signed in to GitHub) and download the latest `autocorrect-*.apk`
   (every push to `main` also leaves the APK as a workflow artifact).
2. Open it. Android asks to allow installs from your browser / file manager ("Install unknown apps"): allow, install.
   Later builds install over earlier ones (same signing key).
3. Open **Autocorrect**. It downloads the model (m2 int4, about 80 MB; use Wi-Fi) and shows the progress under "3. Model".

## Enable the keyboard

In the Autocorrect app:

1. **Enable the keyboard** → turn on "Autocorrect keyboard" (Android warns that keyboards can see what you type; this
   one keeps its log on the phone unless you turn on sharing).
2. **Switch to it** → pick "Autocorrect keyboard". You can also long-press ?123 (or use the keyboard icon in
   the navigation bar) to switch.
3. Type in the "Try the keyboard here" box at the top.

## Models and download

Models are not in the APK or in git. On first run the app fetches
`https://smitten.mostlime.hackclub.app/autocorrect-android/manifest.json`, which lists each model's files with size and
SHA-256, and downloads the active model's files into app-private storage with WorkManager (progress in settings). A file
is used only after its SHA-256 matches. A copy of the manifest is bundled (`app/src/main/assets/manifest.json`) as the
fallback when the host is unreachable.

Why the demo host and not GitHub release assets: the repo is private, so release assets need a GitHub token to download,
which the app shouldn't carry; and a new model only needs a new manifest + file on the host, no new APK.

Model files are **pre-optimized ORT format** (`.ort`), made on an arm64 host by `android_export/to_ort.py` in the
autocorrect repo (all graph optimizations applied at conversion, int4 `DequantizeLinear+MatMul` fused to `MatMulNBits`
with int8-activation kernels). The app loads them with graph optimization off and uses the memory-mapped file's bytes in
place for the weights, so session creation is mostly mmap. `android_export/int4_head.py` also turns the int4 graph's tied
int8 embedding head into a `MatMulNBits` (bits=8) node shared with the embedding lookup: without it onnxruntime
dequantises the whole 32k×640 table on every call (~40% of the step time on ARM).

Publishing a model: in the autocorrect repo, `to_ort.py` → `make_manifest.py --app <this repo>/app/src/main/assets/manifest.json`
→ `android_export/publish.sh`. For a style-token model, list it in `make_manifest.py --style_models <id>` (sets
`supports_style`) and add per-style params under `style_params` when the style evals land.

### Session policy, latency, battery

- The ORT session is preloaded when the keyboard service starts (and again when it's shown, if needed), kept for the
  life of the process, and released only on critical memory pressure (`onTrimMemory` RUNNING_CRITICAL / COMPLETE,
  `onLowMemory`). Until it's ready, word-end correction uses the cheap spatial + unigram path.
- The LM runs only at word end (space / punctuation / enter / strip pick). While typing, the strip uses the cheap path
  (debounced).
- The LM is skipped when the typed word is an exact lexicon hit, its spatial fit is within `SKIP_FIT_PER_LETTER` (0.6
  nats/letter) of a perfect hit, it beats every other candidate by `SKIP_MARGIN` (3 nats) on spatial + prior, and it
  has no confusion-set / contraction / split alternatives (`engine/Corrector.kt`). The skip rate is in the debug overlay
  and the Stats row.
- Only the top `lmTopK` (6) cheap-ranked candidates are LM-scored, plus the typed string and any confusion /
  contraction / split candidates.
- The shared left context's KV + conv state is cached and *extended* between words, so only the new word's tokens run.
- **Battery saver** switches to the 34M model.
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
  batches (WorkManager, Wi-Fi only by default). The endpoint doesn't exist yet; the URL is a placeholder and uploads to
  it are skipped. Request format: [docs/upload-schema.md](docs/upload-schema.md).
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
