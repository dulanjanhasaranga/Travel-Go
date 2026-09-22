(() => {
 'use strict';
 const start = () => {
  document.querySelectorAll('.notification-center').forEach(center => {
   if (center.dataset.initialized) return;
   center.dataset.initialized = 'true';
   const toggle = center.querySelector('summary');
   const popover = center.querySelector('.notification-popover');
   const recent = center.querySelector('.notification-recent');
   const feedback = center.querySelector('.notification-feedback');
   const csrfInput = center.querySelector('input[type="hidden"]');
   let generation = 0;
   let stopped = false;
   const csrfName = csrfInput?.name;
   const csrfToken = csrfInput?.value;
   const text = (tag, value, className) => {const node = document.createElement(tag);node.textContent = value;if(className)node.className = className;return node;};
   const showMessage = value => {feedback.textContent = value;feedback.hidden = !value;};
   const updateCount = count => {
    const badge = center.querySelector('.notification-count');
    badge.textContent = count > 99 ? '99+' : String(count);badge.hidden = count === 0;
    center.querySelector('[data-unread-label]').textContent = `${count} unread`;
    toggle.setAttribute('aria-label', count ? `Notifications, ${count} unread` : 'Notifications');
    center.querySelector('.notification-mark-all button').disabled = count === 0;
   };
   const render = items => {
    recent.replaceChildren();
    if (!items.length) {recent.append(text('p', "You're all caught up. Updates about your travel and support will appear here.", 'notification-empty'));return;}
    for (const item of items) {
     const form = document.createElement('form');form.method = 'post';form.action = `${center.dataset.feedUrl.replace(/\/feed$/, '')}/${item.id}/read`;form.className = `notification-item${item.read ? '' : ' is-unread'}`;
     if(csrfName && csrfToken){const token=document.createElement('input');token.type='hidden';token.name=csrfName;token.value=csrfToken;form.append(token);}
     const button = document.createElement('button');button.type = 'submit';button.className = 'notification-open';
     const heading = text('span', '', 'notification-item-heading');heading.append(text('strong', item.title));
     if (!item.read) {const dot=text('span','','notification-dot');dot.setAttribute('aria-label','Unread');heading.append(dot);}
     button.append(heading, text('span', item.message, 'notification-message'));
     const time = document.createElement('time');time.dateTime = item.createdAt;
     const date = new Date(item.createdAt);time.textContent = Number.isNaN(date.getTime()) ? item.createdAt : new Intl.DateTimeFormat(undefined,{dateStyle:'medium',timeStyle:'short'}).format(date);button.append(time);
     form.append(button);recent.append(form);
    }
   };
   const readResponse = async response => {
    if (response.redirected || response.status === 401 || response.status === 403) {stopped = true;throw new Error('Your session has ended. Sign in again to update notifications.');}
    if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) throw new Error('Notifications could not be updated. Please try again.');
    return response.json();
   };
   const refresh = async () => {
    if(stopped) return;
    const requestGeneration = ++generation;
    if(center.open){popover.setAttribute('aria-busy','true');showMessage('Refreshing notifications…');}
    try {
     const data = await readResponse(await fetch(center.dataset.feedUrl,{headers:{Accept:'application/json'},credentials:'same-origin',cache:'no-store'}));
     if(requestGeneration !== generation) return;
     updateCount(data.unreadCount);render(data.items);showMessage('');
    } catch(error) {if(requestGeneration === generation && center.open) showMessage(error.message || 'Unable to connect. Your saved notifications are unchanged.');}
    finally {if(requestGeneration === generation)popover.removeAttribute('aria-busy');}
   };
   const position = () => {const top = Math.min(toggle.getBoundingClientRect().bottom + 10, Math.max(70, window.innerHeight - 240));center.style.setProperty('--notification-popover-top', `${Math.max(12, top)}px`);};
   center.addEventListener('toggle', () => {toggle.setAttribute('aria-expanded',String(center.open));if(center.open){position();refresh();}});
   document.addEventListener('click', event => {if(center.open && !center.contains(event.target))center.open = false;});
   document.addEventListener('keydown', event => {if(event.key === 'Escape' && center.open){center.open = false;toggle.focus();}});
   window.addEventListener('resize', position, {passive:true});
   center.addEventListener('submit', async event => {
    const form = event.target;
    if(!form.matches('form')) return;
    event.preventDefault();
    if(form.dataset.saving) return;
    form.dataset.saving = 'true';
    ++generation;
    const button=form.querySelector('button');const previouslyDisabled=button.disabled;let saved=false;button.disabled=true;form.setAttribute('aria-busy','true');
    showMessage('Saving…');
    try {
     const data = await readResponse(await fetch(form.action,{method:'POST',body:new URLSearchParams(new FormData(form)),headers:{Accept:'application/json'},credentials:'same-origin'}));
     saved=true;
     updateCount(data.unreadCount);
     if(form.classList.contains('notification-mark-all')){await refresh();showMessage('All notifications marked as read.');}
     else {const url = new URL(data.url, location.origin);if(url.origin !== location.origin) throw new Error('This notification link is unavailable.');location.assign(url.href);}
    } catch(error) {showMessage(error.message || 'Could not save. Please try again.');}
    finally {delete form.dataset.saving;form.removeAttribute('aria-busy');if(!saved || !form.classList.contains('notification-mark-all'))button.disabled=previouslyDisabled;}
   });
   refresh();
   window.setInterval(() => {if(!document.hidden)refresh();},60000);
   document.addEventListener('visibilitychange',() => {if(!document.hidden)refresh();});
  });
 };
 if(document.readyState === 'loading')document.addEventListener('DOMContentLoaded',start);else start();
})();
