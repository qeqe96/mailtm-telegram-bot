package bot.mailtmtelegram;
import okhttp3.*;
import org.json.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class Main {

    // ================== CONFIG ==================
    static final String BOT_TOKEN = "TELEGRAM_BOT_TOKEN";
    static final long CHAT_ID = 123456789L;

    static final String API = "https://api.mail.tm";
    static final String PASSWORD = "123456";
    static final int BATCH_SIZE = 5;
    static final String PREFIX = "xqhlvrna";

    // ================== STATE ==================
    static final Map<Integer, String> activeMails = new LinkedHashMap<>();
    static final Map<String, String> tokenMap = new HashMap<>(); // Hız için tokenları saklıyoruz
    static final Set<String> seenMessageIds = new HashSet<>(); // Aynı kodu tekrar atmaması için
    
    static int batchStart = 1;
    static boolean creating = false;
    static String domain = "";
    static long lastUpdateId = 0;

    static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) throws Exception {
        domain = fetchDomain();
        sendTG("🤖 Bot Jet Modunda Hazır\nDomain: " + domain);

        while (true) {
            try {
                pollTelegram();
                if (!activeMails.isEmpty()) {
                    checkAllInboxes();
                }
            } catch (Exception e) {
                System.out.println("Hata: " + e.getMessage());
            }
            Thread.sleep(2000); // 2 saniyede bir hem komut hem mail kontrolü
        }
    }

    // ================== CORE LOGIC (MAIL KONTROL) ==================
    static void checkAllInboxes() {
        for (String email : activeMails.values()) {
            try {
                String token = getToken(email);
                if (token == null) continue;

                Request req = new Request.Builder()
                        .url(API + "/messages")
                        .addHeader("Authorization", "Bearer " + token)
                        .build();

                try (Response res = client.newCall(req).execute()) {
                    JSONObject json = new JSONObject(res.body().string());
                    JSONArray messages = json.getJSONArray("hydra:member");

                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject m = messages.getJSONObject(i);
                        String msgId = m.getString("id");

                        if (!seenMessageIds.contains(msgId)) {
                            String content = m.getString("intro"); // Mailin özeti (genelde kod buradadır)
                            String from = m.getJSONObject("from").getString("address");
                            
                            sendTG("📩 *YENİ KOD GELDİ!*\n📧 Alıcı: " + email + "\n👤 Gönderen: " + from + "\n📝 İçerik: " + content);
                            seenMessageIds.add(msgId);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    static String getToken(String email) {
        if (tokenMap.containsKey(email)) return tokenMap.get(email);

        try {
            JSONObject loginJson = new JSONObject().put("address", email).put("password", PASSWORD);
            RequestBody body = RequestBody.create(loginJson.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder().url(API + "/token").post(body).build();

            try (Response res = client.newCall(req).execute()) {
                if (res.isSuccessful()) {
                    String token = new JSONObject(res.body().string()).getString("token");
                    tokenMap.put(email, token);
                    return token;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ================== TELEGRAM & API UTILS ==================
    static void pollTelegram() throws Exception {
        Request req = new Request.Builder()
                .url("https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=1")
                .build();

        try (Response res = client.newCall(req).execute()) {
            JSONObject json = new JSONObject(res.body().string());
            for (Object o : json.getJSONArray("result")) {
                JSONObject u = (JSONObject) o;
                lastUpdateId = u.getLong("update_id");
                if (!u.has("message")) continue;
                String text = u.getJSONObject("message").optString("text", "");

                if (text.equals("/new")) createBatch();
                if (text.equals("/list")) sendTG(listMails());
                if (text.equals("/status")) sendTG(status());
            }
        }
    }

    static void sendTG(String text) {
        try {
            RequestBody body = RequestBody.create(
                    new JSONObject().put("chat_id", CHAT_ID).put("text", text).put("parse_mode", "Markdown").toString(),
                    MediaType.parse("application/json")
            );
            Request req = new Request.Builder().url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage").post(body).build();
            client.newCall(req).execute().close();
        } catch (Exception ignored) {}
    }

    static String fetchDomain() throws Exception {
        Request req = new Request.Builder().url(API + "/domains").build();
        try (Response res = client.newCall(req).execute()) {
            return new JSONObject(res.body().string()).getJSONArray("hydra:member").getJSONObject(0).getString("domain");
        }
    }

    static synchronized void createBatch() throws Exception {
        if (creating) return;
        creating = true;
        activeMails.clear();
        tokenMap.clear(); // Yeni batch'te tokenları sıfırla
        
        int created = 0;
        int i = batchStart;
        sendTG("⏳ " + BATCH_SIZE + " adet mail oluşturuluyor...");

        while (created < BATCH_SIZE) {
            if (createAccount(i)) {
                activeMails.put(i, PREFIX + i + "@" + domain);
                created++;
            }
            i++;
        }
        batchStart = i;
        sendTG("✅ Mailler hazır. Dinleme başladı!");
        creating = false;
    }

    static boolean createAccount(int n) {
        String mail = PREFIX + n + "@" + domain;
        try {
            RequestBody body = RequestBody.create(
                    new JSONObject().put("address", mail).put("password", PASSWORD).toString(),
                    MediaType.parse("application/json")
            );
            Request req = new Request.Builder().url(API + "/accounts").post(body).build();
            try (Response res = client.newCall(req).execute()) {
                return res.isSuccessful();
            }
        } catch (Exception e) { return false; }
    }

    static String listMails() {
        if (activeMails.isEmpty()) return "📭 Aktif mail yok";
        StringBuilder sb = new StringBuilder("📋 *AKTİF MAİLLER*\n\n");
        for (String m : activeMails.values()) sb.append("`").append(m).append("`\n");
        return sb.toString();
    }

    static String status() {
        return "📊 *DURUM*\nAktif inbox: " + activeMails.size() + "\nDomain: " + domain;
    }
}