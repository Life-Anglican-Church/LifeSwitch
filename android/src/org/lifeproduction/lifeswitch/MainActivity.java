package org.lifeproduction.lifeswitch;

import android.app.Activity;
import android.app.AlertDialog;
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
    private LinearLayout root, grid;
    private TextView status, tally, feedback, dskStatus;
    private Button arm, cut, auto, dsk;
    private String host = "", token = "", inputLayout = "";
    private String session = UUID.randomUUID().toString();
    private JSONObject state;
    private boolean active, reading, sending, armed, serviceBusy, simulator, uncertain;
    private long commandEpoch;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        host = getPreferences(0).getString("host", "");
        token = getPreferences(0).getString("token", "");
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        root = column();
        root.setPadding(dp(18), dp(16), dp(18), dp(22));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
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
        LinearLayout.LayoutParams wordmarkSize = new LinearLayout.LayoutParams(0, dp(54), 1);
        wordmarkSize.rightMargin = dp(12);
        header.addView(wordmark, wordmarkSize);
        Button settings = button("Connection");
        settings.setOnClickListener(v -> configure());
        header.addView(settings);
        add(root, header, 12);

        status = label("Connect to the booth service", 15, WHITE);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(surface(PANEL, Color.rgb(105, 51, 63)));
        add(root, status, 8);
        tally = label("Preview  •  —     Program  •  —", 17, WHITE);
        add(root, tally, 14);

        arm = button("Enable Live control");
        arm.setOnClickListener(v -> {
            if (armed) { dropLease(); update(); }
            else new AlertDialog.Builder(this).setTitle("Enable Live control?")
                .setMessage("Use the booth multiview to check Preview and Program. This device will be able to change the ATEM.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Enable", (dialog, i) -> command("arm", new JSONObject())).show();
        });
        add(root, arm, 8);
        feedback = label("", 14, ACCENT);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(root, feedback, 12);

        add(root, label("Select Preview", 17, WHITE), 5);
        grid = column();
        add(root, grid, 10);

        LinearLayout transitions = new LinearLayout(this);
        cut = button("Cut");
        auto = button("Auto");
        cut.setOnClickListener(v -> transition("cut"));
        auto.setOnClickListener(v -> transition("auto"));
        addWeighted(transitions, cut);
        addWeighted(transitions, auto);
        add(root, transitions, 16);

        dskStatus = label("DSK 1  •  Status unavailable", 14, MUTED);
        add(root, dskStatus, 4);
        dsk = button("Lower thirds");
        dsk.setOnClickListener(v -> command("dsk", fields("onAir", !state.optBoolean("dsk"))));
        add(root, dsk, 0);
        update();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        state = null;
        update();
        poll.run();
    }

    @Override protected void onPause() {
        active = false;
        dropLease();
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
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

    private void configure() {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText address = new EditText(this);
        address.setHint("Booth PC IPv4 address");
        address.setSingleLine();
        address.setText(host);
        add(form, address, 6);
        EditText code = new EditText(this);
        code.setHint("Pairing code");
        code.setSingleLine();
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code.setText(token);
        add(form, code, 6);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Booth connection")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Connect", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = address.getText().toString().trim();
            String key = code.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!validIp(ip)) { address.setError("Enter the booth PC’s IPv4 address"); return; }
            if (!key.matches("[0-9a-f]{32}")) { code.setError("Enter the 32-character pairing code"); return; }
            dropLease();
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
    private boolean validIp(String value) {
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
        if (!active || state == null || sending || serviceBusy || (!name.equals("arm") && !armed)) return;
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
                    state = null; // Require fresh switcher readback before another tap.
                    feedback.setText(name.equals("arm") ? "" : "ATEM state confirmed");
                }
                update();
            });
        });
    }

    private void update() {
        status.setText(state == null ? (host.isEmpty() ? "Set up a booth connection" : "Switcher status unavailable")
            : simulator ? "Simulator  •  No physical ATEM" : "ATEM connected  •  Watch the booth multiview");
        tally.setText(state == null ? "Preview  •  —     Program  •  —"
            : "Preview  •  " + sourceName(state.optInt("preview")) + "     Program  •  " + sourceName(state.optInt("program")));
        arm.setText(armed ? "Live control on · Turn off" : "Monitor only · Enable Live control");
        arm.setEnabled(armed || state != null && !sending && !serviceBusy);
        boolean enabled = armed && state != null && !sending && !serviceBusy;
        cut.setEnabled(enabled && !state.optBoolean("transitioning"));
        auto.setEnabled(enabled && !state.optBoolean("transitioning"));
        dsk.setEnabled(enabled);
        dskStatus.setText("DSK 1  •  " + (state == null ? "Status unavailable" : state.optBoolean("dsk") ? "On air" : "Off air"));
        dsk.setText(state == null ? "Lower thirds" : state.optBoolean("dsk") ? "Hide lower thirds" : "Show lower thirds");
        JSONArray inputs = state == null ? null : state.optJSONArray("inputs");
        if (inputs != null && !inputs.toString().equals(inputLayout)) {
            inputLayout = inputs.toString();
            sources.clear();
            grid.removeAllViews();
            int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
            LinearLayout row = null;
            for (int i = 0; i < inputs.length(); i++) {
                if (i % columns == 0) { row = new LinearLayout(this); add(grid, row, 5); }
                JSONObject input = inputs.optJSONObject(i);
                int id = input.optInt("id");
                Button tile = button(input.optString("name"));
                tile.setTag(id);
                tile.setOnClickListener(v -> command("preview", fields("source", id)));
                addWeighted(row, tile);
                sources.add(tile);
            }
        }
        for (Button tile : sources) {
            boolean selected = state != null && ((Integer) tile.getTag()) == state.optInt("preview");
            tile.setBackground(surface(PANEL, selected ? GREEN : Color.rgb(106, 54, 66)));
            tile.setSelected(selected);
            tile.setEnabled(enabled);
        }
    }

    private String sourceName(int id) {
        JSONArray inputs = state.optJSONArray("inputs");
        for (int i = 0; inputs != null && i < inputs.length(); i++) {
            JSONObject item = inputs.optJSONObject(i);
            if (item.optInt("id") == id) return item.optString("name");
        }
        return "Source " + id;
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
