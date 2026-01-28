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
    // Sıralı tutmak için TreeMap kullanıyoruz
    static final Map<Integer, String> activeMails = new ConcurrentSkipListMap<>();
    static final Map<String, String> tokenMap = new ConcurrentHashMap<>();
    static final Set<String> seenIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    static int batchStart = 1;
    static volatile boolean creating = false;
    static String domain = "";
    static long lastUpdateId = 0;

    static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) throws Exception {
        domain = fetchDomain();
        sendTG("🚀 Bot Hazır (Sıralı Mod)\nDomain: " + domain);

        while (true) {
            try {
                pollTelegram();
                if (!activeMails.isEmpty() && !creating) {
                    checkEmails(); 
                }
            } catch (Exception ignored) {}
            Thread.sleep(800); // İşlemciyi yormadan seri kontrol
        }
    }

    // ================== TELEGRAM DINLEME ==================
    static void pollTelegram() {
        try {
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=1")
                    .build();

            try (Response res = client.newCall(req).execute()) {
                JSONObject json = new JSONObject(res.body().string());
                if (!json.optBoolean("ok")) return;
                
                JSONArray result = json.getJSONArray("result");
                for (int i = 0; i < result.length(); i++) {
                    JSONObject u = result.getJSONObject(i);
                    lastUpdateId = u.getLong("update_id");
                    if (!u.has("message")) continue;
                    
                    String text = u.getJSONObject("message").optString("text", "");
                    if (text.equals("/new")) {
                        // Sıralı oluşturma için yeni bir işlem başlat
                        new Thread(() -> createBatch()).start();
                    } else if (text.equals("/list")) {
                        sendTG(listMails());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // ================== SIRALI MAIL OLUSTURMA ==================
 // ================== SIRALI VE TAM MAIL OLUSTURMA ==================
    static void createBatch() {
        if (creating) return;
        creating = true;
        
        activeMails.clear();
        tokenMap.clear();
        seenIds.clear();

        sendTG("⏳ " + BATCH_SIZE + " mail sıralı şekilde hazırlanıyor...");

        int createdCount = 0;
        int currentNum = batchStart;

        // Tam 10 tane olana kadar denemeye devam eder
        while (createdCount < BATCH_SIZE) {
            if (createAccount(currentNum)) {
                activeMails.put(currentNum, PREFIX + currentNum + "@" + domain);
                createdCount++;
                currentNum++; // Başarılıysa sonrakine geç
                
                // API'yi yormamak için her başarılı hesapta 400ms bekle
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            } else {
                // Eğer API reddettiyse 1 saniye bekle ve aynı numarayı tekrar dene
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        
        batchStart = currentNum; // Bir sonraki batch için kaldığı yeri güncelle
        sendTG("✅ Mailbox'lar hazır!\n" + listMails());
        creating = false;
    }

    static boolean createAccount(int n) {
        try {
            String mail = PREFIX + n + "@" + domain;
            JSONObject acc = new JSONObject().put("address", mail).put("password", PASSWORD);
            RequestBody body = RequestBody.create(acc.toString(), MediaType.get("application/json"));
            
            try (Response res = client.newCall(new Request.Builder().url(API + "/accounts").post(body).build()).execute()) {
                return res.isSuccessful() || res.code() == 422;
            }
        } catch (Exception e) { return false; }
    }

    // ================== MAIL KONTROL (2. SATIR KODU) ==================
    static void checkEmails() {
        // Sıralı kontrol
        for (String email : activeMails.values()) {
            try {
                String token = getToken(email);
                if (token == null) continue;

                Request req = new Request.Builder()
                        .url(API + "/messages")
                        .addHeader("Authorization", "Bearer " + token)
                        .build();

                try (Response res = client.newCall(req).execute()) {
                    JSONArray messages = new JSONObject(res.body().string()).getJSONArray("hydra:member");
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject m = messages.getJSONObject(i);
                        String id = m.getString("id");

                     // ================== KISA BİLDİRİM FORMATI ==================
                        if (seenIds.add(id)) {
                            String intro = m.optString("intro", "");
                            String[] lines = intro.split("\n");
                            // Sadece 2. satırı (kod) al, yoksa ilk satırı al
                            String code = (lines.length >= 2) ? lines[1].trim() : lines[0].trim();
                            
                            // Bildirimde direk gözükmesi için en sade hali:
                            sendTG("📩 `" + code + "`\n📧 " + email);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    static String getToken(String email) {
        if (tokenMap.containsKey(email)) return tokenMap.get(email);
        try {
            JSONObject login = new JSONObject().put("address", email).put("password", PASSWORD);
            RequestBody body = RequestBody.create(login.toString(), MediaType.get("application/json"));
            try (Response res = client.newCall(new Request.Builder().url(API + "/token").post(body).build()).execute()) {
                String t = new JSONObject(res.body().string()).getString("token");
                tokenMap.put(email, t);
                return t;
            }
        } catch (Exception e) { return null; }
    }

    // ================== UTILS ==================
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

    static String listMails() {
        if (activeMails.isEmpty()) return "📭 Liste boş.";
        StringBuilder sb = new StringBuilder("📋 *AKTİF LİSTE*\n");
        for (String m : activeMails.values()) {
            sb.append("`").append(m).append("`\n");
        }
        return sb.toString();
    }
}