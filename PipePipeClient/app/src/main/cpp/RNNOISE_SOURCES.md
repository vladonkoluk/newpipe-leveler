# Исходники RNNoise в NewPipe Leveler

Оба каталога — официальный код Xiph.Org RNNoise (https://github.com/xiph/rnnoise),
лицензия BSD-3-Clause (см. `COPYING` в каждом каталоге). Код не изменялся.

## rnnoise_soft — «Мягкое» шумоподавление
- Тег `v0.1.1` (последняя «классическая» версия), веса модели в `rnn_data.c` — из того же тега.

## rnnoise_strong — «Сильное» шумоподавление
- Коммит основной ветки `70f1d25` («Remove outdated TRAINING-README», новее релиза 0.2).
- Модель — официальная, указанная в `model_version` этого коммита:
  `rnnoise_data-0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37.tar.gz`
  с https://media.xiph.org/rnnoise/models/. SHA-256 архива проверен и совпадает с `model_version`.
- Из архива взят облегчённый вариант модели: `src/rnnoise_data_little.c`
  (SHA-256 `eea1aa6fd8726d161876508a6f7d79022bf6ec82508660b4c1ac27c4f9b76c04`).
  Его заголовок совпадает с `rnnoise_data.h`, который и подключается.
- x86-оптимизации (`src/x86/*.c`) не включены: приложение собирается под ARM (NEON — `vec_neon.h`).
  Из `src/x86` взят только `x86_arch_macros.h`: `vec.h` подключает его всегда (макросы в нём — только для MSVC).
