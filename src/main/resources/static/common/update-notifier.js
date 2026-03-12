(function () {
    'use strict';

    // ── Custom confirm overlay (replaces native confirm()) ────────────────────
    // Styles live in styles.css under "Custom Confirm Overlay"
    function showConfirmOverlay(message) {
        return new Promise((resolve) => {
            const backdrop = document.createElement('div');
            backdrop.id = 'confirm-overlay-backdrop';
            backdrop.innerHTML = `
                <div id="confirm-overlay-box">
                    <div id="confirm-overlay-icon">⚡</div>
                    <p id="confirm-overlay-message">${message}</p>
                    <div id="confirm-overlay-actions">
                        <button class="confirm-overlay-btn" id="confirm-overlay-cancel">Cancel</button>
                        <button class="confirm-overlay-btn" id="confirm-overlay-ok">Apply &amp; Restart</button>
                    </div>
                </div>
            `;
            document.body.appendChild(backdrop);

            function dismiss(result) {
                backdrop.remove();
                resolve(result);
            }

            document.getElementById('confirm-overlay-ok').addEventListener('click', () => dismiss(true));
            document.getElementById('confirm-overlay-cancel').addEventListener('click', () => dismiss(false));
            backdrop.addEventListener('click', e => { if (e.target === backdrop) dismiss(false); });
            document.addEventListener('keydown', function onKey(e) {
                if (e.key === 'Escape') { dismiss(false); document.removeEventListener('keydown', onKey); }
                if (e.key === 'Enter')  { dismiss(true);  document.removeEventListener('keydown', onKey); }
            });
        });
    }
    // ─────────────────────────────────────────────────────────────────────────

    function attemptReconnect() {
        if (typeof window.attemptReconnect === 'function' && window.attemptReconnect !== attemptReconnect) {
            window.attemptReconnect();
            return;
        }
        fetch('/').then(r => {
            if (r.ok) location.reload();
            else setTimeout(attemptReconnect, 1000);
        }).catch(() => setTimeout(attemptReconnect, 1000));
    }
    if (!window.attemptReconnect) window.attemptReconnect = attemptReconnect;

    function updateHeaderBadges(info) {
        const availTag = document.getElementById('header-update-available');
        const dot      = document.getElementById('header-update-dot');
        const readyTag = document.getElementById('header-update-ready');
        if (!availTag && !dot && !readyTag) return;

        const phase = info?.download?.phase || 'IDLE';
        const autoDownloads = info?.isJpackageInstall && info?.autoUpdate;

        const showAvail = info?.hasUpdate && !autoDownloads && phase === 'IDLE';
        const showDot   = info?.hasUpdate && autoDownloads && phase !== 'READY' && phase !== 'APPLYING';
        const showReady = phase === 'READY' || phase === 'APPLYING';

        if (availTag) {
            availTag.hidden = !showAvail;
            if (showAvail && info.latestVersion) {
                availTag.title = 'Update available: v' + info.latestVersion + ' — go to Settings';
            }
        }
        if (dot) {
            dot.hidden = !showDot;
            if (showDot) {
                dot.title = phase === 'DOWNLOADING'
                    ? 'Downloading update…'
                    : (info.latestVersion ? 'Update available: v' + info.latestVersion : 'Update available');
            }
        }
        if (readyTag) {
            readyTag.hidden = !showReady;
            if (showReady && info.latestVersion) {
                readyTag.title = 'Update v' + info.latestVersion + ' ready — click to apply';
            }
        }
    }

    function poll() {
        fetch('/api/admin/update-info')
            .then(r => r.json())
            .then(updateHeaderBadges)
            .catch(() => {});
    }

    document.addEventListener('DOMContentLoaded', () => {
        poll();
        setInterval(poll, 15000);

        const readyTag = document.getElementById('header-update-ready');
        if (readyTag) {
            readyTag.addEventListener('click', async () => {
                const confirmed = await showConfirmOverlay('Apply update and restart the app?');
                if (!confirmed) return;
                readyTag.style.pointerEvents = 'none';
                readyTag.textContent = 'Restarting…';
                try {
                    await fetch('/api/admin/apply-update', { method: 'POST' });
                } catch (_) {}
                setTimeout(attemptReconnect, 2000);
            });
        }
    });
})();
