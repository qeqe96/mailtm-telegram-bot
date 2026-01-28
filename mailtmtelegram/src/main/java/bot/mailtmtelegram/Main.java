package bot.mailtmtelegram;
import okhttp3.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    // ================== CONFIG ==================
    static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    static final long CHAT_ID = Long.parseLong(System.getenv("CHAT_ID"));
    static final String API = "https://api.mail.tm";
    static final String PASSWORD = "123456";
    static final int BATCH_SIZE = 10;
    static final String PREFIX = "xqhlvrna";

    // ================== STATE ==================
    static final Map<Integer, String> activeMails = new ConcurrentHashMap<>();
    static final Map<String, String> tokenMap = new ConcurrentHashMap<>();
    static final Set<String> seenIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    static int batchStart = 1;
    static boolean creating = false;
    static String domain = "";
    static long lastUpdateId = 0;

    static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(15, 5, TimeUnit.MINUTES)) // Hızlı bağlantı havuzu
            .build();

    public static void main(String[] args) throws Exception {
        if (BOT_TOKEN == null || System.getenv("CHAT_ID") == null) return;
        domain = fetchDomain();
        sendTG("🚀 Bot Jet Modunda Başladı\nDomain: " + domain);

        while (true) {
            try {
                pollTelegram();
                if (!activeMails.isEmpty()) {
                    checkEmailsFast(); // Paralel kontrol
                }
            } catch (Exception ignored) {}
            Thread.sleep(500); // Yarım saniyede bir kontrol (Maks hız)
        }
    }

    // ================== MAKS HIZLI KONTROL ==================
    static void checkEmailsFast() {
        // Parallel stream kullanarak tüm mailleri aynı anda sorgular
        activeMails.values().parallelStream().forEach(email -> {
            try {
                String token = getToken(email);
                if (token == null) return;

                Request req = new Request.Builder()
                        .url(API + "/messages")
                        .addHeader("Authorization", "Bearer " + token)
                        .build();

                try (Response res = client.newCall(req).execute()) {
                    JSONObject json = new JSONObject(res.body().string());
                    JSONArray messages = json.getJSONArray("hydra:member");

                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject m = messages.getJSONObject(i);
                        String id = m.getString("id");

                        if (seenIds.add(id)) {
                            // 2. Satırı çekme mantığı
                            String intro = m.optString("intro", "");
                            String[] lines = intro.split("\n");
                            String code = (lines.length >= 2) ? lines[1].trim() : intro;

                            sendTG("🔑 *YENİ KOD*\n📧: " + email + "\n🔢: `" + code + "`");
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    static String getToken(String email) {
        return tokenMap.computeIfAbsent(email, k -> {
            try {
                RequestBody body = RequestBody.create(
                        new JSONObject().put("address", email).put("password", PASSWORD).toString(),
                        MediaType.get("application/json"));
                Request req = new Request.Builder().url(API + "/token").post(body).build();
                try (Response res = client.newCall(req).execute()) {
                    return new JSONObject(res.body().string()).getString("token");
                }
            } catch (Exception e) { return null; }
        });
    }

    // ================== TELEGRAM & BATCH (STABİL) ==================
    static void pollTelegram() throws Exception {
        Request req = new Request.Builder()
                .url("https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=0")
                .build();

        try (Response res = client.newCall(req).execute()) {
            JSONObject json = new JSONObject(res.body().string());
            if (!json.getBoolean("ok")) return;
            for (Object o : json.getJSONArray("result")) {
                JSONObject u = (JSONObject) o;
                lastUpdateId = u.getLong("update_id");
                if (u.has("message")) {
                    String text = u.getJSONObject("message").optString("text", "");
                    if (text.equals("/new")) createBatch();
                    if (text.equals("/list")) sendTG(listMails());
                }
            }
        }
    }

    static void sendTG(String text) {
        try {
            RequestBody body = RequestBody.create(
                    new JSONObject().put("chat_id", CHAT_ID).put("text", text).put("parse_mode", "Markdown").toString(),
                    MediaType.get("application/json"));
            client.newCall(new Request.Builder().url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage").post(body).build()).execute().close();
        } catch (Exception ignored) {}
    }

    static String fetchDomain() throws Exception {
        try (Response res = client.newCall(new Request.Builder().url(API + "/domains").build()).execute()) {
            return new JSONObject(res.body().string()).getJSONArray("hydra:member").getJSONObject(0).getString("domain");
        }
    }

    static synchronized void createBatch() throws Exception {
        if (creating) return;
        creating = true;
        activeMails.clear();
        tokenMap.clear();
        seenIds.clear();

        int created = 0;
        int i = batchStart;
        while (created < BATCH_SIZE) {
            if (createAccount(i)) {
                activeMails.put(i, PREFIX + i + "@" + domain);
                created++;
            }
            i++;
        }
        batchStart = i;
        sendTG("✅ " + BATCH_SIZE + " Mail Aktif. Dinleniyor...");
        creating = false;
    }

    static boolean createAccount(int n) {
        try {
            String mail = PREFIX + n + "@" + domain;
            RequestBody body = RequestBody.create(
                    new JSONObject().put("address", mail).put("password", PASSWORD).toString(),
                    MediaType.get("application/json"));
            try (Response res = client.newCall(new Request.Builder().url(API + "/accounts").post(body).build()).execute()) {
                return res.isSuccessful();
            }
        } catch (Exception e) { return false; }
    }

    static String listMails() {
        if (activeMails.isEmpty()) return "📭 Boş";
        StringBuilder sb = new StringBuilder("📋 *LİSTE*\n");
        activeMails.values().forEach(m -> sb.append("`").append(m).append("`\n"));
        return sb.toString();
    }

    static String status() { return "📊 Aktif: " + activeMails.size(); }
}