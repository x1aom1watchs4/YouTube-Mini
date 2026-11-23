package com.x1aom1watchs4.youtubemini;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xiaomi.xms.wearable.Wearable;
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
    private NodeApi nodeApi;
    private MessageApi messageApi;
    private Node activeNode;
    private TextView status;
    private TextView playbackStatus;
    private EditText apiKey;
    private EditText searchQuery;
    private EditText videoId;
    private EditText videoTitle;
    private EditText seekSeconds;
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
                    if (!"playback.command".equals(frame.optString("type"))) return;
                    JSONObject payload = frame.optJSONObject("payload");
                    if (payload == null) return;
                    final String action = payload.optString("action", "unknown");
                    runOnUiThread(() ->
                        playbackStatus.setText("Команда с часов: " + action)
                    );
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
        refreshWearable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWearable();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (messageApi != null && activeNode != null) {
            messageApi.removeListener(activeNode.id);
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
        controlsHeading.setText("Управление");
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
        if (nodeApi == null || messageApi == null) return;
        status.setText("Проверяем связь с часами…");
        nodeApi.getConnectedNodes()
            .addOnSuccessListener(nodes -> {
                if (nodes == null || nodes.isEmpty()) {
                    activeNode = null;
                    status.setText("Нет соединения");
                    return;
                }
                activeNode = nodes.get(0);
                messageApi.addListener(activeNode.id, messageListener)
                    .addOnSuccessListener(unused ->
                        runOnUiThread(() -> status.setText("Часы подключены"))
                    )
                    .addOnFailureListener(error ->
                        runOnUiThread(() -> status.setText("Ошибка связи: " + safe(error)))
                    );
            })
            .addOnFailureListener(error ->
                runOnUiThread(() -> status.setText("Нет соединения: " + safe(error)))
            );
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
                runOnUiThread(() -> showResults(items));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Поиск не удался: " + safe(error)));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
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
            JSONObject thumbnails = snippet.optJSONObject("thumbnails");
            JSONObject thumb = thumbnails == null ? null : thumbnails.optJSONObject("default");
            String thumbnailUrl = thumb == null ? "" : thumb.optString("url", "");
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
        put(payload, "videoId", id);
        put(payload, "title", title);
        put(payload, "channelTitle", channel);
        put(payload, "thumbnailUrl", thumbnail);
        send("video.select", payload);
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
        if (node == null || messageApi == null) {
            status.setText("Нет соединения");
            return;
        }
        try {
            JSONObject frame = new JSONObject();
            frame.put("v", 1);
            frame.put("type", type);
            frame.put("payload", payload);
            byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
            messageApi.sendMessage(node.id, bytes)
                .addOnSuccessListener(unused ->
                    runOnUiThread(() -> status.setText("Отправлено на часы"))
                )
                .addOnFailureListener(error ->
                    runOnUiThread(() -> status.setText("Не удалось отправить: " + safe(error)))
                );
        } catch (Exception error) {
            status.setText("Ошибка сообщения: " + safe(error));
        }
    }

    private static JSONObject put(JSONObject object, String key, Object value) {\n        try { object.put(key, value); } catch (Exception ignored) {}\n        return object;\n    }\n\n    private static String readAll(InputStream input) throws Exception {
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
