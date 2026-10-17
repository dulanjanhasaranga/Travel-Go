/* Failed catalogue assets retain a calm, honest placeholder and accessible caption. */
(() => {
  const placeholder = '/images/property-photo-unavailable.svg';
  const aliases = new Map(Array.from(document.querySelectorAll('[data-photo-alias]'), element => [element.dataset.photoAlias, element.dataset.photoSrc]));
  function guard(image) {
    const unavailable = () => {
      if (image.getAttribute('src') === placeholder) return;
      image.removeAttribute('srcset');
      image.src = placeholder;
      image.alt = 'Photo unavailable';
      const caption = image.closest('figure')?.querySelector('figcaption');
      if (caption) caption.textContent = 'Photo unavailable';
    };
    image.addEventListener('error', unavailable);
    if (image.getAttribute('src') && image.complete && image.naturalWidth === 0) unavailable();
  }
  document.querySelectorAll('img[data-catalogue-photo]').forEach(guard);
  function safeSource(value) {
    const source = String(value || '').trim();
    if (/^\/images\/[A-Za-z0-9_./-]+$/.test(source) && !source.includes('..')) return aliases.get(source) || source;
    try {
      const url = new URL(source);
      return url.protocol === 'https:' && !url.username && !url.password ? url.href : null;
    } catch { return null; }
  }
  const previews = new Map();
  document.querySelectorAll('input[data-catalogue-image-input]').forEach(input => {
    const figure = document.createElement('figure');
    figure.className = 'catalogue-photo catalogue-editor-preview';
    figure.hidden = true;
    const image = document.createElement('img');
    image.dataset.cataloguePhoto = '';
    image.alt = 'Catalogue image preview';
    const caption = document.createElement('figcaption');
    figure.append(image, caption);
    input.parentElement.append(figure);
    guard(image);
    const update = () => {
      const source = safeSource(input.value);
      figure.hidden = !source;
      if (source) { image.src = source; caption.textContent = 'Photo preview'; }
      else image.removeAttribute('src');
    };
    previews.set(input, update);
    input.addEventListener('input', update);
    update();
  });
  window.TravelGoCataloguePhotos = { updatePreview(input) { previews.get(input)?.(); } };
})();
