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

    private final LoudnessLeveler.Params params;
    @Nullable
    private LoudnessLeveler leveler;
    private int sampleRate;
    private short[] samples = new short[0];

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
            return;
        }
        if (leveler == null || leveler.getChannelCount() != format.channelCount
                || sampleRate != format.sampleRate) {
            sampleRate = format.sampleRate;
            leveler = new LoudnessLeveler(format.sampleRate, format.channelCount, params);
        } else {
            // перемотка или новый ролик: старый звук из линии задержки не нужен,
            // а набранное усиление сохраняем, чтобы не было скачка громкости
            leveler.clearDelay();
        }
    }

    @Override
    protected void onReset() {
        leveler = null;
        samples = new short[0];
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
        leveler.process(samples, count / leveler.getChannelCount(), sampleRate);
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
}
