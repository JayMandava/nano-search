# Nano Search

A private, on-device home screen and search assistant for Android. One search bar finds anything on the phone (apps, contacts, messages,
calls, calendar, files, settings, and photos by date, place, content and the text inside them), understands requests like "call priya",
answers questions, and listens when you speak. Small language models run entirely on the phone. Your data never leaves it.

## Features

- **Home screen and launcher:** wallpaper, clock, widgets, app shortcuts, several swipeable home pages, an editable dock and an app drawer. Long-press menus are anchored popups, and lists open as bottom sheets, in the style of Android's own launchers. The search bar sits at the top of both
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
- **Optional GPU (experimental):** Settings > Advanced can try running the language models on the GPU through Vulkan. It is off by default, only used if an on-device test shows it is faster, and switches itself off if it fails or crashes.
- **Old School style (optional):** a second look for the whole launcher, chosen in Settings > Appearance, in two eras. **Spirit** (the former Classic) is flat charcoal and white with one blue accent,
  squarer corners, a steel dial clock on home, a labelled glass dock bar, a glass Hub with card rows, Active Frames as live glass cards (now-playing with controls, a notification's picture, your next events, battery and storage, an app's shortcuts), and original glossy drawn gem icons (a handset, an envelope, a camera, a calendar page that shows today's date).
  Other apps keep their own icon on a matching dark tile. **Sense** is graphite and silver with a green accent, inspired by the glossy, metallic phone launchers of around 2011: a flip clock under a glass search strip with the weather (and its high and low) in a dark strip below it, glossy icons that stand straight on the wallpaper,
  and a dock shaped as an arch of dotted carbon with Phone and Camera in it and a big silver ring floating above. Grab the ring and pull it up, as far as the top of the screen if you like, and the app drawer grows round it as a circle;
  drag Phone or Camera onto the ring to open it. The drawer is a charcoal page with an "All apps" title bar and three glossy tabs (All apps, Frequent, Downloaded). Pages swing round as you swipe, and Leap view (pinch in, or from the home menu) shows them all.
  There is a single ring: it opens the drawer from home and, from inside the drawer, takes you back home, with optional haptics for grabbing it, passing the release point and dropping an app on it. The ring is Sense's way into your apps; the Hub and Active Frames are Spirit's. Modern, the default, is unchanged.
- **Flip clock and Steel Dial:** two clock widgets drawn by the launcher, also usable as a live wallpaper behind the lock screen. The flip clock can show the weather, which is off by default (see Privacy).
- **Translucent drawer in Spirit:** the wallpaper shows through the app drawer, and each app sits in a boxed tile with its name on a darker strip along the foot, in the manner of the boxed-tile launchers of the mid-2010s.
- **Hub:** one list for texts, missed calls, upcoming events and the notifications of your other apps, with filters, replying in place where an app allows it,
  and dismissing. Swipe in from the left edge or tap the Hub button by the search bar. It uses Android's notification access, which you grant yourself;
  notifications are held in memory only and are never saved or sent anywhere. Texts, calls and events work without it.
- **Dots on icons, Active Frames and tabs:** a count on an app's icon while it has notifications; a page to the left of home with your recent apps as live tiles;
  and, in Spirit, All / Recent / Favourites tabs in the drawer and named home pages. Each is its own switch, and Spirit turns them on by default.
- **Wallpapers:** seven original wallpapers, the same in every style and drawn on the spot at your screen's size: woven carbon, brushed steel, gold, rose gold, copper, bronze and platinum. They are in the wallpaper screen, with the usual crop and Home / Lock / Both choice. Swipe any Hub item away, or tap Clear all.
- **Peek, snooze and pin:** drag the Hub in from the left edge and it follows your finger; let go early and it slides back, or past a third and it stays. A swipe up and to the left from the bottom of home opens
  it too. Press and hold a row to pin it, snooze it for an hour or until the morning, show only that app, or hide that app from the Hub. A Priority view picks out missed calls, texts from real people and wording that asks for a reply,
  using plain rules on the phone. An optional soft tick (and a haptic one) marks opening, dismissing and clearing.
- **Full screen:** an optional mode that hides the status bar and navigation bar; a swipe from the top or bottom edge brings them back for a moment.
- **Type to search:** with a hardware keyboard, start typing anywhere on home and the keys go to the search bar.
- **Material 3 Expressive design:** colours from your wallpaper (or a fixed accent), light, dark or system, and an adaptive icon.

## Privacy

Everything is indexed into a private database inside the app. Models run on the CPU. The app has the INTERNET permission for two
reasons: downloading models when you tap Download, and, only if you switch on the flip clock's weather and choose a city, fetching that city's current weather from Open-Meteo (the city's coordinates are the only thing sent). The weather can optionally use your approximate location (a separate switch and permission, coarse only, rounded to about a kilometre) instead of a chosen city. Notification access, if you allow it for the Hub, only reads notifications on the phone. Downloads are checked against a SHA-256 before they are used, and
"Download on Wi-Fi only" is on by default. Each search source (contacts, messages, calls, calendar, files, photos) can be switched off
in Settings, and "Clear and rebuild" empties the index.

Some custom Android builds switch off network access for apps that were installed before they declared the INTERNET permission. If a download
never starts, open App info > Mobile data and Wi-Fi and allow network access for Nano Search.

## Install

Download the APK from the [latest release](https://github.com/JayMandava/nano-search/releases/latest), check it against `SHA256SUMS.txt`, allow "Install unknown apps" for your browser or file manager, and open it.
Release APKs are signed with the project's key (the certificate fingerprint is in each release's notes), so updates install over each other.

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

`./gradlew assembleRelease` makes the shrunk release build. It is signed with the keystore named in `keystore.properties` (path in the `NANO_KEYSTORE_PROPERTIES` environment variable, or `~/.nano-search-keystore/keystore.properties`), and with the debug key if there is none.

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
- [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) and [SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers) are used to build the optional GPU backend.
- Place names come from [GeoNames](https://www.geonames.org), licensed CC BY 4.0.
- Models are the work of their authors under the licences in the table above.

The Old School style uses the Source Sans 3 typeface (SIL Open Font License, copyright Adobe), bundled with its licence. The flip clock uses Play (SIL OFL, Playtype) and the Steel Dial uses Barlow (SIL OFL, The Barlow Project Authors), each bundled with its licence. Weather data comes from [Open-Meteo](https://open-meteo.com) (CC BY 4.0).
