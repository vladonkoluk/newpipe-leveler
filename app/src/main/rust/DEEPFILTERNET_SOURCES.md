# DeepFilterNet3 в NewPipe Leveler

Уровень шумоподавления «DeepFilterNet» — официальная библиотека DeepFilterNet
(Hendrik Schröter, https://github.com/Rikorose/DeepFilterNet), лицензия MIT или Apache-2.0
на выбор (`LICENSE`, `LICENSE-MIT`, `LICENSE-APACHE`).

## Что взято из официального репозитория
- Коммит `d375b2d8309e0935d165700c91da9de862a99c31` (25.09.2024), целостность клона
  проверена `git fsck`.
- `libDF/` — крейт `deep_filter` 0.5.7-pre, скопирован без изменений.
- `models/DeepFilterNet3_onnx.tar.gz` — официальная модель DeepFilterNet3 из того же коммита,
  SHA-256 `c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616`.
  Встраивается в библиотеку при сборке (`include_bytes!`, возможность `default-model`).
- `Cargo.lock` — от авторов; в него добавлены только зависимости обвязки (`jni`).
- Вывод нейросети — на tract (чистый Rust, без ONNX Runtime).

## Своё
- `dfjni/` — JNI-обвязка для `org.schabi.newpipe.player.leveler.DeepFilterNet`:
  создание (`DfTract` с параметрами по умолчанию, моно, без предела подавления, без
  пост-фильтра), обработка кадра 480 сэмплов при 48 кГц, удаление. Паники Rust ловятся и не
  роняют приложение.

## Сборка
В CI (`.github/workflows/build-leveler.yml`):
```
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked
cd app/src/main/rust
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../jniLibs build --release --locked -p dfjni
```
Gradle кладёт `app/src/main/jniLibs/<abi>/libdfjni.so` в APK. Без этого шага приложение
собирается и работает, но уровень «DeepFilterNet» сообщит, что библиотека не загрузилась.

## Проверка на компьютере (до встраивания)
- Обработка через JNI совпадает с официальной программой `deep-filter` того же коммита;
  задержка нейросети — 1440 сэмплов (3 кадра по 10 мс).
- Записи с настоящей уличной съёмкой, речь en/ru, громкий шум (0 dB) и тише (10 dB),
  вся цепочка приложения при 44.1 кГц (DeepFilterNet + детектор речи RNNoise для
  «Беречь речь»):

| «Беречь речь» | Пропало речи (громко / тише) | Шум во время речи | Шум в паузах | PESQ голоса en/ru |
|---|---|---|---|---|
| выкл | 17% / 2% | −6.4 dB | −38.7 dB | 4.52 / 3.70 |
| 30% | 11% / 1% | −5.8 dB | −27.9 dB | 4.57 / 4.40 |
| 50% | 6% / 0% | −4.3 dB | −24.9 dB | 4.57 / 4.55 |
| 70% | 4% / 0% | −2.6 dB | −22.7 dB | 4.57 / 4.59 |

Для сравнения, RNNoise «Сильное»: шум во время речи около −5 dB без подмешивания и около −2 dB
с «Беречь речь» 70%. Вся цепочка работала примерно в 7 раз быстрее реального времени на одном
ядре сервера; на телефоне нагрузка заметно выше, чем у RNNoise.
