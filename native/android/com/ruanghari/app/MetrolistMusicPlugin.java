package com.ruanghari.app;

import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Plugin musik Ruang Hari.
 * - search(): protokol pencarian dan pemetaan renderer diadaptasi dari Metrolist YouTube.kt dan SearchPage.kt.
 * - playVideo(): ID lagu -> stream via InnerTubeX (StreamResolver) -> Media3 ExoPlayer (MusicEngine).
 * GPL-3.0; lihat THIRD_PARTY_NOTICES.md dan LICENSE. Dimodifikasi 2026-11-09 untuk Capacitor.
 */
@CapacitorPlugin(name = "MetrolistMusic")
public class MetrolistMusicPlugin extends Plugin {
    private MusicEngine engine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private synchronized MusicEngine engine() {
        if (engine == null) engine = new MusicEngine(getContext().getApplicationContext());
        return engine;
    }

    /** Putar lagu YouTube Music lewat mesin native: {videoId}. */
    @PluginMethod public void playVideo(PluginCall call) {
        String id = call.getString("videoId", "");
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) { call.reject("ID lagu tidak valid"); return; }
        engine().playVideo(id, new MusicEngine.StartCallback() {
            @Override public void onStarted(String clientName, String mimeType, long bitrate) {
                JSObject result = new JSObject();
                result.put("client", clientName);
                result.put("mimeType", mimeType);
                result.put("bitrate", bitrate);
                call.resolve(result);
            }
            @Override public void onError(String message) { call.reject(message); }
        });
    }
    @PluginMethod public void playUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) { call.reject("URL HTTPS diperlukan"); return; }
        engine().playUrl(url); call.resolve();
    }
    @PluginMethod public void pause(PluginCall call) { engine().pause(); call.resolve(); }
    @PluginMethod public void resume(PluginCall call) { engine().resume(); call.resolve(); }
    @PluginMethod public void stop(PluginCall call) { engine().stop(); call.resolve(); }
    @PluginMethod public void seek(PluginCall call) {
        Long position = call.getLong("positionMs");
        if (position == null || position < 0) { call.reject("Posisi tidak valid"); return; }
        engine().seek(position); call.resolve();
    }
    @PluginMethod public void status(PluginCall call) {
        engine().status(new MusicEngine.StatusCallback() {
            @Override public void onStatus(boolean playing, long positionMs, long durationMs, String state, String videoId, String error) {
                JSObject result = new JSObject();
                result.put("playing", playing);
                result.put("positionMs", positionMs);
                result.put("durationMs", durationMs);
                result.put("state", state);
                result.put("videoId", videoId == null ? "" : videoId);
                result.put("error", error == null ? "" : error);
                call.resolve(result);
            }
        });
    }
    @PluginMethod public void search(PluginCall call) {
        String query = call.getString("query", "").trim();
        if (query.isEmpty() || query.length() > 160) { call.reject("Masukkan judul lagu atau artis (maksimal 160 karakter)."); return; }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection();
                connection.setRequestMethod("POST"); connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Origin", "https://music.youtube.com");
                connection.setRequestProperty("Referer", "https://music.youtube.com/");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36");
                JSONObject client = new JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20241104.01.00").put("hl", "id").put("gl", "ID");
                JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client)).put("query", query)
                    .put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D");
                try (OutputStream stream = connection.getOutputStream()) { stream.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int status = connection.getResponseCode();
                if (status != 200) throw new IOException("Layanan musik mengembalikan HTTP " + status);
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) { response.append(line); if(response.length()>8000000) throw new IOException("Respons musik terlalu besar"); }
                }
                JSArray songs = new JSArray(); Set<String> seen = new HashSet<>();
                collect(new JSONObject(response.toString()), songs, seen);
                call.resolve(new JSObject().put("songs", songs));
            } catch (Exception error) { call.reject("Pencarian gagal. Periksa internet atau coba lagi. " + error.getMessage()); }
            finally { if(connection != null) connection.disconnect(); }
        });
    }
    private void collect(Object node, JSArray songs, Set<String> seen) throws JSONException {
        if (songs.length() >= 40) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject item = object.optJSONObject("musicResponsiveListItemRenderer");
            if (item != null) {
                JSONObject data = item.optJSONObject("playlistItemData");
                String id = data == null ? "" : data.optString("videoId");
                if (id.matches("[A-Za-z0-9_-]{11}") && seen.add(id)) {
                    JSONArray columns = item.optJSONArray("flexColumns");
                    String title = column(columns, 0), subtitle = column(columns, 1);
                    if (!title.isEmpty()) songs.put(new JSObject().put("id", id).put("title", title).put("artist", subtitle));
                }
            }
            Iterator<String> keys=object.keys(); while(keys.hasNext()) collect(object.opt(keys.next()),songs,seen);
        } else if(node instanceof JSONArray) { JSONArray items=(JSONArray)node; for(int i=0;i<items.length();i++) collect(items.opt(i),songs,seen); }
    }
    private String column(JSONArray columns, int index) {
        if(columns==null) return "";
        JSONObject column=columns.optJSONObject(index);
        if(column==null) return "";
        JSONObject renderer=column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        if(renderer==null || renderer.optJSONObject("text")==null) return "";
        JSONArray runs=renderer.optJSONObject("text").optJSONArray("runs"); StringBuilder value=new StringBuilder();
        if(runs!=null) for(int i=0;i<runs.length();i++) { JSONObject run=runs.optJSONObject(i); if(run!=null) value.append(run.optString("text")); }
        return value.toString();
    }
    @Override protected void handleOnDestroy() { executor.shutdownNow(); if (engine != null) engine.release(); }
}
