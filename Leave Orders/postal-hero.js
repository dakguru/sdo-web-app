(() => {
  const hero = document.querySelector('.postal-hero');
  const button = document.getElementById('postal-motion');
  if (!hero || !button) return;
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  const choices = [...hero.querySelectorAll('[data-postal-select]')];
  const scenes = [...hero.querySelectorAll('[data-postal-scene]')];
  const controls = hero.querySelector('.postal-scene-controls');
  const status = hero.querySelector('.postal-scene-status');
  const storageKey = 'karur-postal-scene';
  let paused = false;

  function selectScene(name, announce = false) {
    if (!scenes.some(scene => scene.dataset.postalScene === name)) name = 'office';
    scenes.forEach(scene => { scene.hidden = scene.dataset.postalScene !== name; });
    choices.forEach(choice => choice.setAttribute('aria-pressed', String(choice.dataset.postalSelect === name)));
    hero.dataset.scene = name;
    if (announce && status) {
      const choice = choices.find(item => item.dataset.postalSelect === name);
      status.textContent = choice.getAttribute('aria-label') + ' scene selected';
    }
    try { localStorage.setItem(storageKey, name); } catch (_) { /* Storage may be disabled. */ }
  }

  function renderMotion() {
    hero.classList.toggle('is-paused', paused || reduced.matches);
    button.setAttribute('aria-pressed', String(paused || reduced.matches));
    button.textContent = reduced.matches ? 'Reduced motion enabled' : paused ? 'Play animation' : 'Pause animation';
    button.disabled = reduced.matches;
  }
  choices.forEach(choice => choice.addEventListener('click', () => selectScene(choice.dataset.postalSelect, true)));
  let saved = 'office';
  try { saved = localStorage.getItem(storageKey) || saved; } catch (_) { /* Use the default scene. */ }
  selectScene(saved);
  if (controls) controls.hidden = false;
  button.addEventListener('click', () => { paused = !paused; renderMotion(); });
  reduced.addEventListener('change', renderMotion);
  renderMotion();
  // Avoid spending rendering time on the gallery while it is outside the viewport.
  if ('IntersectionObserver' in window) {
    const visibility = new IntersectionObserver(([entry]) => {
      hero.classList.toggle('is-offscreen', !entry.isIntersecting);
    }, { threshold: 0 });
    visibility.observe(hero);
  }
})();
