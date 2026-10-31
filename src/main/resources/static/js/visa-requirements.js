(() => {
    const booking = document.getElementById('visaBookingId');
    const category = document.getElementById('visaApplicationType');
    const region = document.getElementById('visaBookingRequirements');
    if (!booking || !category || !region) return;
    let controller;
    const paragraph = (text) => { const p = document.createElement('p'); p.textContent = text; region.appendChild(p); };
    const heading = () => { region.replaceChildren(); const h = document.createElement('h3'); h.textContent = 'Visa documents'; region.appendChild(h); };
    async function update() {
        if (controller) controller.abort();
        controller = new AbortController();
        heading(); paragraph('Loading document requirements…');
        try {
            const response = await fetch('/customer/visas/requirements?' + new URLSearchParams({ bookingId: booking.value, visaType: category.value }), { credentials: 'same-origin', signal: controller.signal, headers: { Accept: 'application/json' } });
            if (!response.ok || !(response.headers.get('content-type') || '').includes('application/json')) throw new Error('Requirements unavailable');
            const data = await response.json(); heading();
            if (!data.id) { paragraph('Contact your visa officer to confirm the documents and application deadline for this trip.'); return; }
            paragraph('Checklist version ' + data.revision);
            const list = document.createElement('ul');
            for (const item of data.items) { const li = document.createElement('li'); li.textContent = item.label + (item.required ? ' — Required' : ' — Optional'); list.appendChild(li); }
            region.appendChild(list);
            paragraph(data.minimumLeadDays ? 'Apply at least ' + data.minimumLeadDays + ' calendar days before travel.' : 'Ask your visa officer when to apply.');
            paragraph(data.processingMinDays ? 'Estimated processing time: ' + data.processingMinDays + '–' + data.processingMaxDays + ' calendar days. Times may vary; approval is decided by the embassy.' : 'Ask your visa officer about processing times.');
            if (data.guidance) paragraph(data.guidance);
            paragraph('Provide documents for every traveler. Your visa officer will check them before processing.');
        } catch (error) {
            if (error.name === 'AbortError') return;
            heading(); paragraph('We could not load the requirements. Try again or contact your visa officer.');
        }
    }
    booking.addEventListener('change', update); category.addEventListener('change', update); update();
})();
