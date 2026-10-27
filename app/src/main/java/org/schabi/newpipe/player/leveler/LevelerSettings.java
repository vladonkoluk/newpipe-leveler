package org.schabi.newpipe.player.leveler;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.C;

import org.schabi.newpipe.R;

import java.util.Locale;

/**
 * Настройки выравнивания громкости из экрана «Видео и аудио». Один общий
 * {@link LoudnessLeveler.Params} на всё приложение: изменения применяются сразу,
 * даже во время воспроизведения.
 */
public final class LevelerSettings {
    private static LoudnessLeveler.Params params;
    // слушатель хранится в поле: SharedPreferences держит слушателей по слабой ссылке
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private LevelerSettings() {
    }

    @NonNull
    public static synchronized LoudnessLeveler.Params getParams(@NonNull final Context context) {
        if (params == null) {
            final Context app = context.getApplicationContext();
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
            params = new LoudnessLeveler.Params();
            apply(app, prefs);
            listener = (p, key) -> apply(app, p);
            prefs.registerOnSharedPreferenceChangeListener(listener);
        }
        return params;
    }

    private static void apply(@NonNull final Context c, @NonNull final SharedPreferences prefs) {
        params.enabled = prefs.getBoolean(c.getString(R.string.leveler_enabled_key), true);
        params.target = number(prefs, c.getString(R.string.leveler_target_key),
                c.getString(R.string.leveler_target_default));
        params.strength = number(prefs, c.getString(R.string.leveler_strength_key),
                c.getString(R.string.leveler_strength_default)) / 100;
        params.maxBoost = number(prefs, c.getString(R.string.leveler_max_boost_key),
                c.getString(R.string.leveler_max_boost_default));
        params.ratio = number(prefs, c.getString(R.string.leveler_ratio_key),
                c.getString(R.string.leveler_ratio_default));
    }

    /** Строка состояния для настроек: работает ли выравнивание и какая сейчас поправка. */
    @NonNull
    public static String getStatus(@NonNull final Context c) {
        final LoudnessLeveler lev = LevelerAudioProcessor.getLastLeveler();
        if (lev == null) {
            final int enc = LevelerAudioProcessor.getLastOfferedEncoding();
            return enc != C.ENCODING_INVALID && enc != C.ENCODING_PCM_16BIT
                    ? c.getString(R.string.leveler_status_unsupported, enc)
                    : c.getString(R.string.leveler_status_never);
        }
        if (!getParams(c).enabled) {
            return c.getString(R.string.leveler_status_off);
        }
        final String gain = String.format(Locale.ROOT, "%+.1f dB", lev.getGainDb());
        return LevelerAudioProcessor.msSinceLastAudio() > 2000
                ? c.getString(R.string.leveler_status_idle, gain)
                : c.getString(R.string.leveler_status_active, gain,
                        lev.getSampleRate(), lev.getChannelCount());
    }

    private static double number(@NonNull final SharedPreferences prefs,
                                 @NonNull final String key, @NonNull final String def) {
        try {
            return Double.parseDouble(prefs.getString(key, def));
        } catch (final NumberFormatException | ClassCastException e) {
            return Double.parseDouble(def);
        }
    }
}
