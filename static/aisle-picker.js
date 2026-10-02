// Aisle fields: a dropdown of the store aisles under every input that names the
// aisle list (list="aisle-suggestions"). Focusing or tapping the field opens
// every aisle; typing narrows it; a custom aisle can still be typed. Without JS
// the browser's own datalist suggestions remain. Matches the phone app's field.
(function () {
  // Every aisle for an empty field or an exact aisle; otherwise the aisles
  // whose names contain the text, ignoring case.
  function aisleOptions(all, typed) {
    var t = (typed || '').trim().toLowerCase();
    if (!t) { return all.slice(); }
    if (all.some(function (a) { return a.toLowerCase() === t; })) { return all.slice(); }
    return all.filter(function (a) { return a.toLowerCase().indexOf(t) !== -1; });
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { aisleOptions: aisleOptions };
  }
  if (typeof document === 'undefined') { return; }

  var count = 0;

  function enhance(input) {
    var list = document.getElementById(input.getAttribute('list'));
    if (!list) { return; }
    var aisles = Array.prototype.map.call(list.options, function (o) { return o.value; });
    // Our menu replaces the browser's suggestions, so there is one list, not two.
    input.removeAttribute('list');
    input.setAttribute('autocomplete', 'off');

    // The menu floats on the page (fixed, under the field) so a card's rounded,
    // clipped corners can't cut it off, and the field keeps its place in its row.
    var menu = document.createElement('ul');
    menu.className = 'aisle-menu';
    menu.id = 'aisle-menu-' + (++count);
    menu.setAttribute('role', 'listbox');
    menu.hidden = true;
    document.body.appendChild(menu);

    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-controls', menu.id);
    input.setAttribute('aria-expanded', 'false');

    function close() {
      menu.hidden = true;
      input.setAttribute('aria-expanded', 'false');
    }

    function open() {
      var options = aisleOptions(aisles, input.value);
      menu.innerHTML = '';
      options.forEach(function (aisle) {
        var item = document.createElement('li');
        item.setAttribute('role', 'option');
        item.textContent = aisle;
        // mousedown, not click: it runs before the input's blur closes the menu.
        item.addEventListener('mousedown', function (event) {
          event.preventDefault();
          input.value = aisle;
          input.dispatchEvent(new Event('input', { bubbles: true }));
          close();
        });
        menu.appendChild(item);
      });
      menu.hidden = options.length === 0;
      input.setAttribute('aria-expanded', menu.hidden ? 'false' : 'true');
      place();
    }

    // Keep the floating menu under its field. On a phone the keyboard opening
    // resizes and scrolls the page, so the menu follows rather than closing,
    // and shrinks to the room left above the keyboard.
    function place() {
      if (menu.hidden) { return; }
      var box = input.getBoundingClientRect();
      var room = window.innerHeight - box.bottom - 12;
      menu.style.top = (box.bottom + 4) + 'px';
      menu.style.left = box.left + 'px';
      menu.style.width = Math.max(box.width, 192) + 'px';
      menu.style.maxHeight = Math.max(Math.min(room, 256), 132) + 'px';
    }

    input.addEventListener('focus', open);
    input.addEventListener('click', open);
    input.addEventListener('input', function (event) {
      if (event.isTrusted) { open(); }
    });
    input.addEventListener('blur', close);
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') { close(); }
    });
  }

  function init() {
    document.querySelectorAll('input[list="aisle-suggestions"]').forEach(enhance);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
