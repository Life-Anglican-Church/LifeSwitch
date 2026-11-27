package org.lifeproduction.lifeswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SCAN_PAIRING = 31;
    private static final int INSTALL_PERMISSION = 32;
    private static final java.util.regex.Pattern PAIRING_QR = java.util.regex.Pattern.compile(
        "lifeswitch://pair\\?host=([0-9.]{7,15})&code=([a-f0-9]{32})");
    private static final int BACKGROUND = Color.rgb(15, 12, 14);
    private static final int PANEL = Color.rgb(43, 24, 31);
    private static final int ACCENT = Color.rgb(255, 103, 122);
    private static final int MUTED = Color.rgb(206, 176, 184);
    private static final int WHITE = Color.rgb(250, 241, 244);
    private static final int GREEN = Color.rgb(130, 232, 188);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService polling = Executors.newSingleThreadExecutor();
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private final ArrayList<Button> sources = new ArrayList<>();
    private final String[] confirmedSourceNames = new String[8];
    private final String[] candidateSourceNames = new String[8];
    private LinearLayout root;
    private TextView status, previewName, programName, feedback;
    private Button arm, cut, auto, dsk;
    private String host = "", token = "";
    private String session = UUID.randomUUID().toString();
    private JSONObject state;
    private boolean active, reading, sending, armed, serviceBusy, simulator, uncertain;
    private long commandEpoch;
    private boolean awaitingReadback;
    private boolean updateCheckStarted;
    private AndroidUpdates pendingUpdate, installPermissionUpdate;
    private File verifiedUpdate;
    private boolean downloadingUpdate;
    private final ExecutorService updater = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            installPermissionUpdate = (AndroidUpdates) saved.getSerializable("installPermissionUpdate");
        }
        host = getPreferences(0).getString("host", "");
        token = getPreferences(0).getString("token", "");
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        root = column();
        root.setPadding(dp(12), dp(5), dp(12), dp(8));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView wordmark = new ImageView(this);
        wordmark.setImageResource(R.drawable.lifeswitch_wordmark);
        wordmark.setScaleType(ImageView.ScaleType.FIT_START);
        wordmark.setContentDescription("LifeSWITCH");
        LinearLayout.LayoutParams wordmarkSize = new LinearLayout.LayoutParams(dp(126), dp(44));
        wordmarkSize.rightMargin = dp(12);
        header.addView(wordmark, wordmarkSize);
        Button settings = button("Connection");
        settings.setOnClickListener(v -> configure());
        status = label("Connect to the booth service", 13, MUTED);
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(settings);
        add(root, header, 5);

        arm = button("Enable Live control");
        arm.setOnClickListener(v -> {
            if (armed) { dropLease(); update(); offerUpdate(); }
            else new AlertDialog.Builder(this).setTitle("Enable Live control?")
                .setMessage("Use the booth multiview to check Preview and Program. This device will be able to change the ATEM.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Enable", (dialog, i) -> command("arm", new JSONObject())).show();
        });
        LinearLayout buses = new LinearLayout(this);
        LinearLayout preview = bus("Preview", GREEN);
        LinearLayout program = bus("Program", ACCENT);
        previewName = label("—", 16, WHITE);
        programName = label("—", 16, WHITE);
        add(preview, previewName, 3);
        add(program, programName, 3);

        LinearLayout transitions = new LinearLayout(this);
        cut = button("Cut");
        auto = button("Auto");
        cut.setOnClickListener(v -> transition("cut"));
        auto.setOnClickListener(v -> transition("auto"));
        addWeighted(transitions, cut);
        addWeighted(transitions, auto);
        add(preview, transitions, 0);
        buses.addView(preview, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams programSize = new LinearLayout.LayoutParams(0, -1, 1);
        programSize.leftMargin = dp(6);
        buses.addView(program, programSize);
        add(root, buses, 5);

        add(root, label("Select Preview", 13, MUTED), 2);
        for (int rowNumber = 0; rowNumber < 2; rowNumber++) {
            LinearLayout row = new LinearLayout(this);
            for (int column = 0; column < 4; column++) {
                int id = rowNumber * 4 + column + 1;
                Button tile = button("Input " + id);
                tile.setSingleLine(true);
                tile.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tile.setOnClickListener(v -> command("preview", fields("source", id)));
                addWeighted(row, tile);
                sources.add(tile);
            }
            add(root, row, 4);
        }

        LinearLayout footer = new LinearLayout(this);
        addWeighted(footer, arm);
        dsk = button("DSK 1 · Lower thirds");
        dsk.setOnClickListener(v -> command("dsk", fields("onAir", !state.optBoolean("dsk"))));
        addWeighted(footer, dsk);
        add(root, footer, 3);

        feedback = label("", 13, ACCENT);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(root, feedback, 0);
        update();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        state = null;
        update();
        poll.run();
        if (!updateCheckStarted) {
            updateCheckStarted = true;
            updater.execute(() -> {
                try {
                    PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                    long version = AndroidUpdates.versionCode(info);
                    AndroidUpdates.cleanInstalledUpdates(this, version);
                    AndroidUpdates available = AndroidUpdates.check(version);
                    ui.post(() -> {
                        pendingUpdate = available;
                        offerUpdate();
                    });
                } catch (Exception ignored) {
                    // GitHub availability never gates booth control.
                }
            });
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) offerUpdate();
    }

    private void offerUpdate() {
        if (!active || armed || sending || !hasWindowFocus() || isFinishing() || isDestroyed()) return;
        if (verifiedUpdate != null) {
            openInstaller();
            return;
        }
        if (pendingUpdate == null || downloadingUpdate || installPermissionUpdate != null) return;
        AndroidUpdates available = pendingUpdate;
        pendingUpdate = null;
        new AlertDialog.Builder(this)
            .setTitle("LifeSWITCH update available")
            .setMessage("Version " + available.version + " is available.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Update now", (dialog, choice) -> requestUpdate(available)).show();
    }

    private void requestUpdate(AndroidUpdates available) {
        if (getPackageManager().canRequestPackageInstalls()) {
            downloadUpdate(available);
            return;
        }
        installPermissionUpdate = available;
        try {
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(permission, INSTALL_PERMISSION);
        } catch (RuntimeException error) {
            installPermissionUpdate = null;
            Toast.makeText(this, "Android could not open install permission settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void downloadUpdate(AndroidUpdates available) {
        pendingUpdate = null;
        downloadingUpdate = true;
        Toast.makeText(this, "Downloading LifeSWITCH update…", Toast.LENGTH_SHORT).show();
        updater.execute(() -> {
            try {
                File file = available.downloadAndVerify(getApplicationContext());
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    downloadingUpdate = false;
                    verifiedUpdate = file;
                    offerUpdate();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    downloadingUpdate = false;
                    if (active && !isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Update could not be downloaded or verified. Try again on the next launch.",
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void openInstaller() {
        File file = verifiedUpdate;
        verifiedUpdate = null;
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Install permission changed. Reopen LifeSWITCH to retry the update.",
                Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = UpdateApkProvider.uri(this, file);
        Intent install = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, AndroidUpdates.APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.setClipData(ClipData.newRawUri("LifeSWITCH update", uri));
        try {
            startActivity(install);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Android could not open the update installer.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putSerializable("installPermissionUpdate", installPermissionUpdate);
    }

    @Override protected void onPause() {
        active = false;
        dropLease();
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        updater.shutdownNow();
        polling.shutdownNow();
        actions.shutdown();
        super.onDestroy();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }
    private LinearLayout bus(String title, int color) {
        LinearLayout panel = column();
        panel.setPadding(dp(8), dp(4), dp(8), dp(4));
        panel.setBackground(surface(PANEL, color));
        add(panel, label(title, 12, color), 0);
        return panel;
    }
    private void add(LinearLayout panel, View view, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(bottom);
        panel.addView(view, params);
    }
    private void addWeighted(LinearLayout row, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.rightMargin = dp(5);
        row.addView(view, params);
    }
    private GradientDrawable surface(int color, int border) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), border);
        return bg;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private Button button(String text) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextSize(15);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(9), dp(6), dp(9), dp(6));
        view.setTextColor(new ColorStateList(new int[][] {
            new int[] {-android.R.attr.state_enabled}, new int[] {}
        }, new int[] {MUTED, WHITE}));
        view.setBackground(surface(PANEL, Color.rgb(106, 54, 66)));
        return view;
    }

    static boolean isPairingQr(String value) {
        java.util.regex.Matcher code = PAIRING_QR.matcher(value);
        return code.matches() && validIp(code.group(1));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL_PERMISSION) {
            AndroidUpdates available = installPermissionUpdate;
            installPermissionUpdate = null;
            if (available != null && getPackageManager().canRequestPackageInstalls()) {
                downloadUpdate(available);
            } else {
                pendingUpdate = null;
                Toast.makeText(this, "Install permission wasn’t granted. You can update on a later launch.",
                    Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (request != SCAN_PAIRING || result != RESULT_OK || data == null) return;
        String pairing = data.getStringExtra("pairing");
        if (pairing == null || !isPairingQr(pairing)) return;
        java.util.regex.Matcher code = PAIRING_QR.matcher(pairing);
        if (code.matches()) configure(code.group(1), code.group(2));
    }

    private void configure() { configure(host, token); }

    private void configure(String suggestedHost, String suggestedToken) {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText address = new EditText(this);
        address.setHint("Booth PC IPv4 address");
        address.setSingleLine();
        address.setText(suggestedHost);
        add(form, address, 6);
        EditText code = new EditText(this);
        code.setHint("Pairing code");
        code.setSingleLine();
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code.setText(suggestedToken);
        add(form, code, 6);
        Button scan = button("Scan booth QR");
        add(form, scan, 6);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Booth connection")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Connect", null).create();
        scan.setOnClickListener(view -> {
            dropLease();
            update();
            dialog.dismiss();
            startActivityForResult(new Intent(this, PairingScanActivity.class), SCAN_PAIRING);
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = address.getText().toString().trim();
            String key = code.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!validIp(ip)) { address.setError("Enter the booth PC’s IPv4 address"); return; }
            if (!key.matches("[0-9a-f]{32}")) { code.setError("Enter the 32-character pairing code"); return; }
            dropLease();
            if (!host.equals(ip) || !token.equals(key)) {
                java.util.Arrays.fill(confirmedSourceNames, null);
                java.util.Arrays.fill(candidateSourceNames, null);
            }
            host = ip;
            token = key;
            state = null;
            uncertain = false;
            feedback.setText("");
            getPreferences(0).edit().putString("host", host).putString("token", token).apply();
            dialog.dismiss();
            update();
        });
    }
    private static boolean validIp(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            ui.postDelayed(this, 850);
            if (host.isEmpty() || reading) return;
            reading = true;
            String address = host, key = token, id = session;
            long epoch = commandEpoch;
            polling.execute(() -> {
                JSONObject reply = null;
                String failure = null;
                try { reply = request(address, key, id, "state", null); }
                catch (Exception error) { failure = error instanceof Refused ? error.getMessage() : "Booth service unreachable"; }
                JSONObject answer = reply;
                String message = failure;
                ui.post(() -> {
                    reading = false;
                    if (!active || !session.equals(id) || epoch != commandEpoch) return;
                    awaitingReadback = false;
                    if (answer == null) {
                        state = null;
                        if (armed || sending) {
                            if (sending && armed) {
                                uncertain = true;
                                feedback.setText("Outcome unknown. Check the physical switcher.");
                            }
                            dropLease();
                        }
                        if (!uncertain) feedback.setText(message);
                    } else {
                        state = answer.optJSONObject("state");
                        if (!armed && !sending && !uncertain) feedback.setText("");
                        simulator = answer.optBoolean("simulator");
                        serviceBusy = answer.optBoolean("busy");
                        if (armed && !sending && !answer.optBoolean("armed")) {
                            dropLease();
                            if (!uncertain) feedback.setText("Live control ended. Re-enable when ready.");
                        }
                    }
                    refreshSourceNames();
                    update();
                });
            });
        }
    };

    private void dropLease() {
        String address = host, key = token, id = session;
        boolean release = armed || sending;
        armed = false;
        sending = false;
        session = UUID.randomUUID().toString();
        if (release && !address.isEmpty()) {
            actions.execute(() -> {
                try { request(address, key, id, "disarm", new JSONObject()); }
                catch (Exception ignored) { /* The server lease expires on its own. */ }
            });
        }
    }

    private static JSONObject fields(String k, Object v) {
        JSONObject json = new JSONObject();
        try { json.put(k, v); } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
        return json;
    }

    private void transition(String name) {
        JSONObject expected = fields("preview", state.optInt("preview"));
        try { expected.put("program", state.optInt("program")); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        command(name, expected);
    }

    private void command(String name, JSONObject payload) {
        if (!active || state == null || sending || awaitingReadback || serviceBusy || (!name.equals("arm") && !armed)) return;
        sending = true;
        commandEpoch++;
        feedback.setText(name.equals("arm") ? "Arming Live control…" : "Waiting for ATEM confirmation…");
        update();
        String address = host, key = token, id = session;
        actions.execute(() -> {
            String errorMessage = null;
            boolean unknown = false;
            try { request(address, key, id, name, payload); }
            catch (Exception error) {
                unknown = !name.equals("arm") && !(error instanceof Refused && ((Refused)error).status < 500);
                errorMessage = unknown ? "Outcome unknown. Check the physical switcher."
                    : error instanceof Refused ? error.getMessage() : "Could not enable Live control.";
            }
            String result = errorMessage;
            boolean isUnknown = unknown;
            ui.post(() -> {
                if (!active || !session.equals(id)) return;
                commandEpoch++;
                if (result != null) {
                    uncertain = isUnknown;
                    dropLease();
                    feedback.setText(result);
                } else {
                    if (name.equals("arm")) armed = true;
                    sending = false;
                    uncertain = false;
                    awaitingReadback = true; // Keep the rendered snapshot while waiting for fresh readback.
                    feedback.setText(name.equals("arm") ? "" : "ATEM state confirmed");
                }
                update();
            });
        });
    }

    private void update() {
        setTextIfChanged(status, state == null ? (host.isEmpty() ? "Set up a booth connection" : "Switcher status unavailable")
            : simulator ? "Simulator  •  No physical ATEM" : "ATEM connected  •  Watch the booth multiview");
        setTextIfChanged(previewName, state == null ? "—" : sourceName(state.optInt("preview")));
        setTextIfChanged(programName, state == null ? "—" : sourceName(state.optInt("program")));
        feedback.setVisibility(feedback.length() == 0 ? View.GONE : View.VISIBLE);
        setTextIfChanged(arm, armed ? "Live control on · Turn off" : "Monitor only · Enable Live control");
        arm.setEnabled(armed || state != null && !sending && !awaitingReadback && !serviceBusy);
        boolean enabled = armed && state != null && !sending && !awaitingReadback && !serviceBusy;
        cut.setEnabled(enabled && !state.optBoolean("transitioning"));
        auto.setEnabled(enabled && !state.optBoolean("transitioning"));
        dsk.setEnabled(enabled);
        setTextIfChanged(dsk, "DSK 1 · Lower thirds" + (state == null ? ""
            : state.optBoolean("dsk") ? " · On air" : " · Off air"));
        int preview = state == null ? -1 : state.optInt("preview");
        int program = state == null ? -1 : state.optInt("program");
        for (int i = 0; i < sources.size(); i++) {
            int id = i + 1;
            Button tile = sources.get(i);
            JSONObject input = findInput(id);
            String name = confirmedSourceNames[i];
            setTextIfChanged(tile, name == null ? "Input " + id : id + " · " + name);
            int border = id == preview ? GREEN : id == program ? ACCENT : Color.rgb(106, 54, 66);
            if (!Integer.valueOf(border).equals(tile.getTag())) {
                tile.setBackground(surface(PANEL, border));
                tile.setTag(border);
            }
            tile.setSelected(id == preview);
            tile.setEnabled(enabled && input != null);
        }
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private void refreshSourceNames() {
        for (int i = 0; i < confirmedSourceNames.length; i++) {
            JSONObject input = findInput(i + 1);
            String name = input == null || input.isNull("name") ? "" : input.optString("name").trim();
            if (name.isEmpty() || name.equals(confirmedSourceNames[i])) {
                candidateSourceNames[i] = null;
            } else if (confirmedSourceNames[i] == null || name.equals(candidateSourceNames[i])) {
                confirmedSourceNames[i] = name;
                candidateSourceNames[i] = null;
            } else {
                // Require two consecutive polls before replacing a name already on screen.
                candidateSourceNames[i] = name;
            }
        }
    }

    private JSONObject findInput(int id) {
        JSONArray inputs = state == null ? null : state.optJSONArray("inputs");
        for (int i = 0; inputs != null && i < inputs.length(); i++) {
            JSONObject item = inputs.optJSONObject(i);
            if (item != null && item.optInt("id") == id) return item;
        }
        return null;
    }

    private String sourceName(int id) {
        JSONObject input = findInput(id);
        return input == null ? "Source " + id : input.optString("name", "Input " + id);
    }

    private static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        Refused(int status, String message) { super(message); this.status = status; }
    }

    private static JSONObject request(String host, String token, String id, String endpoint, JSONObject payload) throws Exception {
        URL url = new URL("http://" + host + ":8765/" + endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(1600);
        connection.setReadTimeout(payload == null ? 1800 : 10000);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("X-LifeSWITCH-Session", id);
        try {
            if (payload != null) {
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status == 200 ? connection.getInputStream() : connection.getErrorStream()) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                if (input != null) {
                    byte[] buffer = new byte[2048];
                    for (int n; (n = input.read(buffer)) != -1;) {
                        if (content.size() + n > 65536) throw new IOException("Unexpected booth response");
                        content.write(buffer, 0, n);
                    }
                }
                JSONObject json = new JSONObject(new String(content.toByteArray(), StandardCharsets.UTF_8));
                if (status != 200) throw new Refused(status, json.optString("error", "Booth request rejected"));
                return json;
            }
        } finally { connection.disconnect(); }
    }
}
