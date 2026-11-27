package org.lifeproduction.lifeswitch;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class AndroidUpdates implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private static final String DOWNLOADS =
        "https://raw.githubusercontent.com/Life-Anglican-Church/LifeSwitch/beta-downloads/releases/";
    static final String APK_MIME = "application/vnd.android.package-archive";
    static final String APK_NAME = "update-[0-9]{1,10}\\.apk";
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    final String version;
    final long versionCode;
    private final String sha256;

    private AndroidUpdates(String version, long versionCode, String sha256) {
        this.version = version;
        this.versionCode = versionCode;
        this.sha256 = sha256;
    }

    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static File stagingDirectory(Context context) {
        return new File(context.getCacheDir(), "verified-updates");
    }

    static void cleanInstalledUpdates(Context context, long installed) {
        File[] files = stagingDirectory(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.matches(APK_NAME) && Long.parseLong(name.substring(7, name.length() - 4)) <= installed) {
                file.delete();
            }
        }
    }

    private static HttpsURLConnection connect(String filename, int timeout) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(DOWNLOADS + filename).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        return connection;
    }

    static AndroidUpdates check(long installed) throws Exception {
        HttpsURLConnection connection = connect("android-update.json", 3000);
        try {
            if (connection.getResponseCode() != 200) return null;
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    if (bytes.size() + count > 4096) throw new IOException("Update information is too large");
                    bytes.write(buffer, 0, count);
                }
                return parse(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)), installed);
            }
        } finally {
            connection.disconnect();
        }
    }

    static AndroidUpdates parse(JSONObject manifest, long installed) {
        long published = manifest.optLong("versionCode", -1);
        String version = manifest.optString("versionName", "");
        String checksum = manifest.optString("sha256", "");
        if (published <= installed || published > Integer.MAX_VALUE ||
                !version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}(-[a-zA-Z0-9.]{1,24})?") ||
                !checksum.matches("[a-f0-9]{64}")) return null;
        return new AndroidUpdates(version, published, checksum);
    }

    File downloadAndVerify(Context context) throws Exception {
        File directory = stagingDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Update storage is unavailable");
        File partial = File.createTempFile("download-", ".apk", directory);
        try {
            download(partial);
            verifyPackage(context, partial);
            checkCancelled();
            File verified = new File(directory, "update-" + versionCode + ".apk");
            Files.move(partial.toPath(), verified.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return verified;
        } finally {
            partial.delete();
        }
    }

    private void download(File partial) throws Exception {
        HttpsURLConnection connection = connect("LifeSWITCH-Android-" + version + ".apk", 12000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("APK download is unavailable");
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("APK exceeds the update size limit");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[16384];
                long total = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    checkCancelled();
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("APK exceeds the update size limit");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) Integer.parseInt(sha256.substring(i * 2, i * 2 + 2), 16);
            }
            if (!MessageDigest.isEqual(expected, digest.digest())) throw new IOException("APK checksum does not match");
        } finally {
            connection.disconnect();
        }
    }

    private void verifyPackage(Context context, File partial) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageManager manager = context.getPackageManager();
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo archive = manager.getPackageArchiveInfo(partial.getAbsolutePath(), flags);
        if (archive == null || !installed.packageName.equals(archive.packageName) ||
                versionCode(archive) != versionCode || versionCode <= versionCode(installed)) {
            throw new IOException("APK identity or version does not match");
        }
        Signature[] current = signers(installed);
        Signature[] incoming = signers(archive);
        if (current == null || current.length == 0 || incoming == null) throw new IOException("APK signing certificate is missing");
        Comparator<Signature> order = Comparator.comparing(Signature::toCharsString);
        Arrays.sort(current, order);
        Arrays.sort(incoming, order);
        if (!Arrays.equals(current, incoming)) throw new IOException("APK signing certificate does not match");
    }

    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT < 28) return info.signatures;
        return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Update cancelled");
    }
}
