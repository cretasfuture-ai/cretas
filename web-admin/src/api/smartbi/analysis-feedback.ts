import { getPythonAuthHeaders, PYTHON_SMARTBI_URL } from './common';

export async function markAnalysisUnhelpful(cacheToken: string, uploadId?: number): Promise<boolean> {
  try {
    const response = await fetch(`${PYTHON_SMARTBI_URL}/api/smartbi/analysis-feedback`, {
      method: 'POST',
      credentials: 'include',
      headers: await getPythonAuthHeaders(),
      body: JSON.stringify({ cache_token: cacheToken, upload_id: uploadId }),
    });
    return response.ok && (await response.json()).success === true;
  } catch {
    return false;
  }
}
