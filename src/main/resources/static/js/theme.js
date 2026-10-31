/* TravelGO uses light mode on every page, before styles render. */
(function () {
    'use strict';
    const root = document.documentElement;
    root.dataset.theme = 'light';
    root.style.colorScheme = 'light';
    try {
        // Replace older saved dark preferences, including in already open tabs.
        localStorage.setItem('theme', 'light');
    } catch (_) { /* Light mode still applies when browser storage is unavailable. */ }
}());
