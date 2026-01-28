package bot.mailtmtelegram;
import okhttp3.*;
import org.json.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class Main {

    // ================== CONFIG ==================
	static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
	static final long CHAT_ID = Long.parseLong(System.getenv("CHAT_ID"));

    static final String API = "https://api.mail.tm";
    static final String PASSWORD = "123456";
    static final int BATCH_SIZE = 10;

    // sabit + nadir + göze batmayan
    static final String PREFIX = "xqhlvrna";

    // ================== STATE ==================
    static final Map<Integer, String> activeMails = new LinkedHashMap<>();
    static int batchStart = 1;
    static boolean creating = false;
    static String domain = "";

    static long lastUpdateId = 0;

    static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    // ================== MAIN ==================
    public static void main(String[] args) throws Exception {
    	if (BOT_TOKEN == null || System.getenv("CHAT_ID") == null) {
    	    System.out.println("❌ BOT_TOKEN veya CHAT_ID tanımlı değil");
    	    return;
    	}
        domain = fetchDomain();
        sendTG("🤖 Bot hazır\nDomain: " + domain);

        while (true) {
            pollTelegram();
            Thread.sleep(1000);
        }
    }

    // ================== TELEGRAM ==================
    static void sendTG(String text) {
        try {
            RequestBody body = RequestBody.create(
                    new JSONObject()
                            .put("chat_id", CHAT_ID)
                            .put("text", text)
                            .toString(),
                    MediaType.parse("application/json")
            );

            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage")
                    .post(body)
                    .build();

            client.newCall(req).execute().close();
        } catch (Exception ignored) {}
    }

    static void pollTelegram() throws Exception {
        Request req = new Request.Builder()
                .url("https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1))
                .build();

        Response res = client.newCall(req).execute();
        JSONObject json = new JSONObject(res.body().string());
        res.close();

        for (Object o : json.getJSONArray("result")) {
            JSONObject u = (JSONObject) o;
            lastUpdateId = u.getLong("update_id");

            if (!u.has("message")) continue;
            JSONObject msg = u.getJSONObject("message");
            if (!msg.has("text")) continue;

            String text = msg.getString("text");

            if (text.equals("/new")) {
                createBatch();
            }
            if (text.equals("/list")) {
                sendTG(listMails());
            }
            if (text.equals("/status")) {
                sendTG(status());
            }
        }
    }

    // ================== DOMAIN ==================
    static String fetchDomain() throws Exception {
        Request req = new Request.Builder().url(API + "/domains").build();
        Response res = client.newCall(req).execute();
        JSONObject j = new JSONObject(res.body().string());
        res.close();
        return j.getJSONArray("hydra:member").getJSONObject(0).getString("domain");
    }

    // ================== BATCH ==================
    static synchronized void createBatch() throws Exception {
        if (creating) return;
        creating = true;

        activeMails.clear();
        int created = 0;
        int i = batchStart;

        while (created < BATCH_SIZE) {
            boolean ok = createAccount(i);
            if (ok) {
                activeMails.put(i, PREFIX + i + "@" + domain);
                created++;
            }
            i++;
            Thread.sleep(300);
        }

        int start = batchStart;
        int end = batchStart + BATCH_SIZE - 1;
        batchStart += BATCH_SIZE;

        sendTG("▶️ Dinlenen aralık: " + start + "–" + end + "\n✅ Tüm mailbox’lar hazır");
        creating = false;
    }

    // ================== ACCOUNT ==================
    static boolean createAccount(int n) {
        String mail = PREFIX + n + "@" + domain;

        for (int i = 0; i < 3; i++) {
            try {
                JSONObject acc = new JSONObject()
                        .put("address", mail)
                        .put("password", PASSWORD);

                RequestBody body = RequestBody.create(
                        acc.toString(),
                        MediaType.parse("application/json")
                );

                Request req = new Request.Builder()
                        .url(API + "/accounts")
                        .post(body)
                        .build();

                Response res = client.newCall(req).execute();
                res.close();

                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
        }

        return false;
    }

    // ================== UTILS ==================
    static String listMails() {
        if (activeMails.isEmpty()) return "📭 Aktif mail yok";

        StringBuilder sb = new StringBuilder("📋 AKTİF\n\n");
        for (String m : activeMails.values()) sb.append(m).append("\n");
        return sb.toString();
    }

    static String status() {
        return "📊 DURUM\nAktif inbox: " + activeMails.size();
    }
}
