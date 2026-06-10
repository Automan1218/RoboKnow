export function fetchSessions() {
  return request<Api.Chat.Session[]>({ url: 'users/conversation/sessions' });
}

export function createSession() {
  return request<{ convId: string }>({ url: 'users/conversation/sessions', method: 'post' });
}

export function switchSession(convId: string) {
  return request<null>({ url: `users/conversation/sessions/${convId}/switch`, method: 'post' });
}

export function deleteSession(convId: string) {
  return request<null>({ url: `users/conversation/sessions/${convId}`, method: 'delete' });
}
