// ── theme.js — Theme init and toggle, no dependencies ────────────────────────

// Apply theme immediately (before DOM renders) to avoid flash of wrong theme
(function () {
    const stored = localStorage.getItem('portfolio-helper-theme') || localStorage.getItem('ib-viewer-theme');
    const theme = stored || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', theme);
})();

function initThemeToggle() {
    const themeToggle = document.getElementById('theme-toggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', () => {
            const current = document.documentElement.getAttribute('data-theme');
            const next = current === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('portfolio-helper-theme', next);
        });
    }
}
