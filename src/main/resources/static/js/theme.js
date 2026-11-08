/* TravelGO uses light mode on every page, before styles render. */
(function () {
    'use strict';
    const root = document.documentElement;
    // Reserve the compact header before the first paint. If the navigation
    // script cannot load, restore the usable inline links on window load.
    root.classList.add('nav-js');
    window.addEventListener('load', function () {
        if (!root.classList.contains('nav-ready')) root.classList.remove('nav-js');
    }, {once: true});
    root.dataset.theme = 'light';
    root.style.colorScheme = 'light';
    try {
        // Replace older saved dark preferences, including in already open tabs.
        localStorage.setItem('theme', 'light');
    } catch (_) { /* Light mode still applies when browser storage is unavailable. */ }
}());
