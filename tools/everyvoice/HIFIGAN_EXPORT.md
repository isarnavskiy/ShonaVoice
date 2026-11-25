# Экспорт вокодера EveryVoice для шона

Скрипт экспортирует генератор HiFi-GAN из контрольной точки Shona OpenBible в ONNX opset 17. Модель принимает mel-спектрограмму float32 с именем `mel` и размером `[1,80,T]`. Выход `audio` имеет размер `[1,1,T*256]`. Частота дискретизации 22 050 Гц.

| Файл | Источник | SHA-256 |
| --- | --- | --- |
| vocoder.ckpt (988 272 011 байт) | [EveryVoice OpenBible Shona, фиксированная ревизия](https://huggingface.co/multilingual-tts/EveryVoice-OpenBible-Shona/resolve/76dd224de0f4f05034b6f4e561f21dd83dd6c102/vocoder.ckpt) | `48e7055d3b51abf5b4b85eb6bfe1dbbed121c48eb2e82388a7dabd037b677a80` |
| model.py | [EveryVoiceTTS HiFiGAN, фиксированный исходник](https://raw.githubusercontent.com/EveryVoiceTTS/HiFiGAN_iSTFT_lightning/dc2e1a459204ff1440c760f1c3a6874a0c8e490d/hfgl/model.py) | `cd1b64b96cde6f989ea17c1b1e58a6e4d00916ada5bcd52392ade912e839e932` |

Для воспроизведения использованы Python 3.12, PyTorch 2.6.0 CPU, ONNX 1.17.0 и ONNX Runtime 1.22.0. Установите NumPy и эти версии зависимостей, скачайте исходные файлы из таблицы и выполните команду.

```bash
python export_hifigan.py --checkpoint vocoder.ckpt --model-source model.py --output vocoder.onnx --report vocoder-report.json
```

Скрипт проверяет оба входных SHA-256, безопасно читает тензоры с `weights_only=True`, загружает все параметры генератора в строгом режиме и сравнивает вывод ONNX с PyTorch на пяти длинах спектрограммы. При проверке на 5–227 кадрах максимальное абсолютное расхождение составило 0,0000182. Отчёт содержит точные размеры, версии и контрольную сумму результата. Исходный контрольный файл относится к шагу обучения 100 000.

Опубликованная контрольная точка распространяется по [условиям CC BY-SA 4.0](https://huggingface.co/multilingual-tts/EveryVoice-OpenBible-Shona/tree/76dd224de0f4f05034b6f4e561f21dd83dd6c102). ONNX-представление сохраняет генератор и его веса.
