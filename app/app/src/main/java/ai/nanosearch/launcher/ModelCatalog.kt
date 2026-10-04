package ai.nanosearch.launcher

/** The five jobs a model does in Nano Search. Each has its own choice of model. */
enum class Slot(val title: String, val blurb: String) {
    PARSER("Understanding", "Turns what you type or say into an action. Smaller is faster; larger is more accurate."),
    ANSWER("Answering", "Writes the answer when you ask a question."),
    VOICE("Voice", "Turns speech into text for the mic button."),
    PHOTO("Photo search", "Lets you find pictures by what is in them."),
    OCR("Text in photos", "Reads text in screenshots, receipts and documents."),
}

/** How a chat model wants its prompt wrapped. */
enum class ChatFormat(private val sysOpen: String, private val userOpen: String, private val userClose: String, private val reply: String) {
    CHATML("<|im_start|>system\n", "<|im_end|>\n<|im_start|>user\n", "<|im_end|>\n<|im_start|>assistant\n", ""),
    GEMMA("<|turn>system\n", "<turn|>\n<|turn>user\n", "<turn|>\n<|turn>model\n", "");

    /** Everything before the user's words: identical on every request, so it is processed once and cached. */
    fun prefix(system: String) = sysOpen + system + userOpen

    /** A whole user turn that follows an earlier answer: closes that answer, opens the user's turn, and ends with the cue for the reply. */
    fun turn(userText: String, skipThinking: Boolean) = userOpen + userText + suffix(skipThinking)

    /** Everything after them. [skipThinking] closes Qwen's reasoning block up front so it answers at once. */
    fun suffix(skipThinking: Boolean) = userClose + if (skipThinking) "<think>\n\n</think>\n\n" else reply
}

/** One downloadable file with the hash it must have, so a corrupt or swapped download is never used. */
class Part(val file: String, val url: String, val sha256: String, val bytes: Long)

class ModelEntry(
    val id: String,
    val slot: Slot,
    val name: String,
    val note: String,
    val license: String,
    val parts: List<Part>,
    val format: ChatFormat = ChatFormat.CHATML,
    /** Qwen 3.5 reasons before answering unless told not to. */
    val skipThinking: Boolean = false,
    val custom: Boolean = false,
) {
    val bytes get() = parts.sumOf { it.bytes }
    /** What loading it takes in memory: the weights plus working space. */
    val ramBytes get() = when (slot) { Slot.PARSER, Slot.ANSWER -> (bytes * 1.25).toLong() + 300_000_000; else -> bytes * 2 }
    val file get() = parts.first().file
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"
    private fun qwen(size: String, bytes: Long, sha: String) = Part("qwen3.5-$size-q4_0.gguf", "$HF/unsloth/Qwen3.5-${size.uppercase()}-GGUF/resolve/main/Qwen3.5-${size.uppercase()}-Q4_0.gguf", sha, bytes)
    private val q08 = qwen("0.8b", 507_154_688, "444406ddd926550c724ec18d5120a9d40ded44908a063b0e66e9a7e5464c652c")
    private val q2 = qwen("2b", 1_214_873_856, "cd70221bebaee0503e0f6717e174250cd7825aa88438b3aabec9ad55731d9bb1")
    private val q4 = qwen("4b", 2_583_221_408, "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb")
    private val gemma = Part("gemma-4-e2b-it-q4_0.gguf", "$HF/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf", "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52", 2_841_481_184)
    private fun whisper(size: String, bytes: Long, sha: String) = Part("whisper-$size.en-q5_1.bin", "$HF/ggerganov/whisper.cpp/resolve/main/ggml-$size.en-q5_1.bin", sha, bytes)

    const val QWEN = "Apache-2.0"

    val all = listOf(
        ModelEntry("parser-qwen35-0.8b", Slot.PARSER, "Qwen3.5 0.8B", "Fastest: about 1.1 s a request. 35 of 46 test requests right; slips on unusual wording.", QWEN, listOf(q08), skipThinking = true),
        ModelEntry("parser-qwen35-2b", Slot.PARSER, "Qwen3.5 2B", "Recommended: about 1.8 s a request. 45 of 46 test requests right.", QWEN, listOf(q2), skipThinking = true),
        ModelEntry("parser-qwen35-4b", Slot.PARSER, "Qwen3.5 4B", "Most accurate, slower and heavier.", QWEN, listOf(q4), skipThinking = true),

        ModelEntry("answer-gemma4-e2b", Slot.ANSWER, "Gemma 4 E2B", "Recommended. Clear, well-written answers.", "Gemma terms", listOf(gemma), ChatFormat.GEMMA),
        ModelEntry("answer-qwen35-2b", Slot.ANSWER, "Qwen3.5 2B", "Smaller and quicker, plainer answers.", QWEN, listOf(q2), skipThinking = true),
        ModelEntry("answer-qwen35-4b", Slot.ANSWER, "Qwen3.5 4B", "Stronger reasoning, slower.", QWEN, listOf(q4), skipThinking = true),

        ModelEntry("voice-tiny", Slot.VOICE, "Whisper tiny (English)", "Quickest, mishears more.", "MIT", listOf(whisper("tiny", 32_166_155, "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"))),
        ModelEntry("voice-base", Slot.VOICE, "Whisper base (English)", "A good balance.", "MIT", listOf(whisper("base", 59_721_011, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"))),
        ModelEntry("voice-small", Slot.VOICE, "Whisper small (English)", "Recommended. Most accurate, about 2-3 seconds per phrase.", "MIT", listOf(whisper("small", 190_098_681, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"))),

        ModelEntry(
            "photo-mobileclip-s0", Slot.PHOTO, "MobileCLIP S0", "Matches pictures with words on the phone. Weights are for research use only.", "Apple research licence",
            listOf(
                Part("clip-image.onnx", "$HF/Xenova/mobileclip_s0/resolve/main/onnx/vision_model.onnx", "17d3c037b1d488c10c50e09f6009ea5a198caef4e0e8f4ea5617b7cb2d067ac0", 45_543_630),
                Part("clip-text.onnx", "$HF/Xenova/mobileclip_s0/resolve/main/onnx/text_model.onnx", "f6e9bd5742bfc515889e901634d8a2ff2a57fab8564e4ad3760e800b1a51b77c", 169_807_789),
            ),
        ),
        ModelEntry(
            "ocr-ppocrv4", Slot.OCR, "PaddleOCR v4 (English and Chinese)", "Reads printed text. No Hindi yet.", "Apache-2.0",
            listOf(
                Part("ocr-det.onnx", "$HF/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx", "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9", 4_745_517),
                Part("ocr-rec.onnx", "$HF/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx", "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b", 10_857_958),
            ),
        ),
    )

    fun forSlot(slot: Slot) = all.filter { it.slot == slot }
    fun defaultFor(slot: Slot) = when (slot) {
        Slot.PARSER -> all.first { it.id == "parser-qwen35-2b" }
        Slot.ANSWER -> all.first { it.id == "answer-gemma4-e2b" }
        Slot.VOICE -> all.first { it.id == "voice-small" }
        else -> forSlot(slot).first()
    }
}
