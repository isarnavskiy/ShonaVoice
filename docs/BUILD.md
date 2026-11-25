# Сборка Shona Voice для Android

Проект собирает одно приложение с интерфейсом и всеми моделями русского языка и шона. На компьютере понадобятся JDK 17, Python 3.8 или новее, Android SDK Platform 35 и Android SDK Build-Tools 35.0.0. Для команд установки также нужны Android SDK Platform-Tools. Проверенный Wrapper загружает Gradle 8.9, а Gradle загружает Android Gradle Plugin 8.7.3, Kotlin 2.1.21 и ONNX Runtime 1.22.0. Для первой загрузки этих зависимостей нужен интернет.

## Подготовить модели

1. Скачайте [ShonaVoice-Offline.apk из релиза v1.0.2](https://github.com/isarnavskiy/ShonaVoice/releases/download/v1.0.2/ShonaVoice-Offline.apk).
2. Откройте терминал в корне этого репозитория и передайте скрипту путь к скачанному файлу.

```console
python tools/prepare_models.py --apk "/path/to/ShonaVoice-Offline.apk"
```

В Windows, если Python установлен через Python Launcher, используйте `py -3` вместо `python`. При успешной проверке появится сообщение `All 16 model files are ready.` Скрипт проверяет SHA-256 исходного APK и каждого извлечённого файла. Повторный запуск проверяет уже извлечённые файлы и заменяет повреждённые копии. Индекс моделей находится в [tools/release-pack.json](../tools/release-pack.json).

| Что проверяет скрипт | Значение для v1.0.2 |
| --- | --- |
| Размер скачанного APK | 1 715 381 351 байт |
| SHA-256 скачанного APK | `d61d415821620e9fba204cb668bd44e8d30390911a58040c3962b9bb17e64bdb` |
| Проверенные файлы моделей | 16, всего 2 212 985 165 байта |
| Каталог моделей на компьютере | `model-assets/embedded_models/` |
| Каталог моделей в новом APK | `assets/embedded_models/` |

Учитывайте размер исходного APK, извлечённых моделей, временных файлов Gradle и результата. Для свежей сборки выделите около 12 ГБ на диске. Gradle использует до 4 ГБ Java-кучи и один рабочий поток при упаковке крупных файлов.

## Собрать и установить

Укажите расположение Android SDK через переменную окружения `ANDROID_HOME` или настройку SDK в Android Studio. Из корня проекта выполните команды для своей системы.

| Действие | Linux или macOS | Windows PowerShell |
| --- | --- | --- |
| Сборка отладочного APK | `./gradlew :app:assembleDebug` | `.\gradlew.bat :app:assembleDebug` |
| Установка на подключённый телефон | `adb install -r app/build/outputs/apk/debug/app-debug.apk` | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Подготовка релизной сборки для собственной подписи | `./gradlew :app:assembleRelease` | `.\gradlew.bat :app:assembleRelease` |

Отладочный APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Для установки по USB включите на телефоне отладку и подтвердите подключение. Запустите Shona Voice с рабочего стола. При первом запуске приложение проверяет и извлекает модели из установленного APK. Для подготовки и кеша после установки потребуется ещё около 2,73 ГБ.

Для распространения собственной релизной сборки подпишите `app/build/outputs/apk/release/app-release-unsigned.apk` своим ключом через Android Studio **Generate Signed Bundle / APK** или Android SDK Build-Tools `apksigner`. При обновлении приложения Android сверяет сертификаты. Если на телефоне установлена версия с другой подписью, используйте отдельный тестовый профиль или устройство. Удаление ранее установленного приложения стирает его локальные данные.

## Проверка пакета

В Android SDK Build-Tools запустите следующие команды, указав путь к своей сборке.

```console
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
zipalign -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

В манифесте приложения ожидаются пакет `dev.nikita.voice.offline`, минимальный API 26, целевой API 34 и ABI `arm64-v8a`. При нажатии на иконку запускается `dev.nikita.voice.BootstrapActivity`. Затем откроется экран переводчика `dev.nikita.voice.MainActivity`. Переведите короткую фразу в каждом направлении, включите озвучивание и повторите проверку в авиарежиме. Во время голосового ввода разрешите доступ к микрофону. Отпечаток APK из таблицы относится к опубликованному файлу v1.0.2. Локальная сборка содержит подпись выбранного вами ключа.

## Исходники и сторонние компоненты

| Путь | Содержимое |
| --- | --- |
| `app/src/main/java/dev/nikita/voice/` | Код Android, запись с микрофона, распознавание, перевод, синтез речи и подготовка моделей |
| `app/src/main/assets/` | Интерфейс WebView, мост к Android и уведомления о лицензиях |
| `app/src/main/res/` | Ресурсы Android, иконка приложения и тема |
| `app/src/main/resources/` | Небольшой вспомогательный граф ONNX, рецепт сборки декодера и данные декодирования шона |
| `app/src/main/jniLibs/arm64-v8a/` | Готовая JNI-библиотека SentencePiece для ARM64 |
| `native/sentencepiece/` | Исходники JNI-обёртки SentencePiece и CMake |

В репозитории есть готовая нативная библиотека, поэтому обычная сборка обходится без NDK. Её исходный код, зафиксированная версия SentencePiece и порядок самостоятельной сборки описаны в [инструкции JNI](../native/sentencepiece/README.md). Происхождение моделей и условия распространения приведены в [NOTICE.md](../NOTICE.md).
