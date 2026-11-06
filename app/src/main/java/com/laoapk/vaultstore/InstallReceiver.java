package com.laoapk.vaultstore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class InstallReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_COMMIT = "com.laoapk.vaultstore.ACTION_INSTALL_COMMIT";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String pkgName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirmIntent);
                } catch (Exception e) {
                    Toast.makeText(
                            context,
                            "ບໍ່ສາມາດເປີດໜ້າຢືນຢັນການຕິດຕັ້ງໄດ້: " + e.getLocalizedMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(
                    context,
                    "✅ ຕິດຕັ້ງແອັບເວີຊັນເກົ່າສຳເລັດຮຽບຮ້ອຍແລ້ວ!",
                    Toast.LENGTH_LONG
            ).show();
        } else if (status != -999) {
            String detailLao;
            switch (status) {
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    detailLao = "ຍົກເລີກການຕິດຕັ້ງແລ້ວ";
                    break;
                case PackageInstaller.STATUS_FAILURE_BLOCKED:
                    detailLao = "ການຕິດຕັ້ງຖືກບລັອກໂດຍລະບົບໂທລະສັບ";
                    break;
                case PackageInstaller.STATUS_FAILURE_CONFLICT:
                    detailLao = "ມີແອັບເວີຊັນອື່ນທີ່ຂັດແຍ່ງກັນຢູ່ໃນເຄື່ອງ (ກະລຸນາຖອນໂຕເກົ່າອອກກ່ອນ)";
                    break;
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    detailLao = "ໄຟລ໌ APK ບໍ່ຄົບຊຸດ Split APK ຫຼື ບໍ່ຮອງຮັບເຄື່ອງນີ້ (ກະລຸນາດຶງແອັບຈາກເຄື່ອງໃໝ່ອີກຄັ້ງ)";
                    break;
                case PackageInstaller.STATUS_FAILURE_INVALID:
                    detailLao = "ໄຟລ໌ APK ບໍ່ສົມບູນ ຫຼື ເສຍຫາຍ";
                    break;
                case PackageInstaller.STATUS_FAILURE_STORAGE:
                    detailLao = "ພື້ນທີ່ຄວາມຈຳໂທລະສັບບໍ່ພຽງພໍ";
                    break;
                default:
                    detailLao = "ຕິດຕັ້ງບໍ່ສຳເລັດ (ລະຫັດ: " + status + ")";
                    break;
            }
            if (message != null && !message.isEmpty()) {
                detailLao += "\n(" + message + ")";
            }
            Toast.makeText(context, "❌ " + detailLao, Toast.LENGTH_LONG).show();
        }
    }
}
