package dev.nikita.voice

/** SentencePiece uses standard UTF-8. JNI strings use modified UTF-8, so pass bytes. */
internal object NativeSentencePiece {
    init { System.loadLibrary("nikita_sentencepiece") }

    fun encode(modelPath: String, text: String): IntArray =
        encodeBytes(modelPath.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_8))

    fun decode(modelPath: String, pieces: IntArray): String =
        String(decodeBytes(modelPath.toByteArray(Charsets.UTF_8), pieces), Charsets.UTF_8)

    private external fun encodeBytes(path: ByteArray, text: ByteArray): IntArray
    private external fun decodeBytes(path: ByteArray, ids: IntArray): ByteArray
}
