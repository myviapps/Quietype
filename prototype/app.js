const composer = document.querySelector('#composer');
const keys = document.querySelector('#keys');
const panel = document.querySelector('#panel');
const notice = document.querySelector('#notice');
const modeButton = document.querySelector('#modeButton');
const subscriptionToggle = document.querySelector('#subscriptionToggle');
const cloudToggle = document.querySelector('#cloudToggle');
const numberRowToggle = document.querySelector('#numberRowToggle');
const hapticsToggle = document.querySelector('#hapticsToggle');
const localModelSelect = document.querySelector('#localModelSelect');

const modes = ['Grammar Only', 'Natural Casual', 'Professional'];
let modeIndex = 0;
let keyboardMode = 'letters';
let shift = false;
let lastSpace = false;
let emojiMix = [];
let clips = [
  { text: 'Please check and confirm.', pinned: true },
  { text: 'I will send the update today.', pinned: false }
];
let templates = [
  { title: 'Polite follow-up', text: 'Hi, just following up on this. Please let me know when you get a chance.' },
  { title: 'Quick thanks', text: 'Thanks for the update. I appreciate it.' }
];

const layouts = {
  letters: [
    ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
    ['spacer', 'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'spacer'],
    ['shift', 'z', 'x', 'c', 'v', 'b', 'n', 'm', 'back'],
    ['?123', 'emoji', 'mix', ',', 'space', '.', 'enter']
  ],
  symbols: [
    ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
    ['@', '#', '$', '_', '&', '-', '+', '(', ')'],
    ['ABC', '/', '*', "'", '"', ':', ';', '!', '?', 'back'],
    ['ABC', 'emoji', 'mix', ',', 'space', '.', 'enter']
  ],
  emoji: [
    ['😀', '😂', '😊', '😍', '👍', '🙏', '🔥', '🎉'],
    ['❤️', '✅', '✨', '👀', '🤔', '😅', '😭', '💯'],
    ['ABC', 'mix', 'space', 'back', 'enter']
  ],
  mix: [
    ['😀', '😂', '😍', '😎', '🤝', '🙏', '🔥', '✨'],
    ['❤️', '💔', '💯', '✅', '⭐', '🎯', '🚀', '🎉'],
    ['→', '+', '×', '/', '|', '•', '~', '_'],
    ["(ง'̀-'́)ง", '¯\\_(ツ)_/¯', '༼ つ ◕_◕ ༽つ'],
    ['ABC', 'clearMix', 'saveMix', 'pasteMix', 'back']
  ]
};

function renderKeys() {
  keys.innerHTML = '';
  const rows = layouts[keyboardMode];
  if (keyboardMode === 'letters' && numberRowToggle.checked) {
    addRow(['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'], 'symbols');
  }
  rows.forEach((row, index) => {
    const className = rowClass(keyboardMode, index, row);
    addRow(row, className);
  });
}

function rowClass(mode, index, row) {
  if (mode === 'letters' && index === 0) return 'letters-10';
  if (mode === 'letters' && index === 1) return 'letters-9';
  if (mode === 'letters' && index === 3) return 'bottom';
  if (mode === 'symbols') return index === 3 ? 'bottom' : 'symbols';
  if (mode === 'emoji') return index === 2 ? 'mix-actions' : 'emoji';
  if (mode === 'mix') return index === 4 ? 'mix-actions' : row.length === 3 ? 'mix-actions' : 'emoji';
  return 'symbols';
}

function addRow(row, className) {
  const rowEl = document.createElement('div');
  rowEl.className = `key-row ${className}`;
  row.forEach((label) => {
    if (label === 'spacer') {
      const spacer = document.createElement('span');
      spacer.className = 'spacer';
      rowEl.appendChild(spacer);
      return;
    }
    const button = document.createElement('button');
    button.textContent = displayLabel(label);
    if (['space', 'enter', 'back', 'shift', '?123', 'ABC'].includes(label)) {
      button.classList.add('wide');
    }
    button.addEventListener('click', () => pressKey(label));
    rowEl.appendChild(button);
  });
  keys.appendChild(rowEl);
}

function displayLabel(label) {
  const labels = {
    space: 'English',
    back: 'Back',
    enter: 'Enter',
    shift: shift ? 'SHIFT' : 'Shift',
    emoji: 'Emoji',
    mix: 'Mix',
    clearMix: 'Clear',
    saveMix: 'Save',
    pasteMix: 'Paste'
  };
  return labels[label] || label;
}

function pressKey(label) {
  pulse();
  if (keyboardMode === 'mix' && !['ABC', 'clearMix', 'saveMix', 'pasteMix', 'back'].includes(label)) {
    emojiMix.push(label);
    renderEmojiMixPanel();
    renderKeys();
    return;
  }

  if (label === 'space') return handleSpace();
  if (label === 'back') return backspace();
  if (label === 'enter') return insertText('\n');
  if (label === 'shift') {
    shift = !shift;
    renderKeys();
    return;
  }
  if (label === '?123') return switchKeyboard('symbols');
  if (label === 'ABC') return switchKeyboard('letters');
  if (label === 'emoji') return switchKeyboard('emoji');
  if (label === 'mix') return switchKeyboard('mix');
  if (label === 'clearMix') {
    emojiMix = [];
    renderEmojiMixPanel();
    return;
  }
  if (label === 'saveMix') return saveEmojiMix();
  if (label === 'pasteMix') return pasteEmojiMix();

  const output = shift && keyboardMode === 'letters' ? label.toUpperCase() : label;
  insertText(output);
  if (['.', '!', '?'].includes(label)) {
    shift = true;
    renderKeys();
  } else if (shift && keyboardMode === 'letters') {
    shift = false;
    renderKeys();
  }
}

function switchKeyboard(next) {
  keyboardMode = next;
  panel.classList.add('hidden');
  renderKeys();
}

function insertText(text) {
  const start = composer.selectionStart;
  const end = composer.selectionEnd;
  composer.value = composer.value.slice(0, start) + text + composer.value.slice(end);
  composer.selectionStart = composer.selectionEnd = start + text.length;
  composer.focus();
  lastSpace = false;
}

function handleSpace() {
  if (lastSpace && composer.value[composer.selectionStart - 1] === ' ') {
    backspace(false);
    insertText('. ');
    shift = true;
    renderKeys();
    return;
  }
  insertText(' ');
  lastSpace = true;
}

function backspace(resetSpace = true) {
  const start = composer.selectionStart;
  const end = composer.selectionEnd;
  if (start !== end) {
    composer.value = composer.value.slice(0, start) + composer.value.slice(end);
    composer.selectionStart = composer.selectionEnd = start;
  } else if (start > 0) {
    composer.value = composer.value.slice(0, start - 1) + composer.value.slice(start);
    composer.selectionStart = composer.selectionEnd = start - 1;
  }
  if (resetSpace) lastSpace = false;
  composer.focus();
}

function rewriteActiveText(forceMode) {
  if (!subscriptionToggle.checked) {
    setNotice('Subscription required. Normal typing still works.', true);
    return;
  }
  const mode = forceMode || modes[modeIndex];
  const selection = getSelectedText();
  const source = selection.text || currentSentence();
  if (!source.text.trim()) {
    setNotice('Type or select text to rewrite.', true);
    return;
  }
  const rewritten = rewrite(source.text, mode);
  replaceRange(source.start, source.end, rewritten);
  setNotice(`Rewritten with ${mode} using ${modelLabel(localModelSelect.value)}.`);
}

function getSelectedText() {
  const start = composer.selectionStart;
  const end = composer.selectionEnd;
  return { start, end, text: start === end ? '' : composer.value.slice(start, end) };
}

function currentSentence() {
  const value = composer.value;
  const cursor = composer.selectionStart;
  const left = value.slice(0, cursor);
  const right = value.slice(cursor);
  const leftBreak = Math.max(left.lastIndexOf('.'), left.lastIndexOf('!'), left.lastIndexOf('?'), left.lastIndexOf('\n'));
  const rightCandidates = ['.', '!', '?', '\n'].map((mark) => right.indexOf(mark)).filter((index) => index >= 0);
  const rightBreak = rightCandidates.length ? Math.min(...rightCandidates) + cursor + 1 : value.length;
  const start = leftBreak >= 0 ? leftBreak + 1 : 0;
  return { start, end: rightBreak, text: value.slice(start, rightBreak).trim() };
}

function replaceRange(start, end, text) {
  composer.value = composer.value.slice(0, start) + text + composer.value.slice(end);
  composer.selectionStart = composer.selectionEnd = start + text.length;
  composer.focus();
}

function rewrite(text, mode) {
  const fixes = {
    teh: 'the',
    dont: "don't",
    cant: "can't",
    wont: "won't",
    im: "I'm",
    ive: "I've",
    thier: 'their',
    grammer: 'grammar',
    whihc: 'which',
    humam: 'human',
    offile: 'offline',
    subscripton: 'subscription'
  };
  let output = text
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\b[A-Za-z']+\b/g, (word) => fixes[word.toLowerCase()] || word)
    .replace(/\bi\b/g, 'I')
    .replace(/\s+([,.!?])/g, '$1');

  output = output.charAt(0).toUpperCase() + output.slice(1);
  if (mode === 'Natural Casual') {
    output = output.replace(/\bI would like to\b/g, 'I want to').replace(/\bPlease let me know\b/g, 'Let me know');
  }
  if (mode === 'Professional') {
    output = output.replace(/\bI want to\b/g, 'I would like to').replace(/\bLet me know\b/g, 'Please let me know');
  }
  if (!/[.!?]$/.test(output)) output += '.';
  return output;
}

function modelLabel(value) {
  if (value === 'SMOLLM2_360M') return 'SmolLM2 360M';
  if (value === 'QWEN3_06B') return 'Qwen3 0.6B';
  return cloudToggle.checked ? 'cloud fallback' : 'deterministic cleanup';
}

function renderPanel(name) {
  if (name === 'clipboard') return renderClipboardPanel();
  if (name === 'templates') return renderTemplatesPanel();
  if (name === 'emojiMix') {
    keyboardMode = 'mix';
    renderEmojiMixPanel();
    renderKeys();
  }
}

function renderClipboardPanel(query = '') {
  panel.classList.remove('hidden');
  const visible = clips.filter((clip) => clip.text.toLowerCase().includes(query.toLowerCase()));
  panel.innerHTML = `
    <div class="panel-header">
      <h3>Clipboard</h3>
      <button data-close>Close</button>
    </div>
    <button data-save-current>Save current text</button>
    <input id="clipSearch" type="text" placeholder="Search clips" value="${escapeHtml(query)}" />
    <div class="panel-list">
      ${visible.map((clip, index) => `
        <div class="clip">
          <span>${clip.pinned ? 'Pinned: ' : ''}${escapeHtml(clip.text)}</span>
          <button data-paste-clip="${index}">Paste</button>
          <button data-delete-clip="${index}">Delete</button>
        </div>
      `).join('')}
    </div>
  `;
  panel.querySelector('[data-close]').addEventListener('click', () => panel.classList.add('hidden'));
  panel.querySelector('[data-save-current]').addEventListener('click', saveCurrentClip);
  panel.querySelector('#clipSearch').addEventListener('input', (event) => renderClipboardPanel(event.target.value));
  panel.querySelectorAll('[data-paste-clip]').forEach((button) => {
    button.addEventListener('click', () => insertText(visible[Number(button.dataset.pasteClip)].text));
  });
  panel.querySelectorAll('[data-delete-clip]').forEach((button) => {
    const clip = visible[Number(button.dataset.deleteClip)];
    button.addEventListener('click', () => {
      clips = clips.filter((item) => item !== clip);
      renderClipboardPanel(query);
    });
  });
}

function saveCurrentClip() {
  if (!subscriptionToggle.checked) return setNotice('Subscription required to save clips.', true);
  const selection = getSelectedText();
  const text = selection.text || currentSentence().text;
  if (!text.trim()) return setNotice('Nothing to save.', true);
  clips.unshift({ text, pinned: false });
  renderClipboardPanel();
  setNotice('Clip saved.');
}

function renderTemplatesPanel() {
  panel.classList.remove('hidden');
  panel.innerHTML = `
    <div class="panel-header">
      <h3>Templates</h3>
      <button data-close>Close</button>
    </div>
    <div class="panel-list">
      ${templates.map((template, index) => `
        <div class="clip">
          <span>${escapeHtml(template.title)}</span>
          <button data-paste-template="${index}">Paste</button>
          <button data-delete-template="${index}">Delete</button>
        </div>
      `).join('')}
    </div>
  `;
  panel.querySelector('[data-close]').addEventListener('click', () => panel.classList.add('hidden'));
  panel.querySelectorAll('[data-paste-template]').forEach((button) => {
    button.addEventListener('click', () => insertText(templates[Number(button.dataset.pasteTemplate)].text));
  });
  panel.querySelectorAll('[data-delete-template]').forEach((button) => {
    button.addEventListener('click', () => {
      templates.splice(Number(button.dataset.deleteTemplate), 1);
      renderTemplatesPanel();
    });
  });
}

function renderEmojiMixPanel() {
  panel.classList.remove('hidden');
  panel.innerHTML = `
    <div class="panel-header">
      <h3>Emoji Mix</h3>
      <button data-close>Close</button>
    </div>
    <div class="mix-preview">${emojiMix.length ? escapeHtml(emojiMix.join('')) : 'Tap emojis, symbols, or kaomoji to combine.'}</div>
  `;
  panel.querySelector('[data-close]').addEventListener('click', () => panel.classList.add('hidden'));
}

function pasteEmojiMix() {
  if (!emojiMix.length) return setNotice('Add emojis or symbols first.', true);
  insertText(emojiMix.join(''));
}

function saveEmojiMix() {
  if (!subscriptionToggle.checked) return setNotice('Subscription required to save emoji mixes.', true);
  if (!emojiMix.length) return setNotice('Add emojis or symbols first.', true);
  clips.unshift({ text: emojiMix.join(''), pinned: true });
  setNotice('Emoji mix saved as a pinned clip.');
}

function setNotice(message, error = false) {
  notice.textContent = message;
  notice.classList.toggle('locked', error);
}

function pulse() {
  if (!hapticsToggle.checked) return;
  document.body.animate([{ filter: 'brightness(1)' }, { filter: 'brightness(1.05)' }, { filter: 'brightness(1)' }], {
    duration: 100,
    easing: 'ease-out'
  });
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;'
  })[char]);
}

document.querySelectorAll('[data-action]').forEach((button) => {
  button.addEventListener('click', () => {
    const action = button.dataset.action;
    if (action === 'rewrite') rewriteActiveText();
    if (action === 'mode') {
      modeIndex = (modeIndex + 1) % modes.length;
      modeButton.textContent = modes[modeIndex];
      setNotice(`Mode: ${modes[modeIndex]}`);
    }
    if (action === 'humanTone') {
      modeIndex = 1;
      modeButton.textContent = modes[modeIndex];
      rewriteActiveText('Natural Casual');
    }
    if (action === 'professional') {
      modeIndex = 2;
      modeButton.textContent = modes[modeIndex];
      rewriteActiveText('Professional');
    }
  });
});

document.querySelectorAll('[data-panel]').forEach((button) => {
  button.addEventListener('click', () => renderPanel(button.dataset.panel));
});

numberRowToggle.addEventListener('change', renderKeys);
cloudToggle.addEventListener('change', () => {
  setNotice(cloudToggle.checked ? 'Cloud fallback allowed for this prototype.' : 'Cloud fallback disabled.');
});

renderKeys();
