(() => {
    'use strict';
    if (document.body.dataset.staffUpdatesInitialized) return;
    document.body.dataset.staffUpdatesInitialized = 'true';

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (!csrfToken || !csrfHeader || !window.StompJs?.Client || !window.WebSocket) return;

    const notice = document.createElement('aside');
    notice.className = 'staff-live-update';
    notice.setAttribute('aria-label', 'Workspace updates');
    notice.hidden = true;
    const message = document.createElement('p');
    message.setAttribute('role', 'status');
    const actions = document.createElement('div');
    actions.className = 'staff-live-update-actions';
    const refresh = document.createElement('button');
    refresh.type = 'button';
    refresh.className = 'btn btn-primary';
    refresh.textContent = 'Refresh';
    refresh.addEventListener('click', () => window.location.reload());
    const dismiss = document.createElement('button');
    dismiss.type = 'button';
    dismiss.className = 'btn btn-outline';
    dismiss.textContent = 'Dismiss';
    dismiss.addEventListener('click', () => { notice.hidden = true; });
    actions.append(refresh, dismiss);
    notice.append(message, actions);
    document.body.append(notice);

    const eventMessages = {
        NEW_BOOKING: 'is ready for review',
        BOOKING_CONFIRMED: 'has been confirmed',
        BOOKING_COMPLETED: 'has been marked complete',
        BOOKING_UPDATED: 'has updated travel details',
        BOOKING_CANCELLED: 'has been cancelled',
        BOOKING_DECLINED: 'has been declined',
        BOOKING_INFO_REQUESTED: 'is awaiting customer information',
        BOOKING_INFO_REPLIED: 'has a new customer response'
    };
    let updateCount = 0;
    let connectionNotice = false;
    const showConnectionNotice = () => {
        if (updateCount || connectionNotice) return;
        connectionNotice = true;
        message.textContent = 'Live updates are unavailable. Refresh to see the latest changes.';
        notice.hidden = false;
    };
    const receiveUpdate = frame => {
        let event;
        try { event = JSON.parse(frame.body); } catch { return; }
        if (!event || typeof event !== 'object' || !Number.isSafeInteger(event.bookingId)
                || event.bookingId < 1 || !Object.hasOwn(eventMessages, event.type)) return;
        updateCount += 1;
        connectionNotice = false;
        message.textContent = updateCount === 1
            ? `Booking #${event.bookingId} ${eventMessages[event.type]}. Refresh to view it.`
            : `${updateCount} bookings have updates. Refresh to view them.`;
        notice.hidden = false;
    };

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const client = new window.StompJs.Client({
        brokerURL: `${protocol}//${window.location.host}/ws-travelgo`,
        connectHeaders: { [csrfHeader]: csrfToken },
        connectionTimeout: 10000,
        reconnectDelay: 5000,
        heartbeatIncoming: 0,
        heartbeatOutgoing: 15000,
        debug: () => {},
        onConnect: () => {
            if (connectionNotice) { notice.hidden = true; connectionNotice = false; }
            client.subscribe('/topic/staff-updates', receiveUpdate);
        },
        onWebSocketError: showConnectionNotice,
        onWebSocketClose: showConnectionNotice,
        onStompError: () => {
            showConnectionNotice();
            client.deactivate();
        }
    });
    client.activate();
    window.addEventListener('pagehide', () => client.deactivate());
    window.addEventListener('pageshow', event => { if (event.persisted) client.activate(); });
})();
