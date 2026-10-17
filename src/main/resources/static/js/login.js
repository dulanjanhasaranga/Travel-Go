document.querySelector('.password-toggle')?.addEventListener('click', function () {
 const field = document.getElementById('password');
 const visible = field.type === 'password';
 field.type = visible ? 'text' : 'password';
 this.textContent = visible ? 'Hide' : 'Show';
 this.setAttribute('aria-label', visible ? 'Hide password' : 'Show password');
 this.setAttribute('aria-pressed', String(visible));
});

// main.js owns submit loading and duplicate prevention for every form, including
// normal and demo login. A second handler here would cancel the first submission.
