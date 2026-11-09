package com.ruanghari.app;

import com.getcapacitor.*;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Search protocol and renderer mapping adapted from Metrolist YouTube.kt and SearchPage.kt.
 * GPL-3.0; see THIRD_PARTY_NOTICES.md and LICENSE. Modified 2026-11-09 for Capacitor.
 */
@CapacitorPlugin(name = "MetrolistMusic")
public class MetrolistMusicPlugin extends Plugin {
    private ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @PluginMethod public void playUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (!url.startsWith("https://")) { call.reject("URL HTTPS diperlukan"); return; }
        handler.post(() -> { try {
            if (player == null) player = new ExoPlayer.Builder(getContext()).build();
            player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play(); call.resolve();
        } catch(Exception e) { call.reject(e.getMessage()); } });
    }
    @PluginMethod public void pause(PluginCall call) { handler.post(() -> { if(player != null) player.pause(); call.resolve(); }); }
    @PluginMethod public void resume(PluginCall call) { handler.post(() -> { if(player != null) player.play(); call.resolve(); }); }
    @PluginMethod public void stop(PluginCall call) { handler.post(() -> { if(player != null) player.stop(); call.resolve(); }); }
    @PluginMethod public void seek(PluginCall call) {
        Long position = call.getLong("positionMs");
        if(position == null || position < 0) { call.reject("Posisi tidak valid"); return; }
        handler.post(() -> { if(player != null) player.seekTo(position); call.resolve(); });
    }
    @PluginMethod public void status(PluginCall call) { handler.post(() -> {
        JSObject result = new JSObject();
        result.put("playing",player != null && player.isPlaying());
        result.put("positionMs",player == null ? 0 : player.getCurrentPosition());
        result.put("durationMs",player == null ? 0 : player.getDuration());
        call.resolve(result);
    }); }
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
    @Override protected void handleOnDestroy() { executor.shutdownNow(); handler.post(() -> { if(player != null) { player.release(); player = null; } }); }
}
