package com.x1aom1watchs4.youtubemini;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Base64;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.OpenableColumns;
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
import java.io.ByteArrayOutputStream;
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
    private SharedPreferences preferences;
    private EditText searchQuery;
    private EditText videoId;
    private EditText videoTitle;
    private EditText seekSeconds;
    private EditText audioUrl;
    private Uri selectedAudioUri;
    private String selectedAudioName = "";
    private static final int AUDIO_CHUNK_BYTES = 5000;
    private static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    private byte[] pendingAudioBytes;
    private String pendingAudioTransferId = "";
    private String pendingAudioNodeId = "";
    private int pendingAudioTotal;
    private int pendingAudioNextChunk;
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
                    if ("audio.file.ack".equals(type)) {
                        String transferId = payload.optString("id", "");
                        int acknowledged = payload.optInt("index", -1);
                        if (transferId.equals(pendingAudioTransferId) && acknowledged == pendingAudioNextChunk) {
                            pendingAudioNextChunk++;
                            if (pendingAudioNextChunk < pendingAudioTotal) {
                                sendAudioChunk(pendingAudioNodeId, pendingAudioTransferId, selectedAudioName,
                                    pendingAudioBytes, pendingAudioNextChunk, pendingAudioTotal);
                            } else {
                                JSONObject finish = new JSONObject();
                                put(finish, "id", pendingAudioTransferId);
                                put(finish, "name", selectedAudioName);
                                sendAudioFrame(pendingAudioNodeId, "audio.file.finish", finish, () ->
                                    runOnUiThread(() -> playbackStatus.setText("All chunks delivered. Finalizing on watch...")));
                            }
                        }
                    } else if ("audio.file.complete".equals(type)) {
                        if (payload.optString("id", "").equals(pendingAudioTransferId)) {
                            pendingAudioBytes = null;
                            pendingAudioTransferId = "";
                            runOnUiThread(() -> playbackStatus.setText("Audio file is ready on the watch."));
                        }
                    } else if ("audio.file.error".equals(type)) {
                        if (payload.optString("id", "").equals(pendingAudioTransferId)) {
                            pendingAudioBytes = null;
                            pendingAudioTransferId = "";
                            runOnUiThread(() -> playbackStatus.setText("Watch could not save audio file."));
                        }
                    } else if ("video.open".equals(type)) {
                        final String id = payload.optString("videoId", "");
                        if (!id.isEmpty()) {
                            runOnUiThread(() -> {
                                try {
                                    Intent intent = new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://www.youtube.com/shorts/" + Uri.encode(id)));
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
        preferences = getSharedPreferences("youtube_mini_settings", MODE_PRIVATE);
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
        root.setBackgroundColor(android.graphics.Color.rgb(245, 245, 245));
        scroll.addView(root);

        TextView heading = new TextView(this);
        heading.setText("YouTube Mini");
        heading.setTextSize(28);
        heading.setTextColor(android.graphics.Color.rgb(25, 25, 25));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(heading);

        status = new TextView(this);
        status.setText("Проверяем связь с часами…");
        status.setTextSize(16);
        status.setTextColor(android.graphics.Color.rgb(45, 45, 45));
        root.addView(status);

        Button refresh = new Button(this);
        refresh.setText("CONNECT WATCH");
        refresh.setOnClickListener(v -> refreshWearable());
        root.addView(refresh);

        TextView searchHeading = new TextView(this);
        searchHeading.setText("FIND SHORT-FORM VIDEOS");
        searchHeading.setTextColor(android.graphics.Color.rgb(255, 55, 85));
        searchHeading.setTextSize(14);
        searchHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(searchHeading);

        apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setTextColor(android.graphics.Color.rgb(25, 25, 25));
        apiKey.setHintTextColor(android.graphics.Color.rgb(105, 105, 105));
        apiKey.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(90, 90, 90)));
        apiKey.setText(preferences.getString("youtube_api_key", ""));
        apiKey.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                preferences.edit().putString("youtube_api_key", value.toString()).apply();
            }
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint("YouTube Data API key");
        root.addView(apiKey);

        searchQuery = new EditText(this);
        searchQuery.setSingleLine(true);
        searchQuery.setTextColor(android.graphics.Color.rgb(25, 25, 25));
        searchQuery.setHintTextColor(android.graphics.Color.rgb(105, 105, 105));
        searchQuery.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(90, 90, 90)));
        searchQuery.setHintTextColor(android.graphics.Color.GRAY);
        searchQuery.setHint("Search videos");
        root.addView(searchQuery);

        Button search = new Button(this);
        search.setText("SEARCH SHORT-FORM");
        search.setOnClickListener(v -> searchVideos());
        root.addView(search);

        TextView note = new TextView(this);
        note.setText("Short-duration search. Exact Shorts-only filtering is not available in YouTube Data API.");
        note.setTextColor(android.graphics.Color.GRAY);
        note.setTextSize(11);
        note.setTextColor(android.graphics.Color.rgb(90, 90, 90));
        root.addView(note);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        TextView selectedHeading = new TextView(this);
        selectedHeading.setText("SELECTED VIDEO");
        selectedHeading.setTextColor(android.graphics.Color.rgb(255, 55, 85));
        selectedHeading.setTextSize(13);
        root.addView(selectedHeading);

        videoId = new EditText(this);
        videoId.setSingleLine(true);
        videoId.setTextColor(android.graphics.Color.rgb(25, 25, 25));
        videoId.setHintTextColor(android.graphics.Color.rgb(105, 105, 105));
        videoId.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(90, 90, 90)));
        videoId.setHint("Video ID");
        root.addView(videoId);

        videoTitle = new EditText(this);
        videoTitle.setSingleLine(true);
        videoTitle.setTextColor(android.graphics.Color.rgb(25, 25, 25));
        videoTitle.setHintTextColor(android.graphics.Color.rgb(105, 105, 105));
        videoTitle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(90, 90, 90)));
        videoTitle.setHint("Video title");
        root.addView(videoTitle);

        Button send = new Button(this);
        send.setText("SEND TO WATCH");
        send.setOnClickListener(v -> sendSelectedVideo());
        root.addView(send);

        playbackStatus = new TextView(this);
        playbackStatus.setTextColor(android.graphics.Color.rgb(55, 55, 55));
        playbackStatus.setText("Choose a result, then tap it on the watch to load comments.");
        root.addView(playbackStatus);
        setContentView(scroll);
    }


    private void chooseAudioFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, 4107);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 4107 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        selectedAudioUri = data.getData();
        selectedAudioName = queryAudioName(selectedAudioUri);
        playbackStatus.setText("Selected: " + selectedAudioName);
    }


    private String queryAudioName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return "audio-file";
    }

    private void sendSelectedAudioFile() {
        final Uri uri = selectedAudioUri;
        final Node node = activeNode;
        if (uri == null) {
            playbackStatus.setText("Select an audio file first.");
            return;
        }
        if (node == null) {
            playbackStatus.setText("Watch is not connected.");
            return;
        }
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalStateException("Cannot read selected file.");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_AUDIO_BYTES) {
                        throw new IllegalStateException("Audio file is larger than 15 MB.");
                    }
                    output.write(buffer, 0, count);
                }
                byte[] bytes = output.toByteArray();
                if (bytes.length == 0) throw new IllegalStateException("Selected file is empty.");
                int total = (bytes.length + AUDIO_CHUNK_BYTES - 1) / AUDIO_CHUNK_BYTES;
                String transferId = "aud" + System.currentTimeMillis();
                pendingAudioBytes = bytes;
                pendingAudioTransferId = transferId;
                pendingAudioNodeId = node.id;
                pendingAudioTotal = total;
                pendingAudioNextChunk = 0;
                JSONObject start = new JSONObject();
                put(start, "id", transferId);
                put(start, "name", selectedAudioName);
                put(start, "total", total);
                sendAudioFrame(node.id, "audio.file.start", start, () ->
                    runOnUiThread(() -> playbackStatus.setText("Starting safe audio transfer...")));
            } catch (Exception error) {
                runOnUiThread(() -> playbackStatus.setText("File read failed: " + safe(error)));
            }
        });
    }

    private void sendAudioChunk(String nodeId, String transferId, String name,
                                byte[] bytes, int index, int total) {
        int offset = index * AUDIO_CHUNK_BYTES;
        int length = Math.min(AUDIO_CHUNK_BYTES, bytes.length - offset);
        byte[] chunk = new byte[length];
        System.arraycopy(bytes, offset, chunk, 0, length);
        JSONObject payload = new JSONObject();
        put(payload, "id", transferId);
        put(payload, "name", name);
        put(payload, "index", index);
        put(payload, "total", total);
        put(payload, "data", Base64.encodeToString(chunk, Base64.NO_WRAP));
        sendAudioFrame(nodeId, "audio.file.chunk", payload, () ->
            runOnUiThread(() -> playbackStatus.setText("Sending audio: " + (index + 1) + "/" + total)));
    }

    private void sendAudioFrame(String nodeId, String type, JSONObject payload, Runnable success) {
        try {
            JSONObject frame = new JSONObject();
            frame.put("v", 1);
            frame.put("type", type);
            frame.put("payload", payload);
            messageApi.sendMessage(nodeId, frame.toString().getBytes(StandardCharsets.UTF_8))
                .addOnSuccessListener(unused -> { if (success != null) success.run(); })
                .addOnFailureListener(error -> runOnUiThread(() ->
                    playbackStatus.setText("Audio transfer failed: " + safe(error))));
        } catch (Exception error) {
            runOnUiThread(() -> playbackStatus.setText("Audio transfer error: " + safe(error)));
        }
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
                    runOnUiThread(() -> status.setText("Часы подключены. YouTube Mini готов к обмену данными."));
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
                runOnUiThread(() -> {
                    showResults(items);
                    sendSearchResultsToWatch(items);
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Поиск не удался: " + safe(error)));
            }
        });
    }

    private void sendSearchResultsToWatch(JSONArray found) {
        Node node = activeNode;
        if (node == null || found == null) return;
        JSONArray compact = new JSONArray();
        for (int i = 0; i < found.length(); i++) {
            JSONObject item = found.optJSONObject(i);
            if (item == null) continue;
            JSONObject id = item.optJSONObject("id");
            JSONObject snippet = item.optJSONObject("snippet");
            if (id == null || snippet == null) continue;
            String videoId = id.optString("videoId", "");
            if (videoId.isEmpty()) continue;
            JSONObject video = new JSONObject();
            put(video, "videoId", videoId);
            put(video, "title", snippet.optString("title", "Video"));
            put(video, "channelTitle", snippet.optString("channelTitle", ""));
            put(video, "thumbnailUrl", thumbnailUrlFor(snippet, videoId));
            JSONObject stats = item.optJSONObject("statistics");
            put(video, "likeCount", stats == null ? "—" : stats.optString("likeCount", "—"));
            put(video, "commentCount", stats == null ? "—" : stats.optString("commentCount", "—"));
            compact.put(video);
        }
        sendToNode(node.id, "video.results", put(new JSONObject(), "items", compact));
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
                    JSONObject stats = item.optJSONObject("statistics");
                    video.put("likeCount", stats == null ? JSONObject.NULL : stats.opt("likeCount"));
                    video.put("commentCount", stats == null ? JSONObject.NULL : stats.opt("commentCount"));
                    video.put("thumbnailUrl", thumbnailUrlFor(snippet, videoId));
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
                        row.put("author", commentSnippet.optString("authorDisplayName", "User"));
                        row.put("text", commentSnippet.optString("textDisplay", ""));
                        comments.put(row);
                    }
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
            sendToNode(nodeId, "comments.items", put(new JSONObject(), "items", comments));
            sendCommentImages(comments, nodeId);
        } catch (Exception error) {
            JSONObject failure = new JSONObject();
            put(failure, "items", new JSONArray());
            put(failure, "error", "Could not load comments. Check the phone connection, API key, or video comment settings.");
            sendToNode(nodeId, "comments.items", failure);
            runOnUiThread(() -> playbackStatus.setText("Комментарии: " + safe(error)));
        }
    }

    private void sendCommentImages(JSONArray comments, String nodeId) {
        for (int i = 0; i < comments.length(); i++) {
            JSONObject row = comments.optJSONObject(i);
            if (row == null) continue;
            sendCommentPng(nodeId, i, row.optString("author", "User"),
                row.optString("text", ""));
        }
    }

    private void sendCommentPng(String nodeId, int index, String author, String text) {
        try {
            String safeText = text == null ? "" : text.replaceAll("\\r", "").trim();
            if (safeText.isEmpty()) safeText = "(empty comment)";
            Bitmap measureBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas measureCanvas = new Canvas(measureBitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(android.graphics.Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(14f);
            int maxWidth = 420;
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            for (String paragraph : safeText.split("\\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ")) {
                    String candidate = line.length() == 0 ? word : line + " " + word;
                    if (paint.measureText(candidate) > maxWidth - 24 && line.length() > 0) {
                        lines.add(line.toString());
                        line = new StringBuilder(word);
                    } else line = new StringBuilder(candidate);
                }
                lines.add(line.toString());
            }
            measureBitmap.recycle();
            int height = Math.max(76, 48 + lines.size() * 19);
            Bitmap bitmap = Bitmap.createBitmap(maxWidth, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.rgb(37, 43, 54));
            paint.setTextSize(14f);
            String safeAuthor = author == null ? "User" : author;
            if (paint.measureText(safeAuthor) > maxWidth - 24) {
                while (safeAuthor.length() > 3 && paint.measureText(safeAuthor + "...") > maxWidth - 24)
                    safeAuthor = safeAuthor.substring(0, safeAuthor.length() - 1);
                safeAuthor += "...";
            }
            canvas.drawText(safeAuthor, 12, 24, paint);
            paint.setTextSize(13f);
            int y = 48;
            for (String line : lines) {
                canvas.drawText(line, 12, y, paint);
                y += 19;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
            String encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            JSONObject payload = new JSONObject();
            put(payload, "id", "comment" + index);
            put(payload, "base64", encoded);
            sendToNode(nodeId, "comments.image", payload);
        } catch (Exception error) {
            runOnUiThread(() -> status.setText("Comment image error: " + safe(error)));
        }
    }

    private JSONArray fetchVideoResults(String query, String key) throws Exception {
        HttpURLConnection connection = null;
        try {
            String searchTerms = query.trim();
            if (!searchTerms.toLowerCase(java.util.Locale.US).contains("#shorts")) {
                searchTerms += " #Shorts";
            }
            String address = "https://www.googleapis.com/youtube/v3/search"
                + "?part=snippet&type=video&videoDuration=short&maxResults=10"
                + "&q=" + URLEncoder.encode(searchTerms, "UTF-8")
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
            if (items == null) return new JSONArray();
            JSONObject statistics = fetchVideoStatistics(items, key);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                JSONObject id = item == null ? null : item.optJSONObject("id");
                String videoId = id == null ? "" : id.optString("videoId", "");
                JSONObject stats = statistics.optJSONObject(videoId);
                if (item != null && stats != null) item.put("statistics", stats);
            }
            return items;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject fetchVideoStatistics(JSONArray videoItems, String key) {
        JSONObject result = new JSONObject();
        HttpURLConnection connection = null;
        try {
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < videoItems.length(); i++) {
                JSONObject item = videoItems.optJSONObject(i);
                JSONObject id = item == null ? null : item.optJSONObject("id");
                String videoId = id == null ? "" : id.optString("videoId", "");
                if (videoId.isEmpty()) continue;
                if (ids.length() > 0) ids.append(",");
                ids.append(videoId);
            }
            if (ids.length() == 0) return result;
            String address = "https://www.googleapis.com/youtube/v3/videos"
                + "?part=statistics&id=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + "&key=" + URLEncoder.encode(key, "UTF-8");
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != 200) return result;
            JSONArray rows = new JSONObject(readAll(connection.getInputStream())).optJSONArray("items");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    JSONObject stats = row.optJSONObject("statistics");
                    if (stats != null) result.put(row.optString("id"), stats);
                }
            }
        } catch (Exception ignored) {
            // Search still works when public statistics are unavailable.
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
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
            String thumbnailUrl = thumbnailUrlFor(snippet, selectedId);
            JSONObject stats = item.optJSONObject("statistics");
            String likes = stats == null ? "—" : stats.optString("likeCount", "—");
            String comments = stats == null ? "—" : stats.optString("commentCount", "—");
            if (selectedId.isEmpty()) continue;
            Button result = new Button(this);
            result.setText(title + (channel.isEmpty() ? "" : "\n" + channel)
                + "\n♥ " + likes + "   ·   Комментарии: " + comments);
            result.setOnClickListener(v -> {
                videoId.setText(selectedId);
                videoTitle.setText(title);
                sendVideo(selectedId, title, channel, thumbnailUrl, likes, comments);
                Node node = activeNode;
                if (node != null) sendToNode(node.id, "comments.load", put(new JSONObject(), "videoId", selectedId));
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
        sendVideo(id, title.isEmpty() ? "Видео YouTube" : title, "", "", "", "");
    }

    private void sendVideo(String id, String title, String channel, String thumbnail,
                           String likeCount, String commentCount) {
        JSONObject payload = new JSONObject();
        if (thumbnail == null || thumbnail.isEmpty()) thumbnail = thumbnailUrlFor(id);
        put(payload, "videoId", id);
        put(payload, "title", title);
        put(payload, "channelTitle", channel);
        put(payload, "thumbnailUrl", thumbnail);
        put(payload, "likeCount", likeCount == null ? "" : likeCount);
        put(payload, "commentCount", commentCount == null ? "" : commentCount);
        send("video.select", payload);
    }

    private static String thumbnailUrlFor(JSONObject snippet, String videoId) {
        if (snippet != null) {
            JSONObject thumbnails = snippet.optJSONObject("thumbnails");
            if (thumbnails != null) {
                String[] priorities = { "high", "medium", "default" };
                for (String size : priorities) {
                    JSONObject image = thumbnails.optJSONObject(size);
                    if (image == null) continue;
                    String url = image.optString("url", "");
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        return url;
                    }
                }
            }
        }
        return thumbnailUrlFor(videoId);
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
