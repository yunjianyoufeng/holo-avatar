/**
 * 界面逻辑：配置、状态机、字幕、与原生桥接。
 *
 * 原生 → 页面：window.Holo.onEvent(json) / window.Holo.onLevel(v)
 * 页面 → 原生：window.HoloBridge.*
 */
(function () {
  'use strict';

  var bridge = window.HoloBridge || null;

  var el = {
    status: document.getElementById('status'),
    statusText: document.getElementById('statusText'),
    caption: document.getElementById('caption'),
    hint: document.getElementById('hint'),
    sheet: document.getElementById('settings'),
    workspace: document.getElementById('f-workspace'),
    key: document.getElementById('f-key'),
    region: document.getElementById('f-region'),
    voice: document.getElementById('f-voice'),
    instructions: document.getElementById('f-instructions'),
    save: document.getElementById('btn-save'),
    cancel: document.getElementById('btn-cancel')
  };

  var stage = new AvatarStage(document.getElementById('stage'));
  stage.start();

  var state = 'idle';
  var aiText = '';
  var userText = '';
  var clearTimer = null;

  // ------------------------------------------------------------ 状态

  var STATE_TEXT = {
    idle: '待机',
    sleep: '休眠中',
    connecting: '连接中',
    reconnecting: '重连中',
    listening: '在听',
    thinking: '思考中',
    speaking: '说话中',
    error: '出错了'
  };

  var STATE_HINT = {
    sleep: '走近一点，我就醒了',
    idle: '说句话，试着和我聊聊'
  };

  function setState(s) {
    state = s;
    stage.setState(s);

    el.status.className = 'status ' + s;
    el.statusText.textContent = STATE_TEXT[s] || s;

    var hint = STATE_HINT[s];
    if (hint) {
      el.hint.textContent = hint;
      el.hint.classList.add('show');
    } else {
      el.hint.classList.remove('show');
    }
  }

  // ------------------------------------------------------------ 字幕

  function renderCaption() {
    var text = aiText || userText;
    if (!text) {
      el.caption.classList.remove('show');
      el.caption.innerHTML = '';
      return;
    }

    var html = '';
    if (aiText && userText) {
      html += '<span class="user">' + escapeHtml(userText) + '</span>';
    }
    html += escapeHtml(text);
    el.caption.innerHTML = html;
    el.caption.classList.add('show');
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function scheduleClear(ms) {
    if (clearTimer) clearTimeout(clearTimer);
    clearTimer = setTimeout(function () {
      aiText = '';
      userText = '';
      renderCaption();
    }, ms);
  }

  // ------------------------------------------------------------ 配置

  function openSettings(prefill) {
    var cfg = prefill || {};
    el.workspace.value = cfg.workspaceId || '';
    el.key.value = cfg.apiKey || '';
    el.region.value = cfg.region || 'cn-beijing';
    el.voice.value = cfg.voice || 'Tina';
    el.instructions.value = cfg.instructions || '';
    el.sheet.classList.remove('hidden');
  }

  function closeSettings() {
    el.sheet.classList.add('hidden');
  }

  function currentConfig() {
    return {
      workspaceId: el.workspace.value.trim(),
      apiKey: el.key.value.trim(),
      region: el.region.value,
      voice: el.voice.value.trim() || 'Tina',
      instructions: el.instructions.value.trim(),
      model: 'qwen3.8-omni-flash-realtime'
    };
  }

  el.save.addEventListener('click', function () {
    var cfg = currentConfig();
    if (!cfg.workspaceId || !cfg.apiKey) {
      alert('Workspace ID 和 API Key 不能为空');
      return;
    }
    if (bridge) {
      bridge.saveConfig(JSON.stringify(cfg));
      closeSettings();
      setState('connecting');
      bridge.start();
    } else {
      closeSettings();
      alert('未检测到原生桥接，无法启动语音链路');
    }
  });

  el.cancel.addEventListener('click', function () {
    closeSettings();
  });

  // 长按 5 秒打开配置
  var pressTimer = null;
  document.addEventListener('touchstart', function (e) {
    if (e.target.closest && e.target.closest('.sheet')) return;
    pressTimer = setTimeout(function () {
      openSettings(loadedConfig);
    }, 5000);
  }, { passive: true });

  ['touchend', 'touchmove', 'touchcancel'].forEach(function (evt) {
    document.addEventListener(evt, function () {
      if (pressTimer) clearTimeout(pressTimer);
      pressTimer = null;
    }, { passive: true });
  });

  // ------------------------------------------------------------ 与原生通信

  var loadedConfig = null;

  window.Holo = {
    onLevel: function (v) {
      stage.setLevel(v);
    },

    onEvent: function (json) {
      var msg;
      try {
        msg = typeof json === 'string' ? JSON.parse(json) : json;
      } catch (e) {
        return;
      }

      switch (msg.type) {
        case 'state':
          if (msg.state === 'need_config') {
            openSettings(loadedConfig);
            setState('idle');
          } else {
            if (msg.state === 'listening' && state === 'speaking') {
              scheduleClear(4000);
            }
            setState(msg.state);
          }
          break;

        case 'caption':
          if (msg.role === 'user') {
            userText = msg.text;
            aiText = '';
          } else {
            aiText += msg.text;
          }
          renderCaption();
          break;

        case 'error':
          setState('error');
          el.caption.innerHTML = escapeHtml(msg.message || '出错了');
          el.caption.classList.add('show');
          scheduleClear(5000);
          break;
      }
    }
  };

  // ------------------------------------------------------------ 启动

  function boot() {
    if (!bridge) {
      setState('idle');
      el.hint.textContent = '预览模式（未在柜子 App 内运行）';
      el.hint.classList.add('show');
      return;
    }

    var raw = bridge.loadConfig();
    if (raw) {
      try {
        loadedConfig = JSON.parse(raw);
      } catch (e) {
        loadedConfig = null;
      }
    }

    if (loadedConfig && loadedConfig.apiKey && loadedConfig.workspaceId) {
      // 有配置：先休眠，等人走近由原生唤醒（不再自动连接）
      setState('sleep');
    } else {
      openSettings(loadedConfig);
      setState('idle');
    }
  }

  setState('idle');
  boot();

  // 预览用：index.html?demo=1 可看"说话中"的样子，方便美工/现场比对
  if (location.search.indexOf('demo') >= 0) {
    setState('speaking');
    stage.setLevel(0.8);
    aiText = '你好，我是这里的全息讲解员，有什么想了解的可以直接问我。';
    renderCaption();
  }
})();
