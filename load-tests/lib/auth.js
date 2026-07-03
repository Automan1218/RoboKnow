import http from 'k6/http';
import { BASE_URL, USERNAME, PASSWORD } from './config.js';

/**
 * 登录并返回 JWT token。
 * 在各场景的 setup() 中调用一次，避免每次迭代重复登录。
 */
export function login() {
  const res = http.post(
    `${BASE_URL}/api/v1/users/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  let token;
  try {
    token = JSON.parse(res.body).data?.token;
  } catch {
    // fallthrough
  }
  if (!token) {
    throw new Error(`Login failed: status=${res.status} body=${res.body}`);
  }
  return token;
}

export function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}` } };
}
