package com.laoapk.vaultstore;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private VaultManager vaultManager;
    private String pendingImportNote = "";
    private String pendingDowngradeVaultId = null;

    private final ActivityResultLauncher<String[]> pickApkLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    new Thread(() -> {
                        JSONObject res = vaultManager.importApkFromUri(uri, pendingImportNote);
                        runOnUiThread(() -> {
                            String escaped = JSONObject.quote(res.toString());
                            webView.evaluateJavascript(
                                    "window.onApkImported && window.onApkImported(JSON.parse(" + escaped + "))",
                                    null
                            );
                        });
                    }).start();
                }
            }
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        vaultManager = new VaultManager(this);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setTextZoom(100);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handleIncomingApkIntent(getIntent());
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                webView.evaluateJavascript(
                        "(function(){ return window.handleBackNavigation ? window.handleBackNavigation() : false; })()",
                        result -> {
                            if ("true".equals(result)) {
                                // ປິດ Modal ແລ້ວ
                            } else if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                setEnabled(false);
                                getOnBackPressedDispatcher().onBackPressed();
                            }
                        }
                );
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingApkIntent(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIncomingApkIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        }

        if (uri != null) {
            final Uri finalUri = uri;
            intent.setAction(null);
            new Thread(() -> {
                JSONObject res = vaultManager.importApkFromUri(finalUri, "ນຳເຂົ້າຈາກໄຟລ໌ພາຍນອກ");
                runOnUiThread(() -> {
                    String escaped = JSONObject.quote(res.toString());
                    webView.evaluateJavascript(
                            "window.onApkImported && window.onApkImported(JSON.parse(" + escaped + "))",
                            null
                    );
                });
            }).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        String downgradeId = pendingDowngradeVaultId;
        if (downgradeId != null) {
            JSONObject item = vaultManager.findVaultItemById(downgradeId);
            if (item != null) {
                String pkgName = item.optString("packageName");
                long savedCode = item.optLong("versionCode", 0L);
                PackageInfo installedPkg = vaultManager.getInstalledPackageInfo(pkgName);
                long installedCode = installedPkg != null
                        ? PackageInfoCompat.getLongVersionCode(installedPkg)
                        : -1L;

                if (installedPkg == null || installedCode <= savedCode) {
                    pendingDowngradeVaultId = null;
                    Toast.makeText(
                            this,
                            "ຖອນເວີຊັນໃໝ່ສຳເລັດ! ກຳລັງເປີດການຕິດຕັ້ງເວີຊັນເກົ່າ...",
                            Toast.LENGTH_LONG
                    ).show();
                    launchApkInstallerInternal(downgradeId);
                }
            } else {
                pendingDowngradeVaultId = null;
            }
        }

        if (webView != null) {
            webView.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
        }
    }

    /**
     * ລະບົບຕິດຕັ້ງແບບ PackageInstaller.Session (ຮອງຮັບທັງ APK ດ່ຽວ ແລະ ຊຸດ Split APK ຄືກັນກັບ SHAREit ແລະ Play Store)
     */
    private JSONObject launchApkInstallerInternal(String vaultId) {
        JSONObject res = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Intent permIntent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())
                    );
                    startActivity(permIntent);
                    res.put("success", false);
                    res.put("needPermission", true);
                    res.put("message", "ກະລຸນາເປີດອະນຸຍາດ 'ຕິດຕັ້ງແອັບທີ່ບໍ່ຮູ້ຈັກ' ໃຫ້ແອັບນີ້ກ່ອນ ແລ້ວກົດຕິດຕັ້ງອີກຄັ້ງ");
                    return res;
                }
            }

            // ກວດສອບ ແລະ ດຶງ Split APK ອັດຕະໂນມັດກ່ອນຕິດຕັ້ງ
            vaultManager.syncUnindexedApks();

            JSONObject item = vaultManager.findVaultItemById(vaultId);
            List<File> apkFiles = vaultManager.getAllApkFilesForVaultItem(vaultId);
            if (item == null || apkFiles.isEmpty()) {
                res.put("success", false);
                res.put("message", "ບໍ່ພົບໄຟລ໌ APK ໃນຄັງ");
                return res;
            }

            boolean hasSplits = item.optBoolean("hasSplits", false);
            if (hasSplits && apkFiles.size() == 1) {
                res.put("success", false);
                res.put("missingSplitsError", true);
                res.put("message", "⚠️ ໄຟລ໌ \"" + item.optString("appName") + "\" ໃນຄັງນີ້ຖືກສຳຮອງໄວ້ຕອນເວີຊັນເກົ່າ (ມີແຕ່ base.apk ຂາດໄຟລ໌ຍ່ອຍ Split APK ຂອງ CPU/ໜ້າຈໍ).\n\nວິທີແກ້ໄຂງ່າຍໆ:\n1. ຕິດຕັ້ງແອັບນີ້ຈາກ SHAREit ລົງເຄື່ອງກ່ອນ 1 ຄັ້ງ\n2. ກັບມາເປີດແອັບຄັງເກັບ APK ນີ້ — ລະບົບຈະດຶງໄຟລ໌ຍ່ອຍທັງໝົດມາຕື່ມໃສ່ຄັງໃຫ້ຄົບຊຸດໂດຍອັດຕະໂນມັດ!");
                return res;
            }

            // ໃຊ້ PackageInstaller.Session ເພື່ອຕິດຕັ້ງທັງ base.apk ແລະ split_config.*.apk ພ້ອມກັນທຸກໄຟລ໌
            new Thread(() -> installViaPackageInstallerSession(item, apkFiles)).start();

            res.put("success", true);
            res.put("message", "⏳ ກຳລັງກຽມຕິດຕັ້ງຊຸດແອັບ (" + apkFiles.size() + " ໄຟລ໌ APK)... ກະລຸນາລໍຖ້າໜ້າຢືນຢັນ");
            return res;
        } catch (Exception ex) {
            try {
                res.put("success", false);
                res.put("message", "ບໍ່ສາມາດເປີດຕົວຕິດຕັ້ງໄດ້: " + ex.getLocalizedMessage());
            } catch (Exception ignored) {
            }
            return res;
        }
    }

    private void installViaPackageInstallerSession(JSONObject item, List<File> apkFiles) {
        PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            long totalSize = 0L;
            for (File f : apkFiles) {
                totalSize += f.length();
            }

            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
            );
            String pkgName = item.optString("packageName", "");
            if (!pkgName.isEmpty()) {
                params.setAppPackageName(pkgName);
            }
            params.setSize(totalSize);

            int sessionId = packageInstaller.createSession(params);
            session = packageInstaller.openSession(sessionId);

            byte[] buffer = new byte[65536];
            for (int i = 0; i < apkFiles.size(); i++) {
                File apkFile = apkFiles.get(i);
                String entryName = (i == 0) ? "base.apk" : ("split_" + i + ".apk");
                try (InputStream in = new FileInputStream(apkFile);
                     OutputStream out = session.openWrite(entryName, 0, apkFile.length())) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    session.fsync(out);
                }
            }

            Intent callbackIntent = new Intent(this, InstallReceiver.class);
            callbackIntent.setAction(InstallReceiver.ACTION_INSTALL_COMMIT);
            callbackIntent.setPackage(getPackageName());

            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingFlags |= PendingIntent.FLAG_MUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    callbackIntent,
                    pendingFlags
            );

            session.commit(pendingIntent.getIntentSender());
            session.close();
        } catch (Exception e) {
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> Toast.makeText(
                    MainActivity.this,
                    "ຜິດພາດໃນການຕິດຕັ້ງ: " + e.getLocalizedMessage(),
                    Toast.LENGTH_LONG
            ).show());
        }
    }

    public class AndroidBridge {

        @JavascriptInterface
        public String getVaultData() {
            return vaultManager.getVaultItemsJson();
        }

        @JavascriptInterface
        public String getInstalledApps(boolean includeSystem) {
            return vaultManager.getInstalledAppsJson(includeSystem);
        }

        @JavascriptInterface
        public String backupApp(String packageName, String note) {
            return vaultManager.backupInstalledApp(packageName, note).toString();
        }

        @JavascriptInterface
        public void pickApkFile(String customNote) {
            pendingImportNote = customNote != null ? customNote : "";
            runOnUiThread(() -> pickApkLauncher.launch(new String[]{
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "application/zip",
                    "*/*"
            }));
        }

        @JavascriptInterface
        public String installVaultApk(String vaultId) {
            JSONObject res = new JSONObject();
            try {
                vaultManager.syncUnindexedApks();
                JSONObject item = vaultManager.findVaultItemById(vaultId);
                if (item == null) {
                    res.put("success", false);
                    res.put("message", "ບໍ່ພົບຂໍ້ມູນແອັບໃນຄັງ");
                    return res.toString();
                }

                String pkgName = item.optString("packageName");
                long savedCode = item.optLong("versionCode", 0L);
                String savedVerName = item.optString("versionName", "1.0");
                PackageInfo installedPkg = vaultManager.getInstalledPackageInfo(pkgName);

                if (installedPkg != null) {
                    long installedCode = PackageInfoCompat.getLongVersionCode(installedPkg);
                    String installedVerName = installedPkg.versionName != null ? installedPkg.versionName : "1.0";
                    if (installedCode > savedCode) {
                        res.put("success", false);
                        res.put("needDowngradeUninstall", true);
                        res.put("installedVersionName", installedVerName);
                        res.put("savedVersionName", savedVerName);
                        res.put("message", "ໃນເຄື່ອງຂອງທ່ານມີເວີຊັນໃໝ່ກວ່າ (v" + installedVerName + ") ຕິດຕັ້ງຢູ່. ລະບົບ Android ບັງຄັບໃຫ້ຖອນເວີຊັນໃໝ່ອອກກ່ອນ ຈຶ່ງຈະລົງເວີຊັນເກົ່າ (v" + savedVerName + ") ໄດ້.");
                        return res.toString();
                    }
                }
            } catch (Exception ignored) {
            }

            return launchApkInstallerInternal(vaultId).toString();
        }

        @JavascriptInterface
        public String forceInstallVaultApk(String vaultId) {
            return launchApkInstallerInternal(vaultId).toString();
        }

        @JavascriptInterface
        public String startDowngradeFlow(String vaultId) {
            JSONObject res = new JSONObject();
            try {
                JSONObject item = vaultManager.findVaultItemById(vaultId);
                if (item == null) {
                    res.put("success", false);
                    res.put("message", "ບໍ່ພົບແອັບໃນຄັງ");
                    return res.toString();
                }
                String pkgName = item.optString("packageName");
                pendingDowngradeVaultId = vaultId;
                try {
                    Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
                    uninstallIntent.setData(Uri.parse("package:" + pkgName));
                    uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(uninstallIntent);
                    res.put("success", true);
                    res.put("message", "ກະລຸນາກົດ 'ຕົກລົງ (OK)' ເພື່ອຖອນເວີຊັນໃໝ່ — ຫຼັງຈາກຖອນແລ້ວ ແອັບຈະເປີດໜ້າຕິດຕັ້ງເວີຊັນເກົ່າໃຫ້ທັນທີ!");
                    return res.toString();
                } catch (Exception e) {
                    openAppSettings(pkgName);
                    res.put("success", true);
                    res.put("message", "ກະລຸນາກົດ 'ຖອນການຕິດຕັ້ງ (Uninstall)' ໃນໜ້າຕັ້ງຄ່າແອັບ ແລ້ວກັບມາໜ້ານີ້");
                    return res.toString();
                }
            } catch (Exception ex) {
                return res.toString();
            }
        }

        @JavascriptInterface
        public String deleteVaultItem(String vaultId) {
            return vaultManager.deleteVaultItem(vaultId).toString();
        }

        @JavascriptInterface
        public String updateNoteAndPin(String vaultId, String note, boolean pinned) {
            return vaultManager.updateVaultNoteAndPin(vaultId, note, pinned).toString();
        }

        @JavascriptInterface
        public String exportToDownloads(String vaultId) {
            return vaultManager.exportApkToDownloads(vaultId).toString();
        }

        @JavascriptInterface
        public String shareVaultApk(String vaultId) {
            JSONObject res = new JSONObject();
            try {
                File apkFile = vaultManager.getVaultApkFile(vaultId);
                JSONObject item = vaultManager.findVaultItemById(vaultId);
                if (apkFile == null || item == null) {
                    res.put("success", false);
                    res.put("message", "ບໍ່ພົບໄຟລ໌ APK");
                    return res.toString();
                }
                Uri uri = FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".fileprovider",
                        apkFile
                );
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/vnd.android.package-archive");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, item.optString("appName") + " v" + item.optString("versionName"));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(shareIntent, "ແຊຣ໌ໄຟລ໌ APK ເວີຊັນເກົ່າ");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);

                res.put("success", true);
                res.put("message", "ກຳລັງເປີດໜ້າແຊຣ໌ໄຟລ໌...");
                return res.toString();
            } catch (Exception e) {
                try {
                    res.put("success", false);
                    res.put("message", "ບໍ່ສາມາດແຊຣ໌ໄຟລ໌ໄດ້: " + e.getLocalizedMessage());
                } catch (Exception ignored) {
                }
                return res.toString();
            }
        }

        @JavascriptInterface
        public void openPlayStore(String targetPackage) {
            try {
                Intent marketIntent = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + targetPackage)
                );
                marketIntent.setPackage("com.android.vending");
                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(marketIntent);
            } catch (Exception e) {
                try {
                    Intent webIntent = new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=" + targetPackage)
                    );
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(webIntent);
                } catch (Exception ignored) {
                }
            }
        }

        @JavascriptInterface
        public void openAppSettings(String targetPackage) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + targetPackage)
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public boolean canInstallUnknownApps() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return getPackageManager().canRequestPackageInstalls();
            }
            return true;
        }

        @JavascriptInterface
        public void requestInstallPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())
                    );
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
