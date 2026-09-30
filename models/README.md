# Models

Weights are not stored in this repo. Put the files on the phone under the app's `files/models/` folder (or download them from the app once the model manager lands).

| Job | File | Source |
| --- | --- | --- |
| Understanding | `parser.gguf` | Qwen3.5 2B Q4_0 |
| Answering | `answer.gguf` | Gemma 4 E2B Q4_0 |
| Voice | `whisper.bin` | whisper.cpp `ggml-small.en-q5_1.bin` |
| Photo search | `clip-image.onnx`, `clip-text.onnx` | MobileCLIP-S0 (Apple, research-only licence) |
| Text in photos | `ocr-det.onnx`, `ocr-rec.onnx` | PaddleOCR PP-OCRv4 (Apache-2.0) |
