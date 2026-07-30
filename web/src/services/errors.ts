import type { ApiError } from '@/types/api';

export class ApiClientError extends Error {
  constructor(
    public readonly status: number,
    public readonly payload: ApiError,
  ) {
    super(payload.message);
    this.name = 'ApiClientError';
  }
}
