/**
 * Theme Switcher for IB-Viewer
 * Handles light/dark theme switching with localStorage persistence
 */
(function() {
    'use strict';

    const THEME_KEY = 'ib-viewer-theme';
    const THEME_LIGHT = 'light';
    const THEME_DARK = 'dark';

    /**
     * Gets the initial theme based on priority:
     * 1. User preference from localStorage
     * 2. System preference (prefers-color-scheme)
     * 3. Default to light theme
     */
    function getInitialTheme() {
        try {
            const storedTheme = localStorage.getItem(THEME_KEY);
            if (storedTheme === THEME_LIGHT || storedTheme === THEME_DARK) {
                return storedTheme;
            }
        } catch (e) {
            // localStorage might be disabled or throw errors
            console.warn('localStorage not available:', e);
        }

        // Check system preference
        if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
            return THEME_DARK;
        }

        return THEME_LIGHT;
    }

    /**
     * Applies the theme to the document
     */
    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        updateToggleButton(theme);
    }

    /**
     * Updates the toggle button's aria-label for accessibility
     */
    function updateToggleButton(theme) {
        const toggleButton = document.getElementById('theme-toggle');
        if (toggleButton) {
            const label = theme === THEME_LIGHT
                ? 'Switch to dark theme'
                : 'Switch to light theme';
            toggleButton.setAttribute('aria-label', label);
        }
    }

    /**
     * Toggles between light and dark themes
     */
    function toggleTheme() {
        const currentTheme = document.documentElement.getAttribute('data-theme') || THEME_LIGHT;
        const newTheme = currentTheme === THEME_LIGHT ? THEME_DARK : THEME_LIGHT;

        applyTheme(newTheme);

        // Persist to localStorage
        try {
            localStorage.setItem(THEME_KEY, newTheme);
        } catch (e) {
            console.warn('Could not save theme preference:', e);
        }
    }

    /**
     * Initialize theme on page load
     */
    function init() {
        const initialTheme = getInitialTheme();
        applyTheme(initialTheme);

        // Set up event listener for theme toggle button
        const toggleButton = document.getElementById('theme-toggle');
        if (toggleButton) {
            toggleButton.addEventListener('click', toggleTheme);

            // Support keyboard interaction
            toggleButton.addEventListener('keydown', function(event) {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    toggleTheme();
                }
            });
        }

        // Listen for system theme changes (optional enhancement)
        if (window.matchMedia) {
            const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');

            // Only update if user hasn't set a preference
            mediaQuery.addEventListener('change', function(e) {
                try {
                    const hasStoredPreference = localStorage.getItem(THEME_KEY);
                    if (!hasStoredPreference) {
                        const newTheme = e.matches ? THEME_DARK : THEME_LIGHT;
                        applyTheme(newTheme);
                    }
                } catch (err) {
                    // Ignore errors
                }
            });
        }
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
