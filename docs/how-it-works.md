# How Nano Search works

Numbers below were measured on a mid-range 2023 phone (8 GB RAM, CPU only). Other devices will differ.

## Search

1. **Instant path (no model).** One SQLite FTS4 index holds every kind of item (Android's SQLite has no FTS5). Queries match titles first, then
   body text (message text, file folders, phone numbers), then substrings. Each kind is queried separately so thousands of messages or files cannot
   crowd out apps and contacts. Ranking: title starts with the query, then word-start, then substring; ties go apps, contacts, settings, events, files,
   messages, calls. Small typos are tolerated on names and app titles with a Damerau-Levenshtein match.
2. **Model path.** A request that reads like a sentence is parsed after a short pause by the small model into `{action, kind, text}` (grammar-constrained
   JSON). The index is then searched with `text`, filtered by `kind`. This happens silently: the result list just improves.
3. **Enter.** A plain name opens the top result. A request is parsed: `open`, `call` and `message` act on the top match (call opens the dialer, message opens the
   conversation; nothing is dialled or sent automatically). Questions, and anything the index cannot match, go to the answer model.
4. **Answer card.** The text streams into a card above the results. Timings and model names are never shown in the UI.

The index refreshes when the app opens, when apps are installed or removed, and at most every 10 minutes for the heavier sources.
Caps: 6000 messages, 400 calls, 3000 events, 40000 files.

## Photos by date and place

"photos from Goa", "pictures from last September", "images from August 2023", "photos from yesterday".

- **How:** rules, not a model, so it is instant. The phrase is split into a time range and a place, both filter a photos table.
- **Date:** read from each photo's EXIF capture time, because the media database often has no usable "date taken".
- **Place:** each photo's EXIF GPS position is mapped offline to a city, region and country from a bundled list of about 34,000 cities (GeoNames, 15,000+ people).
  The biggest city within 8 km wins, otherwise the nearest within 250 km.
- **Permissions:** photos need the photos permission. Reading GPS needs "access media location", which Android asks for separately.
- **Cost:** the first index reads each photo once (about 13 s for 2,000 photos, in the background); afterwards only new or changed photos are read.

## Photos by what is in them

An image-and-text model (MobileCLIP S0, through ONNX Runtime) turns every photo and every phrase into 512 numbers; photos closest to the phrase are shown.
There are no labels or tags. It combines with the date and place filters ("food photos from Paris").

- **Fingerprints:** each photo is processed once (about 0.3 s, one CPU thread) and stored in the private index. The job goes newest first and resumes where it stopped.
  While charging it runs through everything; on battery it does a small batch each time the launcher opens and nothing below 30% battery.
- **Query time:** about 50 ms once the text model is loaded; it is unloaded two minutes after last use. Search waits for a short pause in typing.
- **Plain words:** a short query that matches nothing else ("beach", "dog") is tried against photos, with a stricter cutoff.
- **Good at:** scenes and objects. **Not built:** faces, reading text (that is OCR), fine detail.
- **Models:** full-precision ONNX. The 8-bit versions were tested and matched no better than chance.

## Text inside photos (OCR)

Words in screenshots, receipts, documents and signs are searchable, and the matching passage is shown in context.

- **How:** PaddleOCR PP-OCRv4 through ONNX Runtime. A detector finds text regions, a recogniser reads each line. Boxes are built in Kotlin without an image library.
- **Cost:** each photo is first asked "does this look like it has text?" by the image model that already fingerprinted it (screenshots are always read). Only texty photos are decoded
  and read, about 1-2 s each. Like fingerprinting, it runs through the library while charging and in small batches on battery.
- **Text tidying:** hyphens and dots inside words are also stored joined, so "wifi" finds "Wi-Fi". Bump `OCR_VERSION` in `MainActivity.kt` to re-read every photo.
- **Limits:** English, digits and Latin text work well (including romanised Hindi), Chinese is supported by the model. Devanagari and handwriting are not supported; tilted text is read less reliably.

## Voice

The mic button records, decides for itself when you have stopped talking, and transcribes with Whisper (quantized) on the big CPU cores, through the same native layer as the language models.
The text lands in the search bar and Enter is pressed for you.

- **Speed:** about 2-3 s for a 3-4 s request. The encoder window is shortened to fit the clip, which can occasionally cause repeats, so repeats are collapsed and output is capped.
- **Accuracy:** clean speech is transcribed well; unusual names are the weak spot, which typo-tolerant search softens for names in your contacts and apps.
- **Fallback:** without a Whisper model the mic uses Android's own recognizer, which needs a language pack on some devices.
- **Privacy:** audio is held in memory only.

## Models and measurements

Understanding models were compared on 46 labelled English and Hinglish requests (action, kind and text all right):

| Model | Score | Time per request |
|---|---|---|
| Qwen3.5 2B | 45/46 | about 1.8 s |
| Qwen3.5 0.8B | 35/46 | about 1.1 s |

The test set was written by hand and some prompt examples resemble test patterns, so treat scores as relative. Small models extract the name almost perfectly; what separates
them is choosing open vs search and app vs contact.

Reference numbers for the recommended models on the test device: answers take 4-11 s and stream at about 7-8 tokens a second; the understanding model holds about 1.4 GB while loaded and the
answer model about 1.6 GB. They are never resident together. The understanding model is unloaded after 5 minutes idle, the answer model after 30 seconds; reloading takes a few seconds because the
processed prompt prefix is cached on disk.

## Performance notes

- **Grammar sampling was 40% of generation time.** llama.cpp's grammar sampler scans the whole vocabulary every token. The native layer takes the best token first, checks only that one against
  the grammar, and falls back to the full scan if it is rejected.
- **Models live in a process-wide singleton** (`Services.kt`). A launcher's activity is recreated often; per-activity models leaked a copy each time.
- **16 KB pages.** Native libraries are linked with flexible page sizes (`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`) so they load on 16 KB-page devices.
- **Debug flags are one-shot** and only honoured on a fresh launch, so rotation cannot replay them.

## Model management

Settings > Models is backed by a catalogue (`ModelCatalog.kt`) of files with sizes and SHA-256 hashes, and a store (`ModelStore.kt`) that downloads with Android's DownloadManager (resumable, survives the app closing),
verifies while copying into private storage, and migrates older fixed file names. Chat models use one of two prompt formats (ChatML or Gemma) chosen per catalogue entry. Imported GGUF files are
assigned a format from their file name.

## Debug hooks

Launch the main activity with one of these string extras (honoured once, on a fresh launch):

| Extra | What it does |
|---|---|
| `bench` | Runs `files/models/queries.txt` through the understanding model and writes `files/bench.txt` |
| `askbench` | Runs `files/models/questions.txt` through the answer model |
| `whisper_wav` | Transcribes a 16 kHz mono WAV and logs the text |
| `clip_tokenize`, `ocr_test` | Logs tokenizer and OCR output for checking |
| `wallpaper_uri`, `wallpaper_which`, `wallpaper_backup`, `wallpaper_restore` | Wallpaper testing |

## Known limitations

- Search is keyword-based with typo tolerance, not semantic (except photos). Notes and browser history are not indexed.
- `call` and `message` open the dialer and conversation; they never act on their own.
- Understanding quality was measured on a hand-written set; real use will differ, most often on `open` vs `search` for borderline phrasing.
- The debug build is signed with the debug key and is not optimised; a release build with shrinking would be much smaller.
- The MobileCLIP weights are research-only (see the README).
