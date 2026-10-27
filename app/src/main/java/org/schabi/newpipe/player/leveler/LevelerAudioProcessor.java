package org.schabi.newpipe.player.leveler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * Встраивает {@link LoudnessLeveler} в цепочку звука ExoPlayer. Звук обрабатывается внутри
 * плеера, до вывода: без задержки (кроме 5 мс лимитера) и без треска.
 * Работает с 16-битным PCM; остальное (например, passthrough) пропускает как есть.
 */
public final class LevelerAudioProcessor extends BaseAudioProcessor {
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
        final int count = bytes / 2;
        if (samples.length < count) {
            samples = new short[count];
        }
        // как SonicAudioProcessor: сэмплы в родном порядке байтов
        inputBuffer.asShortBuffer().get(samples, 0, count);
        inputBuffer.position(inputBuffer.position() + count * 2);
        leveler.process(samples, count / leveler.getChannelCount(), sampleRate);
        output.asShortBuffer().put(samples, 0, count);
        output.position(count * 2);
        output.flip();
    }
}
