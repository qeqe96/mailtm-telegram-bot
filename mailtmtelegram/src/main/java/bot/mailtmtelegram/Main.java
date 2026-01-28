package bot.mailtmtelegram;
import okhttp3.*;
import org.json.*;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    static final Map<Integer, String> activeMails = new ConcurrentSkipListMap<>();
    static final Map<String, String> tokenMap = new ConcurrentHashMap<>();
    static final Set<String> seenIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    static int batchStart = 1;
    static int currentWebIndex = 0;
    static volatile boolean creating = false;
    static String domain = "";
    static long lastUpdateId = 0;

    static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) throws Exception {
        domain = fetchDomain();
        
        // Web Sunucusunu Başlat
        startWebServer();
        
        sendTG("🚀 Bot ve Web Panel Hazır!\nDomain: " + domain);

        while (true) {
            try {
                pollTelegram();
                if (!activeMails.isEmpty() && !creating) {
                    checkEmails(); 
                }
            } catch (Exception ignored) {}
            Thread.sleep(800); 
        }
    }

    // ================== WEB PANEL (GÖMÜLÜ) ==================
    static void startWebServer() throws Exception {
        // Railway/Render PORT değişkenini otomatik okur
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/", (exchange) -> {
            List<String> mails = new ArrayList<>(activeMails.values());
            String response;
            
            if (mails.isEmpty() || currentWebIndex >= mails.size()) {
                response = "<html><head><meta charset='UTF-8'></head><body style='background:#121212;color:white;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;'>" +
                           "<h2>Sırada mail yok!</h2><p>Telegram'dan /new yazın.</p></body></html>";
            } else {
                String currentMail = mails.get(currentWebIndex);
                response = "<html><head><meta charset='UTF-8'><title>Mail Paneli</title></head>" +
                           "<body style='background:#121212;color:white;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;'>" +
                           "<div style='background:#1e1e1e;padding:30px;border-radius:15px;text-align:center;box-shadow:0 10px 30px rgba(0,0,0,0.5);'>" +
                           "<input type='text' id='m' value='" + currentMail + "' readonly style='background:#2c2c2c;color:#00ff88;border:2px solid #444;padding:15px;width:320px;font-size:20px;border-radius:8px;text-align:center;margin-bottom:20px;outline:none;'>" +
                           "<br><button onclick='c()' style='background:#00ff88;color:#121212;border:none;padding:15px 40px;font-size:18px;font-weight:bold;border-radius:8px;cursor:pointer;'>KOPYALA & SONRAKİ</button>" +
                           "</div><script>" +
                           "function c(){var x=document.getElementById('m');x.select();document.execCommand('copy');window.location.href='/next';}" +
                           "</script></body></html>";
            }
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });

        server.createContext("/next", (exchange) -> {
            currentWebIndex++;
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        server.start();
    }

    // ================== TELEGRAM & MAIL LOGIC ==================
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
                        new Thread(() -> createBatch()).start();
                    } else if (text.equals("/list")) {
                        sendTG(listMails());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    static void createBatch() {
        if (creating) return;
        creating = true;
        currentWebIndex = 0; // Web sırasını sıfırla
        activeMails.clear();
        tokenMap.clear();
        seenIds.clear();

        sendTG("⏳ Mailler sıralı hazırlanıyor...");

        int count = 0;
        int currentNum = batchStart;
        while (count < BATCH_SIZE) {
            if (createAccount(currentNum)) {
                activeMails.put(currentNum, PREFIX + currentNum + "@" + domain);
                count++; currentNum++;
                try { Thread.sleep(400); } catch (Exception ignored) {}
            } else {
                try { Thread.sleep(1000); } catch (Exception ignored) {}
            }
        }
        batchStart = currentNum;
        sendTG("✅ Hazır!\nWeb Panelden kopyalamaya başlayabilirsin.");
        creating = false;
    }

    static boolean createAccount(int n) {
        try {
            String mail = PREFIX + n + "@" + domain;
            RequestBody body = RequestBody.create(new JSONObject().put("address", mail).put("password", PASSWORD).toString(), MediaType.parse("application/json"));
            try (Response res = client.newCall(new Request.Builder().url(API + "/accounts").post(body).build()).execute()) {
                return res.isSuccessful() || res.code() == 422;
            }
        } catch (Exception e) { return false; }
    }

    static void checkEmails() {
        for (String email : activeMails.values()) {
            try {
                String token = getToken(email);
                if (token == null) continue;
                Request req = new Request.Builder().url(API + "/messages").addHeader("Authorization", "Bearer " + token).build();
                try (Response res = client.newCall(req).execute()) {
                    JSONArray msgs = new JSONObject(res.body().string()).getJSONArray("hydra:member");
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.getJSONObject(i);
                        if (seenIds.add(m.getString("id"))) {
                            String intro = m.optString("intro", "");
                            String[] lines = intro.split("\n");
                            String code = (lines.length >= 2) ? lines[1].trim() : lines[0].trim();
                            sendTG("📩 `" + code + "`\n📧 " + email);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
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

    static String listMails() {
        StringBuilder sb = new StringBuilder("📋 *LİSTE*\n");
        activeMails.values().forEach(m -> sb.append("`").append(m).append("`\n"));
        return sb.toString();
    }
}