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
  if (!img.closest('.official-mark')) {
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
 const toggle=document.querySelector('.nav-toggle');const navigation=document.getElementById('primary-navigation');if(toggle&&navigation){document.documentElement.classList.add('nav-ready');const setExpanded=expanded=>{toggle.setAttribute('aria-expanded',String(expanded));navigation.classList.toggle('is-open',expanded);};toggle.addEventListener('click',()=>setExpanded(toggle.getAttribute('aria-expanded')!=='true'));document.addEventListener('keydown',event=>{if(event.key==='Escape'&&toggle.getAttribute('aria-expanded')==='true'){setExpanded(false);toggle.focus();}});document.addEventListener('click',event=>{if(!navigation.contains(event.target)&&!toggle.contains(event.target))setExpanded(false);});}
 const main = document.querySelector('main,.main-content,.admin-main,.staff-main');
 if (main) { if(!main.id) main.id='page-content'; const skip=document.createElement('a');skip.className='skip-link';skip.href='#'+main.id;skip.textContent='Skip to content';document.body.prepend(skip);main.tabIndex=-1; }
 document.querySelectorAll('.nav-link').forEach(a => {if(new URL(a.href,location.href).pathname===location.pathname)a.setAttribute('aria-current','page');});
 document.querySelectorAll('.alert').forEach(a=>a.setAttribute('role',a.classList.contains('alert-danger')?'alert':'status'));
 document.querySelectorAll('table').forEach(table=> {if(!table.parentElement.classList.contains('table-responsive')){const wrap=document.createElement('div');wrap.className='table-responsive';wrap.tabIndex=0;wrap.setAttribute('role','region');wrap.setAttribute('aria-label','Scrollable data table');table.before(wrap);wrap.append(table);}});
 document.querySelectorAll('label:not([for])').forEach((label,index)=>{const input=label.querySelector('input,select,textarea')||(label.nextElementSibling?.matches('input,select,textarea')?label.nextElementSibling:null)||label.parentElement.querySelector('input:not([type=hidden]),select,textarea');if(input){if(!input.id)input.id='field-'+index;label.htmlFor=input.id;}});
 document.querySelectorAll('svg:not([aria-label]):not([role=img])').forEach(icon=>{icon.setAttribute('aria-hidden','true');icon.setAttribute('focusable','false');});
 window.addEventListener('pageshow',()=>document.querySelectorAll('[data-original-label]').forEach(button=>{button.innerHTML=button.dataset.originalMarkup||button.dataset.originalLabel;button.removeAttribute('aria-disabled');button.closest('form')?.removeAttribute('aria-busy');}));
});

/**
 * TravelGO Modern Navigation System
 * Provides SPA-like fast, smooth transitions using fetch and DOM swapping.
 */
document.addEventListener('DOMContentLoaded', () => {
    const progressBar = document.createElement('div');
    progressBar.className = 'page-progress-bar';
    document.body.appendChild(progressBar);

    let isNavigating = false;
    let loaderTimeout = null;

    const startLoader = () => {
        loaderTimeout = setTimeout(() => {
            progressBar.classList.add('is-loading');
        }, 50);
    };

    const stopLoader = () => {
        clearTimeout(loaderTimeout);
        progressBar.classList.remove('is-loading');
    };

    const getMainContainer = (doc = document) => {
        return doc.querySelector('main, .main-content, .admin-main, .staff-main, #page-content');
    };

    const updateActiveStates = (url) => {
        const path = new URL(url, window.location.origin).pathname;
        
        document.querySelectorAll('#primary-navigation .nav-link').forEach(link => {
            link.classList.remove('active');
            link.removeAttribute('aria-current');
            if (new URL(link.href, window.location.origin).pathname === path) {
                link.classList.add('active');
                link.setAttribute('aria-current', 'page');
            }
        });

        document.querySelectorAll('.sidebar-menu .sidebar-item').forEach(item => {
            item.classList.remove('active');
            const link = item.querySelector('a');
            if (link && new URL(link.href, window.location.origin).pathname === path) {
                item.classList.add('active');
            }
        });

        document.querySelectorAll('.nav-pills a').forEach(link => {
            link.classList.remove('active');
            if (link.href && link.href !== 'javascript:void(0)' && new URL(link.href, window.location.origin).pathname === path) {
                link.classList.add('active');
            }
        });
    };

    const navigateTo = async (url, options = {}) => {
        if (isNavigating) return;
        
        const pushToHistory = options.pushToHistory !== false;
        const method = options.method || 'GET';
        const body = options.body || null;

        const mainContainer = getMainContainer();
        if (!mainContainer) {
            window.location.assign(url);
            return;
        }

        isNavigating = true;
        startLoader();
        mainContainer.classList.add('is-leaving');
        
        const navToggle = document.querySelector('.nav-toggle');
        const primaryNav = document.getElementById('primary-navigation');
        if (navToggle && navToggle.getAttribute('aria-expanded') === 'true') {
            navToggle.setAttribute('aria-expanded', 'false');
            primaryNav?.classList.remove('is-open');
        }

        try {
            await new Promise(r => setTimeout(r, 50)); 

            const fetchOptions = {
                method: method,
                headers: { 'X-Requested-With': 'XMLHttpRequest' }
            };
            if (body) {
                fetchOptions.body = body;
            }

            const response = await fetch(url, fetchOptions);

            // Handle hard redirects for authentication boundaries
            if (response.redirected) {
                const redirectUrl = new URL(response.url);
                // If it's a redirect to login or error page, do a hard reload for security
                if (redirectUrl.pathname.includes('/auth/') || redirectUrl.pathname.includes('login') || redirectUrl.pathname.includes('error')) {
                    window.location.assign(response.url);
                    return;
                }
            }

            // 400 Bad Request usually means validation errors which we want to render
            if (!response.ok && response.status !== 400 && response.status !== 422) {
                window.location.assign(url);
                return;
            }

            const html = await response.text();
            const parser = new DOMParser();
            const doc = parser.parseFromString(html, 'text/html');

            const newMain = getMainContainer(doc);
            if (!newMain) {
                window.location.assign(url);
                return;
            }

            // If we submitted a form and were redirected (PRG pattern), update the URL to the redirect destination!
            const finalUrl = response.redirected ? response.url : url;

            document.title = doc.title;
            updateActiveStates(finalUrl);
            
            mainContainer.innerHTML = newMain.innerHTML;
            mainContainer.className = newMain.className;

            const scripts = mainContainer.querySelectorAll('script');
            scripts.forEach(s => {
                const newScript = document.createElement('script');
                if (s.src) newScript.src = s.src;
                else newScript.textContent = s.textContent;
                document.body.appendChild(newScript);
                if (!s.src) document.body.removeChild(newScript);
            });

            if (pushToHistory) {
                window.history.pushState({}, '', finalUrl);
            }

            mainContainer.classList.remove('is-leaving');
            mainContainer.classList.add('is-entering');
            
            void mainContainer.offsetWidth;
            requestAnimationFrame(() => mainContainer.classList.remove('is-entering'));

            window.dispatchEvent(new Event('scroll'));
            
            setTimeout(() => {
                document.querySelectorAll('.motion-pending').forEach(el => {
                    el.classList.remove('motion-pending');
                    el.classList.add('revealed');
                });
                // After SPA navigation, new content elements have reveal classes
                // (reveal-up, reveal-zoom, etc.) that set opacity:0 but the
                // IntersectionObserver from page load doesn't re-run for them.
                // Immediately reveal all such elements in the swapped content.
                mainContainer.querySelectorAll('.reveal, .reveal-up, .reveal-left, .reveal-right, .reveal-zoom').forEach(el => {
                    el.classList.add('revealed');
                });
            }, 100);

            mainContainer.focus({ preventScroll: true });
            if (mainContainer.tabIndex === -1 && !mainContainer.getAttribute('tabindex')) {
                mainContainer.tabIndex = -1;
            }

        } catch (error) {
            console.error('Navigation failed:', error);
            window.location.assign(url);
        } finally {
            stopLoader();
            isNavigating = false;
        }
    };

    // 5. Intercept Clicks
    document.addEventListener('click', (e) => {
        const link = e.target.closest('a');
        if (!link) return;
        if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;

        const href = link.getAttribute('href');
        if (!href || href.startsWith('javascript:') || href.startsWith('mailto:') || href.startsWith('tel:') || link.hasAttribute('download')) return;
        if (link.getAttribute('target') === '_blank') return;

        const url = new URL(link.href, window.location.origin);
        if (url.origin !== window.location.origin) return;
        if (url.pathname === window.location.pathname && url.hash) return;
        if (href === '#') return;
        if (href.match(/\.(pdf|zip|jpg|png|doc|docx)$/i)) return;

        // Auth pages have their own standalone layout — always do a full page load
        if (url.pathname.startsWith('/auth/') || url.pathname === '/auth') return;

        e.preventDefault();
        navigateTo(link.href, { pushToHistory: true, method: 'GET' });
        
        const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
        window.scrollTo({ top: 0, behavior: motion.matches ? 'auto' : 'smooth' });
    });

    // 6. Intercept Form Submissions Globally
    document.addEventListener('submit', (e) => {
        const form = e.target;
        
        // Prevent double submissions visually
        if (form.getAttribute('aria-busy') === 'true') {
            e.preventDefault();
            return;
        }

        // Check if form opts out of SPA routing via data-spa="false"
        if (form.getAttribute('data-spa') === 'false') {
            return; 
        }

        // Only intercept forms pointing to our own origin
        const actionUrl = new URL(form.action || window.location.href, window.location.origin);
        if (actionUrl.origin !== window.location.origin) return;

        // Auth forms (login, register, logout, etc.) need a full browser submit
        // for proper session/cookie handling by Spring Security
        if (actionUrl.pathname.startsWith('/auth/') || actionUrl.pathname === '/auth') return;

        // Skip multipart forms as they can be tricky with Spring and files unless handled precisely.
        // For large file uploads, a standard reload is safer and provides browser-native upload progress.
        if (form.enctype === 'multipart/form-data') return;

        e.preventDefault();
        
        // Disable button visually
        const submitBtn = e.submitter || form.querySelector('button[type="submit"], input[type="submit"]');
        if (submitBtn) {
            form.setAttribute('aria-busy', 'true');
            submitBtn.dataset.originalLabel = submitBtn.textContent;
            submitBtn.dataset.originalMarkup = submitBtn.innerHTML;
            submitBtn.textContent = 'Please wait…';
            submitBtn.setAttribute('aria-disabled', 'true');
        }

        const formData = new FormData(form);
        
        // If a specific submit button was clicked and it has a name, include it
        if (e.submitter && e.submitter.name) {
            formData.append(e.submitter.name, e.submitter.value || '');
        }

        const method = form.method.toUpperCase();

        if (method === 'GET') {
            const params = new URLSearchParams(formData);
            const targetUrl = new URL(form.action || window.location.href);
            targetUrl.search = params.toString();
            navigateTo(targetUrl.href, { pushToHistory: true, method: 'GET' });
        } else {
            navigateTo(form.action || window.location.href, { 
                pushToHistory: true, 
                method: method, 
                body: formData 
            });
        }
    });

    // 7. Handle Browser Back/Forward
    window.addEventListener('popstate', () => {
        navigateTo(window.location.href, { pushToHistory: false, method: 'GET' });
    });
});
