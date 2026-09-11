package org.traccar.family;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

final class FamilyRequest {
    final String id;
    final String token;
    final String kind;
    final long expires;
    final String base;

    FamilyRequest(String id, String token, String kind, long expires, String base) {
        this.id = id; this.token = token; this.kind = kind; this.expires = expires; this.base = base;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("family_access", Context.MODE_PRIVATE);
    }

    static String normalizeUrl(String text) {
        URI uri = URI.create(text.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Use the HTTPS address of your Traccar web server");
        }
        String path = uri.getPath();
        if (path != null && (path.contains("..") || path.contains("\\"))) throw new IllegalArgumentException();
        return uri.toString().replaceAll("/+$", "");
    }

    static FamilyRequest fromPush(Context context, Map<String, Object> data) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean("enabled", false) || !prefs.getString("deviceId", "").equals(data.get("deviceId"))) return null;
        String id = String.valueOf(data.get("familyId"));
        String token = String.valueOf(data.get("familyToken"));
        String kind = String.valueOf(data.get("familyKind"));
        long expires = Long.parseLong(String.valueOf(data.get("familyExpires")));
        if (!id.matches("[a-f0-9-]{36}") || !token.matches("[A-Za-z0-9_-]{43}")
                || !(kind.equals("audio") || kind.equals("screenshot") || kind.equals("ring"))
                || expires <= System.currentTimeMillis()) return null;
        return new FamilyRequest(id, token, kind, expires, normalizeUrl(prefs.getString("url", "")));
    }

    Intent intent(Context context, Class<?> destination) {
        return new Intent(context, destination).putExtra("id", id).putExtra("token", token)
                .putExtra("kind", kind).putExtra("expires", expires).putExtra("base", base);
    }

    static FamilyRequest fromIntent(Intent intent) {
        return new FamilyRequest(intent.getStringExtra("id"), intent.getStringExtra("token"),
                intent.getStringExtra("kind"), intent.getLongExtra("expires", 0), intent.getStringExtra("base"));
    }

    private byte[] exchange(Context context, String state, byte[] bytes) throws Exception {
        if (expires <= System.currentTimeMillis() || !prefs(context).getBoolean("enabled", false)
                || !base.equals(prefs(context).getString("url", ""))) throw new IOException("Session disabled or expired");
        URL url = new URL(base + "/api/family/" + id + "/device" + (state == null ? "" : "?state=" + state));
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setRequestProperty("X-Family-Token", token);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (state != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            if (connection.getResponseCode() != 200) throw new IOException("Session rejected");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8192) throw new IOException("Invalid server response");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    void validate(Context context, boolean pending) throws Exception {
        JSONObject status = new JSONObject(new String(exchange(context, null, null), java.nio.charset.StandardCharsets.UTF_8));
        if (!kind.equals(status.getString("kind")) || !id.equals(status.getString("id"))
                || expires != status.getLong("expires")
                || !(pending ? "pending" : "active").equals(status.getString("state"))) {
            throw new IOException("Invalid session");
        }
    }

    void send(Context context, String state, byte[] bytes) throws Exception { exchange(context, state, bytes); }
    void state(Context context, String state) throws Exception { send(context, state, new byte[0]); }
    void finish(Context context, String state) {
        new Thread(() -> { try { state(context, state); } catch (Exception ignored) { } }, "family-result").start();
    }
}
