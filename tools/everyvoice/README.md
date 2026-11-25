# Экспорт голоса EveryVoice для шона

Акустическая модель предсказывает спектрограмму по тексту. Вокодер преобразует её в монофоническую звуковую дорожку 22 050 Гц. Оба графа работают на CPU через ONNX Runtime 1.22.0.

| Файл | Вход | Выход |
| --- | --- | --- |
| `acoustic.onnx` | `tokens`, int64, [1, число токенов] | `mel`, float32, [1, 80, число кадров] |
| `vocoder.onnx` | `mel`, float32, [1, 80, число кадров] | `audio`, float32, [1, 1, число кадров × 256] |

## Воспроизведение

Команды ниже выполняются из каталога `tools/everyvoice`. Контрольные точки акустической модели (218 514 762 байта) и вокодера (988 272 011 байт) в сумме занимают 1,21 ГБ. Готовые два графа и словарь занимают 126,6 МБ.

Эталонный экспорт выполнен на Linux x86‑64 с Python 3.12, PyTorch 2.6.0+cpu, ONNX 1.17.0 и ONNX Runtime 1.22.0. Нужен выпуск EveryVoice 0.5.0 как wheel-файл с исходниками слоёв. Параметр `--conformer-source` принимает файл из torchaudio 2.6.0. Скрипт проверяет контрольные суммы каждого входного файла и загружает веса через `torch.load(weights_only=True)`.

```bash
python -m pip install --index-url https://download.pytorch.org/whl/cpu torch==2.6.0
python -m pip install onnx==1.17.0 onnxruntime==1.22.0
python -m pip download --no-deps everyvoice==0.5.0
curl -fL -o conformer.py https://raw.githubusercontent.com/pytorch/audio/v2.6.0/src/torchaudio/models/conformer.py
curl -fL -o feature_prediction.ckpt https://huggingface.co/multilingual-tts/EveryVoice-OpenBible-Shona/resolve/76dd224de0f4f05034b6f4e561f21dd83dd6c102/feature_prediction.ckpt
python export_everyvoice_acoustic.py \
  --checkpoint feature_prediction.ckpt \
  --everyvoice-wheel everyvoice-0.5.0-py3-none-any.whl \
  --conformer-source conformer.py \
  --output shona
```

Скрипт создаёт `acoustic.onnx` и `voice.json`. Команда для второго графа приведена в [описании экспорта вокодера](HIFIGAN_EXPORT.md).

## Совместимость

Экспорт фиксирует диктора `SPEAKER_00_Shona` из исходной контрольной точки. Вход содержит одну фразу без заполнителей. Словарь `tokens` в `voice.json` соответствует таблице эмбеддингов контрольной точки FastSpeech2 1.2. Сохраняются регистр букв и исходная пунктуация. Последовательности пробелов превращаются в один пробел, начальные и конечные пробелы удаляются. Символы начала и конца фразы и пустые токены между буквами не добавляются.

Для динамического экспорта внимание и растяжение фонетических признаков по предсказанной длительности выражены через операции ONNX opset 17. При проверке сравниваются оригинальные слои PyTorch, их экспортируемая реализация и ONNX Runtime на трёх фразах разной длины. В релизном экспорте все формы выходных тензоров совпали, максимальное абсолютное отклонение в спектрограмме составило 0,000012.

| Артефакт | Байт | SHA-256 |
| --- | ---: | --- |
| `acoustic.onnx` | 70 842 432 | `40e55f8b3d85b0a2a16ea804efd58ecf22778938a990791b5ca28969855eaad1` |
| `voice.json` | 1 186 | `4887b1c08741242d19e1d5ef31fce4d09a3d7bb5ba69cd63eaa9c49c2d939119` |
| `vocoder.onnx` | 55 743 663 | `4c398991d4d4cee2c09b7de6870d40044d8992bc1bac010722cd3aa6846fa13f` |

Исходная модель и экспортированные веса распространяются по CC BY-SA 4.0. [Карточка закреплённой версии EveryVoice-OpenBible-Shona](https://huggingface.co/multilingual-tts/EveryVoice-OpenBible-Shona/tree/76dd224de0f4f05034b6f4e561f21dd83dd6c102) · [Условия лицензии](https://creativecommons.org/licenses/by-sa/4.0/)
