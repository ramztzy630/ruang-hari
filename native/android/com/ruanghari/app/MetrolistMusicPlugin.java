package com.ruanghari.app;

import com.getcapacitor.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
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
    private volatile MusicEngine engine;
    private volatile boolean closed;
    private volatile String bindingError;
    private boolean bound;
    private final CountDownLatch serviceReady = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService metadataExecutor = Executors.newFixedThreadPool(2);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try { engine = ((RuangMusicService.LocalBinder) binder).getEngine(); }
            catch (Throwable error) { bindingError = describe(error); }
            finally { serviceReady.countDown(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { engine = null; bindingError = "Layanan musik terputus. Buka ulang aplikasi."; }
        @Override public void onNullBinding(ComponentName name) { bindingError = "Layanan musik tidak dapat dihubungkan."; serviceReady.countDown(); }
        @Override public void onBindingDied(ComponentName name) { engine = null; bindingError = "Layanan musik terputus. Buka ulang aplikasi."; serviceReady.countDown(); }
    };
    @Override public void load() {
        getActivity().runOnUiThread(() -> {
            if (closed) return;
            try {
                Intent intent = new Intent(getContext(), RuangMusicService.class).setAction(RuangMusicService.LOCAL_BIND);
                bound = getContext().getApplicationContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bound) { bindingError = "Gagal menghubungkan layanan musik."; serviceReady.countDown(); }
            } catch (Throwable error) { bindingError = describe(error); serviceReady.countDown(); }
        });
    }
    private MusicEngine engine() throws Exception {
        if (closed) throw new IllegalStateException("Tampilan aplikasi sudah ditutup.");
        if (!serviceReady.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Layanan musik tidak merespons.");
        MusicEngine current = engine;
        if (current == null) throw new IllegalStateException(bindingError == null ? "Layanan musik belum siap." : bindingError);
        return current;
    }
    private interface EngineCommand { void run(MusicEngine engine) throws Exception; }
    private void command(PluginCall call, EngineCommand action) {
        playbackExecutor.execute(() -> {
            try { action.run(engine()); call.resolve(); }
            catch (Throwable error) { call.reject(describe(error)); }
        });
    }

    /** Putar lagu YouTube Music lewat mesin native: {videoId}. */
    @PluginMethod public void playVideo(PluginCall call) {
        String id = call.getString("videoId", "");
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) { call.reject("ID lagu tidak valid"); return; }
        // Keep Capacitor's plugin thread available for status/stop, even during class loading.
        playbackExecutor.execute(() -> {
            try {
                MusicEngine current = engine();
                current.setQueue(call.getArray("queue", new JSArray()));
                current.setQueueMode(call.getString("mode", "shuffle"));
                current.setDisplayMetadata(id, call.getString("title", ""), call.getString("artist", ""), call.getString("album", ""), call.getString("cover", ""));
                current.playVideo(id, new MusicEngine.StartCallback() {
                    @Override public void onStarted(String clientName, String mimeType, long bitrate) {
                        JSObject result = new JSObject();
                        result.put("client", clientName); result.put("mimeType", mimeType); result.put("bitrate", bitrate);
                        call.resolve(result);
                    }
                    @Override public void onError(String message) { call.reject(message); }
                });
            } catch (Throwable error) {
                android.util.Log.e("RuangStream", "Native music initialization failed", error);
                call.reject(describe(error));
            }
        });
    }

    private String describe(Throwable error) {
        StringBuilder detail = new StringBuilder("Native 09-11e: ");
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) detail.append(" → ");
            detail.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
        }
        return detail.toString();
    }

    @PluginMethod public void diagnostics(PluginCall call) {
        JSObject result = new JSObject();
        result.put("nativeBuild", "09-11e");
        result.put("androidApi", android.os.Build.VERSION.SDK_INT);
        call.resolve(result);
    }

    @PluginMethod public void playUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) { call.reject("URL HTTPS diperlukan"); return; }
        command(call, e -> e.playUrl(url));
    }
    @PluginMethod public void pause(PluginCall call) { command(call, e -> e.pause()); }
    @PluginMethod public void resume(PluginCall call) { command(call, e -> e.resume()); }
    @PluginMethod public void stop(PluginCall call) { command(call, e -> e.stop()); }
    @PluginMethod public void seek(PluginCall call) {
        Long position = call.getLong("positionMs");
        if (position == null || position < 0) { call.reject("Posisi tidak valid"); return; }
        command(call, e -> e.seek(position));
    }
    @PluginMethod public void status(PluginCall call) {
        MusicEngine current = engine;
        if (current == null) {
            JSObject result = new JSObject(); result.put("playing", false); result.put("state", "connecting");
            result.put("error", bindingError == null ? "" : bindingError); call.resolve(result); return;
        }
        try { current.status(new MusicEngine.StatusCallback() {
            @Override public void onStatus(boolean playing, long positionMs, long durationMs, String state, String videoId, String error) {
                JSObject result = new JSObject();
                result.put("metadata", current.metadataSnapshot());
                result.put("playing", playing);
                result.put("positionMs", positionMs);
                result.put("durationMs", durationMs);
                result.put("state", state);
                result.put("videoId", videoId == null ? "" : videoId);
                result.put("error", error == null ? "" : error);
                call.resolve(result);
            }
        }); } catch (Throwable error) { call.reject(describe(error)); }
    }
    @PluginMethod public void lyrics(PluginCall call) {
        String title=call.getString("title", "").trim(), artist=call.getString("artist", "").trim();
        String album=call.getString("album", "").trim();
        Double duration=call.getDouble("duration", 0.0);
        if(title.isEmpty()||artist.isEmpty()||title.length()>200||artist.length()>200||album.length()>200){call.reject("Metadata lagu belum lengkap untuk mencari lirik.");return;}
        metadataExecutor.execute(()->{
            try{call.resolve(LyricsClient.fetch(title,artist,album,duration));}
            catch(Exception error){call.reject("Lirik belum dapat dimuat: "+error.getMessage());}
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
                    if (!title.isEmpty()) {
                        JSObject song=new JSObject();song.put("id",id);song.put("title",title);song.put("artist",subtitle);
                        String[] parts=subtitle.split("[•·]");
                        song.put("artistName",parts.length>0?parts[0].trim():"");
                        song.put("album",parts.length>2?parts[1].trim():"");
                        String cover="";
                        JSONObject thumb=item.optJSONObject("thumbnail");
                        JSONObject renderer=thumb==null?null:thumb.optJSONObject("musicThumbnailRenderer");
                        JSONObject image=renderer==null?null:renderer.optJSONObject("thumbnail");
                        JSONArray thumbnails=image==null?null:image.optJSONArray("thumbnails");
                        long largest = -1;
                        if(thumbnails!=null)for(int t=0;t<thumbnails.length();t++){
                            JSONObject imageData=thumbnails.optJSONObject(t);if(imageData==null)continue;
                            long area=(long)imageData.optInt("width",0)*imageData.optInt("height",0);
                            String candidate=imageData.optString("url", "");
                            if(candidate.startsWith("https://")&&area>=largest){cover=candidate;largest=area;}
                        }
                        song.put("coverFallback",cover);song.put("cover",hdCover(cover));songs.put(song);
                    }
                }
            }
            Iterator<String> keys=object.keys(); while(keys.hasNext()) collect(object.opt(keys.next()),songs,seen);
        } else if(node instanceof JSONArray) { JSONArray items=(JSONArray)node; for(int i=0;i<items.length();i++) collect(items.opt(i),songs,seen); }
    }
    private String hdCover(String cover) {
        try {
            URI uri=URI.create(cover);String host=uri.getHost();
            if(!"https".equals(uri.getScheme())||host==null)return "";
            if(host.endsWith(".googleusercontent.com")||host.endsWith(".ggpht.com")){
                cover=cover.replaceAll("([=-])w[0-9]+", "$1w600").replaceAll("([=-])h[0-9]+", "$1h600").replaceAll("([=-])s[0-9]+", "$1s600");
            }
            return cover;
        }catch(Exception error){return "";}
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
    @Override protected void handleOnDestroy() {
        closed = true;
        executor.shutdownNow(); metadataExecutor.shutdownNow(); playbackExecutor.shutdownNow();
        if (bound) {
            try { getContext().getApplicationContext().unbindService(connection); } catch (IllegalArgumentException ignored) {}
            bound = false;
        }
        // Only RuangMusicService owns/releases the player. Leaving the UI must not stop it.
        engine = null;
    }
}
