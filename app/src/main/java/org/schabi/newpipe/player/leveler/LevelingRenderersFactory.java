package org.schabi.newpipe.player.leveler;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DefaultAudioSink;

/**
 * {@link DefaultRenderersFactory}, который добавляет в звук {@link LevelerAudioProcessor}.
 * Остальное — как у ExoPlayer по умолчанию.
 */
public class LevelingRenderersFactory extends DefaultRenderersFactory {

    public LevelingRenderersFactory(@NonNull final Context context) {
        super(context);
    }

    @Nullable
    @Override
    protected AudioSink buildAudioSink(@NonNull final Context context,
                                       final boolean enableFloatOutput,
                                       final boolean enableAudioTrackPlaybackParams,
                                       final boolean enableOffload) {
        return new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setOffloadMode(enableOffload
                        ? DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
                        : DefaultAudioSink.OFFLOAD_MODE_DISABLED)
                .setAudioProcessors(new AudioProcessor[]{
                        new LevelerAudioProcessor(LevelerSettings.getParams(context))})
                .build();
    }
}
