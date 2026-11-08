// Theme initialization is in theme.js, loaded before page styles.

document.addEventListener('DOMContentLoaded', () => {
 const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
 const navbar = document.querySelector('.navbar');
 const topButton = document.querySelector('.scroll-top-btn');
 const onScroll = () => {
  navbar?.classList.toggle('scrolled', window.scrollY > 10);
  topButton?.classList.toggle('visible', window.scrollY > 400);
 };
 window.addEventListener('scroll', onScroll, {passive:true});
 onScroll();
 topButton?.addEventListener('click', () => window.scrollTo({top:0,behavior:motion.matches ? 'auto' : 'smooth'}));
 // Content remains visible without JavaScript. Animate only elements below the fold, once.
 // Premium Animation & Scroll Reveal System
 const elements = [...document.querySelectorAll('.reveal, .reveal-up, .reveal-left, .reveal-right, .reveal-zoom, .grid .card')];
 let observer;
 const revealAll = () => { elements.forEach(el => el.classList.remove('motion-pending')); observer?.disconnect(); };
 if (!motion.matches && 'IntersectionObserver' in window) {
  observer = new IntersectionObserver(entries => entries.forEach(entry => {
   if (entry.isIntersecting) { 
     entry.target.classList.remove('motion-pending'); 
     entry.target.classList.add('revealed'); 
     setTimeout(() => entry.target.style.removeProperty('transition-delay'), 800); 
     observer.unobserve(entry.target); 
   }
  }), {threshold:0.1, rootMargin:'0px 0px -50px 0px'});
  
  elements.forEach((el, index) => {
   // Add base reveal class to cards if they don't have one
   if (el.classList.contains('card') && !el.className.includes('reveal')) {
       el.classList.add('reveal-up');
   }
   
   if (el.getBoundingClientRect().top > window.innerHeight * 0.8) { 
     // Stagger grid elements
     if(el.closest('.grid')) {
         el.style.animationDelay = `${Math.min(index % 4, 3) * 100}ms`; 
     }
     el.classList.add('motion-pending'); 
     observer.observe(el); 
   } else {
     el.classList.add('revealed');
   }
   el.addEventListener('focusin', () => {
       el.classList.remove('motion-pending');
       el.classList.add('revealed');
   });
  });
 } else {
  // If reduced motion or no IO, reveal all immediately
  elements.forEach(el => el.classList.add('revealed'));
 }
 motion.addEventListener('change', revealAll);
 // Keep important messages and exact formatted financial totals intact.
 document.querySelectorAll('img').forEach(img => {
  if (!img.closest('.official-mark') && !img.hasAttribute('data-catalogue-photo') && !img.hasAttribute('data-image-fallback')) {
   img.decoding = 'async';
   img.addEventListener('error', () => { if (!img.src.endsWith('/images/hero-travel.jpg')) {img.removeAttribute('srcset');img.src = '/images/hero-travel.jpg';} }, {once:true});
  }
 });
});

// Shared, accessible feedback without altering form values or financial actions.
document.addEventListener('DOMContentLoaded', () => {
 const sidebar=document.querySelector('.admin-sidebar');
 if(sidebar){
  document.documentElement.classList.add('workspace-nav-ready');
  const mobile=window.matchMedia('(max-width:900px)');
  sidebar.id=sidebar.id||'workspace-navigation';
  const header=document.createElement('div');header.className='workspace-mobile-header';
  const brand=sidebar.querySelector('.sidebar-logo')?.cloneNode(true)||document.createElement('a');brand.href='/';if(!brand.textContent.trim())brand.textContent='TravelGO';header.append(brand);
  const opener=document.createElement('button');opener.type='button';opener.className='workspace-toggle';opener.textContent='Menu';opener.setAttribute('aria-label','Open workspace menu');opener.setAttribute('aria-controls',sidebar.id);opener.setAttribute('aria-expanded','false');header.append(opener);
  sidebar.closest('.admin-layout')?.before(header);
  const closer=document.createElement('button');closer.type='button';closer.className='workspace-toggle';closer.textContent='Close';closer.setAttribute('aria-label','Close workspace menu');sidebar.querySelector('.sidebar-header')?.append(closer);
  const backdrop=document.createElement('div');backdrop.className='workspace-backdrop';backdrop.setAttribute('aria-hidden','true');document.body.append(backdrop);
  const content=document.querySelector('.admin-main');
  const setOpen=(open,restoreFocus=true)=>{
   sidebar.classList.toggle('nav-expanded',open);backdrop.classList.toggle('is-visible',open);document.body.classList.toggle('workspace-drawer-open',open&&mobile.matches);opener.setAttribute('aria-expanded',String(open));
   sidebar.inert=mobile.matches&&!open;if(content)content.inert=mobile.matches&&open;
   if(open){sidebar.setAttribute('role','dialog');sidebar.setAttribute('aria-modal','true');sidebar.setAttribute('aria-label','Workspace navigation');closer.focus();}
   else{sidebar.removeAttribute('role');sidebar.removeAttribute('aria-modal');if(restoreFocus&&mobile.matches)opener.focus();}
  };
  opener.addEventListener('click',()=>setOpen(true));closer.addEventListener('click',()=>setOpen(false));backdrop.addEventListener('click',()=>setOpen(false));mobile.addEventListener('change',()=>setOpen(false,false));setOpen(false,false);
  sidebar.addEventListener('keydown',event=>{
   if(!mobile.matches||!sidebar.classList.contains('nav-expanded'))return;
   if(event.key==='Escape'){event.preventDefault();setOpen(false);}
   if(event.key==='Tab'){const items=[...sidebar.querySelectorAll('a[href],button:not([disabled]),[tabindex="0"]')].filter(el=>el.getClientRects().length);const first=items[0],last=items[items.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}
  });
 }
 const toggle=document.querySelector('.nav-toggle');
 const panel=document.getElementById('site-navigation-panel');
 if(toggle&&panel){
  const header=toggle.closest('header.navbar');
  const backdrop=header.querySelector('.nav-backdrop');
  const closer=panel.querySelector('.nav-close');
  const mobileNavigation=window.matchMedia('(max-width:1199px)');
  const focusable='a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),summary,[tabindex]:not([tabindex="-1"])';
  let expanded=false,lockedScroll=null,inertSiblings=[];
  const lockBackground=()=>{
   if(lockedScroll)return;
   const properties=['position','top','left','right','width','overflow'];
   lockedScroll={y:window.scrollY,styles:properties.map(name=>[name,document.body.style.getPropertyValue(name),document.body.style.getPropertyPriority(name)])};
   Object.assign(document.body.style,{position:'fixed',top:`-${lockedScroll.y}px`,left:'0',right:'0',width:'100%',overflow:'hidden'});
   // Inert each sibling branch, including pages whose header is nested in a wrapper.
   let branch=header;
   while(branch&&branch!==document.body){
    for(const sibling of branch.parentElement?.children||[]){
     if(sibling!==branch&&!['SCRIPT','STYLE','LINK'].includes(sibling.tagName)){
      inertSiblings.push([sibling,sibling.inert]);sibling.inert=true;
     }
    }
    branch=branch.parentElement;
   }
  };
  const unlockBackground=()=>{
   inertSiblings.forEach(([element,wasInert])=>{element.inert=wasInert;});inertSiblings=[];
   if(!lockedScroll)return;
   const previous=lockedScroll;lockedScroll=null;
   previous.styles.forEach(([name,value,priority])=>{if(value)document.body.style.setProperty(name,value,priority);else document.body.style.removeProperty(name);});
   window.scrollTo({top:previous.y,left:0,behavior:'instant'});
  };
  const setExpanded=(requested,restoreFocus=true)=>{
   expanded=Boolean(requested&&mobileNavigation.matches);
   header.classList.toggle('nav-is-open',expanded);
   toggle.setAttribute('aria-expanded',String(expanded));
   toggle.setAttribute('aria-label',expanded?'Close navigation':'Open navigation');
   backdrop.hidden=!expanded;
   panel.inert=mobileNavigation.matches&&!expanded;
   if(mobileNavigation.matches&&!expanded)panel.setAttribute('aria-hidden','true');else panel.removeAttribute('aria-hidden');
   if(expanded){
    panel.setAttribute('role','dialog');panel.setAttribute('aria-modal','true');panel.setAttribute('aria-labelledby','site-navigation-title');
    lockBackground();
    // Wait for the closed panel's visibility/inert state to leave the render tree.
    requestAnimationFrame(()=>{if(expanded)closer.focus({preventScroll:true});});
   }else{
    panel.removeAttribute('role');panel.removeAttribute('aria-modal');panel.removeAttribute('aria-labelledby');
    panel.querySelectorAll('details[open]').forEach(details=>{details.open=false;});
    unlockBackground();
    if(restoreFocus&&mobileNavigation.matches)toggle.focus({preventScroll:true});
   }
  };
  document.documentElement.classList.add('nav-js','nav-ready');
  setExpanded(false,false);
  toggle.addEventListener('click',()=>setExpanded(!expanded));
  header.querySelectorAll('[data-nav-dismiss]').forEach(button=>button.addEventListener('click',()=>setExpanded(false)));
  panel.addEventListener('click',event=>{if(event.target.closest('a[href]'))setExpanded(false,false);});
  mobileNavigation.addEventListener('change',()=>setExpanded(false,false));
  window.addEventListener('pageshow',event=>{if(event.persisted)setExpanded(false,false);});
  document.addEventListener('keydown',event=>{
   if(!expanded||event.defaultPrevented)return;
   if(event.key==='Escape'){event.preventDefault();setExpanded(false);return;}
   if(event.key==='Tab'){
    const items=[...panel.querySelectorAll(focusable)].filter(element=>element.getClientRects().length&&!element.closest('[inert]'));
    const first=items[0]||panel,last=items[items.length-1]||panel;
    if(event.shiftKey&&(document.activeElement===first||!panel.contains(document.activeElement))){event.preventDefault();last.focus();}
    else if(!event.shiftKey&&(document.activeElement===last||!panel.contains(document.activeElement))){event.preventDefault();first.focus();}
   }
  });
  document.addEventListener('focusin',event=>{if(expanded&&!panel.contains(event.target))closer.focus({preventScroll:true});});
 }
 const accountMenu=document.querySelector('.account-menu');
 if(accountMenu){
  const summary=accountMenu.querySelector('summary');
  document.addEventListener('click',event=>{if(!accountMenu.contains(event.target))accountMenu.open=false;});
  accountMenu.addEventListener('keydown',event=>{if(event.key==='Escape'&&accountMenu.open){event.preventDefault();event.stopPropagation();accountMenu.open=false;summary.focus();}});
  accountMenu.addEventListener('toggle',()=>{if(accountMenu.open){const notifications=document.querySelector('.notification-center');if(notifications)notifications.open=false;}});
  document.querySelector('.notification-center')?.addEventListener('toggle',event=>{if(event.target.open)accountMenu.open=false;});
 }
 const main = document.querySelector('main,.main-content,.admin-main,.staff-main');
 if (main) { if(!main.id) main.id='page-content'; const skip=document.createElement('a');skip.className='skip-link';skip.href='#'+main.id;skip.textContent='Skip to content';document.body.prepend(skip);main.tabIndex=-1; }
 document.querySelectorAll('.nav-link,.account-menu-links a').forEach(a => {
  const path=new URL(a.href,location.href).pathname;
  const exact=path===location.pathname;
  const section=(path==='/packages'||path==='/destinations')&&location.pathname.startsWith(path+'/');
  if(exact||section)a.setAttribute('aria-current',exact?'page':'location');
 });
 document.querySelectorAll('.alert').forEach(a=>a.setAttribute('role',a.classList.contains('alert-danger')?'alert':'status'));
 document.querySelectorAll('table').forEach(table=> {if(!table.parentElement.classList.contains('table-responsive')){const wrap=document.createElement('div');wrap.className='table-responsive';wrap.tabIndex=0;wrap.setAttribute('role','region');wrap.setAttribute('aria-label','Scrollable data table');table.before(wrap);wrap.append(table);}});
  const labelableSelector = 'input:not([type="hidden"]),select,textarea';
  document.querySelectorAll('label:not([for])').forEach((label,index)=>{
   const input=label.querySelector(labelableSelector)
    ||(label.nextElementSibling?.matches(labelableSelector)?label.nextElementSibling:null)
    ||label.parentElement.querySelector(labelableSelector);
   if(input){if(!input.id)input.id='field-'+index;label.htmlFor=input.id;}
  });
 document.querySelectorAll('svg:not([aria-label]):not([role=img])').forEach(icon=>{icon.setAttribute('aria-hidden','true');icon.setAttribute('focusable','false');});
 window.addEventListener('pageshow',()=>document.querySelectorAll('[data-original-label]').forEach(button=>{button.innerHTML=button.dataset.originalMarkup||button.dataset.originalLabel;button.removeAttribute('aria-disabled');button.closest('form')?.removeAttribute('aria-busy');}));
});

// Shared modal manager. Pages may use the newer custom backdrop, the original
// modal backdrop, or a staff slide panel; this keeps their keyboard and focus
// behavior the same.
document.addEventListener('DOMContentLoaded', () => {
    const modalSelector = '.custom-modal-backdrop, .modal-backdrop, .slide-panel';
    const modalStates = new WeakMap();
    let openOrder = 0;

    const isModal = (element) => element instanceof HTMLElement && element.matches(modalSelector);
    const isSlidePanel = (modal) => modal.classList.contains('slide-panel');
    const getDialog = (modal) => isSlidePanel(modal)
        ? modal
        : modal.querySelector('[role="dialog"], .custom-modal-box, .modal-card') || modal.firstElementChild;
    const normaliseId = (value) => value.replace(/[^a-zA-Z0-9_-]/g, '-');

    const getSlideBackdrop = (panel) => {
        if (!isSlidePanel(panel)) return null;

        const panelId = panel.id;
        const candidateIds = [panel.dataset.backdrop];
        if (panelId) {
            candidateIds.push(`${panelId}Backdrop`);
            if (panelId.endsWith('Modal')) candidateIds.push(`${panelId.slice(0, -'Modal'.length)}Backdrop`);
        }

        return candidateIds.filter(Boolean)
            .map((id) => document.getElementById(id.replace(/^#/, '')))
            .find((backdrop) => backdrop?.classList.contains('slide-backdrop'))
            || (panel.previousElementSibling?.classList.contains('slide-backdrop') ? panel.previousElementSibling : null);
    };

    const isVisible = (modal) => {
        if (modal.hidden) return false;
        if (isSlidePanel(modal)) return modal.classList.contains('open');
        const styles = window.getComputedStyle(modal);
        return styles.display !== 'none' && styles.visibility !== 'hidden';
    };

    const getState = (modal) => {
        if (!modalStates.has(modal)) {
            modalStates.set(modal, { open: false, opener: null, order: 0 });
        }
        return modalStates.get(modal);
    };

    const focusableElements = (container) => [...container.querySelectorAll(
        'a[href], area[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )].filter((element) => element.getAttribute('aria-hidden') !== 'true'
        && !element.closest('[hidden], [inert], [aria-hidden="true"]')
        && window.getComputedStyle(element).visibility !== 'hidden'
        && window.getComputedStyle(element).display !== 'none'
        && element.getClientRects().length > 0);

    const configureModal = (modal) => {
        if (!isModal(modal)) return;

        const dialog = getDialog(modal);
        if (!dialog) return;

        const state = getState(modal);
        dialog.setAttribute('role', 'dialog');
        dialog.setAttribute('aria-modal', 'true');
        if (!dialog.hasAttribute('tabindex')) dialog.tabIndex = -1;

        const slideBackdrop = getSlideBackdrop(modal);
        if (slideBackdrop) slideBackdrop.setAttribute('aria-hidden', 'true');

        const title = dialog.querySelector('h1, h2, h3, h4, [data-modal-title]');
        if (title && !dialog.hasAttribute('aria-labelledby')) {
            if (!title.id) title.id = `${normaliseId(modal.id || 'modal')}-title`;
            dialog.setAttribute('aria-labelledby', title.id);
        }
        if (!title && !dialog.hasAttribute('aria-label') && !dialog.hasAttribute('aria-labelledby')) {
            dialog.setAttribute('aria-label', 'Dialog');
        }

        dialog.querySelectorAll('button').forEach((button) => {
            const text = button.textContent.trim();
            const isCloseControl = button.classList.contains('close-modal-btn')
                || button.hasAttribute('data-modal-close')
                || /close/i.test(button.getAttribute('data-ui-click') || '')
                || text === '×' || text === '✕';
            if (isCloseControl) {
                if (!button.hasAttribute('type')) button.type = 'button';
                if (!button.hasAttribute('aria-label') && (text === '×' || text === '✕' || !text)) {
                    button.setAttribute('aria-label', 'Close dialog');
                }
            }
        });

        const visible = isVisible(modal);
        modal.setAttribute('aria-hidden', String(!visible));
        dialog.inert = !visible;
        if (visible && !state.open) activateModal(modal);
        else if (!visible && state.open) deactivateModal(modal);
    };

    const restoreFocus = (state) => {
        const opener = state.opener;
        state.opener = null;
        if (opener instanceof HTMLElement && opener.isConnected && !opener.hasAttribute('disabled')) {
            opener.focus({ preventScroll: true });
        }
    };

    const activateModal = (modal, opener = document.activeElement) => {
        const dialog = getDialog(modal);
        if (!dialog) return;

        const state = getState(modal);
        if (!state.open) {
            state.open = true;
            state.opener = opener instanceof HTMLElement ? opener : null;
            state.order = ++openOrder;
        }
        modal.setAttribute('aria-hidden', 'false');
        dialog.inert = false;
        window.requestAnimationFrame(() => {
            if (!isVisible(modal)) return;
            const target = dialog.querySelector('[autofocus]') || focusableElements(dialog)[0] || dialog;
            target.focus({ preventScroll: true });
        });
    };

    const deactivateModal = (modal, shouldRestoreFocus = true) => {
        const state = getState(modal);
        modal.setAttribute('aria-hidden', 'true');
        const dialog = getDialog(modal);
        if (dialog) dialog.inert = true;
        if (state.open) {
            state.open = false;
            if (shouldRestoreFocus) restoreFocus(state);
            else state.opener = null;
        }
    };

    const openModal = (modal, opener = document.activeElement) => {
        if (!isModal(modal)) return;
        configureModal(modal);
        modal.hidden = false;
        if (isSlidePanel(modal)) {
            modal.classList.add('open');
            getSlideBackdrop(modal)?.classList.add('open');
        } else {
            if (modal.classList.contains('custom-modal-backdrop')) modal.classList.add('active');
            else modal.classList.add('open');
            if (!isVisible(modal)) modal.style.display = 'flex';
        }
        activateModal(modal, opener);
    };

    const closeModal = (modal, shouldRestoreFocus = true) => {
        if (!isModal(modal)) return;
        modal.classList.remove('active', 'open');
        if (isSlidePanel(modal)) getSlideBackdrop(modal)?.classList.remove('open');
        else if (isVisible(modal)) modal.style.display = 'none';
        deactivateModal(modal, shouldRestoreFocus);
    };

    const activeModal = () => [...document.querySelectorAll(modalSelector)]
        .filter((modal) => isVisible(modal) && getState(modal).open)
        .sort((left, right) => getState(right).order - getState(left).order)[0];

    const configureAll = (root = document) => {
        if (isModal(root)) configureModal(root);
        root.querySelectorAll?.(modalSelector).forEach(configureModal);
        root.querySelectorAll?.('.open-modal-btn, [data-modal-open]').forEach((opener) => {
            const targetId = opener.dataset.target || opener.dataset.modalOpen;
            const modal = targetId && document.getElementById(targetId.replace(/^#/, ''));
            if (modal && isModal(modal)) {
                opener.setAttribute('aria-controls', modal.id);
                opener.setAttribute('aria-haspopup', 'dialog');
                if (opener.tagName === 'BUTTON' && !opener.hasAttribute('type')) opener.type = 'button';
            }
        });
    };

    configureAll();
    window.TravelGoModals = { open: openModal, close: closeModal };

    document.addEventListener('click', (event) => {
        const opener = event.target.closest('.open-modal-btn, [data-modal-open]');
        if (opener) {
            const targetId = opener.dataset.target || opener.dataset.modalOpen;
            const modal = targetId && document.getElementById(targetId.replace(/^#/, ''));
            if (modal && isModal(modal)) {
                event.preventDefault();
                openModal(modal, opener);
                return;
            }
        }

        const closeControl = event.target.closest('.close-modal-btn, [data-modal-close]');
        if (closeControl) {
            const modal = closeControl.closest(modalSelector);
            if (modal) {
                event.preventDefault();
                closeModal(modal);
                return;
            }
        }

        const backdrop = event.target.closest(modalSelector);
        if (backdrop && event.target === backdrop) closeModal(backdrop);

        const slideBackdrop = event.target.closest('.slide-backdrop');
        if (slideBackdrop && event.target === slideBackdrop) {
            const panel = [...document.querySelectorAll('.slide-panel')]
                .find((candidate) => getSlideBackdrop(candidate) === slideBackdrop);
            if (panel) closeModal(panel);
        }
    });

    document.addEventListener('keydown', (event) => {
        const modal = activeModal();
        if (!modal) return;

        if (event.key === 'Escape') {
            event.preventDefault();
            closeModal(modal);
            return;
        }
        if (event.key !== 'Tab') return;

        const dialog = getDialog(modal);
        const items = focusableElements(dialog);
        if (!items.length) {
            event.preventDefault();
            dialog.focus();
            return;
        }
        const first = items[0];
        const last = items[items.length - 1];
        if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {
            event.preventDefault();
            first.focus();
        }
    });

    new MutationObserver((changes) => {
        changes.forEach((change) => {
            if (change.type === 'attributes' && isModal(change.target)) configureModal(change.target);
            if (change.type === 'childList') {
                change.addedNodes.forEach((node) => {
                    if (node.nodeType === Node.ELEMENT_NODE) configureAll(node);
                });
            }
        });
    }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style', 'hidden'] });
});

// Native navigation preserves each response's CSP nonce, CSRF fields and page-specific initialization.
