/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_AMAP_KEY: string;
  readonly VITE_AMAP_SECURITY_CODE: string;
  readonly VITE_API_BASE_URL: string;
  readonly VITE_USE_MOCK_API: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
