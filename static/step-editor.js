// Instruction step editor for the Edit Recipe page: reorder (drag or arrow
// keys on the handle), add, remove, and split a step in two at the cursor.
// Mirrors ingredient-editor.js: rows carry a key, and the hidden
// `step_order` field lists the keys in their on-screen order. Existing
// steps keep their `e<n>` key so the server can carry their notes across;
// anything created here gets an `n<n>` key.
(function () {
  var HANDLE_SVG =
    '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">' +
    '<circle cx="7.5" cy="5" r="1.35"/><circle cx="12.5" cy="5" r="1.35"/>' +
    '<circle cx="7.5" cy="10" r="1.35"/><circle cx="12.5" cy="10" r="1.35"/>' +
    '<circle cx="7.5" cy="15" r="1.35"/><circle cx="12.5" cy="15" r="1.35"/></svg>';

  var counter = 0;

  function autosize(textarea) {
    // A box that has no width yet (page still laying out, tab hidden)
    // reports a nonsense scrollHeight; leave it and let the resize
    // observer below size it once it is actually on screen.
    if (!textarea.clientWidth) { return; }
    textarea.style.height = 'auto';
    textarea.style.height = textarea.scrollHeight + 'px';
  }

  function initEditor(block) {
    var editor = block.querySelector('.js-step-editor');
    var orderField = block.querySelector('.js-step-order');

    function rows() {
      return Array.prototype.slice.call(editor.querySelectorAll('.step-edit-row'));
    }

    // Renumber the badges and rewrite step_order after any change.
    function sync() {
      var keys = [];
      rows().forEach(function (row, index) {
        keys.push(row.dataset.key);
        row.querySelector('.step-edit-num').textContent = String(index + 1);
        var textarea = row.querySelector('.step-edit-text');
        textarea.setAttribute('aria-label', 'Step ' + (index + 1));
        row.querySelector('.ing-handle').setAttribute('aria-label', 'Reorder step ' + (index + 1));
      });
      orderField.value = keys.join(',');
    }

    function actionButton(cls, label, title) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'pantry-row-btn ' + cls;
      button.textContent = label;
      button.title = title;
      return button;
    }

    function buildRow(key, text) {
      var row = document.createElement('li');
      row.className = 'step-edit-row';
      row.dataset.key = key;

      var handle = document.createElement('button');
      handle.type = 'button';
      handle.className = 'ing-handle';
      handle.draggable = true;
      handle.title = 'Drag to reorder, or focus and use the arrow keys';
      handle.setAttribute('aria-label', 'Reorder step');
      handle.innerHTML = HANDLE_SVG;
      row.appendChild(handle);

      var num = document.createElement('span');
      num.className = 'step-edit-num';
      num.setAttribute('aria-hidden', 'true');
      row.appendChild(num);

      var textarea = document.createElement('textarea');
      textarea.className = 'step-edit-text';
      textarea.name = 'step_text_' + key;
      textarea.rows = 2;
      textarea.value = text || '';
      row.appendChild(textarea);

      var actions = document.createElement('div');
      actions.className = 'ing-row-actions';
      actions.appendChild(actionButton('js-split-step', 'Split here', 'Split this step in two at the cursor'));
      actions.appendChild(actionButton('js-remove-step', 'Remove', 'Remove this step'));
      row.appendChild(actions);

      return row;
    }

    // Everything before the cursor stays in this step; everything after it
    // becomes a new step directly below. With the cursor at the very end
    // (or nothing selected yet) that is simply "insert an empty step after".
    function splitStep(row) {
      var textarea = row.querySelector('.step-edit-text');
      var pos = typeof textarea.selectionStart === 'number' ? textarea.selectionStart : textarea.value.length;
      var before = textarea.value.slice(0, pos).trim();
      var after = textarea.value.slice(pos).trim();
      if (!before && after) {
        // Cursor at the start: nothing to keep here, so just leave it alone.
        textarea.focus();
        return;
      }
      textarea.value = before;
      autosize(textarea);
      var next = buildRow('n' + counter++, after);
      row.parentNode.insertBefore(next, row.nextSibling);
      sync();
      var nextArea = next.querySelector('.step-edit-text');
      autosize(nextArea);
      nextArea.focus();
      nextArea.setSelectionRange(0, 0);
    }

    block.querySelector('.js-add-step').addEventListener('click', function () {
      var row = buildRow('n' + counter++, '');
      editor.appendChild(row);
      sync();
      row.querySelector('.step-edit-text').focus();
    });

    editor.addEventListener('click', function (event) {
      var removeBtn = event.target.closest('.js-remove-step');
      if (removeBtn) {
        removeBtn.closest('.step-edit-row').remove();
        sync();
        return;
      }
      var splitBtn = event.target.closest('.js-split-step');
      if (splitBtn) {
        splitStep(splitBtn.closest('.step-edit-row'));
      }
    });

    editor.addEventListener('input', function (event) {
      var textarea = event.target.closest('.step-edit-text');
      if (textarea) { autosize(textarea); }
    });

    editor.addEventListener('keydown', function (event) {
      // Ctrl+Enter (Cmd+Enter on a Mac) inside a step splits it at the cursor.
      var textarea = event.target.closest('.step-edit-text');
      if (textarea && event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        splitStep(textarea.closest('.step-edit-row'));
        return;
      }

      // Arrow keys on a focused handle, so reordering is not mouse-only.
      var handle = event.target.closest('.ing-handle');
      if (!handle) { return; }
      if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') { return; }
      event.preventDefault();
      var row = handle.closest('.step-edit-row');
      if (event.key === 'ArrowUp' && row.previousElementSibling) {
        editor.insertBefore(row, row.previousElementSibling);
      } else if (event.key === 'ArrowDown' && row.nextElementSibling) {
        editor.insertBefore(row, row.nextElementSibling.nextSibling);
      }
      handle.focus();
      sync();
    });

    var dragging = null;

    editor.addEventListener('dragstart', function (event) {
      var handle = event.target.closest('.ing-handle');
      if (!handle) {
        event.preventDefault();
        return;
      }
      dragging = handle.closest('.step-edit-row');
      dragging.classList.add('is-dragging');
      event.dataTransfer.effectAllowed = 'move';
      // Firefox will not start a drag unless something is on the transfer.
      event.dataTransfer.setData('text/plain', dragging.dataset.key);
    });

    editor.addEventListener('dragend', function () {
      if (dragging) { dragging.classList.remove('is-dragging'); }
      dragging = null;
      sync();
    });

    editor.addEventListener('dragover', function (event) {
      if (!dragging) { return; }
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';

      var over = event.target.closest('.step-edit-row');
      if (!over || over === dragging) { return; }
      var box = over.getBoundingClientRect();
      var after = event.clientY > box.top + box.height / 2;
      editor.insertBefore(dragging, after ? over.nextSibling : over);
    });

    editor.addEventListener('drop', function (event) {
      event.preventDefault();
      sync();
    });

    function autosizeAll() {
      rows().forEach(function (row) { autosize(row.querySelector('.step-edit-text')); });
    }

    // Re-measure whenever the editor's width changes: first layout, a
    // window resize, or the pane it lives in becoming visible.
    if (typeof ResizeObserver === 'function') {
      new ResizeObserver(autosizeAll).observe(editor);
    } else {
      window.addEventListener('resize', autosizeAll);
    }
    window.addEventListener('load', autosizeAll);

    autosizeAll();
    sync();
  }

  document.querySelectorAll('.step-editor-block').forEach(initEditor);
})();
