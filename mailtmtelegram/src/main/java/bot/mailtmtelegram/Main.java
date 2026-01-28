package bot.mailtmtelegram;
import okhttp3.*;
import org.json.*;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    // ================== AYARLAR ==================
    // Railway panelinden Variables kısmına eklemeyi unutma!
    static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    static final long CHAT_ID = Long.parseLong(System.getenv("CHAT_ID") != null ? System.getenv("CHAT_ID") : "0");
    
    static final String API = "https://api.mail.tm";
    static final String PASSWORD = "123456";
    static final int BATCH_SIZE = 10;
    static final String PREFIX = "xqhlvrna";
    static final String TARGET_URL = "https://dynamic-starburst-9a0ca2.netlify.app/";

    // ================== DURUM YÖNETİMİ ==================
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
            .connectionPool(new ConnectionPool(15, 5, TimeUnit.MINUTES))
            .build();

    public static void main(String[] args) throws Exception {
        if (BOT_TOKEN == null) {
            System.out.println("❌ HATA: BOT_TOKEN bulunamadı!");
            return;
        }

        domain = fetchDomain();
        startWebServer();
        
        sendTG("🚀 *Bot ve Web Panel Yayında!*\n🌐 Domain: " + domain + "\nSıralı ve kilitli mod aktif.");

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

    // ================== KİLİTLİ WEB PANEL ==================
    static void startWebServer() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/", (exchange) -> {
            List<String> mails = new ArrayList<>(activeMails.values());
            String response;
            
            String htmlHead = "<html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'><title>Mail Paneli</title>" +
                    "<style>" +
                    "body { background: #121212; color: white; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; font-family: sans-serif; margin: 0; }" +
                    ".card { background: #1e1e1e; padding: 30px; border-radius: 15px; text-align: center; box-shadow: 0 10px 30px rgba(0,0,0,0.5); width: 350px; border: 1px solid #333; }" +
                    ".btn-site { background: #007bff; color: white; border: none; padding: 14px; width: 100%; font-size: 16px; font-weight: bold; border-radius: 8px; cursor: pointer; margin-bottom: 25px; transition: 0.2s; }" +
                    ".btn-site:hover { background: #0056b3; }" +
                    "input { background: #2c2c2c; color: #00ff88; border: 2px solid #444; padding: 15px; width: 100%; font-size: 18px; border-radius: 8px; text-align: center; margin-bottom: 15px; outline: none; box-sizing: border-box; }" +
                    ".btn-mail { background: #00ff88; color: #121212; border: none; padding: 15px; width: 100%; font-size: 18px; font-weight: bold; border-radius: 8px; cursor: pointer; transition: 0.2s; }" +
                    ".btn-mail:disabled { background: #444; color: #888; cursor: not-allowed; opacity: 0.4; }" +
                    "hr { border: 0; border-top: 1px solid #333; margin: 20px 0; }" +
                    ".counter { font-size: 14px; color: #888; margin-bottom: 10px; }" +
                    "</style></head><body>";

            if (mails.isEmpty() || currentWebIndex >= mails.size()) {
                response = htmlHead + "<div class='card'>" +
                           "<button class='btn-site' onclick='copySite()'>1. SİTEYİ KOPYALA</button>" +
                           "<hr><h3>Sırada mail yok!</h3><p style='color:#888'>Telegram'dan /new yazın.</p></div>";
            } else {
                String currentMail = mails.get(currentWebIndex);
                int total = mails.size();
                int current = currentWebIndex + 1;
                
                response = htmlHead + "<div class='card'>" +
                           "<div class='counter'>Sıradaki: " + current + " / " + total + "</div>" +
                           "<button class='btn-site' onclick='copySite()'>1. SİTEYİ KOPYALA</button>" +
                           "<hr>" +
                           "<input type='text' id='m' value='" + currentMail + "' readonly>" +
                           "<button class='btn-mail' id='btnMail' onclick='c()' disabled>2. KOPYALA & SONRAKİ</button>" +
                           "</div>";
            }

            response += "<script>" +
                       "function copySite() {" +
                       "  navigator.clipboard.writeText('" + TARGET_URL + "').then(() => {" +
                       "    document.getElementById('btnMail').disabled = false;" +
                       "    document.getElementById('btnMail').style.background = '#00ff88';" +
                       "  });" +
                       "}" +
                       "function c() {" +
                       "  var x = document.getElementById('m');" +
                       "  navigator.clipboard.writeText(x.value).then(() => {" +
                       "    window.location.href='/next';" +
                       "  });" +
                       "}" +
                       "</script></body></html>";

            byte[] bytes = response.getBytes("UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
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

    // ================== TELEGRAM VE MAIL MANTIĞI ==================
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
        currentWebIndex = 0;
        activeMails.clear();
        tokenMap.clear();
        seenIds.clear();

        sendTG("⏳ *10 Mail* sıralı olarak hazırlanıyor...");

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
        sendTG("✅ *Mailler Hazır!*\nWeb panelden kopyalamaya başlayabilirsin.");
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
        activeMails.values().parallelStream().forEach(email -> {
            try {
                String token = getToken(email);
                if (token == null) return;
                Request req = new Request.Builder().url(API + "/messages").addHeader("Authorization", "Bearer " + token).build();
                try (Response res = client.newCall(req).execute()) {
                    JSONArray msgs = new JSONObject(res.body().string()).getJSONArray("hydra:member");
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.getJSONObject(i);
                     // ================== SADECE KOD BİLDİRİMİ ==================
                        if (seenIds.add(m.getString("id"))) {
                            String intro = m.optString("intro", "");
                            String[] lines = intro.split("\n");
                            // 2. satırı al, yoksa 1. satırı al
                            String code = (lines.length >= 2) ? lines[1].trim() : lines[0].trim();
                            
                            // Bildirimde SADECE kod görünür (Tıklayınca kopyalanır)
                            sendTG("`" + code + "`");
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

    static String listMails() {
        if (activeMails.isEmpty()) return "📭 Liste boş.";
        StringBuilder sb = new StringBuilder("📋 *AKTİF LİSTE*\n");
        activeMails.values().forEach(m -> sb.append("`").append(m).append("`\n"));
        return sb.toString();
    }
}