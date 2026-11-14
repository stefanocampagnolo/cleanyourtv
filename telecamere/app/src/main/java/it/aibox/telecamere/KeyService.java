package it.aibox.telecamere;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Servizio di accessibilità che legge SOLO i tasti del telecomando (non il contenuto dello schermo):
 * il tasto giallo apre l'app Telecamere. Durante la visione TV / Televideo / HbbTV (modo "smart")
 * il tasto viene lasciato passare, perché lì il giallo serve a quelle funzioni.
 * Si attiva via ADB (settings secure enabled_accessibility_services) o da Impostazioni > Accessibilità.
 */
public class KeyService extends AccessibilityService {

    /** App in cui il giallo ha già una funzione propria (canali, Televideo, HbbTV). */
    static final String[] TV_PACKAGES = {
            "org.droidtv.playtv", "org.droidtv.teletext", "org.droidtv.nettvbrowser", "com.vewd.core"
    };

    String foreground = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        // finestre di sistema sovrapposte (volume, tastiera) non cambiano l'app in primo piano
        if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod")) return;
        foreground = pkg;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() != KeyEvent.KEYCODE_PROG_YELLOW) return false;
        String mode = Config.load(this).yellowMode;
        if (Config.YELLOW_OFF.equals(mode)) return false;
        if (foreground.equals(getPackageName())) return false; // dentro l'app: ci pensa MainActivity (chiude)
        if (Config.YELLOW_SMART.equals(mode)) {
            for (String p : TV_PACKAGES) if (foreground.startsWith(p)) return false;
        }
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        }
        return true; // consuma sia la pressione sia il rilascio
    }

    @Override
    public void onInterrupt() { }
}
