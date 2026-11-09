package com.ruanghari.app;

import com.getcapacitor.JSObject;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** LRCLIB provider, following Metrolist's LrcLib provider API. */
final class LyricsClient {
    private static String enc(String text) throws Exception { return URLEncoder.encode(text, "UTF-8"); }
    private static String get(String path) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("https://lrclib.net"+path).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent","RuangHari/1.1 (https://github.com/ramztzy630/ruang-hari)");
        try {
            if(c.getResponseCode()==404)return null;
            if(c.getResponseCode()!=200)throw new IOException("Layanan lirik HTTP "+c.getResponseCode());
            StringBuilder body=new StringBuilder();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=r.readLine())!=null){body.append(line);if(body.length()>1000000)throw new IOException("Respons lirik terlalu besar");}
            }
            return body.toString();
        }finally{c.disconnect();}
    }
    static JSObject fetch(String title,String artist,String album,double duration) throws Exception {
        String query="track_name="+enc(title)+"&artist_name="+enc(artist);
        if(!album.isEmpty())query+="&album_name="+enc(album);
        if(duration>0)query+="&duration="+Math.round(duration);
        String body=get("/api/get?"+query);
        JSONObject found=body==null?null:new JSONObject(body);
        if(found==null){
            String search=get("/api/search?track_name="+enc(title)+"&artist_name="+enc(artist));
            JSONArray items=new JSONArray(search==null?"[]":search);
            double difference=Double.MAX_VALUE;
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);
                if(!item.optString("trackName").equalsIgnoreCase(title)||!item.optString("artistName").equalsIgnoreCase(artist))continue;
                double diff=duration>0?Math.abs(item.optDouble("duration",0)-duration):0;
                if(diff<=4&&diff<difference){found=item;difference=diff;}
            }
        }
        JSObject result=new JSObject();result.put("source","LRCLIB");
        result.put("plain",found==null?"":found.optString("plainLyrics", ""));
        result.put("synced",found==null?"":found.optString("syncedLyrics", ""));
        result.put("instrumental",found!=null&&found.optBoolean("instrumental",false));
        return result;
    }
}
