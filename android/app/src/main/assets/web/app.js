// WorkBuddy Mobile 交互逻辑 - 同源直连 (http://127.0.0.1:8800)

let currentTab = 'overview';
let activePollTimer = null;
let currentAuthUrl = '';

function showToast(msg) {
  let container = document.getElementById('toastContainer');
  if (!container) {
    container = document.createElement('div');
    container.id = 'toastContainer';
    container.style.cssText = 'position: fixed; top: 20px; left: 50%; transform: translateX(-50%); z-index: 9999; pointer-events: none;';
    document.body.appendChild(container);
  }
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = msg;
  container.appendChild(el);
  setTimeout(() => {
    el.style.opacity = '0';
    el.style.transition = 'opacity 0.3s ease';
    setTimeout(() => el.remove(), 300);
  }, 2000);
}

function copyAuthUrl() {
  if (currentAuthUrl) {
    copyText(currentAuthUrl);
    const btn = document.getElementById('copyAuthUrlBtn');
    if (btn) {
      btn.textContent = '✅ 已复制';
      setTimeout(() => { btn.textContent = '📋 复制链接'; }, 2000);
    }
  }
}

document.addEventListener('DOMContentLoaded', () => {
  loadOverview();
  setInterval(() => {
    if (document.visibilityState === 'visible' && currentTab === 'overview') {
      loadOverview();
    }
  }, 8000);
});

// Tab 切换
function switchTab(tab) {
  currentTab = tab;
  document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));

  const view = document.getElementById(`view-${tab}`);
  if (view) view.classList.add('active');

  const nav = document.querySelector(`.nav-item[data-tab="${tab}"]`);
  if (nav) nav.classList.add('active');

  if (tab === 'overview') loadOverview();
  if (tab === 'accounts') loadAccounts();
  if (tab === 'keys') loadKeys();
}

// 1. 概览
async function loadOverview() {
  const statusPill = document.getElementById('headerStatus');
  const statusText = document.getElementById('headerStatusText');

  try {
    const res = await fetch('/panel/api/overview', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

    const isOnline = (data.healthy > 0 || data.total > 0);
    statusText.textContent = isOnline ? '网关在线' : '等待账号';
    statusPill.style.color = isOnline ? 'var(--primary)' : 'var(--warning)';
    statusPill.style.background = isOnline ? 'rgba(16, 185, 129, 0.15)' : 'rgba(245, 158, 11, 0.15)';

    document.getElementById('statAccounts').textContent = `${data.healthy || 0} / ${data.total || 0}`;

    // 统计所有账号的用量与 Token
    let todayReq = 0;
    let todayTokens = 0;
    (data.accounts || []).forEach(a => {
      const u = a.token_usage || {};
      todayReq += (u.request_count || 0);
      todayTokens += (u.total_tokens || 0);
    });

    document.getElementById('statTodayReq').textContent = todayReq.toLocaleString();
    document.getElementById('statTodayTokens').textContent = todayTokens.toLocaleString();
    document.getElementById('statActiveKeys').textContent = data.healthy || 0;
    document.getElementById('infoCoreUrl').textContent = window.location.origin;
  } catch (e) {
    statusText.textContent = '连接中断';
    statusPill.style.color = 'var(--danger)';
    statusPill.style.background = 'rgba(239, 68, 68, 0.15)';
  }
}

// 2. 账号
async function loadAccounts() {
  const container = document.getElementById('accountList');
  try {
    const res = await fetch('/panel/api/overview', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    const accounts = data.accounts || [];
    document.getElementById('accountCount').textContent = accounts.length;

    if (accounts.length === 0) {
      container.innerHTML = `
        <div style="text-align: center; padding: 40px; color: var(--text-dim);">
          暂无账号，点击上方「+ 授权新账号」快速添加
        </div>`;
      return;
    }

    container.innerHTML = accounts.map(a => {
      const initial = (a.nickname || 'U').charAt(0).toUpperCase();
      const isOnline = !a.disabled && !a.cooling;
      const statusClass = isOnline ? 'tag-online' : 'tag-offline';
      const statusText = isOnline ? '● 在线' : (a.disabled ? '● 已停用' : '● 冷却中');
      const realmText = a.realm === 'global' ? '国际版' : '国内版';
      const tokensUsed = (a.token_usage && a.token_usage.total_tokens) ? a.token_usage.total_tokens.toLocaleString() : '0';
      const creditsDisplay = a.credits >= 0 ? `· 积分 ${a.credits}` : '';

      return `
        <div class="item-card">
          <div class="item-main">
            <div class="item-avatar">${initial}</div>
            <div class="item-info">
              <div class="item-title">
                <span>${escapeHtml(a.nickname)}</span>
                <span class="tag tag-global">${realmText}</span>
                <span class="tag ${statusClass}">${statusText}</span>
              </div>
              <div class="item-sub">UID: ${escapeHtml(a.uid)} ${creditsDisplay} · 消耗: ${tokensUsed} tokens</div>
            </div>
          </div>
          <button class="btn btn-outline btn-sm" onclick="copyText('${escapeHtml(a.uid)}')">复制UID</button>
        </div>`;
    }).join('');
  } catch (e) {
    container.innerHTML = `<div style="text-align: center; padding: 40px; color: var(--danger);">加载失败: ${e.message}</div>`;
  }
}

// 授权弹窗
function openAuthModal() {
  document.getElementById('authModal').classList.add('active');
  document.getElementById('authResultArea').style.display = 'none';
  document.getElementById('startAuthBtn').style.display = 'block';
  document.getElementById('startAuthBtn').disabled = false;
  document.getElementById('startAuthBtn').textContent = '获取授权链接';
  if (activePollTimer) {
    clearInterval(activePollTimer);
    activePollTimer = null;
  }
}

async function requestAuthLink() {
  const btn = document.getElementById('startAuthBtn');
  btn.disabled = true;
  btn.textContent = '正在向官方申请链接...';

  try {
    const res = await fetch('/panel/api/login/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ realm: 'global' })
    });
    const data = await res.json();
    if (data.ok && data.url) {
      currentAuthUrl = data.url;
      const urlInput = document.getElementById('authUrlInput');
      if (urlInput) urlInput.value = data.url;

      const linkBtn = document.getElementById('authLinkBtn');
      linkBtn.href = data.url;
      linkBtn.onclick = (e) => {
        window.open(data.url, '_blank');
      };
      document.getElementById('authResultArea').style.display = 'block';
      btn.style.display = 'none';

      // 启动自动轮询
      startAuthPolling(data.state);
    } else {
      alert('获取授权链接失败：' + (data.error || JSON.stringify(data)));
      btn.disabled = false;
      btn.textContent = '获取授权链接';
    }
  } catch (e) {
    alert('请求失败：' + e.message);
    btn.disabled = false;
    btn.textContent = '获取授权链接';
  }
}

function startAuthPolling(state) {
  if (activePollTimer) clearInterval(activePollTimer);
  const statusDiv = document.createElement('div');
  statusDiv.id = 'pollStatusText';
  statusDiv.style.cssText = 'font-size: 12px; color: var(--warning); margin-top: 8px; text-align: center; line-height: 1.6;';
  statusDiv.innerHTML = '⏳ 正在等待浏览器完成登录授权...<br><span style="color:var(--text-dim);font-size:11px;">完成后系统将自动检测并入库，无需刷新</span>';
  
  const oldText = document.getElementById('pollStatusText');
  if (oldText) oldText.remove();
  document.getElementById('authResultArea').appendChild(statusDiv);

  activePollTimer = setInterval(async () => {
    try {
      const res = await fetch('/panel/api/login/poll?state=' + encodeURIComponent(state));
      const data = await res.json();
      if (data.done) {
        clearInterval(activePollTimer);
        activePollTimer = null;
        statusDiv.style.color = 'var(--primary)';
        statusDiv.innerHTML = `🎉 <b>授权成功！</b><br>已添加账号 <b>${escapeHtml(data.nickname || data.uid)}</b>，已自动载入池中！`;
        setTimeout(() => {
          closeModal('authModal');
          loadAccounts();
          loadOverview();
        }, 1800);
      }
    } catch (ignored) {}
  }, 2500);
}

// 3. 接入配置
async function loadKeys() {
  const container = document.getElementById('keyList');
  const baseOrigin = window.location.origin;
  document.getElementById('baseUrlDisplay').textContent = `${baseOrigin}/v1`;

  container.innerHTML = `
    <div class="card" style="margin-bottom: 12px;">
      <div class="section-title">本地免鉴权模式已启用</div>
      <p style="font-size: 13px; color: var(--text-muted); line-height: 1.6;">
        App 运行在手机独立本地沙箱中，为保障第三方客户端极简接入，所有本地请求均免密放行。
      </p>
      <div style="font-size: 13px; color: var(--text-main); margin-top: 10px; font-family: var(--font-mono);">
        API 接口: <span style="color: var(--primary);">${baseOrigin}/v1/chat/completions</span><br>
        API Key: <span style="color: var(--accent);">任意填写（如 123456）</span>
      </div>
    </div>`;
}

function copyBaseUrl() {
  const base = window.location.origin.startsWith('http') ? window.location.origin : 'http://127.0.0.1:8800';
  copyText(`${base}/v1`);
}

// 4. 即时对话工作台 (直接调用本地引擎流式 SSE)
let chatHistory = [];

async function sendMessage() {
  const input = document.getElementById('chatInput');
  const text = input.value.trim();
  if (!text) return;

  const model = document.getElementById('chatModelSelect').value;
  input.value = '';
  const messagesBox = document.getElementById('chatMessages');

  // 用户气泡
  const userBubble = document.createElement('div');
  userBubble.className = 'chat-bubble user';
  userBubble.textContent = text;
  messagesBox.appendChild(userBubble);

  chatHistory.push({ role: 'user', content: text });

  // Assistant 气泡占位
  const aiBubble = document.createElement('div');
  aiBubble.className = 'chat-bubble assistant';
  aiBubble.innerHTML = '<span style="color: var(--text-dim);">思考中...</span>';
  messagesBox.appendChild(aiBubble);
  messagesBox.scrollTop = messagesBox.scrollHeight;

  const sendBtn = document.getElementById('sendBtn');
  sendBtn.disabled = true;

  let assistantContent = '';
  let thinkingContent = '';

  try {
    const response = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: model,
        messages: chatHistory,
        stream: true
      })
    });

    if (!response.ok) {
      const errText = await response.text();
      throw new Error(`HTTP ${response.status}: ${errText}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    aiBubble.innerHTML = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop(); // 保留不完整行

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || trimmed === 'data: [DONE]') continue;
        if (trimmed.startsWith('data: ')) {
          try {
            const parsed = JSON.parse(trimmed.slice(6));
            if (parsed.error) {
              assistantContent += `\n[错误: ${parsed.error.message || JSON.stringify(parsed.error)}]`;
            } else if (parsed.choices && parsed.choices[0]) {
              const delta = parsed.choices[0].delta || {};
              if (delta.reasoning_content) {
                thinkingContent += delta.reasoning_content;
              }
              if (delta.content) {
                assistantContent += delta.content;
              }
            }
          } catch (err) {}
        }
      }

      // 实时渲染思维链与正文
      let html = '';
      if (thinkingContent) {
        html += `<div class="thinking-block">💭 ${escapeHtml(thinkingContent)}</div>`;
      }
      html += escapeHtml(assistantContent) || '<span style="color: var(--text-dim);">输入中...</span>';
      aiBubble.innerHTML = html;
      messagesBox.scrollTop = messagesBox.scrollHeight;
    }

    chatHistory.push({ role: 'assistant', content: assistantContent });
  } catch (e) {
    aiBubble.textContent = `发送失败: ${e.message}`;
  } finally {
    sendBtn.disabled = false;
  }
}

// 通用工具
function closeModal(id) {
  document.getElementById(id).classList.remove('active');
  if (activePollTimer) {
    clearInterval(activePollTimer);
    activePollTimer = null;
  }
}

function copyText(str) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(str).then(() => {
      showToast('已复制到剪贴板！');
    }).catch(() => {
      fallbackCopy(str);
    });
  } else {
    fallbackCopy(str);
  }
}

function fallbackCopy(str) {
  const ta = document.createElement('textarea');
  ta.value = str;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand('copy');
    showToast('已复制到剪贴板！');
  } catch (e) {
    prompt('请手动选择复制以下内容：', str);
  } finally {
    document.body.removeChild(ta);
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
