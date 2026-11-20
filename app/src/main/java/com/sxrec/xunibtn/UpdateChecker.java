package com.sxrec.xunibtn;

import android.os.Handler;
import android.os.Looper;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class UpdateChecker implements AutoCloseable {
    public static final String REPOSITORY_URL = "https://github.com/hcllmsx/xuniBTN";
    public static final String RELEASE_URL = REPOSITORY_URL + "/releases/latest";
    public static final String FAILURE_MESSAGE = "检查更新失败，请前往发布页查看";
    private static final String API_URL = "https://api.github.com/repos/hcllmsx/xuniBTN/releases/latest";
    private static final String[] SOURCES = {"https://gh-proxy.com/" + API_URL, API_URL};
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)+");
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_NOTES_CHARS = 8000;
    private static final AtomicBoolean STARTUP_CHECKED = new AtomicBoolean();

    public interface Listener {
        void onUpdateResult(Result result, boolean manual);
    }

    public static final class Result {
        public final boolean successful;
        public final boolean newer;
        public final String version;
        public final String notes;

        private Result(boolean successful, boolean newer, String version, String notes) {
            this.successful = successful;
            this.newer = newer;
            this.version = version;
            this.notes = notes;
        }

        private static Result failure() {
            return new Result(false, false, "", "");
        }
    }

    private final String currentVersion;
    private final WeakReference<Listener> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xuniBTN-update");
        thread.setDaemon(true);
        return thread;
    });
    private volatile HttpURLConnection activeConnection;
    private volatile boolean closed;
    // check(), isChecking(), close() and result delivery run on the UI thread.
    private boolean checking;
    private boolean reportManually;

    public UpdateChecker(String currentVersion, Listener listener) {
        this.currentVersion = currentVersion;
        this.listener = new WeakReference<>(listener);
    }

    /** A manual request joins an in-flight startup check instead of starting another one. */
    public boolean check(boolean manual) {
        if (closed) return false;
        if (checking) {
            reportManually |= manual;
            return true;
        }
        if (!manual && !STARTUP_CHECKED.compareAndSet(false, true)) return false;
        checking = true;
        reportManually = manual;
        try {
            executor.execute(() -> {
                Result result = fetchLatest();
                if (!closed) main.post(() -> deliver(result));
            });
        } catch (RejectedExecutionException ignored) {
            checking = false;
            return false;
        }
        return true;
    }

    public boolean isChecking() {
        return checking;
    }

    private void deliver(Result result) {
        if (closed) return;
        checking = false;
        boolean manual = reportManually;
        reportManually = false;
        Listener target = listener.get();
        if (target != null) target.onUpdateResult(result, manual);
    }

    private Result fetchLatest() {
        if (!validVersion(currentVersion)) return Result.failure();
        for (String source : SOURCES) {
            if (closed || Thread.currentThread().isInterrupted()) break;
            try {
                return parseRelease(readResponse(source), currentVersion);
            } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException ignored) {
                // A missing first Release (404), bad mirror response or timeout all fall back.
            }
        }
        return Result.failure();
    }

    private String readResponse(String source) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        activeConnection = connection;
        try {
            if (closed) throw new IOException("Cancelled");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "xuniBTN");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP status");
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                long deadline = System.nanoTime() + 12_000_000_000L;
                while ((read = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    if (System.nanoTime() > deadline) throw new IOException("Response timed out");
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
                    output.write(buffer, 0, read);
                }
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            }
        } finally {
            connection.disconnect();
            activeConnection = null;
        }
    }

    static Result parseRelease(String json, String currentVersion) throws IOException {
        String tag = null;
        String body = "";
        Boolean draft = null;
        Boolean prerelease = null;
        boolean hasBody = false;
        // Strict streaming parsing avoids JSONTokener's acceptance of non-JSON syntax.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Expected Release object");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "tag_name":
                        if (tag != null || reader.peek() != JsonToken.STRING) throw new IOException("Invalid tag");
                        tag = reader.nextString();
                        break;
                    case "draft":
                        if (draft != null || reader.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid draft flag");
                        draft = reader.nextBoolean();
                        break;
                    case "prerelease":
                        if (prerelease != null || reader.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid prerelease flag");
                        prerelease = reader.nextBoolean();
                        break;
                    case "body":
                        if (hasBody) throw new IOException("Duplicate body");
                        hasBody = true;
                        if (reader.peek() == JsonToken.NULL) reader.nextNull();
                        else if (reader.peek() == JsonToken.STRING) body = reader.nextString();
                        else throw new IOException("Invalid Release body");
                        break;
                    default:
                        reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing response data");
        }
        if (tag == null || draft == null || prerelease == null || draft || prerelease || !hasBody) {
            throw new IOException("Invalid or non-final Release");
        }
        if (!tag.startsWith("v") || !validVersion(tag.substring(1))) {
            throw new IOException("Invalid Release tag");
        }
        String version = tag.substring(1);
        return new Result(true, compareVersions(version, currentVersion) > 0, version, extractNotes(body));
    }

    private static boolean validVersion(String version) {
        return version != null && version.length() <= 80 && VERSION.matcher(version).matches();
    }

    static int compareVersions(String first, String second) {
        if (!validVersion(first) || !validVersion(second)) throw new IllegalArgumentException("Invalid version");
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            BigInteger a = i < left.length ? new BigInteger(left[i]) : BigInteger.ZERO;
            BigInteger b = i < right.length ? new BigInteger(right[i]) : BigInteger.ZERO;
            int comparison = a.compareTo(b);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    static String extractNotes(String body) {
        java.util.regex.Matcher separator = Pattern.compile("(?m)^[\\t ]*---[\\t ]*\\r?$").matcher(body);
        String visible = separator.find() ? body.substring(0, separator.start()) : body;
        StringBuilder notes = new StringBuilder();
        for (String line : visible.split("\\r?\\n")) {
            String trimmed = line.trim();
            String item;
            if (trimmed.startsWith("### ")) {
                item = trimmed.substring(4).trim();
            } else if (trimmed.startsWith("- ")) {
                item = "• " + trimmed.substring(2).trim();
            } else {
                continue;
            }
            // Plain text only: no HTML, Markdown link handling, or clickable remote URLs.
            item = item.replaceAll("[\\p{Cc}\\p{Cf}]", "");
            if (item.isEmpty()) continue;
            if (notes.length() > 0) notes.append('\n');
            int remaining = MAX_NOTES_CHARS - notes.length();
            if (item.length() > remaining) {
                notes.append(item, 0, Math.max(0, remaining)).append("…");
                break;
            }
            notes.append(item);
            if (notes.length() >= MAX_NOTES_CHARS) break;
        }
        return notes.length() == 0 ? "此版本未提供条目说明，可前往发布页查看。" : notes.toString();
    }

    @Override public void close() {
        closed = true;
        listener.clear();
        checking = false;
        executor.shutdownNow();
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        main.removeCallbacksAndMessages(null);
    }
}
