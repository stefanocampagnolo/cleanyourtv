package it.aibox.telecamere;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Impostazioni navigabili col telecomando: ogni riga è selezionabile, OK la modifica.
 * Ogni modifica viene salvata subito; Indietro torna alle telecamere.
 */
public class SettingsActivity extends Activity {

    interface TextSetter { void set(String v); }
    interface ChoiceSetter { void set(int index); }

    Config cfg;
    LinearLayout list;
    ScrollView scroll;
    int focusRow = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cfg = Config.load(this);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101820);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(120), dp(32), dp(120), dp(48));
        scroll.addView(list);
        setContentView(scroll);
        rebuild();
    }

    void changed() {
        cfg.save(this);
        rebuild();
    }

    void rebuild() {
        list.removeAllViews();
        title("Impostazioni telecamere");
        hint("Frecce = scegli · OK = modifica · Indietro = torna alle telecamere. Le modifiche si salvano subito.");

        section("NVR");
        row("Indirizzo IP", cfg.host.isEmpty() ? "(da impostare)" : cfg.host, () ->
                editText("Indirizzo IP dell'NVR", cfg.host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                        v -> { cfg.host = v.trim(); changed(); }));
        row("Porta RTSP", String.valueOf(cfg.port), () ->
                editText("Porta RTSP (di solito 554)", String.valueOf(cfg.port), InputType.TYPE_CLASS_NUMBER,
                        v -> { try { cfg.port = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { } changed(); }));
        row("Utente", cfg.user, () ->
                editText("Utente", cfg.user, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        v -> { cfg.user = v.trim(); changed(); }));
        row("Password", cfg.pass.isEmpty() ? "(vuota)" : "••••••••", () ->
                editText("Password (visibile mentre scrivi)", cfg.pass,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        v -> { cfg.pass = v; changed(); }));

        section("Visualizzazione");
        String[] layouts = {"Griglia 2×2", "Una grande + piccole", "Una alla volta (rotazione)"};
        String[] layoutIds = {Config.LAYOUT_GRID, Config.LAYOUT_BIG, Config.LAYOUT_CYCLE};
        int li = indexOf(layoutIds, cfg.layout);
        row("Disposizione", layouts[li], () -> choose("Disposizione", layouts, li,
                i -> { cfg.layout = layoutIds[i]; changed(); }));
        row("Secondi per telecamera (rotazione)", String.valueOf(cfg.cycleSec), () ->
                editText("Secondi per ogni telecamera", String.valueOf(cfg.cycleSec), InputType.TYPE_CLASS_NUMBER,
                        v -> { try { cfg.cycleSec = Math.max(3, Integer.parseInt(v.trim())); } catch (NumberFormatException ignored) { } changed(); }));

        for (int i = 0; i < cfg.cams.size(); i++) {
            final Config.CamCfg c = cfg.cams.get(i);
            final int idx = i;
            section("Telecamera " + (i + 1) + ": " + c.name);
            row("Nome", c.name, () -> editText("Nome", c.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                    v -> { c.name = v.trim().isEmpty() ? c.name : v.trim(); changed(); }));
            row("Canale NVR", String.valueOf(c.channel), () -> editText("Canale NVR (1, 2, 3…)", String.valueOf(c.channel),
                    InputType.TYPE_CLASS_NUMBER,
                    v -> { try { c.channel = Math.max(1, Integer.parseInt(v.trim())); } catch (NumberFormatException ignored) { } changed(); }));
            String[] streams = {"Secondario (leggero)", "Principale (alta qualità)"};
            row("Flusso nella griglia", streams[c.gridMain ? 1 : 0], () -> choose("Flusso nella griglia", streams, c.gridMain ? 1 : 0,
                    k -> { c.gridMain = k == 1; changed(); }));
            String[] engines = {"VLC (compatibile)", "ExoPlayer (bassa latenza)"};
            row("Lettore", engines["exo".equals(c.engine) ? 1 : 0], () -> choose("Lettore video", engines, "exo".equals(c.engine) ? 1 : 0,
                    k -> { c.engine = k == 1 ? "exo" : "vlc"; changed(); }));
            row("RTSP personalizzato – griglia", c.customUrl.isEmpty() ? "(no, usa l'NVR)" : "impostato", () ->
                    editText("rtsp://utente:password@ip:554/percorso (vuoto = usa l'NVR)", c.customUrl,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                            v -> { c.customUrl = v.trim(); changed(); }));
            row("RTSP personalizzato – schermo intero", c.customMain.isEmpty() ? "(no)" : "impostato", () ->
                    editText("rtsp://… per lo schermo intero (vuoto = come la griglia / NVR)", c.customMain,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                            v -> { c.customMain = v.trim(); changed(); }));
            if (idx > 0) row("Sposta su", "", () -> { cfg.cams.remove(idx); cfg.cams.add(idx - 1, c); changed(); });
            row("Rimuovi questa telecamera", "", () -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Rimuovere " + c.name + "?")
                    .setPositiveButton("Rimuovi", (d, w) -> { cfg.cams.remove(idx); changed(); })
                    .setNegativeButton("Annulla", null).show());
        }
        if (cfg.cams.size() < Config.MAX_CAMS) {
            section("");
            row("+ Aggiungi telecamera", "", () -> {
                Config.CamCfg c = new Config.CamCfg();
                c.channel = cfg.cams.size() + 1;
                c.name = "Telecamera " + c.channel;
                cfg.cams.add(c);
                changed();
            });
        }
        section("");
        row("← Torna alle telecamere", "", this::finish);

        int n = list.getChildCount();
        View target = null;
        int seen = 0;
        for (int i = 0; i < n; i++) {
            View v = list.getChildAt(i);
            if (v.isFocusable()) { if (seen == focusRow) { target = v; break; } seen++; }
        }
        if (target != null) target.requestFocus();
    }

    int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    // ---- elementi grafici ----

    void title(String t) {
        TextView tv = text(t, 30, Color.WHITE);
        tv.setPadding(0, 0, 0, dp(4));
        list.addView(tv);
    }

    void hint(String t) {
        TextView tv = text(t, 15, 0xFF9AA5B1);
        tv.setPadding(0, 0, 0, dp(12));
        list.addView(tv);
    }

    void section(String t) {
        TextView tv = text(t, 18, 0xFFFFC107);
        tv.setPadding(dp(4), dp(18), 0, dp(6));
        list.addView(tv);
    }

    void row(String label, String value, Runnable onOk) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackgroundResource(R.drawable.row_bg);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 19, Color.WHITE);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(value, 19, 0xFFB0BEC5);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        final int myIndex = countFocusable();
        r.setOnClickListener(x -> { focusRow = myIndex; onOk.run(); });
        r.setOnFocusChangeListener((x, has) -> { if (has) focusRow = myIndex; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(2), 0, dp(2));
        list.addView(r, lp);
    }

    int countFocusable() {
        int c = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) c++;
        return c;
    }

    TextView text(String t, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return tv;
    }

    void editText(String title, String current, int inputType, TextSetter setter) {
        EditText et = new EditText(this);
        et.setText(current);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Salva", (x, w) -> setter.set(et.getText().toString()))
                .setNegativeButton("Annulla", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
        et.requestFocus();
    }

    void choose(String title, String[] items, int checked, ChoiceSetter setter) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, (d, which) -> { d.dismiss(); setter.set(which); })
                .setNegativeButton("Annulla", null)
                .show();
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
