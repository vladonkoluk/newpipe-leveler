package org.schabi.newpipe.player.leveler;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.C;

import org.schabi.newpipe.R;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Настройки выравнивания громкости из экрана «Видео и аудио». Один общий
 * {@link LoudnessLeveler.Params} на всё приложение: изменения применяются сразу,
 * даже во время воспроизведения.
 *
 * <p>Настройки перечитываются из SharedPreferences не чаще раза в 300 мс по запросу
 * обработчика звука, а не через OnSharedPreferenceChangeListener: SharedPreferences держит
 * слушателей по слабой ссылке, и в минифицированной сборке R8 убирал поле со слушателем
 * (его только записывали), после чего слушатель собирался сборщиком мусора.</p>
 */
public final class LevelerSettings {
    /** Значение «Силы выравнивания» для режима «Максимум». */
    private static final String MAX_STRENGTH = "max";
    private static final String EXTREME = "extreme";
    private static final String ULTRA = "ultra";
    private static final long REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(300);

    private static LoudnessLeveler.Params params;
    private static Context appContext;
    private static SharedPreferences prefs;
    private static long lastRefreshNanos;

    private LevelerSettings() {
    }

    @NonNull
    public static synchronized LoudnessLeveler.Params getParams(@NonNull final Context context) {
        if (params == null) {
            appContext = context.getApplicationContext();
            prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
            params = new LoudnessLeveler.Params();
            lastRefreshNanos = System.nanoTime();
            apply(appContext, prefs);
        } else {
            refreshIfStale();
        }
        return params;
    }

    /** Перечитывает настройки, если с прошлого раза прошло больше 300 мс. */
    static synchronized void refreshIfStale() {
        if (params == null) {
            return;
        }
        final long now = System.nanoTime();
        if (now - lastRefreshNanos >= REFRESH_NANOS) {
            lastRefreshNanos = now;
            apply(appContext, prefs);
        }
    }

    private static void apply(@NonNull final Context c, @NonNull final SharedPreferences prefs) {
        params.enabled = prefs.getBoolean(c.getString(R.string.leveler_enabled_key), true);
        params.target = number(prefs, c.getString(R.string.leveler_target_key),
                c.getString(R.string.leveler_target_default));
        final String strength = prefs.getString(c.getString(R.string.leveler_strength_key),
                c.getString(R.string.leveler_strength_default));
        applyPreset(prefs, c, strength);
        params.maxBoost = number(prefs, c.getString(R.string.leveler_max_boost_key),
                c.getString(R.string.leveler_max_boost_default));
        params.ratio = number(prefs, c.getString(R.string.leveler_ratio_key),
                c.getString(R.string.leveler_ratio_default));
        if (ULTRA.equals(strength) || EXTREME.equals(strength)) {
            params.ratio = 20; // эти режимы подобраны со сжатием 20:1
        }
    }

    /**
     * Скорость реакции и пороги для «Силы выравнивания». Подобраны на тестах
     * LoudnessLevelerTest: чем сильнее режим, тем ровнее, но тем громче фон в паузах
     * и заметнее сжатие голоса.
     */
    private static void applyPreset(@NonNull final SharedPreferences prefs,
                                    @NonNull final Context c, @NonNull final String strength) {
        final LoudnessLeveler.Params p = params;
        p.strength = 1;
        p.agcTime = 3;
        p.pauseRel = 3;
        p.compRel = 0.25;
        if (ULTRA.equals(strength)) {
            // предельно: окно 30 мс, слабая защита пауз — фон почти как голос
            p.levTime = 0.03;
            p.levAtt = 0.005;
            p.levRel = 0.03;
            p.compAbove = -4;
            p.agcTime = 1;
            p.compRel = 0.05;
            p.pauseRel = 1;
        } else if (EXTREME.equals(strength)) {
            // окно 50 мс, компрессор чуть ниже цели
            p.levTime = 0.05;
            p.levAtt = 0.01;
            p.levRel = 0.05;
            p.compAbove = -2;
            p.agcTime = 1.5;
            p.compRel = 0.1;
        } else if (MAX_STRENGTH.equals(strength)) {
            // быстрее реагирует (окно 0.1 с, 20/100 мс) и прижимает всё выше цели
            p.levTime = 0.1;
            p.levAtt = 0.02;
            p.levRel = 0.1;
            p.compAbove = 0;
        } else {
            p.strength = number(prefs, c.getString(R.string.leveler_strength_key),
                    c.getString(R.string.leveler_strength_default)) / 100;
            p.levTime = 0.2;
            p.levAtt = 0.05;
            p.levRel = 0.2;
            p.compAbove = 4;
        }
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
