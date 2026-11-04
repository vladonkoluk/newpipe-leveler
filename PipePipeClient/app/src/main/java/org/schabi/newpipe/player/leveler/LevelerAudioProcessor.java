package org.schabi.newpipe.player.leveler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Встраивает {@link LoudnessLeveler} в цепочку звука ExoPlayer. Звук обрабатывается внутри
 * плеера, до вывода: без треска и без заметной задержки (25 мс: заглядывание вперёд и лимитер).
 * Работает с 16-битным PCM; остальное (например, passthrough) пропускает как есть.
 */
public final class LevelerAudioProcessor extends BaseAudioProcessor {
    // для строки состояния в настройках: работает ли обработка и какая сейчас поправка
    @Nullable
    private static volatile LoudnessLeveler lastLeveler;
    private static volatile long lastAudioNanos;
    private static volatile int lastOfferedEncoding = C.ENCODING_INVALID;
    // какое шумоподавление сейчас работает (для строки состояния)
    private static volatile int activeNoise = LoudnessLeveler.Params.NOISE_OFF;

    private final LoudnessLeveler.Params params;
    @Nullable
    private LoudnessLeveler leveler;
    private int sampleRate;
    private short[] samples = new short[0];
    // шумоподавление нейросетью — перед выравниванием; null, если выключено
    @Nullable
    private NoiseSuppressor suppressor;
    private int suppressorLevel = LoudnessLeveler.Params.NOISE_OFF;

    public LevelerAudioProcessor(@NonNull final LoudnessLeveler.Params params) {
        this.params = params;
    }

    @NonNull
    @Override
    protected AudioFormat onConfigure(@NonNull final AudioFormat inputAudioFormat) {
        lastOfferedEncoding = inputAudioFormat.encoding;
        return inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
                ? inputAudioFormat : AudioFormat.NOT_SET;
    }

    @Override
    protected void onFlush() {
        final AudioFormat format = inputAudioFormat;
        if (format.encoding != C.ENCODING_PCM_16BIT) {
            leveler = null;
            releaseSuppressor();
            return;
        }
        if (leveler == null || leveler.getChannelCount() != format.channelCount
                || sampleRate != format.sampleRate) {
            sampleRate = format.sampleRate;
            leveler = new LoudnessLeveler(format.sampleRate, format.channelCount, params);
            releaseSuppressor(); // частота или каналы поменялись — создадим заново
        } else {
            // перемотка или новый ролик: старый звук из линии задержки не нужен,
            // а набранное усиление сохраняем, чтобы не было скачка громкости
            leveler.clearDelay();
            if (suppressor != null) {
                suppressor.clear();
            }
        }
    }

    @Override
    protected void onReset() {
        leveler = null;
        samples = new short[0];
        releaseSuppressor();
    }

    private void releaseSuppressor() {
        if (suppressor != null) {
            suppressor.release();
            suppressor = null;
        }
        suppressorLevel = LoudnessLeveler.Params.NOISE_OFF;
        activeNoise = LoudnessLeveler.Params.NOISE_OFF;
    }

    /** Включает, выключает или меняет шумоподавление, если его переключили в настройках. */
    private void updateSuppressor(final int channelCount) {
        // «Ровная громкость» выключена — выключается и шумоподавление: звук как в оригинале
        final int want = params.enabled ? params.noise : LoudnessLeveler.Params.NOISE_OFF;
        if (want == suppressorLevel) {
            return;
        }
        releaseSuppressor();
        suppressorLevel = want; // даже если нейросеть не загрузится — не пытаться каждый буфер
        if (want != LoudnessLeveler.Params.NOISE_OFF) {
            final NativeRnNoise engine = NativeRnNoise.create(want);
            // у DeepFilterNet для «Беречь речь» речь распознаёт RNNoise: оценка DeepFilterNet
            // (SNR кадра) в громком шуме низкая там, где он глушит речь, и подмешивание не
            // срабатывает. Если RNNoise не загрузится — работаем без отдельного детектора
            final NativeRnNoise detector = engine != null
                    && want == LoudnessLeveler.Params.NOISE_DFN
                    ? NativeRnNoise.create(LoudnessLeveler.Params.NOISE_STRONG) : null;
            if (engine != null) {
                suppressor = new NoiseSuppressor(sampleRate, channelCount, engine, detector);
                activeNoise = want;
            }
        }
    }

    @Override
    public void queueInput(@NonNull final ByteBuffer inputBuffer) {
        final int bytes = inputBuffer.remaining();
        if (bytes == 0) {
            return;
        }
        final ByteBuffer output = replaceOutputBuffer(bytes);
        if (leveler == null) {
            output.put(inputBuffer);
            output.flip();
            return;
        }
        LevelerSettings.refreshIfStale(); // изменения настроек — прямо во время просмотра
        final int count = bytes / 2;
        if (samples.length < count) {
            samples = new short[count];
        }
        // как SonicAudioProcessor: сэмплы в родном порядке байтов
        inputBuffer.asShortBuffer().get(samples, 0, count);
        inputBuffer.position(inputBuffer.position() + count * 2);
        final int frames = count / leveler.getChannelCount();
        updateSuppressor(leveler.getChannelCount());
        if (suppressor != null) {
            suppressor.setKeepSpeech(params.keepSpeech);
            suppressor.process(samples, frames);
        }
        leveler.process(samples, frames, sampleRate);
        lastLeveler = leveler;
        lastAudioNanos = System.nanoTime();
        output.asShortBuffer().put(samples, 0, count);
        output.position(count * 2);
        output.flip();
    }

    /** Обработчик, через который последним проходил звук, или null, если звука ещё не было. */
    @Nullable
    static LoudnessLeveler getLastLeveler() {
        return lastLeveler;
    }

    /** Сколько миллисекунд назад через выравнивание проходил звук. */
    static long msSinceLastAudio() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastAudioNanos);
    }

    /** Формат звука, который плеер последним предлагал обработчику. */
    static int getLastOfferedEncoding() {
        return lastOfferedEncoding;
    }

    /** Какое шумоподавление сейчас работает: {@link LoudnessLeveler.Params#NOISE_OFF} и т.д. */
    static int getActiveNoise() {
        return activeNoise;
    }
}
