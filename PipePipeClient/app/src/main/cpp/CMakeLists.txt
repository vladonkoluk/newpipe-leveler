# Шумоподавление NewPipe Leveler: две версии RNNoise (Xiph.Org, BSD-3-Clause), каждая в своей
# библиотеке. Происхождение и контрольные суммы — в RNNOISE_SOURCES.md.
cmake_minimum_required(VERSION 3.18)
project(newpipe_leveler_rnnoise C)

set(COMMON_FLAGS -O2 -fvisibility=hidden -ffunction-sections -fdata-sections -w)
set(COMMON_LINK -Wl,--gc-sections -Wl,-Bsymbolic -Wl,--exclude-libs,ALL)

# «Мягкое» — RNNoise 0.1.1
add_library(rnnoise_soft SHARED
        rnnoise_jni.c
        rnnoise_soft/denoise.c rnnoise_soft/rnn.c rnnoise_soft/rnn_data.c
        rnnoise_soft/rnn_reader.c rnnoise_soft/pitch.c rnnoise_soft/kiss_fft.c
        rnnoise_soft/celt_lpc.c)
target_include_directories(rnnoise_soft PRIVATE rnnoise_soft)
target_compile_definitions(rnnoise_soft PRIVATE JNI_CLASS=RnNoiseSoft)
target_compile_options(rnnoise_soft PRIVATE ${COMMON_FLAGS})
target_link_options(rnnoise_soft PRIVATE ${COMMON_LINK})
target_link_libraries(rnnoise_soft m)

# «Сильное» — RNNoise из основной ветки с официальной моделью little
add_library(rnnoise_strong SHARED
        rnnoise_jni.c
        rnnoise_strong/denoise.c rnnoise_strong/rnn.c rnnoise_strong/pitch.c
        rnnoise_strong/kiss_fft.c rnnoise_strong/celt_lpc.c rnnoise_strong/nnet.c
        rnnoise_strong/nnet_default.c rnnoise_strong/parse_lpcnet_weights.c
        rnnoise_strong/rnnoise_tables.c rnnoise_strong/rnnoise_data_little.c)
target_include_directories(rnnoise_strong PRIVATE rnnoise_strong)
target_compile_definitions(rnnoise_strong PRIVATE JNI_CLASS=RnNoiseStrong)
target_compile_options(rnnoise_strong PRIVATE ${COMMON_FLAGS})
target_link_options(rnnoise_strong PRIVATE ${COMMON_LINK})
target_link_libraries(rnnoise_strong m)
