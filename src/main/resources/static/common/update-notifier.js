(function () {
    'use strict';

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
                if (!confirm('Apply update and restart the app?')) return;
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
