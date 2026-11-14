package it.aibox.telecamere;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Configurazione dell'app, salvata nella memoria PRIVATA dell'app (SharedPreferences).
 * Import facoltativo da Raspberry: se esiste files/config.json (caricato via ADB) viene letto e poi cancellato,
 * così password e indirizzi non restano nella memoria condivisa.
 */
final class Config {

    static final String LAYOUT_GRID = "grid", LAYOUT_BIG = "big", LAYOUT_CYCLE = "cycle";
    static final int MAX_CAMS = 4;
    static final String YELLOW_SMART = "smart", YELLOW_ALWAYS = "always", YELLOW_OFF = "off";

    static final class CamCfg {
        String name = "Telecamera";
        int channel = 1;              // canale NVR (1, 2, 3…) → /Streaming/Channels/<ch>01 e <ch>02
        boolean gridMain = false;     // true = anche in griglia usa il flusso principale
        String engine = "vlc";        // "vlc" (libVLC) oppure "exo" (Media3 ExoPlayer)
        String customUrl = "";        // se compilato sostituisce l'indirizzo NVR nella griglia (e a schermo intero se customMain è vuoto)
        String customMain = "";       // se compilato è l'indirizzo usato a schermo intero
    }

    String host = "", user = "admin", pass = "";
    int port = 554;
    String layout = LAYOUT_GRID;
    int cycleSec = 15;
    String yellowMode = YELLOW_SMART;   // tasto giallo: apre le telecamere
    final List<CamCfg> cams = new ArrayList<>();

    boolean isUsable() {
        for (CamCfg c : cams) if (!c.customUrl.isEmpty() || !c.customMain.isEmpty() || !host.isEmpty()) return true;
        return false;
    }

    String url(CamCfg c, boolean main) {
        if (main && !c.customMain.isEmpty()) return c.customMain;
        if (!c.customUrl.isEmpty()) return c.customUrl;
        String auth = user.isEmpty() ? "" : Uri.encode(user) + ":" + Uri.encode(pass) + "@";
        return "rtsp://" + auth + host + ":" + port + "/Streaming/Channels/" + c.channel + (main ? "01" : "02");
    }

    // ---- persistenza ----

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    /** Ritorna un messaggio se è stato importato un file dal Raspberry, altrimenti null. */
    static String importFromFile(Context ctx) {
        File f = new File(ctx.getExternalFilesDir(null), "config.json");
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; }
            Config c = fromJson(new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)));
            c.save(ctx);
            return "Configurazione importata (" + c.cams.size() + " telecamere)";
        } catch (Exception e) {
            return "Import non riuscito: " + e.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete(); // la password non deve restare nella memoria condivisa
            //noinspection ResultOfMethodCallIgnored
            new File(ctx.getExternalFilesDir(null), "cameras.json").delete(); // vecchio formato
        }
    }

    static Config load(Context ctx) {
        String s = prefs(ctx).getString("json", null);
        if (s != null) {
            try { return fromJson(new JSONObject(s)); } catch (Exception ignored) { }
        }
        Config c = new Config(); // primo avvio: 3 telecamere sui canali 1-3, NVR da impostare
        for (int i = 1; i <= 3; i++) {
            CamCfg cc = new CamCfg();
            cc.name = "Telecamera " + i;
            cc.channel = i;
            c.cams.add(cc);
        }
        return c;
    }

    void save(Context ctx) {
        try { prefs(ctx).edit().putString("json", toJson().toString()).apply(); } catch (Exception ignored) { }
    }

    static Config fromJson(JSONObject o) throws Exception {
        Config c = new Config();
        JSONObject nvr = o.optJSONObject("nvr");
        if (nvr != null) {
            c.host = nvr.optString("host", "");
            c.port = nvr.optInt("port", 554);
            c.user = nvr.optString("user", "admin");
            c.pass = nvr.optString("pass", "");
        }
        c.layout = o.optString("layout", LAYOUT_GRID);
        c.cycleSec = Math.max(3, o.optInt("cycleSec", 15));
        c.yellowMode = o.optString("yellowMode", YELLOW_SMART);
        JSONArray arr = o.optJSONArray("cameras");
        if (arr != null) for (int i = 0; i < arr.length() && i < MAX_CAMS; i++) {
            JSONObject j = arr.getJSONObject(i);
            CamCfg cc = new CamCfg();
            cc.name = j.optString("name", "Telecamera " + (i + 1));
            cc.channel = j.optInt("channel", i + 1);
            cc.gridMain = j.optBoolean("gridMain", false);
            cc.engine = j.optString("engine", "vlc");
            cc.customUrl = j.optString("customUrl", "");
            cc.customMain = j.optString("customMain", "");
            c.cams.add(cc);
        }
        return c;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        JSONObject nvr = new JSONObject();
        nvr.put("host", host).put("port", port).put("user", user).put("pass", pass);
        o.put("nvr", nvr).put("layout", layout).put("cycleSec", cycleSec).put("yellowMode", yellowMode);
        JSONArray arr = new JSONArray();
        for (CamCfg c : cams) {
            arr.put(new JSONObject().put("name", c.name).put("channel", c.channel).put("gridMain", c.gridMain)
                    .put("engine", c.engine).put("customUrl", c.customUrl).put("customMain", c.customMain));
        }
        o.put("cameras", arr);
        return o;
    }
}
