/* Fixed UI actions: text from records is data, never executable event-handler code. */
(() => {
    'use strict';
    const noArguments = new Set([
        'closeAddCatModal', 'closeAddDestinationModal', 'closeAddHotelModal', 'closeAddTransportModal',
        'closeConsultantPackageModal', 'closeDeclineModal', 'closeEditCatModal', 'closeEditDestinationModal',
        'closeEditHotelModal', 'closeEditPackageModal', 'closeEditTransportModal', 'closeInfoModal',
        'closeManageDeparturesModal', 'closeRecordModal', 'closeReplyModal', 'closeSlidePanel', 'closeUpdateModal',
        'deleteDestination', 'deletePackage', 'filterBookings', 'filterDestinations', 'filterHotels',
        'filterInquiries', 'filterPackages', 'filterTransports', 'openAddCatModal', 'openAddDestinationModal',
        'openAddHotelModal', 'openAddTransportModal', 'openConsultantPackageModal', 'openRecordModal',
        'updateSettlementRequirement'
    ]);
    const positiveId = value => /^[1-9]\d*$/.test(value || '') && Number.isSafeInteger(Number(value))
        ? Number(value) : null;
    const invoke = (name, args) => {
        // Call sites below and the fixed no-argument list define the complete vocabulary.
        if (typeof window[name] === 'function') window[name](...args);
    };
    const act = (name, element, event) => {
        const d = element.dataset;
        if (noArguments.has(name)) { invoke(name, []); return; }
        switch (name) {
            case 'close-dialog': element.closest('dialog')?.close(); break;
            case 'setBookingStatusFilter':
                if (['all', 'PROCESSING', 'CONFIRMED', 'COMPLETED', 'CANCELLED'].includes(d.uiStatus))
                    invoke(name, [d.uiStatus, element]);
                break;
            case 'setInquiryStatusFilter':
                if (['all', 'OPEN', 'RESOLVED'].includes(d.uiStatus)) invoke(name, [d.uiStatus, element]);
                break;
            case 'openSlidePanel':
                if (['DESTINATION', 'HOTEL'].includes(d.uiKind)) invoke(name, [d.uiKind]);
                break;
            case 'loadPackageEditor':
                if (positiveId(d.packageId)) invoke(name, [positiveId(d.packageId), element]);
                break;
            case 'openInfoModal': case 'openDeclineModal': case 'openManageDeparturesModal': case 'recalcTotal':
                if (positiveId(d.uiId)) invoke(name, [positiveId(d.uiId)]);
                break;
            case 'openUpdateModal':
                if (positiveId(d.id)) invoke(name, [d.id, d.ref, d.status, d.type, d.amount]);
                break;
            case 'openEditCatModal':
                if (positiveId(d.uiId)) invoke(name, [positiveId(d.uiId), d.uiName, d.uiDescription]);
                break;
            case 'openEditDestinationModal':
                if (positiveId(d.uiId)) invoke(name, [positiveId(d.uiId), d.uiCountry, d.uiCity, d.uiDescription, d.uiImage]);
                break;
            case 'openEditHotelModal':
                if (positiveId(d.uiId)) invoke(name, [positiveId(d.uiId), d.uiName,
                    positiveId(d.uiDestination) || 0, d.uiAddress,
                    d.uiStars === '' ? null : Number(d.uiStars), d.uiPrice, d.uiImage, d.uiDescription]);
                break;
            case 'openEditTransportModal':
                if (positiveId(d.uiId)) invoke(name, [positiveId(d.uiId), d.uiName,
                    positiveId(d.uiDestination) || 0, d.uiMode, d.uiDeparture, d.uiArrival, d.uiPrice, d.uiDescription]);
                break;
            case 'openReplyModal':
                if (positiveId(d.uiId)) invoke(name, [positiveId(d.uiId), d.uiName, d.uiEmail, d.uiSubject, d.uiMessage]);
                break;
            case 'handlePaymentSubmit':
                if (typeof window.handlePaymentSubmit !== 'function' || window.handlePaymentSubmit(element) === false)
                    event.preventDefault();
                break;
            case 'submitSlidePanel': case 'submitDepartureForm':
                // Prevent native submission even if a page's custom script failed to load.
                event.preventDefault();
                invoke(name, [event]);
                break;
        }
    };
    for (const eventName of ['click', 'keyup', 'change', 'input', 'submit']) {
        document.addEventListener(eventName, event => {
            if (!(event.target instanceof Element)) return;
            const element = event.target.closest(`[data-ui-${eventName}],[data-confirm-${eventName}]`);
            if (!element || (eventName === 'submit' && element !== event.target)) return;
            const confirmText = element.getAttribute(`data-confirm-${eventName}`);
            if (confirmText != null) {
                const suffix = element.dataset.confirmName === 'true' ? (element.dataset.name || '') + '?' : '';
                if (!window.confirm(confirmText + suffix)) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    return;
                }
            }
            if (!event.defaultPrevented) act(element.getAttribute(`data-ui-${eventName}`), element, event);
        }, true);
    }

    const fallback = image => {
        if (!(image instanceof HTMLImageElement) || image.dataset.imageFallbackUsed === 'true') return;
        const mode = image.dataset.imageFallback;
        if (!['brand', 'property', 'hide'].includes(mode)) return;
        image.dataset.imageFallbackUsed = 'true';
        if (mode === 'hide') { image.hidden = true; return; }
        image.closest('picture')?.querySelectorAll('source').forEach(source => source.remove());
        image.removeAttribute('srcset');
        image.src = mode === 'brand' ? '/images/brand/travelgo-airborne.png' : '/images/property-photo-unavailable.svg';
        if (mode === 'property') image.alt = 'Photo unavailable';
    };
    document.addEventListener('error', event => fallback(event.target), true);
    // A cached/broken image can finish before a deferred script starts observing errors.
    document.querySelectorAll('img[data-image-fallback]').forEach(image => {
        if (image.complete && image.naturalWidth === 0) fallback(image);
    });
})();
