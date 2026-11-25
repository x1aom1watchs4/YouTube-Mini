package com.x1aom1watchs4.youtubemini;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputType;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean activityResumed;
    private boolean nodeQueryInFlight;
    private boolean listenerRegistrationInFlight;
    private final Runnable connectionCheck = new Runnable() {
        @Override public void run() {
            if (!activityResumed) return;
            refreshWearable();
            mainHandler.postDelayed(this, 15000L);
        }
    };
    private NodeApi nodeApi;
    private MessageApi messageApi;
    private Node activeNode;
    private String listeningNodeId = "";
    private String permissionRequestNodeId = "";
    private String launchRequestedNodeId = "";
    private TextView status;
    private TextView playbackStatus;
    private EditText apiKey;
    private EditText searchQuery;
    private EditText videoId;
    private EditText videoTitle;
    private EditText seekSeconds;
    private EditText audioUrl;
    private LinearLayout results;

    private final OnMessageReceivedListener messageListener =
        new OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String nodeId, byte[] message) {
                if (message == null || message.length > 16 * 1024) return;
                try {
                    JSONObject frame = new JSONObject(
                        new String(message, StandardCharsets.UTF_8)
                    );
                    if (frame.optInt("v", 0) != 1) return;
                    String type = frame.optString("type", "");
                    JSONObject payload = frame.optJSONObject("payload");
                    if (payload == null) return;
                    if ("video.open".equals(type)) {
                        final String id = payload.optString("videoId", "");
                        if (!id.isEmpty()) {
                            runOnUiThread(() -> {
                                try {
                                    Intent intent = new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://www.youtube.com/watch?v=" + Uri.encode(id)));
                                    startActivity(intent);
                                    playbackStatus.setText("Открываем видео в YouTube");
                                } catch (Exception error) {
                                    playbackStatus.setText("Не удалось открыть YouTube: " + safe(error));
                                }
                            });
                        }
                    } else if ("playback.command".equals(type)) {
                        final String action = payload.optString("action", "unknown");
                        runOnUiThread(() ->
                            playbackStatus.setText("Команда с часов: " + action + " (плеер пока не подключён)")
                        );
                    } else if ("video.select".equals(type)) {
                        final String title = payload.optString("title", "Видео выбрано на часах");
                        final String id = payload.optString("videoId", "");
                        runOnUiThread(() -> {
                            videoId.setText(id);
                            videoTitle.setText(title);
                            playbackStatus.setText("На часах выбрано: " + title);
                        });
                    } else if ("video.search".equals(type)) {
                        final String query = payload.optString("query", "");
                        runOnUiThread(() -> searchVideosForWatch(query, nodeId));
                    } else if ("comments.load".equals(type)) {
                        final String id = payload.optString("videoId", "");
                        if (!id.isEmpty()) io.execute(() -> fetchCommentsForWatch(id, nodeId));
                    }
                } catch (Exception ignored) {
                    // Ignore malformed or unrelated messages.
                }
            }
        };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        nodeApi = Wearable.getNodeApi(getApplicationContext());
        messageApi = Wearable.getMessageApi(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        mainHandler.removeCallbacks(connectionCheck);
        refreshWearable();
        mainHandler.postDelayed(connectionCheck, 15000L);
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        mainHandler.removeCallbacks(connectionCheck);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        mainHandler.removeCallbacks(connectionCheck);
        io.shutdownNow();
        if (messageApi != null && !listeningNodeId.isEmpty()) {
            messageApi.removeListener(listeningNodeId);
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        scroll.addView(root);

        TextView heading = new TextView(this);
        heading.setText("YouTube Mini");
        heading.setTextSize(28);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(heading);

        status = new TextView(this);
        status.setText("Проверяем связь с часами…");
        status.setTextSize(16);
        root.addView(status);

        Button refresh = new Button(this);
        refresh.setText("Проверить связь");
        refresh.setOnClickListener(v -> refreshWearable());
        root.addView(refresh);

        TextView searchHeading = new TextView(this);
        searchHeading.setText("Поиск YouTube");
        searchHeading.setTextSize(20);
        root.addView(searchHeading);

        apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint("YouTube Data API key (не сохраняется)");
        root.addView(apiKey);

        searchQuery = new EditText(this);
        searchQuery.setSingleLine(true);
        searchQuery.setHint("Название видео или запрос");
        root.addView(searchQuery);

        Button search = new Button(this);
        search.setText("Найти видео");
        search.setOnClickListener(v -> searchVideos());
        root.addView(search);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        TextView manualHeading = new TextView(this);
        manualHeading.setText("Отправить видео по ссылке или ID");
        manualHeading.setTextSize(18);
        root.addView(manualHeading);

        videoId = new EditText(this);
        videoId.setSingleLine(true);
        videoId.setHint("YouTube video ID");
        root.addView(videoId);

        videoTitle = new EditText(this);
        videoTitle.setSingleLine(true);
        videoTitle.setHint("Название видео");
        root.addView(videoTitle);

        Button send = new Button(this);
        send.setText("Отправить на часы");
        send.setOnClickListener(v -> sendSelectedVideo());
        root.addView(send);

        TextView controlsHeading = new TextView(this);
        controlsHeading.setText("Команды плеера (демо)");
        controlsHeading.setTextSize(20);
        root.addView(controlsHeading);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        addControl(controls, "▶", "play");
        addControl(controls, "Ⅱ", "pause");
        addControl(controls, "■", "stop");
        root.addView(controls);

        seekSeconds = new EditText(this);
        seekSeconds.setSingleLine(true);
        seekSeconds.setHint("Перейти к секунде");
        root.addView(seekSeconds);

        Button seek = new Button(this);
        seek.setText("Перемотать");
        seek.setOnClickListener(v -> sendSeek());
        root.addView(seek);

        TextView audioHeading = new TextView(this);
        audioHeading.setText("Аудио на часах (прямой разрешённый URL)");
        audioHeading.setTextSize(18);
        root.addView(audioHeading);

        audioUrl = new EditText(this);
        audioUrl.setSingleLine(true);
        audioUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        audioUrl.setHint("https://.../audio.mp3");
        root.addView(audioUrl);

        Button sendAudio = new Button(this);
        sendAudio.setText("Передать аудио на часы");
        sendAudio.setOnClickListener(v -> sendAudioSource());
        root.addView(sendAudio);

        playbackStatus = new TextView(this);
        playbackStatus.setText("Плеер часов ещё не подтверждён на устройстве.");
        root.addView(playbackStatus);

        setContentView(scroll);
    }

    private void addControl(LinearLayout row, String label, String action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> send("playback.command",
            put(new JSONObject(), "action", action)));
        row.addView(button, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void refreshWearable() {
        if (nodeApi == null || messageApi == null || nodeQueryInFlight) return;
        nodeQueryInFlight = true;
        status.setText("Проверяем связь с часами…");
        nodeApi.getConnectedNodes()
            .addOnSuccessListener(nodes -> {
                nodeQueryInFlight = false;
                if (nodes == null || nodes.isEmpty()) {
                    if (!listeningNodeId.isEmpty()) {
                        messageApi.removeListener(listeningNodeId);
                    }
                    activeNode = null;
                    listeningNodeId = "";
                    listenerRegistrationInFlight = false;
                    permissionRequestNodeId = "";
                    launchRequestedNodeId = "";
                    status.setText("Нет соединения");
                    return;
                }

                Node nextNode = null;
                if (activeNode != null) {
                    for (Node candidate : nodes) {
                        if (candidate != null && activeNode.id.equals(candidate.id)) {
                            nextNode = candidate;
                            break;
                        }
                    }
                }
                if (nextNode == null) {
                    for (Node candidate : nodes) {
                        if (candidate != null && candidate.id != null && !candidate.id.isEmpty()) {
                            nextNode = candidate;
                            break;
                        }
                    }
                }
                if (nextNode == null) {
                    activeNode = null;
                    status.setText("Нет соединения");
                    return;
                }

                final Node selectedNode = nextNode;
                String nextId = selectedNode.id;
                if (!listeningNodeId.isEmpty() && !listeningNodeId.equals(nextId)) {
                    messageApi.removeListener(listeningNodeId);
                    listeningNodeId = "";
                }
                activeNode = selectedNode;
                // XMS message reception may be denied until DEVICE_MANAGER is granted.
                // Check/request watch permission first; register the listener only afterwards.
                authorizeAndOpenWatch(selectedNode);
            })
            .addOnFailureListener(error -> {
                nodeQueryInFlight = false;
                runOnUiThread(() -> {
                    status.setText("Нет соединения");
                    playbackStatus.setText("Проверка связи не удалась: " + safe(error));
                });
            });
    }

    /**
     * Mirrors the proven WristAI/WristBook startup sequence:
     * find a connected XMS node -> check DEVICE_MANAGER permission ->
     * request it if needed -> launch the Vela app on the watch.
     */
    private void authorizeAndOpenWatch(Node node) {
        if (node == null || node.id == null || node.id.isEmpty()) return;
        final String nodeId = node.id;
        try {
            Wearable.getAuthApi(getApplicationContext())
                .checkPermissions(nodeId, new Permission[] { Permission.DEVICE_MANAGER })
                .addOnSuccessListener(grants -> {
                    boolean granted = grants != null && grants.length > 0 && grants[0];
                    if (!granted) {
                        if (nodeId.equals(permissionRequestNodeId)) {
                            runOnUiThread(() -> status.setText(
                                "Подтверди разрешение для YouTube Mini в Notify for Xiaomi"
                            ));
                            return;
                        }
                        permissionRequestNodeId = nodeId;
                        runOnUiThread(() -> status.setText(
                            "Запрашиваем разрешение на связь с часами…"
                        ));
                        try {
                            Wearable.getAuthApi(getApplicationContext())
                                .requestPermission(nodeId, Permission.DEVICE_MANAGER)
                                .addOnSuccessListener(unused -> {
                                    runOnUiThread(() -> status.setText(
                                        "Запрос отправлен. Подтверди разрешение в Notify for Xiaomi, если появится"
                                    ));
                                    launchWatchApp(nodeId);
                                })
                                .addOnFailureListener(error -> runOnUiThread(() ->
                                    status.setText("Не удалось запросить разрешение: " + safe(error))
                                ));
                        } catch (Exception error) {
                            runOnUiThread(() ->
                                status.setText("Ошибка запроса разрешения: " + safe(error))
                            );
                        }
                        return;
                    }
                    permissionRequestNodeId = "";
                    registerMessageListener(node);
                    launchWatchApp(nodeId);
                })
                .addOnFailureListener(error -> runOnUiThread(() ->
                    status.setText("Не удалось проверить разрешение часов: " + safe(error))
                ));
        } catch (Exception error) {
            runOnUiThread(() ->
                status.setText("Ошибка проверки разрешения: " + safe(error))
            );
        }
    }

    private void registerMessageListener(Node node) {
        if (node == null || node.id == null || node.id.isEmpty()
                || node.id.equals(listeningNodeId) || listenerRegistrationInFlight) return;
        final String nodeId = node.id;
        listenerRegistrationInFlight = true;
        try {
            messageApi.addListener(nodeId, messageListener)
                .addOnSuccessListener(unused -> {
                    listenerRegistrationInFlight = false;
                    listeningNodeId = nodeId;
                    runOnUiThread(() -> status.setText("Разрешение получено. Приём сообщений включён."));
                })
                .addOnFailureListener(error -> {
                    listenerRegistrationInFlight = false;
                    runOnUiThread(() -> status.setText(
                        "Разрешение проверено, но приём сообщений отклонён: " + safe(error)
                    ));
                });
        } catch (Exception error) {
            listenerRegistrationInFlight = false;
            runOnUiThread(() -> status.setText(
                "Ошибка регистрации приёма сообщений: " + safe(error)
            ));
        }
    }

    private void launchWatchApp(String nodeId) {
        if (nodeId == null || nodeId.isEmpty() || nodeId.equals(launchRequestedNodeId)) return;
        launchRequestedNodeId = nodeId;
        try {
            Wearable.getNodeApi(getApplicationContext())
                .launchWearApp(nodeId, "/pages/index")
                .addOnSuccessListener(unused -> runOnUiThread(() ->
                    status.setText("Часы подключены. YouTube Mini открыт на часах")
                ))
                .addOnFailureListener(error -> {
                    launchRequestedNodeId = "";
                    runOnUiThread(() ->
                        status.setText("Связь найдена, но не удалось открыть YouTube Mini: " + safe(error))
                    );
                });
        } catch (Exception error) {
            launchRequestedNodeId = "";
            runOnUiThread(() ->
                status.setText("Ошибка запуска YouTube Mini на часах: " + safe(error))
            );
        }
    }

    private void searchVideos() {
        String key = apiKey.getText().toString().trim();
        String query = searchQuery.getText().toString().trim();
        if (key.isEmpty() || query.isEmpty()) {
            status.setText("Введи поисковый запрос и свой API key.");
            return;
        }
        status.setText("Ищем видео…");
        results.removeAllViews();
        io.execute(() -> {
            try {
                JSONArray items = fetchVideoResults(query, key);
                runOnUiThread(() -> showResults(items));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Поиск не удался: " + safe(error)));
            }
        });
    }

    private void searchVideosForWatch(String query, String nodeId) {
        String key = apiKey.getText().toString().trim();
        if (query.trim().isEmpty() || key.isEmpty()) {
            JSONObject error = put(new JSONObject(), "message",
                "Введи YouTube API key в приложении телефона.");
            sendToNode(nodeId, "video.search.error", error);
            return;
        }
        io.execute(() -> {
            try {
                JSONArray found = fetchVideoResults(query, key);
                JSONArray compact = new JSONArray();
                for (int i = 0; i < found.length(); i++) {
                    JSONObject item = found.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject id = item.optJSONObject("id");
                    JSONObject snippet = item.optJSONObject("snippet");
                    if (id == null || snippet == null) continue;
                    String videoId = id.optString("videoId", "");
                    JSONObject video = new JSONObject();
                    video.put("videoId", videoId);
                    video.put("title", snippet.optString("title", "Видео"));
                    video.put("channelTitle", snippet.optString("channelTitle", ""));
                    video.put("thumbnailUrl", thumbnailUrlFor(videoId));
                    compact.put(video);
                }
                sendToNode(nodeId, "video.results", put(new JSONObject(), "items", compact));
            } catch (Exception error) {
                sendToNode(nodeId, "video.search.error",
                    put(new JSONObject(), "message", safe(error)));
            }
        });
    }

    private void fetchCommentsForWatch(String videoId, String nodeId) {
        String key = apiKey.getText().toString().trim();
        try {
            if (key.isEmpty()) throw new IllegalStateException("Сначала укажи YouTube API key в приложении телефона.");
            HttpURLConnection connection = null;
            JSONArray comments = new JSONArray();
            try {
                String address = "https://www.googleapis.com/youtube/v3/commentThreads"
                    + "?part=snippet&videoId=" + URLEncoder.encode(videoId, "UTF-8")
                    + "&maxResults=8&textFormat=plainText"
                    + "&key=" + URLEncoder.encode(key, "UTF-8");
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
                String body = readAll(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("YouTube API: HTTP " + code);
                }
                JSONArray items = new JSONObject(body).optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        JSONObject snippet = item == null ? null : item.optJSONObject("snippet");
                        JSONObject top = snippet == null ? null : snippet.optJSONObject("topLevelComment");
                        JSONObject commentSnippet = top == null ? null : top.optJSONObject("snippet");
                        if (commentSnippet == null) continue;
                        JSONObject row = new JSONObject();
                        row.put("author", commentSnippet.optString("authorDisplayName", "Пользователь"));
                        row.put("text", commentSnippet.optString("textDisplay", ""));
                        comments.put(row);
                    }
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
            sendToNode(nodeId, "comments.items", put(new JSONObject(), "items", comments));
        } catch (Exception error) {
            sendToNode(nodeId, "comments.items",
                put(new JSONObject(), "items", new JSONArray()));
            runOnUiThread(() -> playbackStatus.setText("Комментарии: " + safe(error)));
        }
    }

    private JSONArray fetchVideoResults(String query, String key) throws Exception {
        HttpURLConnection connection = null;
        try {
            String address = "https://www.googleapis.com/youtube/v3/search"
                + "?part=snippet&type=video&maxResults=10"
                + "&q=" + URLEncoder.encode(query, "UTF-8")
                + "&key=" + URLEncoder.encode(key, "UTF-8");
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("YouTube API ответил: HTTP " + code);
            }
            JSONArray items = new JSONObject(body).optJSONArray("items");
            return items == null ? new JSONArray() : items;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showResults(JSONArray items) {
        results.removeAllViews();
        if (items == null || items.length() == 0) {
            status.setText("Ничего не найдено.");
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject id = item.optJSONObject("id");
            JSONObject snippet = item.optJSONObject("snippet");
            if (id == null || snippet == null) continue;
            String selectedId = id.optString("videoId", "");
            String title = snippet.optString("title", "Видео");
            String channel = snippet.optString("channelTitle", "");
            String thumbnailUrl = thumbnailUrlFor(selectedId);
            if (selectedId.isEmpty()) continue;
            Button result = new Button(this);
            result.setText(title + (channel.isEmpty() ? "" : "\n" + channel));
            result.setOnClickListener(v -> {
                videoId.setText(selectedId);
                videoTitle.setText(title);
                sendVideo(selectedId, title, channel, thumbnailUrl);
            });
            results.addView(result);
        }
        status.setText("Найдено видео: " + results.getChildCount());
    }

    private void sendSelectedVideo() {
        String id = videoId.getText().toString().trim();
        String title = videoTitle.getText().toString().trim();
        if (id.isEmpty()) {
            status.setText("Сначала выбери видео или введи его ID.");
            return;
        }
        sendVideo(id, title.isEmpty() ? "Видео YouTube" : title, "", "");
    }

    private void sendVideo(String id, String title, String channel, String thumbnail) {
        JSONObject payload = new JSONObject();
        if (thumbnail == null || thumbnail.isEmpty()) thumbnail = thumbnailUrlFor(id);
        put(payload, "videoId", id);
        put(payload, "title", title);
        put(payload, "channelTitle", channel);
        put(payload, "thumbnailUrl", thumbnail);
        send("video.select", payload);
    }

    private static String thumbnailUrlFor(String videoId) {
        if (videoId == null || videoId.isEmpty()) return "";
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    private void sendAudioSource() {
        String source = audioUrl.getText().toString().trim();
        Uri parsed = Uri.parse(source);
        String scheme = parsed.getScheme();
        if (source.isEmpty() || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            status.setText("Укажи прямой HTTP(S) URL аудиофайла или разрешённого аудиопотока.");
            return;
        }
        JSONObject payload = new JSONObject();
        put(payload, "src", source);
        put(payload, "title", "Аудио");
        send("audio.source", payload);
    }

    private void sendSeek() {
        try {
            int seconds = Integer.parseInt(seekSeconds.getText().toString().trim());
            if (seconds < 0) throw new NumberFormatException();
            JSONObject payload = new JSONObject();
            put(payload, "action", "seek");
            put(payload, "seconds", seconds);
            send("playback.command", payload);
        } catch (NumberFormatException error) {
            status.setText("Укажи время в секундах.");
        }
    }

    private void send(String type, JSONObject payload) {
        Node node = activeNode;
        if (node == null) {
            status.setText("Нет соединения");
            return;
        }
        sendToNode(node.id, type, payload);
    }

    private void sendToNode(String nodeId, String type, JSONObject payload) {
        if (nodeId == null || nodeId.isEmpty() || messageApi == null) return;
        try {
            JSONObject frame = new JSONObject();
            frame.put("v", 1);
            frame.put("type", type);
            frame.put("payload", payload);
            byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
            messageApi.sendMessage(nodeId, bytes)
                .addOnSuccessListener(unused ->
                    runOnUiThread(() -> {
                        status.setText("Отправлено на часы");
                        if ("playback.command".equals(type)) {
                            playbackStatus.setText("Команда доставлена. Видеоплеер на часах ещё не подключён.");
                        }
                    })
                )
                .addOnFailureListener(error ->
                    runOnUiThread(() -> status.setText("Не удалось отправить: " + safe(error)))
                );
        } catch (Exception error) {
            runOnUiThread(() -> status.setText("Ошибка сообщения: " + safe(error)));
        }
    }

    private static JSONObject put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) {}
        return object;
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return text.toString();
        }
    }

    private static String safe(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isEmpty() ? "неизвестная ошибка" : message;
    }
}
