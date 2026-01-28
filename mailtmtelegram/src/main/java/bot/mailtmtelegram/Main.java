package bot.mailtmtelegram;
import okhttp3.*;
import org.json.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    // ================== AYARLAR ==================
    static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    static final long CHAT_ID = Long.parseLong(System.getenv("CHAT_ID") != null ? System.getenv("CHAT_ID") : "0");
    static final String API = "https://api.mail.tm";
    static final String PASSWORD = "123456";
    static final int BATCH_SIZE = 10;
    static final String TARGET_URL = "https://dynamic-starburst-9a0ca2.netlify.app/";

    static final Map<Integer, String> activeMails = new ConcurrentSkipListMap<>();
    static final Map<String, String> tokenMap = new ConcurrentHashMap<>();
    static final Set<String> seenIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    static int currentWebIndex = 0;
    static volatile boolean creating = false;
    static String domain = "";
    static long lastUpdateId = 0;
    static final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build();

    public static void main(String[] args) throws Exception {
        domain = fetchDomain();
        startWebServer();
        
        // MOTİVASYON ZAMANLAYICISI BAŞLAT
        startMotivationScheduler();

        sendTG("🚀 *Sistem Yenilendi!* \nSayaç, Kilitli Panel ve Motivasyon Botu Aktif.");

        while (true) {
            try {
                pollTelegram();
                if (!activeMails.isEmpty() && !creating) { checkEmails(); }
            } catch (Exception ignored) {}
            Thread.sleep(800); 
        }
    }

    // ================== MOTİVASYON SİSTEMİ ==================
    static void startMotivationScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Random r = new Random();

        // Günde 3 kez rastgele zamanlarda mesaj atar
        for (int i = 0; i < 3; i++) {
            int randomDelayMinutes = r.nextInt(1440); // 0-24 saat arası rastgele başla
            scheduler.scheduleAtFixedRate(() -> {
                sendTG("💰 *Hadi biraz hesap açalım, paraya ihtiyacımız var!*");
            }, randomDelayMinutes, 1440, TimeUnit.MINUTES);
        }
    }

    // ================== WEB PANEL (SAYAÇLI & KİLİTLİ) ==================
    static void startWebServer() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", (exchange) -> {
            List<String> mails = new ArrayList<>(activeMails.values());
            String response;
            
            String h = "<html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'><style>" +
                    "body{background:#121212;color:white;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;margin:0;}" +
                    ".card{background:#1e1e1e;padding:30px;border-radius:15px;text-align:center;box-shadow:0 10px 30px rgba(0,0,0,0.5);width:320px;border:1px solid #333;}" +
                    ".counter-badge{background:#333;color:#00ff88;padding:5px 15px;border-radius:20px;display:inline-block;margin-bottom:20px;font-weight:bold;font-size:18px;border:1px solid #444;}" +
                    ".btn-site{background:#007bff;color:white;border:none;padding:12px;width:100%;font-size:16px;font-weight:bold;border-radius:8px;cursor:pointer;margin-bottom:20px;}" +
                    "input{background:#2c2c2c;color:#00ff88;border:2px solid #444;padding:12px;width:100%;font-size:18px;border-radius:8px;text-align:center;margin-bottom:15px;outline:none;box-sizing:border-box;}" +
                    ".btn-mail{background:#00ff88;color:#121212;border:none;padding:14px;width:100%;font-size:18px;font-weight:bold;border-radius:8px;cursor:pointer;width:100%;}" +
                    ".btn-mail:disabled{background:#444;color:#888;cursor:not-allowed;opacity:0.5;}" +
                    "hr{border:0;border-top:1px solid #333;margin:15px 0;}" +
                    "</style></head><body>";

            if (mails.isEmpty() || currentWebIndex >= mails.size()) {
                response = h + "<div class='card'><h3>BİTTİ</h3><p style='color:#666'>Telegram'dan /new yazın.</p></div>";
            } else {
                int displayNum = currentWebIndex + 1;
                response = h + "<div class='card'>" +
                        "<div class='counter-badge'>" + displayNum + " / " + BATCH_SIZE + "</div>" +
                        "<button class='btn-site' onclick='cs()'>1. SİTEYİ KOPYALA</button><hr>" +
                        "<input type='text' id='m' value='" + mails.get(currentWebIndex) + "' readonly>" +
                        "<button class='btn-mail' id='bm' onclick='c()' disabled>2. KOPYALA & SONRAKİ</button></div>";
            }
            response += "<script>" +
                        "function cs(){navigator.clipboard.writeText('" + TARGET_URL + "').then(()=>{ " +
                        "document.getElementById('bm').disabled=false; " +
                        "document.getElementById('bm').innerText='2. ŞİMDİ MAİLİ KOPYALA';" +
                        "});}" +
                        "function c(){var x=document.getElementById('m');navigator.clipboard.writeText(x.value).then(()=>{window.location.href='/next';});}" +
                        "</script></body></html>";

            byte[] b = response.getBytes("UTF-8");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.createContext("/next", (e) -> { currentWebIndex++; e.getResponseHeaders().set("Location", "/"); e.sendResponseHeaders(302, -1); e.close(); });
        server.start();
    }

    // ================== MAIL MANTIĞI ==================
    static void createBatch() {
        if (creating) return;
        creating = true;
        currentWebIndex = 0;
        activeMails.clear();
        tokenMap.clear();
        seenIds.clear();
        sendTG("🎲 *Rastgele mailler oluşturuluyor...*");
        Random r = new Random();
        int count = 0;
        while (count < BATCH_SIZE) {
            String randomName = generateSmartUsername(r);
            if (createAccount(randomName)) {
                activeMails.put(count, randomName + "@" + domain);
                count++;
                try { Thread.sleep(300); } catch (Exception ignored) {}
            }
        }
        sendTG("✅ *10 Mail Hazır!*");
        creating = false;
    }

    static boolean createAccount(String name) {
        try {
            String mail = name + "@" + domain;
            RequestBody body = RequestBody.create(new JSONObject().put("address", mail).put("password", PASSWORD).toString(), MediaType.parse("application/json"));
            try (Response res = client.newCall(new Request.Builder().url(API + "/accounts").post(body).build()).execute()) {
                return res.isSuccessful() || res.code() == 422;
            }
        } catch (Exception e) { return false; }
    }

    static String generateSmartUsername(Random r) {
        String[] v = {"a", "e", "i", "o", "u"}, c = {"b", "c", "d", "f", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "y", "z"};
        StringBuilder sb = new StringBuilder();
        int len = r.nextInt(3) + 4;
        for (int i = 0; i < len; i++) sb.append(i % 2 == 0 ? c[r.nextInt(c.length)] : v[r.nextInt(v.length)]);
        sb.append(r.nextInt(90) + 10);
        return sb.toString();
    }

    static void checkEmails() {
        activeMails.values().forEach(email -> {
            try {
                String token = getToken(email);
                if (token == null) return;
                Request req = new Request.Builder().url(API + "/messages").addHeader("Authorization", "Bearer " + token).build();
                try (Response res = client.newCall(req).execute()) {
                    JSONArray msgs = new JSONObject(res.body().string()).getJSONArray("hydra:member");
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.getJSONObject(i);
                        if (seenIds.add(m.getString("id"))) {
                            // SADECE SAF KOD (RAKAMLAR)
                            String onlyCode = m.optString("intro", "").replaceAll("[^0-9]", "").trim();
                            if (!onlyCode.isEmpty()) sendTG("`" + onlyCode + "`");
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    static String getToken(String email) {
        return tokenMap.computeIfAbsent(email, k -> {
            try {
                RequestBody body = RequestBody.create(new JSONObject().put("address", email).put("password", PASSWORD).toString(), MediaType.parse("application/json"));
                try (Response res = client.newCall(new Request.Builder().url(API + "/token").post(body).build()).execute()) {
                    return new JSONObject(res.body().string()).getString("token");
                }
            } catch (Exception e) { return null; }
        });
    }

    static void sendTG(String text) {
        try {
            RequestBody body = RequestBody.create(new JSONObject().put("chat_id", CHAT_ID).put("text", text).put("parse_mode", "Markdown").toString(), MediaType.parse("application/json"));
            client.newCall(new Request.Builder().url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage").post(body).build()).execute().close();
        } catch (Exception ignored) {}
    }

    static String fetchDomain() throws Exception {
        try (Response res = client.newCall(new Request.Builder().url(API + "/domains").build()).execute()) {
            return new JSONObject(res.body().string()).getJSONArray("hydra:member").getJSONObject(0).getString("domain");
        }
    }

    static void pollTelegram() {
        try {
            Request req = new Request.Builder().url("https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=1").build();
            try (Response res = client.newCall(req).execute()) {
                JSONObject json = new JSONObject(res.body().string());
                JSONArray result = json.getJSONArray("result");
                for (int i = 0; i < result.length(); i++) {
                    JSONObject u = result.getJSONObject(i);
                    lastUpdateId = u.getLong("update_id");
                    if (u.has("message")) {
                        String txt = u.getJSONObject("message").optString("text", "");
                        if (txt.equals("/new")) new Thread(() -> createBatch()).start();
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}