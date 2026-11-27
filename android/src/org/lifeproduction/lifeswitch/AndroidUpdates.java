package org.lifeproduction.lifeswitch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class AndroidUpdates {
    private static final String DOWNLOADS =
        "https://raw.githubusercontent.com/Life-Anglican-Church/LifeSwitch/beta-downloads/releases/";

    final String version;
    final String downloadUrl;

    private AndroidUpdates(String version, String downloadUrl) {
        this.version = version;
        this.downloadUrl = downloadUrl;
    }

    static AndroidUpdates check(long installedVersion) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(DOWNLOADS + "android-update.json").openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(2500);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (connection.getResponseCode() != 200) return null;
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    if (bytes.size() + count > 4096) throw new IOException("Update information is too large");
                    bytes.write(buffer, 0, count);
                }
                return parse(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)), installedVersion);
            }
        } finally {
            connection.disconnect();
        }
    }

    static AndroidUpdates parse(JSONObject manifest, long installedVersion) {
        long published = manifest.optLong("versionCode", -1);
        String version = manifest.optString("versionName", "");
        if (published <= installedVersion || !version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}(-[a-zA-Z0-9.]{1,24})?")) {
            return null;
        }
        return new AndroidUpdates(version, DOWNLOADS + "LifeSWITCH-Android-" + version + ".apk");
    }
}
