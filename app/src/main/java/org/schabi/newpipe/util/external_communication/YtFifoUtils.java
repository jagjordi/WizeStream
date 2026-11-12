package org.schabi.newpipe.util.external_communication;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.R;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Util class for yt-fifo, a self-hosted server that plays the audio of a video on a Snapcast
 * multi-room setup ("home audio"). The server is reached over its small HTTP API; the only thing
 * to configure is its base URL, in the video and audio settings.
 * @see <a href="https://github.com/jagjordi/yt-fifo">yt-fifo on GitHub</a>
 */
public final class YtFifoUtils {
    private static final String TAG = "YtFifoUtils";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // yt-fifo resolves the video with yt-dlp before answering, which can take several seconds.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build();

    private YtFifoUtils() { }

    /**
     * @param context the context to use
     * @return the configured server URL without a trailing slash, with {@code http://} prepended
     *         when no scheme was typed, or {@code null} when the setting is empty
     */
    @Nullable
    public static String getServerUrl(@NonNull final Context context) {
        String url = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.yt_fifo_url_key), "");
        if (url == null) {
            return null;
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            return null;
        }
        if (!url.contains("://")) {
            url = "http://" + url;
        }
        return url;
    }

    /**
     * @param context the context to use
     * @return whether the "home audio" entries should be offered, i.e. a server URL is set.
     *         yt-fifo resolves streams with yt-dlp, which covers every service WizeStream has, so
     *         there is no per-service check.
     */
    public static boolean shouldShowHomeAudio(@NonNull final Context context) {
        return getServerUrl(context) != null;
    }

    /**
     * Sends the stream url to the yt-fifo server, asynchronously, and reports the outcome in a
     * toast: the resolved title on success, the server's error message otherwise.
     *
     * @param context   the context to use (only its application context is kept)
     * @param streamUrl the url of the stream
     * @param playNow   true to interrupt what is playing and play this stream right away,
     *                  false to append it to the queue
     */
    public static void sendToHomeAudio(@NonNull final Context context,
                                       @NonNull final String streamUrl,
                                       final boolean playNow) {
        final Context appContext = context.getApplicationContext();
        final String server = getServerUrl(context);
        if (server == null) {
            return;
        }
        final JSONObject body = new JSONObject();
        try {
            body.put("url", streamUrl);
            body.put("play_now", playNow);
        } catch (final JSONException e) {
            throw new IllegalStateException(e); // fixed keys and string values cannot fail
        }
        final Request request = new Request.Builder()
                .url(server + "/queue")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull final Call call, @NonNull final IOException e) {
                Log.w(TAG, "Could not reach yt-fifo at " + server, e);
                toast(appContext, appContext.getString(R.string.home_audio_failed,
                        e.getLocalizedMessage()));
            }

            @Override
            public void onResponse(@NonNull final Call call, @NonNull final Response response) {
                try (response) {
                    final String text = response.body().string();
                    if (response.isSuccessful()) {
                        final String title = firstTitle(text, streamUrl);
                        toast(appContext, appContext.getString(playNow
                                ? R.string.home_audio_playing : R.string.home_audio_queued,
                                title));
                    } else {
                        toast(appContext, appContext.getString(R.string.home_audio_failed,
                                errorMessage(text, response.code())));
                    }
                } catch (final IOException e) {
                    toast(appContext, appContext.getString(R.string.home_audio_failed,
                            e.getLocalizedMessage()));
                }
            }
        });
    }

    /**
     * @param json     a {@code POST /queue} response: {@code {"enqueued": [{"title": …}, …]}}
     * @param fallback what to return when the title is missing
     * @return the title of the first enqueued track
     */
    @NonNull
    static String firstTitle(@NonNull final String json, @NonNull final String fallback) {
        try {
            final String title = new JSONObject(json).getJSONArray("enqueued")
                    .getJSONObject(0).optString("title", "");
            return title.isEmpty() ? fallback : title;
        } catch (final JSONException e) {
            return fallback;
        }
    }

    /**
     * @param json an error response: {@code {"error": "…"}}
     * @param code the HTTP status, used when the body carries no message
     * @return a message for the user
     */
    @NonNull
    static String errorMessage(@NonNull final String json, final int code) {
        try {
            final String message = new JSONObject(json).optString("error", "");
            return message.isEmpty() ? "HTTP " + code : message;
        } catch (final JSONException e) {
            return "HTTP " + code;
        }
    }

    private static void toast(@NonNull final Context context, @NonNull final String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
