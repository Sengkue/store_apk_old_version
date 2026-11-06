package com.laoapk.vaultstore;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;

import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class VaultManager {

    private final Context context;
    private final PackageManager pm;

    public VaultManager(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    public File getVaultDir() {
        File dir = context.getExternalFilesDir("apk_vault");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "apk_vault");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private File getIndexFile() {
        return new File(getVaultDir(), "vault_index.json");
    }

    private synchronized JSONArray loadIndexArray() {
        File indexFile = getIndexFile();
        if (!indexFile.exists()) {
            return new JSONArray();
        }
        try (FileInputStream fis = new FileInputStream(indexFile)) {
            byte[] bytes = new byte[(int) indexFile.length()];
            int read = fis.read(bytes);
            if (read <= 0) return new JSONArray();
            String content = new String(bytes, 0, read, StandardCharsets.UTF_8);
            return new JSONArray(content);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private synchronized void saveIndexArray(JSONArray array) {
        File indexFile = getIndexFile();
        try (FileOutputStream fos = new FileOutputStream(indexFile, false)) {
            fos.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /**
     * ກວດສອບວ່າໄຟລ໌ base.apk ຕ້ອງການ Split APKs ຫຼືບໍ່ (ເຊັ່ນ APB HRM ທີ່ມີ com.android.vending.splits.required)
     */
    @SuppressWarnings("deprecation")
    private boolean doesBaseApkRequireSplits(File apkFile) {
        try {
            PackageInfo pkgInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pkgInfo = pm.getPackageArchiveInfo(
                        apkFile.getAbsolutePath(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA)
                );
            } else {
                pkgInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_META_DATA);
            }
            if (pkgInfo != null && pkgInfo.applicationInfo != null && pkgInfo.applicationInfo.metaData != null) {
                if (pkgInfo.applicationInfo.metaData.getBoolean("com.android.vending.splits.required", false)) {
                    return true;
                }
                if (pkgInfo.applicationInfo.metaData.containsKey("com.android.vending.splits")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * ກວດສອບ ແລະ ສ້ອມແປງລາຍການແອັບໃນຄັງທີ່ເປັນ Split APK ແຕ່ຍັງຂາດໄຟລ໌ split_config.*.apk ໂດຍດຶງຈາກແອັບໃນເຄື່ອງອັດຕະໂນມັດ
     */
    public synchronized void syncUnindexedApks() {
        File vaultDir = getVaultDir();
        JSONArray array = loadIndexArray();
        Set<String> indexedFiles = new HashSet<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            indexedFiles.add(obj.optString("fileName"));
            JSONArray splits = obj.optJSONArray("splitFileNames");
            if (splits != null) {
                for (int s = 0; s < splits.length(); s++) {
                    indexedFiles.add(splits.optString(s));
                }
            }
        }

        boolean changed = false;
        File[] apkFiles = vaultDir.listFiles(file ->
                file.isFile() &&
                file.getName().toLowerCase(Locale.US).endsWith(".apk") &&
                !file.getName().contains("_split_")
        );

        if (apkFiles != null) {
            for (File apkFile : apkFiles) {
                if (!indexedFiles.contains(apkFile.getName())) {
                    JSONObject meta = extractApkMetadataFromFile(apkFile, "ນຳເຂົ້າອັດຕະໂນມັດຈາກໂຟນເດີຄັງແອັບ");
                    if (meta != null) {
                        array.put(meta);
                        changed = true;
                    }
                }
            }
        }

        // ລຶບລາຍການທີ່ໄຟລ໌ຕົວຈິງບໍ່ມີຢູ່ແລ້ວ ແລະ ສ້ອມແປງ Split APK ອັດຕະໂນມັດຖ້າແອັບນັ້ນຕິດຕັ້ງຢູ່ໃນເຄື່ອງ
        JSONArray cleaned = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String fileName = obj.optString("fileName");
            File baseFile = new File(vaultDir, fileName);
            if (!baseFile.exists()) {
                changed = true;
                continue;
            }

            boolean requiresSplits = obj.optBoolean("hasSplits", false) || doesBaseApkRequireSplits(baseFile);
            if (requiresSplits && !obj.optBoolean("hasSplits", false)) {
                try {
                    obj.put("hasSplits", true);
                    changed = true;
                } catch (Exception ignored) {
                }
            }

            JSONArray splitNames = obj.optJSONArray("splitFileNames");
            int savedSplitCount = splitNames != null ? splitNames.length() : 0;

            // ຖ້າເປັນແອັບ Split APK ແຕ່ຕອນສຳຮອງຄັ້ງກ່ອນຍັງບໍ່ມີໄຟລ໌ຍ່ອຍ (savedSplitCount == 0),
            // ໃຫ້ດຶງໄຟລ໌ split_config.*.apk ຈາກແອັບທີ່ຕິດຕັ້ງຢູ່ໃນເຄື່ອງມາຕື່ມໃສ່ຄັງອັດຕະໂນມັດ!
            if (requiresSplits && savedSplitCount == 0) {
                String pkgName = obj.optString("packageName");
                long savedCode = obj.optLong("versionCode", 0L);
                PackageInfo installedPkg = getInstalledPackageInfo(pkgName);
                if (installedPkg != null && installedPkg.applicationInfo != null) {
                    long installedCode = PackageInfoCompat.getLongVersionCode(installedPkg);
                    String[] splitDirs = installedPkg.applicationInfo.splitSourceDirs;
                    if (installedCode == savedCode && splitDirs != null && splitDirs.length > 0) {
                        try {
                            JSONArray newSplitArray = new JSONArray();
                            long totalSize = baseFile.length();
                            String safePkg = pkgName.replaceAll("[^a-zA-Z0-9._-]", "_");
                            long ts = System.currentTimeMillis();
                            for (int s = 0; s < splitDirs.length; s++) {
                                File srcSplit = new File(splitDirs[s]);
                                if (srcSplit.exists() && srcSplit.canRead()) {
                                    String splitOutName = safePkg + "_v" + savedCode + "_" + ts + "_split_" + s + "_" + srcSplit.getName();
                                    File dstSplit = new File(vaultDir, splitOutName);
                                    copyFile(srcSplit, dstSplit);
                                    newSplitArray.put(splitOutName);
                                    totalSize += dstSplit.length();
                                }
                            }
                            if (newSplitArray.length() > 0) {
                                obj.put("splitFileNames", newSplitArray);
                                obj.put("fileSize", totalSize);
                                changed = true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            cleaned.put(obj);
        }

        if (changed) {
            saveIndexArray(cleaned);
        }
    }

    @SuppressWarnings("deprecation")
    private JSONObject extractApkMetadataFromFile(File apkFile, String note) {
        try {
            PackageInfo pkgInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pkgInfo = pm.getPackageArchiveInfo(
                        apkFile.getAbsolutePath(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA)
                );
            } else {
                pkgInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_META_DATA);
            }
            if (pkgInfo == null || pkgInfo.applicationInfo == null) return null;

            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            appInfo.sourceDir = apkFile.getAbsolutePath();
            appInfo.publicSourceDir = apkFile.getAbsolutePath();

            String appName;
            try {
                CharSequence label = pm.getApplicationLabel(appInfo);
                appName = label != null ? label.toString() : pkgInfo.packageName;
            } catch (Exception e) {
                appName = pkgInfo.packageName;
            }

            Drawable iconDrawable = null;
            try {
                iconDrawable = pm.getApplicationIcon(appInfo);
            } catch (Exception ignored) {
            }

            boolean requiresSplits = doesBaseApkRequireSplits(apkFile);
            String iconBase64 = drawableToBase64(iconDrawable);
            String versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "1.0";
            long versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo);
            int minSdk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? appInfo.minSdkVersion : 21;
            int targetSdk = appInfo.targetSdkVersion;

            long now = System.currentTimeMillis();
            JSONObject obj = new JSONObject();
            obj.put("id", UUID.randomUUID().toString());
            obj.put("packageName", pkgInfo.packageName);
            obj.put("appName", appName);
            obj.put("versionName", versionName);
            obj.put("versionCode", versionCode);
            obj.put("minSdk", minSdk);
            obj.put("targetSdk", targetSdk);
            obj.put("fileName", apkFile.getName());
            obj.put("splitFileNames", new JSONArray());
            obj.put("fileSize", apkFile.length());
            obj.put("savedAt", now);
            obj.put("savedDateText", formatTimestamp(now));
            obj.put("note", (note == null || note.trim().isEmpty()) ? "ເວີຊັນເກົ່າທີ່ເກັບໄວ້" : note.trim());
            obj.put("pinned", true);
            obj.put("hasSplits", requiresSplits);
            obj.put("iconBase64", iconBase64);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ນຳເຂົ້າໄຟລ໌ .APK ຫຼື ຊຸດໄຟລ໌ Split APK (.apks / .xapk / .apkm / .zip)
     */
    @SuppressWarnings("deprecation")
    public synchronized JSONObject importApkFromUri(Uri uri, String customNote) {
        JSONObject result = new JSONObject();
        try {
            File vaultDir = getVaultDir();
            File tempFile = new File(context.getCacheDir(), "import_temp_" + System.currentTimeMillis() + ".bin");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tempFile)) {
                if (in == null) {
                    result.put("success", false);
                    result.put("message", "ບໍ່ສາມາດອ່ານໄຟລ໌ທີ່ເລືອກໄດ້");
                    return result;
                }
                copyStream(in, out);
            }

            // ກວດສອບກ່ອນວ່າເປັນໄຟລ໌ຊຸດ .apks / .xapk / .zip ທີ່ບັນຈຸຫຼາຍ .apk ຢູ່ທາງໃນຫຼືບໍ່
            if (isMultiApkBundleArchive(tempFile)) {
                JSONObject bundleRes = importMultiApkBundleFile(tempFile, customNote);
                tempFile.delete();
                return bundleRes;
            }

            PackageInfo pkgInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pkgInfo = pm.getPackageArchiveInfo(
                        tempFile.getAbsolutePath(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA)
                );
            } else {
                pkgInfo = pm.getPackageArchiveInfo(tempFile.getAbsolutePath(), PackageManager.GET_META_DATA);
            }

            if (pkgInfo == null || pkgInfo.applicationInfo == null) {
                tempFile.delete();
                result.put("success", false);
                result.put("message", "ໄຟລ໌ທີ່ເລືອກບໍ່ແມ່ນໄຟລ໌ຕິດຕັ້ງ Android (.APK / .APKS) ທີ່ຖືກຕ້ອງ");
                return result;
            }

            long versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo);
            String safePkg = pkgInfo.packageName.replaceAll("[^a-zA-Z0-9._-]", "_");
            long ts = System.currentTimeMillis();
            String finalFileName = safePkg + "_v" + versionCode + "_" + ts + ".apk";
            File finalFile = new File(vaultDir, finalFileName);

            copyFile(tempFile, finalFile);
            tempFile.delete();

            String noteToSave = (customNote == null || customNote.trim().isEmpty())
                    ? "ໄຟລ໌ APK ເວີຊັນເກົ່າ (ນຳເຂົ້າຈາກເຄື່ອງ)"
                    : customNote.trim();

            JSONObject meta = extractApkMetadataFromFile(finalFile, noteToSave);
            if (meta == null) {
                finalFile.delete();
                result.put("success", false);
                result.put("message", "ເກີດຂໍ້ຜິດພາດໃນການອ່ານຂໍ້ມູນ APK");
                return result;
            }

            JSONArray array = loadIndexArray();
            array.put(meta);
            saveIndexArray(array);

            // ກວດສອບທັນທີວ່າ ຖ້າໄຟລ໌ທີ່ນຳເຂົ້າເປັນ base.apk ທີ່ຕ້ອງການ Split APK ແລະ ແອັບນັ້ນມີໃນເຄື່ອງ ໃຫ້ດຶງ Split ມາຕື່ມທັນທີ
            syncUnindexedApks();

            boolean requiresSplits = meta.optBoolean("hasSplits", false);
            JSONObject updatedMeta = findVaultItemById(meta.optString("id"));
            int splitCount = (updatedMeta != null && updatedMeta.optJSONArray("splitFileNames") != null)
                    ? updatedMeta.optJSONArray("splitFileNames").length()
                    : 0;

            result.put("success", true);
            if (requiresSplits && splitCount == 0) {
                result.put("message", "⚠️ ໄຟລ໌ \"" + meta.optString("appName") + "\" ນີ້ມີແຕ່ base.apk (ຂາດໄຟລ໌ຍ່ອຍ Split APK). ກະລຸນາຕິດຕັ້ງແອັບນີ້ຈາກ SHAREit ກ່ອນ 1 ຄັ້ງ ແລ້ວກົດ 'ດຶງຈາກເຄື່ອງ' ເພື່ອເກັບຊຸດເຕັມ!");
            } else {
                result.put("message", "ບັນທຶກແອັບ \"" + meta.optString("appName") + "\" (v" + meta.optString("versionName") + ") ເຂົ້າຄັງສຳເລັດແລ້ວ!");
            }
            result.put("item", updatedMeta != null ? updatedMeta : meta);
            return result;
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("message", "ຜິດພາດ: " + (e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "ບໍ່ສາມາດນຳເຂົ້າໄຟລ໌ໄດ້"));
            } catch (Exception ignored) {
            }
            return result;
        }
    }

    private boolean isMultiApkBundleArchive(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            boolean hasRootManifest = zip.getEntry("AndroidManifest.xml") != null;
            if (hasRootManifest) return false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int apkCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                    apkCount++;
                }
            }
            return apkCount > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject importMultiApkBundleFile(File bundleZipFile, String customNote) {
        JSONObject result = new JSONObject();
        List<File> tempExtractedApks = new ArrayList<>();
        try (ZipFile zip = new ZipFile(bundleZipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int idx = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                    String shortName = new File(entry.getName()).getName();
                    File extracted = new File(context.getCacheDir(), "bundle_" + System.currentTimeMillis() + "_" + (idx++) + "_" + shortName);
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(extracted)) {
                        copyStream(in, out);
                    }
                    tempExtractedApks.add(extracted);
                }
            }

            File baseApkTemp = null;
            List<File> splitApkTemps = new ArrayList<>();
            for (File f : tempExtractedApks) {
                PackageInfo info = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (info != null && info.applicationInfo != null && (info.splitNames == null || info.splitNames.length == 0)) {
                    if (f.getName().toLowerCase(Locale.US).contains("base") || baseApkTemp == null) {
                        if (baseApkTemp != null) splitApkTemps.add(baseApkTemp);
                        baseApkTemp = f;
                        continue;
                    }
                }
                splitApkTemps.add(f);
            }

            if (baseApkTemp == null && !tempExtractedApks.isEmpty()) {
                baseApkTemp = tempExtractedApks.get(0);
                splitApkTemps.remove(baseApkTemp);
            }

            if (baseApkTemp == null) {
                result.put("success", false);
                result.put("message", "ບໍ່ພົບໄຟລ໌ APK ໃນຊຸດໄຟລ໌ນີ້");
                return result;
            }

            PackageInfo pkgInfo = pm.getPackageArchiveInfo(baseApkTemp.getAbsolutePath(), PackageManager.GET_META_DATA);
            if (pkgInfo == null || pkgInfo.applicationInfo == null) {
                result.put("success", false);
                result.put("message", "ບໍ່ສາມາດອ່ານຂໍ້ມູນ base.apk ໃນຊຸດໄຟລ໌ນີ້ໄດ້");
                return result;
            }

            File vaultDir = getVaultDir();
            long versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo);
            String safePkg = pkgInfo.packageName.replaceAll("[^a-zA-Z0-9._-]", "_");
            long ts = System.currentTimeMillis();
            String baseFileName = safePkg + "_v" + versionCode + "_" + ts + ".apk";
            File finalBaseFile = new File(vaultDir, baseFileName);
            copyFile(baseApkTemp, finalBaseFile);

            long totalSize = finalBaseFile.length();
            JSONArray splitFileNames = new JSONArray();
            for (int i = 0; i < splitApkTemps.size(); i++) {
                File sTemp = splitApkTemps.get(i);
                String sName = safePkg + "_v" + versionCode + "_" + ts + "_split_" + i + ".apk";
                File finalSplit = new File(vaultDir, sName);
                copyFile(sTemp, finalSplit);
                splitFileNames.put(sName);
                totalSize += finalSplit.length();
            }

            String noteToSave = (customNote == null || customNote.trim().isEmpty())
                    ? "ຊຸດແອັບ Split APK ເຕັມຮູບແບບ (" + (splitFileNames.length() + 1) + " ໄຟລ໌)"
                    : customNote.trim();

            JSONObject meta = extractApkMetadataFromFile(finalBaseFile, noteToSave);
            if (meta != null) {
                meta.put("splitFileNames", splitFileNames);
                meta.put("hasSplits", splitFileNames.length() > 0);
                meta.put("fileSize", totalSize);

                JSONArray array = loadIndexArray();
                array.put(meta);
                saveIndexArray(array);

                result.put("success", true);
                result.put("message", "✅ ນຳເຂົ້າຊຸດແອັບ \"" + meta.optString("appName") + "\" ຄົບທັງໝົດ " + (splitFileNames.length() + 1) + " ໄຟລ໌ສຳເລັດແລ້ວ!");
                result.put("item", meta);
            } else {
                result.put("success", false);
                result.put("message", "ບໍ່ສາມາດບັນທຶກຂໍ້ມູນຊຸດ APK ໄດ້");
            }
            return result;
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("message", "ຜິດພາດໃນການແตกໄຟລ໌ຊຸດ APK: " + e.getLocalizedMessage());
            } catch (Exception ignored) {
            }
            return result;
        } finally {
            for (File f : tempExtractedApks) {
                f.delete();
            }
        }
    }

    /**
     * ດຶງໄຟລ໌ APK ທັງໝົດ (ທັງ base.apk ແລະ split_config.*.apk ທຸກໄຟລ໌) ຈາກແອັບໃນໂທລະສັບເຂົ້າຄັງ (ຄືກັນກັບ SHAREit)
     */
    public synchronized JSONObject backupInstalledApp(String packageName, String customNote) {
        JSONObject result = new JSONObject();
        try {
            PackageInfo pkgInfo = getInstalledPackageInfo(packageName);
            if (pkgInfo == null || pkgInfo.applicationInfo == null) {
                result.put("success", false);
                result.put("message", "ບໍ່ພົບແອັບນີ້ໃນໂທລະສັບ");
                return result;
            }

            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            File sourceApk = new File(appInfo.sourceDir);
            if (!sourceApk.exists() || !sourceApk.canRead()) {
                result.put("success", false);
                result.put("message", "ບໍ່ສາມາດອ່ານໄຟລ໌ຕົ້ນສະບັບຂອງແອັບນີ້ໄດ້");
                return result;
            }

            long versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo);
            String versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "1.0";
            String appName;
            try {
                CharSequence label = pm.getApplicationLabel(appInfo);
                appName = label != null ? label.toString() : packageName;
            } catch (Exception e) {
                appName = packageName;
            }

            File vaultDir = getVaultDir();
            String safePkg = packageName.replaceAll("[^a-zA-Z0-9._-]", "_");
            long now = System.currentTimeMillis();
            String finalFileName = safePkg + "_v" + versionCode + "_" + now + ".apk";
            File destFile = new File(vaultDir, finalFileName);

            copyFile(sourceApk, destFile);
            long totalBytes = destFile.length();

            // ດຶງໄຟລ໌ຍ່ອຍ Split APKs ທັງໝົດ (split_config.arm64_v8a.apk, split_config.xxhdpi.apk, ...)
            String[] splitDirs = appInfo.splitSourceDirs;
            boolean hasSplits = splitDirs != null && splitDirs.length > 0;
            JSONArray splitFileNames = new JSONArray();

            if (hasSplits) {
                for (int i = 0; i < splitDirs.length; i++) {
                    File splitSrc = new File(splitDirs[i]);
                    if (splitSrc.exists() && splitSrc.canRead()) {
                        String splitOutName = safePkg + "_v" + versionCode + "_" + now + "_split_" + i + "_" + splitSrc.getName();
                        File splitDst = new File(vaultDir, splitOutName);
                        copyFile(splitSrc, splitDst);
                        splitFileNames.put(splitOutName);
                        totalBytes += splitDst.length();
                    }
                }
            }

            Drawable iconDrawable = null;
            try {
                iconDrawable = pm.getApplicationIcon(appInfo);
            } catch (Exception ignored) {
            }

            int totalApkFilesCount = 1 + splitFileNames.length();
            String defaultNote = hasSplits
                    ? "ສຳຮອງຈາກເຄື່ອງຄົບຊຸດ (" + totalApkFilesCount + " ໄຟລ໌ Split APK • v" + versionName + ")"
                    : "ສຳຮອງຈາກແອັບໃນເຄື່ອງ (v" + versionName + ")";

            JSONObject meta = new JSONObject();
            meta.put("id", UUID.randomUUID().toString());
            meta.put("packageName", packageName);
            meta.put("appName", appName);
            meta.put("versionName", versionName);
            meta.put("versionCode", versionCode);
            meta.put("minSdk", Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? appInfo.minSdkVersion : 21);
            meta.put("targetSdk", appInfo.targetSdkVersion);
            meta.put("fileName", finalFileName);
            meta.put("splitFileNames", splitFileNames);
            meta.put("fileSize", totalBytes);
            meta.put("savedAt", now);
            meta.put("savedDateText", formatTimestamp(now));
            meta.put("note", (customNote == null || customNote.trim().isEmpty()) ? defaultNote : customNote.trim());
            meta.put("pinned", true);
            meta.put("hasSplits", hasSplits);
            meta.put("iconBase64", drawableToBase64(iconDrawable));

            // ຖ້າມີລາຍການເວີຊັນດຽວກັນທີ່ເຄີຍບັນທຶກໄວ້ແບບບໍ່ຄົບ Split APK ໃຫ້ລຶບໂຕເກົ່າທີ່ບໍ່ຄົບອອກແລ້ວແທນທີ່ດ້ວຍໂຕຄົບຊຸດທັນທີ
            JSONArray oldArray = loadIndexArray();
            JSONArray newArray = new JSONArray();
            for (int i = 0; i < oldArray.length(); i++) {
                JSONObject existing = oldArray.optJSONObject(i);
                if (existing == null) continue;
                boolean samePkgAndVer = packageName.equals(existing.optString("packageName"))
                        && versionCode == existing.optLong("versionCode", -1L);
                JSONArray existingSplits = existing.optJSONArray("splitFileNames");
                int existingSplitCount = existingSplits != null ? existingSplits.length() : 0;

                if (samePkgAndVer && existingSplitCount == 0 && hasSplits) {
                    String oldBase = existing.optString("fileName");
                    if (!oldBase.isEmpty()) {
                        new File(vaultDir, oldBase).delete();
                    }
                } else {
                    newArray.put(existing);
                }
            }

            newArray.put(meta);
            saveIndexArray(newArray);

            result.put("success", true);
            result.put("message", "✅ ເກັບແອັບ \"" + appName + "\" (v" + versionName + ") ຄົບທັງໝົດ " + totalApkFilesCount + " ໄຟລ໌ໄວ້ໃນຄັງສຳເລັດແລ້ວ!");
            result.put("item", meta);
            return result;
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("message", "ເກີດຂໍ້ຜິດພາດໃນການສຳຮອງແອັບ: " + e.getLocalizedMessage());
            } catch (Exception ignored) {
            }
            return result;
        }
    }

    public synchronized String getVaultItemsJson() {
        syncUnindexedApks();
        JSONArray array = loadIndexArray();
        List<JSONObject> enrichedList = new ArrayList<>();

        long totalBytes = 0L;
        int autoUpdatedCount = 0;
        Set<String> uniquePackages = new HashSet<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            try {
                JSONObject clone = new JSONObject(item.toString());
                String pkgName = clone.optString("packageName");
                long savedVersionCode = clone.optLong("versionCode", 0L);
                long fileSize = clone.optLong("fileSize", 0L);
                totalBytes += fileSize;
                uniquePackages.add(pkgName);

                JSONArray splitArr = clone.optJSONArray("splitFileNames");
                int splitCount = splitArr != null ? splitArr.length() : 0;
                boolean hasSplits = clone.optBoolean("hasSplits", false);
                boolean missingRequiredSplits = hasSplits && splitCount == 0;

                clone.put("splitCount", splitCount);
                clone.put("missingRequiredSplits", missingRequiredSplits);

                PackageInfo installedInfo = getInstalledPackageInfo(pkgName);
                if (installedInfo != null) {
                    long installedCode = PackageInfoCompat.getLongVersionCode(installedInfo);
                    String installedName = installedInfo.versionName != null ? installedInfo.versionName : "1.0";
                    clone.put("isInstalledOnPhone", true);
                    clone.put("installedVersionName", installedName);
                    clone.put("installedVersionCode", installedCode);

                    if (installedCode == savedVersionCode) {
                        clone.put("status", "MATCH");
                        clone.put("statusLabelLao", "ກຳລັງໃຊ້ເວີຊັນເກົ່ານີ້ຢູ່ (ປອດໄພ)");
                    } else if (installedCode > savedVersionCode) {
                        clone.put("status", "AUTO_UPDATED");
                        clone.put("statusLabelLao", "ແອັບໃນເຄື່ອງຖືກອັບເດດເປັນ v" + installedName + " ແລ້ວ!");
                        autoUpdatedCount++;
                    } else {
                        clone.put("status", "OLDER_INSTALLED");
                        clone.put("statusLabelLao", "ໃນເຄື່ອງເປັນເວີຊັນເກົ່າກວ່າ (v" + installedName + ")");
                    }
                } else {
                    clone.put("isInstalledOnPhone", false);
                    clone.put("installedVersionName", "");
                    clone.put("installedVersionCode", -1L);
                    clone.put("status", "NOT_INSTALLED");
                    clone.put("statusLabelLao", "ຍັງບໍ່ໄດ້ຕິດຕັ້ງໃນເຄື່ອງ (ພ້ອມຕິດຕັ້ງ)");
                }

                clone.put("fileSizeText", formatFileSize(fileSize));
                enrichedList.add(clone);
            } catch (Exception ignored) {
            }
        }

        Collections.sort(enrichedList, (a, b) -> {
            boolean aAuto = "AUTO_UPDATED".equals(a.optString("status"));
            boolean bAuto = "AUTO_UPDATED".equals(b.optString("status"));
            if (aAuto != bAuto) return aAuto ? -1 : 1;

            boolean aPin = a.optBoolean("pinned", false);
            boolean bPin = b.optBoolean("pinned", false);
            if (aPin != bPin) return aPin ? -1 : 1;

            return Long.compare(b.optLong("savedAt", 0L), a.optLong("savedAt", 0L));
        });

        JSONArray outArray = new JSONArray();
        for (JSONObject obj : enrichedList) {
            outArray.put(obj);
        }

        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("items", outArray);
            wrapper.put("totalVersions", enrichedList.size());
            wrapper.put("uniqueApps", uniquePackages.size());
            wrapper.put("autoUpdatedCount", autoUpdatedCount);
            wrapper.put("totalStorageText", formatFileSize(totalBytes));
            wrapper.put("vaultPath", getVaultDir().getAbsolutePath());
        } catch (Exception ignored) {
        }
        return wrapper.toString();
    }

    @SuppressWarnings("deprecation")
    public String getInstalledAppsJson(boolean includeSystemApps) {
        JSONArray vaultArray = loadIndexArray();
        Set<String> savedKeys = new HashSet<>();
        Set<String> savedPackages = new HashSet<>();

        for (int i = 0; i < vaultArray.length(); i++) {
            JSONObject obj = vaultArray.optJSONObject(i);
            if (obj == null) continue;
            String pkg = obj.optString("packageName");
            long code = obj.optLong("versionCode");
            boolean hasSplits = obj.optBoolean("hasSplits", false);
            JSONArray splitArr = obj.optJSONArray("splitFileNames");
            int splitCount = splitArr != null ? splitArr.length() : 0;
            // ນັບວ່າເກັບຄົບແລ້ວກໍຕໍ່ເມື່ອບໍ່ຂາດໄຟລ໌ Split
            if (!hasSplits || splitCount > 0) {
                savedKeys.add(pkg + ":" + code);
            }
            savedPackages.add(pkg);
        }

        List<PackageInfo> packages;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0));
        } else {
            packages = pm.getInstalledPackages(0);
        }

        List<JSONObject> appList = new ArrayList<>();
        for (PackageInfo pkgInfo : packages) {
            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            if (appInfo == null) continue;
            if (pkgInfo.packageName.equals(context.getPackageName())) continue;

            boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;

            if (isSystem && !includeSystemApps) continue;

            String appName;
            try {
                CharSequence label = pm.getApplicationLabel(appInfo);
                appName = label != null ? label.toString() : pkgInfo.packageName;
            } catch (Exception e) {
                appName = pkgInfo.packageName;
            }

            String versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "1.0";
            long versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo);
            File sourceFile = new File(appInfo.sourceDir);
            long fileSize = sourceFile.exists() ? sourceFile.length() : 0L;

            String[] splitDirs = appInfo.splitSourceDirs;
            boolean hasSplits = splitDirs != null && splitDirs.length > 0;
            int splitCount = hasSplits ? splitDirs.length : 0;
            if (hasSplits) {
                for (String sPath : splitDirs) {
                    File sf = new File(sPath);
                    if (sf.exists()) fileSize += sf.length();
                }
            }

            Drawable iconDrawable = null;
            try {
                iconDrawable = pm.getApplicationIcon(appInfo);
            } catch (Exception ignored) {
            }

            try {
                JSONObject obj = new JSONObject();
                obj.put("packageName", pkgInfo.packageName);
                obj.put("appName", appName);
                obj.put("versionName", versionName);
                obj.put("versionCode", versionCode);
                obj.put("fileSize", fileSize);
                obj.put("fileSizeText", formatFileSize(fileSize));
                obj.put("isSystem", isSystem);
                obj.put("hasSplits", hasSplits);
                obj.put("splitCount", splitCount);
                obj.put("lastUpdateTime", pkgInfo.lastUpdateTime);
                obj.put("lastUpdateText", formatTimestamp(pkgInfo.lastUpdateTime));
                obj.put("isExactVersionSaved", savedKeys.contains(pkgInfo.packageName + ":" + versionCode));
                obj.put("hasAnyVersionSaved", savedPackages.contains(pkgInfo.packageName));
                obj.put("iconBase64", drawableToBase64(iconDrawable));
                appList.add(obj);
            } catch (Exception ignored) {
            }
        }

        Collections.sort(appList, (a, b) -> {
            boolean aSaved = a.optBoolean("hasAnyVersionSaved", false);
            boolean bSaved = b.optBoolean("hasAnyVersionSaved", false);
            if (aSaved != bSaved) return aSaved ? -1 : 1;
            return a.optString("appName", "").toLowerCase(Locale.getDefault())
                    .compareTo(b.optString("appName", "").toLowerCase(Locale.getDefault()));
        });

        JSONArray out = new JSONArray();
        for (JSONObject item : appList) {
            out.put(item);
        }
        return out.toString();
    }

    public synchronized JSONObject findVaultItemById(String id) {
        JSONArray array = loadIndexArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj != null && id.equals(obj.optString("id"))) {
                return obj;
            }
        }
        return null;
    }

    public File getVaultApkFile(String id) {
        JSONObject item = findVaultItemById(id);
        if (item == null) return null;
        String fileName = item.optString("fileName");
        if (fileName == null || fileName.isEmpty()) return null;
        File file = new File(getVaultDir(), fileName);
        return file.exists() ? file : null;
    }

    /**
     * ດຶງລາຍການໄຟລ໌ APK ທັງໝົດຂອງແອັບນີ້ (base.apk + split_config.*.apk ທຸກໄຟລ໌) ເພື່ອຕິດຕັ້ງຜ່ານ PackageInstaller.Session
     */
    public List<File> getAllApkFilesForVaultItem(String id) {
        List<File> files = new ArrayList<>();
        JSONObject item = findVaultItemById(id);
        if (item == null) return files;

        File vaultDir = getVaultDir();
        String baseName = item.optString("fileName");
        if (!baseName.isEmpty()) {
            File baseFile = new File(vaultDir, baseName);
            if (baseFile.exists()) {
                files.add(baseFile);
            }
        }

        JSONArray splits = item.optJSONArray("splitFileNames");
        if (splits != null) {
            for (int i = 0; i < splits.length(); i++) {
                String sName = splits.optString(i);
                if (!sName.isEmpty()) {
                    File sFile = new File(vaultDir, sName);
                    if (sFile.exists()) {
                        files.add(sFile);
                    }
                }
            }
        }
        return files;
    }

    public synchronized JSONObject deleteVaultItem(String id) {
        JSONObject res = new JSONObject();
        JSONArray array = loadIndexArray();
        JSONArray updated = new JSONArray();
        JSONObject deletedItem = null;
        File vaultDir = getVaultDir();

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            if (id.equals(obj.optString("id"))) {
                deletedItem = obj;
                String fileName = obj.optString("fileName");
                if (!fileName.isEmpty()) {
                    new File(vaultDir, fileName).delete();
                }
                JSONArray splits = obj.optJSONArray("splitFileNames");
                if (splits != null) {
                    for (int s = 0; s < splits.length(); s++) {
                        String sName = splits.optString(s);
                        if (!sName.isEmpty()) {
                            new File(vaultDir, sName).delete();
                        }
                    }
                }
            } else {
                updated.put(obj);
            }
        }

        try {
            if (deletedItem != null) {
                saveIndexArray(updated);
                res.put("success", true);
                res.put("message", "ລຶບໄຟລ໌ APK ອອກຈາກຄັງສຳເລັດແລ້ວ");
            } else {
                res.put("success", false);
                res.put("message", "ບໍ່ພົບລາຍການທີ່ຕ້ອງການລຶບ");
            }
        } catch (Exception ignored) {
        }
        return res;
    }

    public synchronized JSONObject updateVaultNoteAndPin(String id, String newNote, boolean pinned) {
        JSONObject res = new JSONObject();
        JSONArray array = loadIndexArray();
        boolean found = false;
        try {
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null && id.equals(obj.optString("id"))) {
                    obj.put("note", newNote != null ? newNote.trim() : "");
                    obj.put("pinned", pinned);
                    found = true;
                    break;
                }
            }
            if (found) {
                saveIndexArray(array);
                res.put("success", true);
                res.put("message", "ບັນທຶກຂໍ້ມູນສຳເລັດແລ້ວ");
            } else {
                res.put("success", false);
                res.put("message", "ບໍ່ພົບລາຍການ");
            }
        } catch (Exception ignored) {
        }
        return res;
    }

    /**
     * ບັນທຶກໄຟລ໌ລົງໂຟນເດີ Download (ຖ້າເປັນ Split APK ຈະລວມເປັນໄຟລ໌ຊຸດ .apks ໃຫ້ຄົບທຸກໄຟລ໌)
     */
    public JSONObject exportApkToDownloads(String id) {
        JSONObject res = new JSONObject();
        try {
            JSONObject item = findVaultItemById(id);
            List<File> allFiles = getAllApkFilesForVaultItem(id);
            if (item == null || allFiles.isEmpty()) {
                res.put("success", false);
                res.put("message", "ບໍ່ພົບໄຟລ໌ APK ໃນຄັງ");
                return res;
            }

            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File targetFolder = new File(downloadsDir, "LaoApkVault");
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }

            String safeName = item.optString("appName", "App")
                    .replaceAll("[^a-zA-Z0-9._ -]", "")
                    .trim()
                    .replace(" ", "_");
            if (safeName.isEmpty()) {
                safeName = item.optString("packageName", "app");
            }
            String versionName = item.optString("versionName", "1.0");

            if (allFiles.size() == 1) {
                File outFile = new File(targetFolder, safeName + "_v" + versionName + ".apk");
                copyFile(allFiles.get(0), outFile);
                res.put("success", true);
                res.put("message", "ສົ່ງອອກໄຟລ໌ໄປໄວ້ທີ່: Download/LaoApkVault/" + outFile.getName() + " ສຳເລັດແລ້ວ!");
                res.put("exportPath", outFile.getAbsolutePath());
            } else {
                File outBundle = new File(targetFolder, safeName + "_v" + versionName + ".apks");
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outBundle))) {
                    for (int i = 0; i < allFiles.size(); i++) {
                        File f = allFiles.get(i);
                        String entryName = (i == 0) ? "base.apk" : ("split_" + i + ".apk");
                        zos.putNextEntry(new ZipEntry(entryName));
                        try (InputStream in = new FileInputStream(f)) {
                            copyStream(in, zos);
                        }
                        zos.closeEntry();
                    }
                }
                res.put("success", true);
                res.put("message", "ສົ່ງອອກຊຸດ Split APK (" + allFiles.size() + " ໄຟລ໌) ໄປໄວ້ທີ່: Download/LaoApkVault/" + outBundle.getName());
                res.put("exportPath", outBundle.getAbsolutePath());
            }
            return res;
        } catch (Exception e) {
            try {
                res.put("success", false);
                res.put("message", "ສົ່ງອອກບໍ່ສຳເລັດ: " + e.getLocalizedMessage());
            } catch (Exception ignored) {
            }
            return res;
        }
    }

    @SuppressWarnings("deprecation")
    public PackageInfo getInstalledPackageInfo(String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
            } else {
                return pm.getPackageInfo(packageName, 0);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String drawableToBase64(Drawable drawable) {
        if (drawable == null) return "";
        try {
            int size = 96;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream);
            byte[] bytes = stream.toByteArray();
            return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        if (mb >= 1.0) {
            return String.format(Locale.US, "%.2f MB", mb);
        } else {
            return String.format(Locale.US, "%.0f KB", kb);
        }
    }

    private String formatTimestamp(long ts) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
            return sdf.format(new Date(ts));
        } catch (Exception e) {
            return "";
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[32768];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        out.flush();
    }
}
