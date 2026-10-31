/* Local demonstration only. Card fields deliberately have no names and never
   enter FormData, fetch, storage, logging or a third-party payment request. */
(() => {
    'use strict';
    const selector = '[data-demo-number],[data-demo-expiry],[data-demo-code]';
    const clearFields = () => document.querySelectorAll('[data-demo-card]').forEach(fields => {
        fields.querySelectorAll('[data-demo-holder],[data-demo-number],[data-demo-expiry],[data-demo-code]').forEach(input => {
            input.value = '';
            input.disabled = false;
        });
        fields.querySelector('[data-demo-result]').value = '';
    });
    window.addEventListener('pagehide', clearFields);
    window.addEventListener('pageshow', event => {
        if (!event.persisted) return;
        clearFields();
        document.querySelectorAll('form[data-provider="simulated"] .pay-submit-btn').forEach(button => {
            button.disabled = false;
            button.textContent = 'Record test payment';
            button.style.removeProperty('pointer-events');
            button.style.removeProperty('opacity');
        });
    });
    document.addEventListener('input', event => {
        const input = event.target;
        if (!(input instanceof HTMLInputElement) || !input.matches(selector)) return;
        const digits = input.value.replace(/\D/g, '');
        if (input.hasAttribute('data-demo-number')) input.value = digits.slice(0, 16).replace(/(.{4})(?=.)/g, '$1 ');
        else if (input.hasAttribute('data-demo-expiry')) input.value = digits.slice(0, 4).replace(/^(\d{2})(\d)/, '$1/$2');
        else input.value = digits.slice(0, 3);
    });
    document.addEventListener('click', event => {
        const fill = event.target.closest('[data-demo-fill]');
        if (!fill) return;
        const fields = fill.closest('[data-demo-card]');
        const future = new Date().getFullYear() + 2;
        fields.querySelector('[data-demo-holder]').value = 'Showcase Customer';
        fields.querySelector('[data-demo-number]').value = '4242 4242 4242 4242';
        fields.querySelector('[data-demo-expiry]').value = '12/' + String(future).slice(-2);
        fields.querySelector('[data-demo-code]').value = '123';
        fields.querySelector('[data-demo-error]').hidden = true;
        fields.querySelectorAll('[aria-invalid]').forEach(input => input.removeAttribute('aria-invalid'));
    });
    window.TravelGODemoCard = Object.freeze({
        prepare(form) {
            const fields = form.querySelector('[data-demo-card]');
            if (!fields) return false;
            const holder = fields.querySelector('[data-demo-holder]');
            const number = fields.querySelector('[data-demo-number]');
            const expiry = fields.querySelector('[data-demo-expiry]');
            const code = fields.querySelector('[data-demo-code]');
            const result = fields.querySelector('[data-demo-result]');
            const error = fields.querySelector('[data-demo-error]');
            result.value = '';
            fields.querySelectorAll('[aria-invalid]').forEach(input => input.removeAttribute('aria-invalid'));
            const invalid = (input, message) => {
                input.setAttribute('aria-invalid', 'true');
                error.textContent = message;
                error.hidden = false;
                input.focus();
                return false;
            };
            if (holder.value.trim().length < 2) return invalid(holder, 'Enter a demo cardholder name.');
            if (number.value.replace(/\s/g, '') !== '4242424242424242') return invalid(number, 'Use only the displayed demo number 4242 4242 4242 4242. Real cards are not accepted.');
            const parts = /^(0[1-9]|1[0-2])\/(\d{2})$/.exec(expiry.value);
            const now = new Date();
            if (!parts || Number(parts[2]) + 2000 < now.getFullYear() ||
                (Number(parts[2]) + 2000 === now.getFullYear() && Number(parts[1]) < now.getMonth() + 1))
                return invalid(expiry, 'Enter a current or future demo expiry in MM/YY format.');
            if (code.value !== '123') return invalid(code, 'Use the demo security code 123.');
            const outcome = fields.querySelector('[data-demo-outcome]').value;
            if (!['APPROVED', 'DECLINED'].includes(outcome)) return invalid(fields.querySelector('[data-demo-outcome]'), 'Choose a simulated result.');
            result.value = outcome;
            // Clear sensitive-looking fields before the native form submission.
            for (const input of [holder, number, expiry, code]) { input.value = ''; input.disabled = true; }
            error.hidden = true;
            return true;
        }
    });
})();
