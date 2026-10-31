/* Progressive administrator-only enhancements. main.js owns drawers and modals. */
(() => {
    'use strict';
    const init = () => {
        if (!document.body.classList.contains('admin-console')) return;
        const search = document.querySelector('[data-console-nav-search]');
        const groups = [...document.querySelectorAll('.console-nav-group')];
        const empty = document.querySelector('[data-console-nav-empty]');
        const normalise = value => value.toLocaleLowerCase().replace(/\s+/g, ' ').trim();
        const filterSections = () => {
            const query = normalise(search.value);
            let matches = 0;
            groups.forEach(group => {
                const groupMatch = query && normalise(group.querySelector('h2')?.textContent || '').includes(query);
                const items = [...group.querySelectorAll('.sidebar-item')];
                items.forEach(item => {
                    const match = !query || groupMatch || normalise(item.textContent).includes(query);
                    item.hidden = !match;
                    if (match) matches++;
                });
                group.hidden = items.every(item => item.hidden);
            });
            if (empty) empty.hidden = matches !== 0;
        };
        if (search) {
            search.addEventListener('input', filterSections);
            search.addEventListener('keydown', event => {
                if (event.key === 'Escape' && search.value) {
                    event.preventDefault(); event.stopPropagation();
                    search.value = ''; filterSections();
                }
            });
            filterSections();
        }
        // Count only the rows rendered on this page, without changing filtering.
        document.querySelectorAll('.admin-content table').forEach(table => {
            const wrapper = table.parentElement;
            if (!wrapper.classList.contains('table-responsive')) return;
            const rows = [...table.querySelectorAll('tbody > tr')].filter(row =>
                !row.hidden && row.querySelectorAll('td').length > 1 && !row.querySelector('td[colspan]'));
            const footer = document.createElement('div'); footer.className = 'console-table-meta';
            const count = document.createElement('span');
            count.textContent = `${rows.length} ${rows.length === 1 ? 'row' : 'rows'} on this page`;
            const hint = document.createElement('span'); hint.className = 'console-table-scroll-hint';
            hint.textContent = 'Scroll horizontally to see all columns →';
            footer.append(count, hint); wrapper.after(footer);
            const update = () => { hint.hidden = table.scrollWidth <= wrapper.clientWidth + 1; };
            update();
            if ('ResizeObserver' in window) new ResizeObserver(update).observe(wrapper);
        });
        if (window.location.pathname === '/admin/settings') {
            const content = document.querySelector('.admin-content');
            const headings = [...content.querySelectorAll('.table-header h2, .table-header h4')];
            if (headings.length > 1) {
                const index = document.createElement('nav'); index.className = 'console-section-index';
                index.setAttribute('aria-label', 'Settings sections');
                headings.forEach((heading, position) => {
                    if (!heading.id) heading.id = `console-settings-section-${position + 1}`;
                    const link = document.createElement('a'); link.href = `#${heading.id}`;
                    link.textContent = heading.textContent.trim(); index.append(link);
                });
                content.querySelector('.grid')?.before(index);
            }
        }
    };
    // Defer until the shared DOMContentLoaded listeners have wrapped tables.
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => setTimeout(init, 0));
    else init();
})();
