# PipePipe Leveler

[PipePipe](https://github.com/InfinityLoop1308/PipePipe) 5.5.0 (сторонний клиент YouTube на основе
NewPipe, поддерживает вход в аккаунт YouTube) с тем же выравниванием громкости и шумоподавлением,
что и NewPipe Leveler (ветка `main` этого репозитория).

**Скачать:** [PipePipeLeveler.apk](https://github.com/vladonkoluk/newpipe-leveler/releases/download/pipepipe-latest/PipePipeLeveler.apk)
(arm64, для большинства телефонов). Для старых 32-битных телефонов —
[PipePipeLeveler-armeabi-v7a.apk](https://github.com/vladonkoluk/newpipe-leveler/releases/download/pipepipe-latest/PipePipeLeveler-armeabi-v7a.apk).

Ставится отдельным приложением «PipePipe Leveler» рядом с обычным PipePipe и NewPipe Leveler.

## Настройки
Настройки → Видео и аудио → раздел «Выравнивание громкости» (в конце списка): ровная громкость,
сила выравнивания (до «Ультра»), шумоподавление (Мягкое / Сильное / DeepFilterNet),
«Беречь речь», разборчивая речь, уровень, усиление тихого, сжатие громкого.
Подробности и замеры — в README ветки `main`.

Вход в аккаунт YouTube (против ошибки «Подтвердите, что вы не бот») — штатная функция PipePipe:
Настройки → Аккаунт → YouTube. Лучше входить запасным аккаунтом.

## Что изменено относительно PipePipe
- `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/leveler/` — выравнивание и
  шумоподавление (тот же код, что в NewPipe Leveler);
- `PipePipeClient/app/src/main/cpp` — RNNoise, `PipePipeClient/app/src/main/rust` — DeepFilterNet3
  (происхождение и контрольные суммы — в `RNNOISE_SOURCES.md` и `DEEPFILTERNET_SOURCES.md`);
- `Player.java`, `CustomRenderersFactory.java` — звук идёт через выравнивание;
- `VideoAudioSettingsFragment.java`, `video_audio_settings.xml`, ресурсы `leveler_*` — настройки;
- `app/build.gradle` — нативная сборка, только ARM, свой ключ подписи, отдельный id приложения
  (`.leveler`); `.github/workflows/build-pipepipe-leveler.yml` — сборка.

Ключ подписи `app/leveler.keystore` лежит в репозитории, поэтому устанавливайте APK только
со страницы релизов этого репозитория.

Основа: PipePipe v5.5.0 (InfinityLoop1308/PipePipe@3fd38c6, PipePipeClient@b91f008,
PipePipeExtractor@1a09d20), лицензия GPLv3 (`LICENSE`). Исходное описание — `README.PipePipe.md`.
RNNoise — BSD-3-Clause, DeepFilterNet — MIT/Apache-2.0.
