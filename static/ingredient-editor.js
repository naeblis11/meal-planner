// Ingredient editor behaviour, shared by Edit Recipe and Review Import.
// One instance per `.ingredient-editor-block`; field names carry the
// block's data-prefix so several editors can post from a single form.
(function () {
  var HANDLE_SVG =
    '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">' +
    '<circle cx="7.5" cy="5" r="1.35"/><circle cx="12.5" cy="5" r="1.35"/>' +
    '<circle cx="7.5" cy="10" r="1.35"/><circle cx="12.5" cy="10" r="1.35"/>' +
    '<circle cx="7.5" cy="15" r="1.35"/><circle cx="12.5" cy="15" r="1.35"/></svg>';

  var counter = 0;

  function initEditor(block) {
    var prefix = block.dataset.prefix || '';
    var editor = block.querySelector('.js-ingredient-editor');
    var orderField = block.querySelector('.js-row-order');

    function syncOrder() {
      var keys = [];
      editor.querySelectorAll('.ing-row').forEach(function (row) {
        keys.push(row.dataset.key);
      });
      orderField.value = keys.join(',');
    }

    function field(cls, name, value, listId, placeholder) {
      var input = document.createElement('input');
      input.type = 'text';
      input.className = 'ing-field ' + cls;
      input.name = prefix + name;
      input.value = value || '';
      if (listId) { input.setAttribute('list', listId); }
      if (placeholder) { input.placeholder = placeholder; }
      return input;
    }

    function kindSelect(key, kind) {
      var select = document.createElement('select');
      select.className = 'ing-kind';
      select.name = prefix + 'row_kind_' + key;
      select.setAttribute('aria-label', 'Row type');
      [['ingredient', 'Ingredient'], ['section', 'Sub-recipe']].forEach(function (pair) {
        var option = document.createElement('option');
        option.value = pair[0];
        option.textContent = pair[1];
        option.selected = pair[0] === kind;
        select.appendChild(option);
      });
      return select;
    }

    function actionButton(cls, label, title) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'pantry-row-btn ' + cls;
      button.textContent = label;
      button.title = title;
      return button;
    }

    function buildRow(kind, key, values) {
      values = values || {};
      var row = document.createElement('div');
      row.className = 'ing-row ing-row--' + kind;
      row.dataset.key = key;

      var handle = document.createElement('button');
      handle.type = 'button';
      handle.className = 'ing-handle';
      handle.draggable = true;
      handle.title = 'Drag to reorder, or focus and use the arrow keys';
      handle.setAttribute('aria-label', 'Reorder row');
      handle.innerHTML = HANDLE_SVG;
      row.appendChild(handle);

      row.appendChild(kindSelect(key, kind));

      if (kind === 'section') {
        row.appendChild(field('ing-field--section', 'row_name_' + key, values.name, null, 'Sub-recipe name'));
      } else {
        row.appendChild(field('ing-field--name', 'row_name_' + key, values.name, null, 'Ingredient'));
        row.appendChild(field('ing-field--amount', 'row_amount_' + key, values.amount, null, 'Amount'));
        row.appendChild(field('ing-field--unit', 'row_unit_' + key, values.unit, 'unit-suggestions', 'Unit'));
      }

      var actions = document.createElement('div');
      actions.className = 'ing-row-actions';
      if (kind === 'ingredient') {
        var noteBtn = actionButton('js-add-note', 'Note', 'Add a note to this ingredient (e.g. sifted, to taste)');
        noteBtn.hidden = !!values.notes;
        actions.appendChild(noteBtn);
      }
      actions.appendChild(actionButton('js-remove-row', 'Remove', 'Remove this row'));
      row.appendChild(actions);

      if (kind === 'ingredient') {
        // The note line stays hidden until the row has a note or the user
        // asks for one, so rows without notes keep to a single line.
        var noteLine = document.createElement('div');
        noteLine.className = 'ing-note-line';
        noteLine.hidden = !values.notes;
        var noteInput = field('ing-field--notes', 'row_notes_' + key, values.notes, null,
          'Note, e.g. sifted, to taste (separate several with ;)');
        noteInput.setAttribute('aria-label', 'Note for ingredient');
        noteLine.appendChild(noteInput);
        row.appendChild(noteLine);
      }

      return row;
    }

    block.querySelector('.js-add-ingredient').addEventListener('click', function () {
      var row = buildRow('ingredient', 'n' + counter++);
      editor.appendChild(row);
      syncOrder();
      row.querySelector('input[type="text"]').focus();
    });

    block.querySelector('.js-add-section').addEventListener('click', function () {
      var row = buildRow('section', 'n' + counter++);
      editor.appendChild(row);
      syncOrder();
      row.querySelector('input[type="text"]').focus();
    });

    editor.addEventListener('click', function (event) {
      var removeBtn = event.target.closest('.js-remove-row');
      if (removeBtn) {
        removeBtn.closest('.ing-row').remove();
        syncOrder();
        return;
      }
      var noteBtn = event.target.closest('.js-add-note');
      if (noteBtn) {
        var noteLine = noteBtn.closest('.ing-row').querySelector('.ing-note-line');
        noteBtn.hidden = true;
        noteLine.hidden = false;
        noteLine.querySelector('input').focus();
      }
    });

    // Switching a row between Ingredient and Sub-recipe rebuilds it as the
    // other kind, keeping the name so nothing typed is lost.
    editor.addEventListener('change', function (event) {
      var select = event.target.closest('.ing-kind');
      if (!select) { return; }
      var row = select.closest('.ing-row');
      var currentKind = row.classList.contains('ing-row--section') ? 'section' : 'ingredient';
      if (select.value === currentKind) { return; }
      var nameInput = row.querySelector('.ing-field--name, .ing-field--section');
      var amountInput = row.querySelector('.ing-field--amount');
      var unitInput = row.querySelector('.ing-field--unit');
      var notesInput = row.querySelector('.ing-field--notes');
      var replacement = buildRow(select.value, 'n' + counter++, {
        name: nameInput ? nameInput.value : '',
        amount: amountInput ? amountInput.value : '',
        unit: unitInput ? unitInput.value : '',
        notes: notesInput ? notesInput.value : ''
      });
      row.replaceWith(replacement);
      syncOrder();
      replacement.querySelector('input[type="text"]').focus();
    });

    // A row flagged as needing input stays red until its amount is touched.
    editor.addEventListener('input', function (event) {
      var amount = event.target.closest('.ing-field--amount');
      if (!amount) { return; }
      var row = amount.closest('.ing-row');
      if (row.classList.contains('ing-row--needs-input')) {
        row.classList.remove('ing-row--needs-input');
        amount.removeAttribute('aria-invalid');
        block.dispatchEvent(new CustomEvent('needs-input-resolved', { bubbles: true }));
      }
    });

    var dragging = null;

    editor.addEventListener('dragstart', function (event) {
      var handle = event.target.closest('.ing-handle');
      if (!handle) {
        event.preventDefault();
        return;
      }
      dragging = handle.closest('.ing-row');
      dragging.classList.add('is-dragging');
      event.dataTransfer.effectAllowed = 'move';
      // Firefox will not start a drag unless something is on the transfer.
      event.dataTransfer.setData('text/plain', dragging.dataset.key);
    });

    editor.addEventListener('dragend', function () {
      if (dragging) { dragging.classList.remove('is-dragging'); }
      dragging = null;
      syncOrder();
    });

    editor.addEventListener('dragover', function (event) {
      if (!dragging) { return; }
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';

      var over = event.target.closest('.ing-row');
      if (!over || over === dragging) { return; }
      var box = over.getBoundingClientRect();
      var after = event.clientY > box.top + box.height / 2;
      editor.insertBefore(dragging, after ? over.nextSibling : over);
    });

    editor.addEventListener('drop', function (event) {
      event.preventDefault();
      syncOrder();
    });

    // Arrow keys on a focused handle, so reordering is not mouse-only.
    editor.addEventListener('keydown', function (event) {
      var handle = event.target.closest('.ing-handle');
      if (!handle) { return; }
      if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') { return; }
      event.preventDefault();
      var row = handle.closest('.ing-row');
      if (event.key === 'ArrowUp' && row.previousElementSibling) {
        editor.insertBefore(row, row.previousElementSibling);
      } else if (event.key === 'ArrowDown' && row.nextElementSibling) {
        editor.insertBefore(row, row.nextElementSibling.nextSibling);
      }
      handle.focus();
      syncOrder();
    });

    syncOrder();
  }

  document.querySelectorAll('.ingredient-editor-block').forEach(initEditor);
})();
