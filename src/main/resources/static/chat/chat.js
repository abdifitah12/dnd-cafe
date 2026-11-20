const element = (id) => document.getElementById(id);
const staff = new URLSearchParams(location.search).get('staff') === '1';
let session;
try { session = JSON.parse(localStorage.getItem('dnd-chat-session')); } catch { localStorage.removeItem('dnd-chat-session'); }
let adminToken = '';
let conversationId = staff ? '' : session?.id;
let lastId = 0;
let polling = false;
let generation = 0;

async function request(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...options.headers };
  if (staff) headers.Authorization = `Bearer ${adminToken}`;
  else if (session) headers['X-Chat-Token'] = session.token;
  const response = await fetch(`/api/chat${path}`, { ...options, headers });
  if (!response.ok) throw new Error(response.status === 401 || response.status === 403
    ? 'Your session could not be verified. Please sign in again or start a new conversation.'
    : 'We couldn’t connect to the cafe. Please try again.');
  return response.json();
}

function messagePath() {
  return `${staff ? '/admin' : ''}/conversations/${conversationId}/messages`;
}

function render(message) {
  if (element('messages').querySelector(`[data-id="${message.id}"]`)) return;
  element('messages').querySelector('.empty')?.remove();
  const bubble = document.createElement('div');
  bubble.className = `message${message.sender === (staff ? 'staff' : 'visitor') ? ' mine' : ''}`;
  bubble.dataset.id = message.id;
  const label = document.createElement('small');
  label.textContent = `${message.sender === 'staff' ? 'Cafe team' : 'Visitor'} · ${new Date(message.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
  const content = document.createElement('p');
  content.textContent = message.content;
  bubble.append(label, content);
  element('messages').append(bubble);
  element('messages').scrollTop = element('messages').scrollHeight;
}

async function poll() {
  if (!conversationId || polling) return;
  polling = true;
  const currentGeneration = generation;
  try {
    const messages = await request(`${messagePath()}?after=${lastId}`);
    if (currentGeneration !== generation) return;
    messages.forEach((message) => { render(message); lastId = Math.max(lastId, message.id); });
    element('status').textContent = 'Connected';
    element('error').textContent = '';
  } catch (error) {
    if (currentGeneration === generation) {
      element('status').textContent = 'Reconnecting…';
      element('error').textContent = error.message;
    }
  } finally { polling = false; }
}

async function loadInbox() {
  const conversations = await request('/admin/conversations');
  const select = element('conversations');
  select.replaceChildren(new Option('Choose a conversation', ''));
  conversations.forEach((conversation) => select.add(new Option(
    `${conversation.visitorName} · ${new Date(conversation.createdAt).toLocaleString()}`, conversation.id)));
  select.value = conversationId;
}

element('start-form').hidden = staff || Boolean(conversationId);
element('login-form').hidden = !staff;
element('message-form').hidden = !conversationId;
if (staff) element('title').textContent = 'Staff inbox';

element('start-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    session = await request('/conversations', { method: 'POST', body: JSON.stringify({ name: element('name').value.trim() }) });
    localStorage.setItem('dnd-chat-session', JSON.stringify(session));
    conversationId = session.id;
    element('start-form').hidden = true;
    element('message-form').hidden = false;
    element('message').focus();
    await poll();
  } catch (error) { element('error').textContent = error.message; }
  finally { button.disabled = false; }
});

element('login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  event.submitter.disabled = true;
  try {
    const response = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: element('username').value, password: element('password').value }) });
    if (!response.ok) throw new Error('Please check your staff username and password.');
    adminToken = (await response.json()).token;
    await loadInbox();
    element('password').value = '';
    element('login-form').hidden = true;
    element('conversation-list').hidden = false;
    element('error').textContent = '';
  } catch (error) { element('error').textContent = error.message; }
  finally { event.submitter.disabled = false; }
});

element('conversations').addEventListener('change', () => {
  generation++;
  conversationId = element('conversations').value;
  lastId = 0;
  element('messages').replaceChildren();
  element('message-form').hidden = !conversationId;
  void poll();
});
element('refresh').addEventListener('click', () => loadInbox().catch((error) => { element('error').textContent = error.message; }));

element('message-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const content = element('message').value.trim();
  if (!content) return;
  element('send').disabled = true;
  const currentGeneration = generation;
  try {
    await request(messagePath(), { method: 'POST', body: JSON.stringify({ content }) });
    if (currentGeneration === generation) { element('message').value = ''; await poll(); }
  } catch (error) { element('error').textContent = error.message; }
  finally { element('send').disabled = false; }
});
void poll();
setInterval(() => { if (!document.hidden) void poll(); }, 2000);
