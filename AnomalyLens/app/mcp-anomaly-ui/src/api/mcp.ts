// type JsonRpcResponse = {
//   jsonrpc: "2.0";
//   id: string | number;
//   result?: any;
//   error?: { code: number; message: string };
// };

const API_URL = import.meta.env.VITE_API_BASE_URL ?? "";

type ApiError = {
  message?: string;
  error?: unknown;
  details?: string;
};

async function apiCall<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const res = await fetch(`${API_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers ?? {}),
    },
  });

  const contentType = res.headers.get("content-type") ?? "";
  const data = contentType.includes("application/json")
    ? await res.json()
    : await res.text();

  if (!res.ok) {
    const err = data as ApiError;
    throw new Error(
      err?.message ??
        err?.details ??
        `Request failed with status ${res.status}`
    );
  }

  return data as T;
}

export async function uploadFile(key: string, contentBase64: string) {
  return apiCall("/api/files/upload", {
    method: "POST",
    body: JSON.stringify({
      key,
      contentBase64,
    }),
  });
}

export async function scoreFile(
  inputKey: string,
  warnPercentile = 92.5,
  critPercentile = 99.9,
  outputKey?: string
) {
  return apiCall("/api/scoring/run", {
    method: "POST",
    body: JSON.stringify({
      inputKey,
      outputKey,
      warnPercentile,
      critPercentile,
    }),
  });
}

export async function explainTop(scoredKey: string, k = 5) {
  const params = new URLSearchParams({
    scoredKey,
    k: String(k),
  });

  return apiCall(`/api/anomalies/top?${params.toString()}`, {
    method: "GET",
  });
}

export async function getLastRun() {
  return apiCall("/api/runs/last", {
    method: "GET",
  });
}

export async function summarizeScored(scoredKey: string, k = 10) {
  return apiCall("/api/llm/summary", {
    method: "POST",
    body: JSON.stringify({
      scoredKey,
      k,
    }),
  });
}

export async function qaLastRun(questionId: string, k = 5) {
  return apiCall("/api/llm/qa-last-run", {
    method: "POST",
    body: JSON.stringify({
      questionId,
      k,
    }),
  });
}

export async function remediationChecklist(scoredKey: string, k = 10) {
  return apiCall("/api/llm/remediation", {
    method: "POST",
    body: JSON.stringify({
      scoredKey,
      k,
    }),
  });
}

export async function downloadScored(scoredKey: string) {
  const params = new URLSearchParams({
    scoredKey,
  });

  return apiCall<{
    contentBase64: string;
    filename?: string;
    mimeType?: string;
    meta?: unknown;
  }>(`/api/scored/download?${params.toString()}`, {
    method: "GET",
  });
}

export async function downloadScoredCsv(scoredKey: string) {
  const result = await downloadScored(scoredKey);

  const base64 = result.contentBase64;
  const filename = result.filename ?? "scored.csv";
  const mimeType = result.mimeType ?? "text/csv";

  if (!base64) {
    throw new Error("No contentBase64 received from backend");
  }

  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);

  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }

  const blob = new Blob([bytes], { type: mimeType });
  const url = URL.createObjectURL(blob);

  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();

  a.remove();
  URL.revokeObjectURL(url);
}