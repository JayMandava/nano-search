# Nano Search

A private, on-device home screen and search assistant for Android. One search bar finds anything on the phone (apps, contacts, messages,
calls, calendar, files, settings, and photos by date, place, content and the text inside them), understands requests like "call priya",
answers questions, and listens when you speak. Small language models run entirely on the phone. Your data never leaves it.

## Features

- **Home screen and launcher:** wallpaper, clock, widgets, an editable dock and an app drawer. The search bar sits at the top of both
  the home screen and the drawer and searches in place. Portrait only.
- **Search everything:** apps, contacts, messages, call history, calendar events, files, system settings and photos. Results appear as
  you type, with no model involved, and small typos still find things ("calclator" finds Calculator).
- **Understands requests:** "open the calculator", "call priya", "text vikram", "find a plumber". A small language model turns the
  sentence into an action; Enter carries it out. Nothing is dialled or sent automatically.
- **Answers questions:** a second, larger model answers in a card above the results and streams the text as it is written.
- **Photos by meaning:** "beach photos from Goa last week", "pictures of food", "photos from September". Date and place come from EXIF and an
  offline city list; content comes from an on-device image-and-text model.
- **Text in photos:** on-device OCR makes screenshots, receipts and documents searchable ("wifi password", "invoice").
- **Voice:** the mic button transcribes with an on-device Whisper model and presses Enter for you.
- **Models you choose:** pick, download, test or import the model for each job in Settings > Models.
- **Adapts to the phone:** it detects the CPU core layout and RAM, measures once in the background which core grouping runs the models fastest, and sizes how long models stay loaded to the available memory. Settings > Advanced shows what it chose and lets you override it.
- **Material 3 Expressive design:** colours from your wallpaper (or a fixed accent), light, dark or system, and an adaptive icon.

## Privacy

Everything is indexed into a private database inside the app. Models run on the CPU. The app has the INTERNET permission for one
reason only: downloading models when you tap Download. Downloads are checked against a SHA-256 before they are used, and
"Download on Wi-Fi only" is on by default. Each search source (contacts, messages, calls, calendar, files, photos) can be switched off
in Settings, and "Clear and rebuild" empties the index.

Some custom Android builds switch off network access for apps that were installed before they declared the INTERNET permission. If a download
never starts, open App info > Mobile data and Wi-Fi and allow network access for Nano Search.

## Requirements

- Android 13 (API 33) or newer, arm64.
- About 8 GB of RAM is comfortable for the recommended models (they are loaded one at a time and unloaded when idle). Smaller models are
  offered for phones with less, and models that are too large for the device are marked.
- About 4.5 GB of free storage for the recommended set of models.

## Build

You need JDK 17, the Android SDK (platform 36) and NDK 27 with CMake. The llama.cpp and whisper.cpp sources are submodules.

```
git clone --recurse-submodules https://github.com/JayMandava/nano-search.git
cd nano-search/app
echo "sdk.dir=/path/to/Android/sdk" > local.properties    # add cmake.dir=... if CMake is not in the SDK
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch a short setup tour asks what the app may read, offers the recommended models and helps you make it the default home app.

## Models

Settings > Models has one choice per job. Files live in the app's private storage; nothing is bundled in this repository.

| Job | Options | Licence |
|---|---|---|
| Understanding requests | Qwen3.5 0.8B, 2B (recommended), 4B | Apache-2.0 |
| Answering questions | Gemma 4 E2B (recommended), Qwen3.5 2B, 4B | Gemma terms / Apache-2.0 |
| Voice | Whisper tiny, base, small (English) | MIT |
| Photo search | MobileCLIP S0 | Apple research-only licence |
| Text in photos | PaddleOCR PP-OCRv4 (English and Chinese) | Apache-2.0 |

"Try it" on a language model runs it on the device and reports accuracy (understanding models) or speed and a sample answer
(answering models). You can also import your own GGUF language model or Whisper file.

**Licence note:** MobileCLIP's weights are released for research use only. They are downloaded by you, on your device, and are not part of this
repository. To ship an app built on Nano Search, replace them with an openly licensed image-and-text model.

## Tools

`tools/parser-eval/` holds a 46-request labelled set and a scorer for measuring how well a model turns requests into actions.
Copy `queries.txt` to the app's `files/models/`, run the app's `bench` debug hook, and score the output with `score.py`.

## More

[docs/how-it-works.md](docs/how-it-works.md) explains the search index, the photo, OCR and voice pipelines, the performance work, and the limits.

## Licence

Nano Search is licensed under the [Apache License 2.0](LICENSE). The models it downloads have their own licences, listed above.

## Credits and licences

- [llama.cpp](https://github.com/ggml-org/llama.cpp) and [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) run the language and speech models.
- [ONNX Runtime](https://onnxruntime.ai) (MIT) runs the image and OCR models.
- Place names come from [GeoNames](https://www.geonames.org), licensed CC BY 4.0.
- Models are the work of their authors under the licences in the table above.
