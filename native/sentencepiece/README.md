# Нативный токенизатор NLLB

Приложение использует Google SentencePiece v0.2.1 с исходной моделью `nllb/sentencepiece.bpe.model`. JNI-обёртка кодирует и декодирует UTF-8. Идентификаторы языков и преобразование между индексами SentencePiece и NLLB задаёт Kotlin-класс `Nllb`. Версия скомпилирована для arm64-v8a и API 26, с ELF-выравниванием 16 КиБ.

Готовая библиотека хранится в `app/src/main/jniLibs/arm64-v8a/`. Чтобы собрать её заново, установите из Android SDK Manager NDK 27.2.12479018 и CMake 3.22.1. В каталоге проекта выполните команду, указав путь к Android SDK.

```bash
python tools/rebuild_tokenizer.py --sdk "/path/to/Android/Sdk"
```

CMake загружает исходный архив с контрольной суммой SHA-256, указанной в `CMakeLists.txt`. Для сборки без сети передайте `--source` с путём к уже распакованным исходникам той же версии. Повторная сборка APK включает обновлённый файл. Тексты лицензий включены в `app/src/main/assets/licenses/sentencepiece/`.
