// LKR is an optional display estimate. No payment/form values are converted.
document.addEventListener('DOMContentLoaded', () => {
    const controls = [...document.querySelectorAll('[data-lkr-toggle]')];
    if (!controls.length) return;
    const preferenceKey = 'travelgo.lkr-estimates';
    const apply = enabled => {
        document.documentElement.dataset.lkrEstimates = String(enabled);
        controls.forEach(input => { input.checked = enabled; });
    };
    let saved = false;
    try { saved = localStorage.getItem(preferenceKey) === 'true'; } catch (_) { /* Storage may be disabled. */ }
    apply(saved);
    controls.forEach(input => input.addEventListener('change', () => {
        apply(input.checked);
        try { localStorage.setItem(preferenceKey, String(input.checked)); } catch (_) { /* Keep the current page usable. */ }
    }));
    window.addEventListener('storage', event => { if (event.key === preferenceKey) apply(event.newValue === 'true'); });
    const updateTotal = value => {
        const target = document.querySelector('[data-lkr-booking-total]');
        const rate = Number(target?.dataset.lkrRate);
        const usd = value === null || value === '' || value === undefined ? NaN : Number(value);
        if (!target) return;
        if (!Number.isFinite(usd) || usd < 0 || !Number.isFinite(rate) || rate <= 0) { target.textContent = ''; return; }
        target.textContent = '≈ LKR ' + new Intl.NumberFormat('en-US', {minimumFractionDigits:2, maximumFractionDigits:2}).format(usd * rate);
    };
    document.addEventListener('travelgo:estimate-total', event => updateTotal(event.detail?.usd));
    const total = document.getElementById('displayTotal');
    if (total) updateTotal(total.dataset.usdAmount);
});
