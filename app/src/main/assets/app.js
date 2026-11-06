let vaultItems = [];
let installedApps = [];
let currentFilter = 'ALL';
let currentEditingVaultId = null;
let currentEditingPinned = true;

function showToast(msg) {
    const box = document.getElementById('toastBox');
    const txt = document.getElementById('toastText');
    txt.textContent = msg;
    box.classList.add('show');
    clearTimeout(window._toastTimer);
    window._toastTimer = setTimeout(() => {
        box.classList.remove('show');
    }, 4200);
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function checkPermissionBanner() {
    if (window.AndroidBridge && window.AndroidBridge.canInstallUnknownApps) {
        const allowed = window.AndroidBridge.canInstallUnknownApps();
        document.getElementById('permissionBanner').style.display = allowed ? 'none' : 'flex';
    }
}

function requestInstallPerm() {
    if (window.AndroidBridge && window.AndroidBridge.requestInstallPermission) {
        window.AndroidBridge.requestInstallPermission();
    }
}

function switchTab(tabName) {
    document.querySelectorAll('.tab-panel').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));

    const panel = document.getElementById('tab-' + tabName);
    const nav = document.getElementById('nav-' + tabName);
    if (panel) panel.classList.add('active');
    if (nav) nav.classList.add('active');

    if (tabName === 'vault') {
        loadVaultData();
    } else if (tabName === 'backup') {
        loadInstalledApps(false);
    }
}

function setVaultFilter(filter, btnEl) {
    currentFilter = filter;
    document.querySelectorAll('.filter-pills .pill').forEach(el => el.classList.remove('active'));
    if (btnEl) btnEl.classList.add('active');
    renderVaultList();
}

function loadVaultData() {
    checkPermissionBanner();
    if (!window.AndroidBridge) return;
    try {
        const raw = window.AndroidBridge.getVaultData();
        const data = JSON.parse(raw);
        vaultItems = data.items || [];

        document.getElementById('statUniqueApps').textContent = data.uniqueApps || 0;
        document.getElementById('statTotalVersions').textContent = data.totalVersions || 0;
        document.getElementById('statAutoUpdated').textContent = data.autoUpdatedCount || 0;
        document.getElementById('statStorage').textContent = data.totalStorageText || '0 MB';

        const autoBanner = document.getElementById('autoUpdateBanner');
        if (data.autoUpdatedCount > 0) {
            autoBanner.style.display = 'flex';
            document.getElementById('autoUpdateBannerDesc').textContent =
                `ພົບ ${data.autoUpdatedCount} ແອັບໃນເຄື່ອງຖືກອັບເດດເປັນເວີຊັນໃໝ່! ກົດປຸ່ມ "ກັບຄືນໃຊ້ເວີຊັນເກົ່ານີ້" ຢູ່ລຸ່ມນີ້ເພື່ອກູ້ຄືນເວີຊັນເກົ່າທັນທີ.`;
        } else {
            autoBanner.style.display = 'none';
        }

        renderVaultList();
    } catch (e) {
        console.error(e);
    }
}

function renderVaultList() {
    const container = document.getElementById('vaultListContainer');
    const query = (document.getElementById('vaultSearchInput').value || '').trim().toLowerCase();

    const filtered = vaultItems.filter(item => {
        if (currentFilter !== 'ALL' && item.status !== currentFilter) {
            return false;
        }
        if (!query) return true;
        const name = (item.appName || '').toLowerCase();
        const pkg = (item.packageName || '').toLowerCase();
        const ver = (item.versionName || '').toLowerCase();
        const note = (item.note || '').toLowerCase();
        return name.includes(query) || pkg.includes(query) || ver.includes(query) || note.includes(query);
    });

    if (filtered.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-icon">📦</div>
                <div class="empty-title">ຍັງບໍ່ມີແອັບໃນໝວດນີ້</div>
                <div class="empty-desc">
                    ທ່ານສາມາດກົດ <b>"ດຶງຈາກແອັບໃນເຄື່ອງ"</b> ເພື່ອສຳຮອງແອັບທີ່ກຳລັງໃຊ້ຢູ່ ຫຼື ກົດ <b>"ນຳເຂົ້າໄຟລ໌ .APK"</b> ຈາກໂທລະສັບມາເກັບໄວ້ໃນຄັງນີ້ໄດ້ເລີຍ!
                </div>
                <button class="btn btn-primary" onclick="switchTab('backup')">
                    📲 ດຶງເກັບແອັບຈາກໃນເຄື່ອງດຽວນີ້
                </button>
            </div>
        `;
        return;
    }

    container.innerHTML = filtered.map(item => {
        const iconHtml = item.iconBase64
            ? `<img class="apk-icon" src="${item.iconBase64}" alt="icon" />`
            : `<div class="apk-icon-placeholder">${escapeHtml((item.appName || 'A').charAt(0))}</div>`;

        const isAutoUpdated = item.status === 'AUTO_UPDATED';
        const mainActionBtn = isAutoUpdated
            ? `<button class="btn btn-warning" onclick="handleInstallClick('${item.id}')">
                   🔄 ກັບຄືນໃຊ້ເວີຊັນເກົ່ານີ້ (v${escapeHtml(item.versionName)})
               </button>`
            : `<button class="btn btn-primary" onclick="handleInstallClick('${item.id}')">
                   📲 ຕິດຕັ້ງເວີຊັນນີ້ (v${escapeHtml(item.versionName)})
               </button>`;

        let splitBadge = '';
        if (item.hasSplits) {
            if (item.splitCount > 0) {
                splitBadge = `<span class="meta-tag" style="color:#34D399;background:rgba(16,185,129,0.16);">✅ ຄົບຊຸດ Split APK (${item.splitCount + 1} ໄຟລ໌)</span>`;
            } else {
                splitBadge = `<span class="meta-tag" style="color:#FBBF24;background:rgba(245,158,11,0.18);">⚠️ ຂາດໄຟລ໌ຍ່ອຍ Split APK (ກະລຸນາດຶງຈາກເຄື່ອງໃໝ່)</span>`;
            }
        } else {
            splitBadge = `<span class="meta-tag" style="color:#34D399;">✅ APK ດ່ຽວສົມບູນ</span>`;
        }

        return `
            <div class="apk-card status-${item.status}">
                <div class="apk-card-top">
                    ${iconHtml}
                    <div class="apk-info">
                        <div class="apk-title-row">
                            <div class="apk-name">${escapeHtml(item.appName)}</div>
                            <span class="pin-badge">🔒 ເວີຊັນທີ່ເກັບໄວ້</span>
                        </div>
                        <div class="apk-pkg">${escapeHtml(item.packageName)}</div>
                        <div class="apk-meta-tags">
                            <span class="meta-tag version">ເວີຊັນເກົ່າ: v${escapeHtml(item.versionName)} (${item.versionCode})</span>
                            <span class="meta-tag">💾 ${escapeHtml(item.fileSizeText)}</span>
                            <span class="meta-tag">📅 ${escapeHtml(item.savedDateText)}</span>
                            ${splitBadge}
                        </div>
                    </div>
                </div>

                <div class="status-strip ${item.status}">
                    <span>${escapeHtml(item.statusLabelLao)}</span>
                    ${item.isInstalledOnPhone ? `<span>ໃນເຄື່ອງ: v${escapeHtml(item.installedVersionName)}</span>` : ''}
                </div>

                ${item.note ? `<div class="note-box">📝 <b>ໝາຍເຫດ:</b> ${escapeHtml(item.note)}</div>` : ''}

                <div class="card-actions">
                    ${mainActionBtn}
                </div>
                <div class="card-actions" style="margin-top: 8px;">
                    <button class="btn btn-secondary" onclick="openPlayStoreForPkg('${escapeHtml(item.packageName)}')">
                        🛡️ ບລັອກອັບເດດ Play Store
                    </button>
                    <button class="btn btn-secondary" onclick="exportApkItem('${item.id}')">
                        📥 ບັນທຶກລົງ Download
                    </button>
                    <button class="btn btn-secondary" onclick="shareApkItem('${item.id}')">
                        📤 ແຊຣ໌
                    </button>
                    <button class="btn btn-secondary" onclick="openEditNoteModal('${item.id}')">
                        ✏️ ໝາຍເຫດ
                    </button>
                    <button class="btn btn-danger" onclick="deleteApkItem('${item.id}', '${escapeHtml(item.appName)}')">
                        🗑️ ລຶບ
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function handleInstallClick(vaultId) {
    if (!window.AndroidBridge) return;
    const raw = window.AndroidBridge.installVaultApk(vaultId);
    const res = JSON.parse(raw);

    if (res.needDowngradeUninstall) {
        openDowngradeModal(vaultId, res.message);
        return;
    }

    if (res.missingSplitsError) {
        alert(res.message);
        return;
    }

    if (res.message) {
        showToast(res.message);
    }
}

function openDowngradeModal(vaultId, message) {
    const modal = document.getElementById('downgradeModal');
    document.getElementById('downgradeModalText').textContent = message;

    document.getElementById('btnStartDowngrade').onclick = function () {
        closeModals();
        const raw = window.AndroidBridge.startDowngradeFlow(vaultId);
        const res = JSON.parse(raw);
        if (res.message) showToast(res.message);
    };

    document.getElementById('btnForceInstall').onclick = function () {
        closeModals();
        const raw = window.AndroidBridge.forceInstallVaultApk(vaultId);
        const res = JSON.parse(raw);
        if (res.message) showToast(res.message);
    };

    modal.classList.add('open');
}

function openEditNoteModal(vaultId) {
    const item = vaultItems.find(x => x.id === vaultId);
    if (!item) return;
    currentEditingVaultId = vaultId;
    currentEditingPinned = !!item.pinned;
    document.getElementById('editNoteInput').value = item.note || '';
    document.getElementById('noteModal').classList.add('open');
}

function saveEditedNote() {
    if (!currentEditingVaultId || !window.AndroidBridge) return;
    const newNote = document.getElementById('editNoteInput').value || '';
    const raw = window.AndroidBridge.updateNoteAndPin(currentEditingVaultId, newNote, currentEditingPinned);
    const res = JSON.parse(raw);
    closeModals();
    showToast(res.message || 'ບັນທຶກສຳເລັດ');
    loadVaultData();
}

function closeModals() {
    document.querySelectorAll('.modal-backdrop').forEach(el => el.classList.remove('open'));
}

function deleteApkItem(vaultId, appName) {
    if (!confirm(`ທ່ານຕ້ອງການລຶບໄຟລ໌ APK ຂອງແອັບ "${appName}" ອອກຈາກຄັງແທ້ບໍ່?`)) {
        return;
    }
    const raw = window.AndroidBridge.deleteVaultItem(vaultId);
    const res = JSON.parse(raw);
    showToast(res.message || 'ລຶບສຳເລັດແລ້ວ');
    loadVaultData();
}

function exportApkItem(vaultId) {
    const raw = window.AndroidBridge.exportToDownloads(vaultId);
    const res = JSON.parse(raw);
    showToast(res.message || '');
}

function shareApkItem(vaultId) {
    const raw = window.AndroidBridge.shareVaultApk(vaultId);
    const res = JSON.parse(raw);
    if (!res.success && res.message) {
        showToast(res.message);
    }
}

function openPlayStoreForPkg(pkgName) {
    showToast('ເມື່ອເຂົ້າ Play Store ແລ້ວ ໃຫ້ກົດປຸ່ມ (⋮) ມຸມຂວາເທິງ ແລ້ວຕິກອອກ "ອັບເດດອັດຕະໂນມັດ"');
    setTimeout(() => {
        if (window.AndroidBridge) {
            window.AndroidBridge.openPlayStore(pkgName);
        }
    }, 600);
}

function triggerPickApkFile() {
    if (!window.AndroidBridge) return;
    const note = document.getElementById('importNoteInput').value || '';
    window.AndroidBridge.pickApkFile(note);
}

window.onApkImported = function (res) {
    if (res && res.message) {
        showToast(res.message);
    }
    if (res && res.success) {
        document.getElementById('importNoteInput').value = '';
        switchTab('vault');
    }
};

let installedLoadedOnce = false;
function loadInstalledApps(forceReload) {
    if (!window.AndroidBridge) return;
    const showSystem = document.getElementById('showSystemCheck').checked;
    if (installedLoadedOnce && !forceReload && installedApps.length > 0) {
        renderInstalledList();
        return;
    }
    const container = document.getElementById('installedListContainer');
    container.innerHTML = `<div class="empty-state"><div class="empty-title">⏳ ກຳລັງອ່ານລາຍຊື່ແອັບໃນໂທລະສັບ...</div></div>`;

    setTimeout(() => {
        try {
            const raw = window.AndroidBridge.getInstalledApps(showSystem);
            installedApps = JSON.parse(raw) || [];
            installedLoadedOnce = true;
            renderInstalledList();
        } catch (e) {
            container.innerHTML = `<div class="empty-state"><div class="empty-title">ບໍ່ສາມາດໂຫຼດລາຍຊື່ແອັບໄດ້</div></div>`;
        }
    }, 50);
}

function renderInstalledList() {
    const container = document.getElementById('installedListContainer');
    const query = (document.getElementById('installedSearchInput').value || '').trim().toLowerCase();

    const filtered = installedApps.filter(app => {
        if (!query) return true;
        return (app.appName || '').toLowerCase().includes(query) ||
               (app.packageName || '').toLowerCase().includes(query);
    });

    if (filtered.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-title">ບໍ່ພົບແອັບທີ່ຄົ້ນຫາ</div>
            </div>
        `;
        return;
    }

    container.innerHTML = filtered.map(app => {
        const iconHtml = app.iconBase64
            ? `<img class="apk-icon" src="${app.iconBase64}" alt="icon" />`
            : `<div class="apk-icon-placeholder">${escapeHtml((app.appName || 'A').charAt(0))}</div>`;

        const savedBadge = app.isExactVersionSaved
            ? `<span class="pin-badge">✅ ເກັບຄົບຊຸດແລ້ວ</span>`
            : `<span class="meta-tag">ຍັງບໍ່ທັນເກັບ</span>`;

        const splitInfoBadge = app.hasSplits
            ? `<span class="meta-tag" style="color:#60A5FA;">📦 ຊຸດ Split APK (${(app.splitCount || 0) + 1} ໄຟລ໌)</span>`
            : `<span class="meta-tag">APK ດ່ຽວ</span>`;

        return `
            <div class="apk-card">
                <div class="apk-card-top">
                    ${iconHtml}
                    <div class="apk-info">
                        <div class="apk-title-row">
                            <div class="apk-name">${escapeHtml(app.appName)}</div>
                            ${savedBadge}
                        </div>
                        <div class="apk-pkg">${escapeHtml(app.packageName)}</div>
                        <div class="apk-meta-tags">
                            <span class="meta-tag version">v${escapeHtml(app.versionName)} (${app.versionCode})</span>
                            <span class="meta-tag">💾 ${escapeHtml(app.fileSizeText)}</span>
                            ${splitInfoBadge}
                        </div>
                    </div>
                </div>
                <div class="card-actions">
                    <button class="btn btn-primary" onclick="backupInstalledPkg('${escapeHtml(app.packageName)}', '${escapeHtml(app.appName)}', '${escapeHtml(app.versionName)}')">
                        📥 ເກັບເວີຊັນນີ້ໄວ້ໃນຄັງຄົບຊຸດ (v${escapeHtml(app.versionName)})
                    </button>
                    <button class="btn btn-secondary" onclick="openPlayStoreForPkg('${escapeHtml(app.packageName)}')">
                        🛡️ ປິດອັບເດດ
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function backupInstalledPkg(pkgName, appName, verName) {
    if (!window.AndroidBridge) return;
    showToast(`⏳ ກຳລັງດຶງໄຟລ໌ APK ທັງໝົດຂອງ "${appName}" ເຂົ້າຄັງ...`);
    setTimeout(() => {
        const raw = window.AndroidBridge.backupApp(pkgName, '');
        const res = JSON.parse(raw);
        showToast(res.message || 'ສຳຮອງແອັບສຳເລັດ');
        if (res.success) {
            installedLoadedOnce = false;
            loadVaultData();
            switchTab('vault');
        }
    }, 60);
}

function refreshAll() {
    installedLoadedOnce = false;
    loadVaultData();
    showToast('🔄 ກວດສອບ ແລະ ອັບເດດຂໍ້ມູນຄັງແອັບຮຽບຮ້ອຍແລ້ວ');
}

window.onNativeResume = function () {
    loadVaultData();
};

window.handleBackNavigation = function () {
    const openModal = document.querySelector('.modal-backdrop.open');
    if (openModal) {
        closeModals();
        return true;
    }
    return false;
};

document.addEventListener('DOMContentLoaded', () => {
    loadVaultData();
});
