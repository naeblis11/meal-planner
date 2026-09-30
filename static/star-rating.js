// Rate a recipe in place. Each star is a submit button in a small form;
// without JS the form posts and the page reloads. With JS we post the same
// form via fetch and repaint the stars where they are, so the page never
// reloads or scrolls.
(function () {
  function label(n, current) {
    if (n === current) { return { title: 'Clear rating', aria: 'Clear rating' }; }
    var s = n === 1 ? '' : 's';
    return { title: n + ' star' + s, aria: 'Rate ' + n + ' star' + s };
  }

  function paint(form, rating) {
    var max = parseInt(form.dataset.max, 10) || 5;
    form.dataset.rating = String(rating);
    form.setAttribute('aria-label', rating ? 'Rated ' + rating + ' of ' + max + ' stars' : 'Not rated yet');
    form.querySelectorAll('.star').forEach(function (star) {
      var n = parseInt(star.dataset.star, 10);
      var text = label(n, rating);
      star.value = n === rating ? '0' : String(n);
      star.classList.toggle('star--on', n <= rating);
      star.title = text.title;
      star.setAttribute('aria-label', text.aria);
    });
    var container = form.parentElement;
    var textEl = container && container.querySelector('[data-rating-text]');
    if (textEl) { textEl.textContent = rating ? rating + '/' + max : 'Not rated'; }
  }

  document.addEventListener('submit', function (event) {
    var form = event.target.closest('.star-rating');
    if (!form || !window.fetch) { return; }
    var star = event.submitter;
    if (!star || star.name !== 'rating') { return; }
    event.preventDefault();

    var body = new FormData(form);
    body.set('rating', star.value);
    form.classList.add('is-saving');
    fetch(form.action, {
      method: 'POST',
      body: body,
      credentials: 'same-origin',
      headers: { 'X-Requested-With': 'fetch' }
    }).then(function (response) {
      if (response.status === 401) { window.location.href = '/login'; return null; }
      return response.json();
    }).then(function (data) {
      if (data && data.ok) { paint(form, data.rating); }
      else if (data) { throw new Error(data.error || 'Rating failed'); }
    }).catch(function () {
      // Fall back to the plain post so the rating still lands. form.submit()
      // sends no button value, so carry the clicked star in a hidden field.
      var hidden = document.createElement('input');
      hidden.type = 'hidden';
      hidden.name = 'rating';
      hidden.value = star.value;
      form.appendChild(hidden);
      form.submit();
    }).finally(function () {
      form.classList.remove('is-saving');
    });
  });
})();
