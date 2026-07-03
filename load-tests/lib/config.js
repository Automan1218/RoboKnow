// 全局配置：所有场景共用，通过环境变量覆盖
// 例：k6 run -e BASE_URL=http://10.0.0.5:8081 scenario4-search-capacity.js

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
export const WS_URL = __ENV.WS_URL || 'ws://localhost:8081';
export const USERNAME = __ENV.K6_USERNAME || 'admin';
export const PASSWORD = __ENV.K6_PASSWORD || 'admin123';
