import json5 from 'json5';

/**
 * Create service config by current env
 *
 * @param env The current env
 */
export function createServiceConfig(env: Env.ImportMeta) {
  const { VITE_SERVICE_BASE_URL, VITE_OTHER_SERVICE_BASE_URL } = env;

  let rawOther = {} as Record<App.Service.OtherBaseURLKey, ServiceBaseURLValue>;
  try {
    rawOther = json5.parse(VITE_OTHER_SERVICE_BASE_URL);
  } catch {
    // eslint-disable-next-line no-console
    console.error('VITE_OTHER_SERVICE_BASE_URL is not a valid json5 string');
  }

  const httpConfig: App.Service.SimpleServiceConfig = {
    baseURL: VITE_SERVICE_BASE_URL,
    other: normalizeOtherBaseURLs(rawOther)
  };

  const otherHttpKeys = Object.keys(httpConfig.other) as App.Service.OtherBaseURLKey[];

  const otherConfig: App.Service.OtherServiceConfigItem[] = otherHttpKeys.map(key => {
    return {
      key,
      baseURL: normalizeBaseURL(httpConfig.other[key]),
      proxyPattern: createProxyPattern(key)
    };
  });

  const config: App.Service.ServiceConfig = {
    baseURL: httpConfig.baseURL,
    proxyPattern: createProxyPattern(),
    other: otherConfig
  };

  return config;
}

/**
 * get backend service base url
 *
 * @param env - the current env
 * @param isProxy - if use proxy
 */
export function getServiceBaseURL(env: Env.ImportMeta, isProxy: boolean) {
  const { baseURL, other } = createServiceConfig(env);

  const otherBaseURL = {} as Record<App.Service.OtherBaseURLKey, string>;

  other.forEach(item => {
    otherBaseURL[item.key] = isProxy ? item.proxyPattern : item.baseURL;
  });

  return {
    baseURL: isProxy ? createProxyPattern() : baseURL,
    otherBaseURL
  };
}

/**
 * Get proxy pattern of backend service base url
 *
 * @param key If not set, will use the default key
 */
function createProxyPattern(key?: App.Service.OtherBaseURLKey) {
  if (!key) {
    return '/proxy-default';
  }

  return `/proxy-${key}`;
}

type ServiceBaseURLValue = string | { target?: string; baseURL?: string; url?: string };

function normalizeOtherBaseURLs(other: Record<App.Service.OtherBaseURLKey, ServiceBaseURLValue>) {
  const normalized = {} as Record<App.Service.OtherBaseURLKey, string>;

  for (const key of Object.keys(other) as App.Service.OtherBaseURLKey[]) {
    normalized[key] = normalizeBaseURL(other[key]);
  }

  return normalized;
}

function normalizeBaseURL(value: ServiceBaseURLValue) {
  if (typeof value === 'string') {
    return value;
  }

  return value.target || value.baseURL || value.url || '';
}
