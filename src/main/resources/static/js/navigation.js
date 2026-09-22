/**
 * TravelGO Modern Navigation System
 * Provides SPA-like fast, smooth transitions using fetch and DOM swapping.
 */

document.addEventListener('DOMContentLoaded', () => {
    // 1. Initialize the Progress Bar
    const progressBar = document.createElement('div');
    progressBar.className = 'page-progress-bar';
    document.body.appendChild(progressBar);

    let isNavigating = false;
    let loaderTimeout = null;

    const startLoader = () => {
        // Smart loading: only show if navigation takes longer than 50ms
        loaderTimeout = setTimeout(() => {
            progressBar.classList.add('is-loading');
        }, 50);
    };

    const stopLoader = () => {
        clearTimeout(loaderTimeout);
        progressBar.classList.remove('is-loading');
    };

    // 2. Identify main content container
    const getMainContainer = (doc = document) => {
        return doc.querySelector('main, .main-content, .admin-main, .staff-main, #page-content');
    };

    // 3. Update Active Navigation States
    const updateActiveStates = (url) => {
        const path = new URL(url, window.location.origin).pathname;
        
        // Update Navbar
        document.querySelectorAll('#primary-navigation .nav-link').forEach(link => {
            link.classList.remove('active');
            link.removeAttribute('aria-current');
            if (new URL(link.href, window.location.origin).pathname === path) {
                link.classList.add('active');
                link.setAttribute('aria-current', 'page');
            }
        });

        // Update Sidebars
        document.querySelectorAll('.sidebar-menu .sidebar-item').forEach(item => {
            item.classList.remove('active');
            const link = item.querySelector('a');
            if (link && new URL(link.href, window.location.origin).pathname === path) {
                item.classList.add('active');
            }
        });

        // Update Dashboard Pills
        document.querySelectorAll('.nav-pills a').forEach(link => {
            link.classList.remove('active');
            if (link.href && link.href !== 'javascript:void(0)' && new URL(link.href, window.location.origin).pathname === path) {
                link.classList.add('active');
            }
        });
    };

    // 4. Core Navigation Logic
    const navigateTo = async (url, pushToHistory = true) => {
        if (isNavigating) return;
        
        const mainContainer = getMainContainer();
        if (!mainContainer) {
            // Fallback if no main container exists
            window.location.assign(url);
            return;
        }

        isNavigating = true;
        startLoader();

        // Exit transition
        mainContainer.classList.add('is-leaving');
        
        // Close mobile menu if open
        const navToggle = document.querySelector('.nav-toggle');
        const primaryNav = document.getElementById('primary-navigation');
        if (navToggle && navToggle.getAttribute('aria-expanded') === 'true') {
            navToggle.setAttribute('aria-expanded', 'false');
            primaryNav?.classList.remove('is-open');
        }

        try {
            // Wait a tiny bit for the exit transition to start visually before locking thread
            await new Promise(r => setTimeout(r, 50)); 

            const response = await fetch(url, {
                headers: {
                    'X-Requested-With': 'XMLHttpRequest' // Helps backend know it's async if needed
                }
            });

            // Handle server errors or unauthorized redirects
            if (!response.ok) {
                // If it's 4xx or 5xx, we fallback to hard navigation to let the server render the error page properly
                window.location.assign(url);
                return;
            }

            // If the server redirected us (e.g. Spring Security login redirect)
            if (response.redirected) {
                const redirectUrl = new URL(response.url);
                const requestedUrl = new URL(url, window.location.origin);
                
                // If we were redirected to a different path (like /auth/login), hard reload to guarantee security state
                if (redirectUrl.pathname !== requestedUrl.pathname) {
                    window.location.assign(response.url);
                    return;
                }
            }

            const html = await response.text();
            const parser = new DOMParser();
            const doc = parser.parseFromString(html, 'text/html');

            const newMain = getMainContainer(doc);
            if (!newMain) {
                window.location.assign(url);
                return;
            }

            // Update DOM
            document.title = doc.title;
            updateActiveStates(url);
            
            // Swap content
            mainContainer.innerHTML = newMain.innerHTML;
            
            // Maintain class structure (in case the container classes changed)
            mainContainer.className = newMain.className;

            // Re-run inline scripts inside the new main container
            const scripts = mainContainer.querySelectorAll('script');
            scripts.forEach(s => {
                const newScript = document.createElement('script');
                if (s.src) newScript.src = s.src;
                else newScript.textContent = s.textContent;
                document.body.appendChild(newScript);
                if (!s.src) document.body.removeChild(newScript);
            });

            if (pushToHistory) {
                window.history.pushState({}, '', url);
            }

            // Entrance Transition
            mainContainer.classList.remove('is-leaving');
            mainContainer.classList.add('is-entering');
            
            // Force reflow
            void mainContainer.offsetWidth;

            requestAnimationFrame(() => {
                mainContainer.classList.remove('is-entering');
            });

            // Re-trigger global reveal animations from main.js
            // By emitting a synthetic resize or scroll event, or re-initializing observer if we can
            window.dispatchEvent(new Event('scroll'));
            
            // Also manual fallback to remove pending state if IO doesn't catch it
            setTimeout(() => {
                document.querySelectorAll('.motion-pending').forEach(el => {
                    el.classList.remove('motion-pending');
                    el.classList.add('revealed');
                });
            }, 100);

            // Focus management for accessibility
            mainContainer.focus({ preventScroll: true });
            if (mainContainer.tabIndex === -1 && !mainContainer.getAttribute('tabindex')) {
                mainContainer.tabIndex = -1;
            }

        } catch (error) {
            console.error('Navigation failed:', error);
            window.location.assign(url); // Fallback
        } finally {
            stopLoader();
            isNavigating = false;
        }
    };

    // 5. Intercept Clicks
    document.addEventListener('click', (e) => {
        // Find closest anchor
        const link = e.target.closest('a');
        if (!link) return;

        // Ignore clicks with modifiers
        if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;

        // Ignore external links, mailto, tel, downloads
        const href = link.getAttribute('href');
        if (!href || href.startsWith('javascript:') || href.startsWith('mailto:') || href.startsWith('tel:') || link.hasAttribute('download')) return;

        // Ignore target="_blank"
        if (link.getAttribute('target') === '_blank') return;

        // Ignore cross-origin
        const url = new URL(link.href, window.location.origin);
        if (url.origin !== window.location.origin) return;

        // Ignore hash links on the same page
        if (url.pathname === window.location.pathname && url.hash) return;
        
        // If it's an exact same page reload (e.g. #), maybe ignore or fetch? Let's fetch for exact same path if there's no hash
        if (href === '#') return;

        // Prevent default and navigate
        e.preventDefault();
        navigateTo(link.href, true);
        
        // Scroll to top smoothly unless reduced motion
        const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
        window.scrollTo({ top: 0, behavior: motion.matches ? 'auto' : 'smooth' });
    });

    // 6. Handle Browser Back/Forward
    window.addEventListener('popstate', () => {
        navigateTo(window.location.href, false);
    });
});
