import { useMemo, useState } from "react";
import { fileToBase64 } from "./utils/fileToBase64";
import "./css/App.css";
import cloudIcon from "./assets/Icon.png";
import {
  uploadFile,
  scoreFile,
  explainTop,
  summarizeScored,
  qaLastRun,
  remediationChecklist,
  downloadScoredCsv,
  getLastRun,
} from "./apiClient";

// type LogLine = { ts: string; msg: string };

type ToastType = "success" | "error" | "info";

type ToastMessage = {
  id: number;
  type: ToastType;
  message: string;
};

type ChatMessage = {
  id: number;
  role: "user" | "assistant";
  text: string;
};

type ActiveTab = "explain" | "chat";

type ExplainRow = {
  rank: string;
  intervalStartRaw: string;
  intervalStartFormatted: string;
  anomalyScore: string;
  warning: boolean;
  critical: boolean;
};

const predefinedQuestions = [
  { id: "MOST_CRITICAL_INTERVAL", label: "Most critical interval" },
  { id: "COUNT_WARN_CRIT", label: "Count warnings and criticals" },
  { id: "TIME_RANGE", label: "Time range of the run" },
  { id: "TOP5_SUMMARY", label: "Summarize top 5 anomalies" },
  { id: "DID_WE_HAVE_CRITICALS", label: "Did we have critical anomalies?" },
  { id: "IS_CLUSTERED", label: "Are anomalies clustered?" },
];

export default function App() {
  const [file, setFile] = useState<File | null>(null);
  // const [logs, setLogs] = useState<LogLine[]>([]);
  const [busy, setBusy] = useState(false);

  const [inputKey, setInputKey] = useState("");
  const [outputKey, setOutputKey] = useState("");

  const [kTop, setKTop] = useState(5);
  const [explainText, setExplainText] = useState("");

  // const [llmText, setLlmText] = useState("");
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [questionId, setQuestionId] = useState(predefinedQuestions[0].id);

  const [activeTab, setActiveTab] = useState<ActiveTab>("chat");
  const [toast, setToast] = useState<ToastMessage | null>(null);

  // function addLog(msg: string) {
  //   setLogs((prev) => [{ ts: new Date().toISOString(), msg }, ...prev]);
  // }

  function addLog(msg: string) {
    console.log(`[${new Date().toISOString()}] ${msg}`);
  }

  function showToast(type: ToastType, message: string) {
    const id = Date.now();

    setToast({
      id,
      type,
      message,
    });

    window.setTimeout(() => {
      setToast((current) => {
        if (current?.id === id) {
          return null;
        }

        return current;
      });
    }, 3500);
  }

  function formatUnixTimestamp(value: string | number) {
    const numeric = typeof value === "string" ? Number(value.trim()) : value;

    if (!Number.isFinite(numeric)) {
      return String(value);
    }

    const date = new Date(numeric * 1000);

    if (Number.isNaN(date.getTime())) {
      return String(value);
    }

    return new Intl.DateTimeFormat("en-GB", {
      timeZone: "Europe/Bucharest",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    })
      .format(date)
      .replace(",", "");
  }

  function humanizeUnixTimestamps(text: string) {
    if (!text) return text;

    return text.replace(/\b\d{10}\b/g, (match) => {
      return formatUnixTimestamp(match);
    });
  }

  function parseExplainTable(text: string): {
    title: string;
    rows: ExplainRow[];
    summary: string;
  } {
    if (!text) {
      return { title: "", rows: [], summary: "" };
    }

    const lines = text
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);

    const title =
      lines.find((line) => line.toLowerCase().startsWith("top "))?.replace(/`/g, "") ??
      "Top anomalies";

    const summary =
      lines.find((line) => line.toLowerCase().startsWith("summary:")) ?? "";

    const rows: ExplainRow[] = [];

    for (const line of lines) {
      if (!line.includes("|")) continue;

      const cells = line
        .split("|")
        .map((cell) => cell.trim())
        .filter(Boolean);

      if (cells.length < 5) continue;
      if (cells[0] === "#" || cells[0].includes("---")) continue;

      const rank = cells[0];
      const intervalStartRaw = cells[1];
      const anomalyScore = cells[2];
      const warningCell = cells[3];
      const criticalCell = cells[4];

      if (!/^\d+$/.test(rank)) continue;
      if (!/^\d{10}$/.test(intervalStartRaw)) continue;

      const warning =
        warningCell.includes("✅") ||
        warningCell.toLowerCase() === "true" ||
        warningCell.toLowerCase() === "yes" ||
        warningCell === "1";

      const critical =
        criticalCell.includes("✅") ||
        criticalCell.includes("🟥") ||
        criticalCell.toLowerCase() === "true" ||
        criticalCell.toLowerCase() === "yes" ||
        criticalCell === "1";

      rows.push({
        rank,
        intervalStartRaw,
        intervalStartFormatted: formatUnixTimestamp(intervalStartRaw),
        anomalyScore,
        warning,
        critical,
      });
    }

    return {
      title,
      rows,
      summary: humanizeUnixTimestamps(summary),
    };
  }

  function addChatMessage(role: "user" | "assistant", text: string) {
    setChatMessages((prev) => [
      ...prev,
      {
        id: Date.now() + Math.random(),
        role,
        text: role === "assistant" ? humanizeUnixTimestamps(text) : text,
      },
    ]);
  }

  function clearConversation() {
    setChatMessages([]);
  }

  const canUpload = useMemo(() => !!file && !busy, [file, busy]);
  const canScore = useMemo(() => !!inputKey && !busy, [inputKey, busy]);
  const canExplain = useMemo(() => !!outputKey && !busy, [outputKey, busy]);
  const canLlm = useMemo(() => !!outputKey && !busy, [outputKey, busy]);

  const parsedExplain = useMemo(() => parseExplainTable(explainText), [explainText]);

  async function onUpload() {
    if (!file) return;

    setBusy(true);
    setExplainText("");
    clearConversation();
    setOutputKey("");
    setActiveTab("chat");

    try {
      addLog(`Reading file: ${file.name} (${file.size} bytes)`);

      const b64 = await fileToBase64(file);
      const key = `incoming/${file.name}`;

      addLog(`Uploading CSV through backend API to ${key}`);

      const res: any = await uploadFile(key, b64);

      addLog(`Upload OK: ${res?.meta?.bucket ?? ""}/${res?.meta?.key ?? key}`);
      setInputKey(key);

      showToast("success", "CSV file uploaded successfully.");
    } catch (e: any) {
      addLog(`Upload ERROR: ${e.message}`);
      showToast("error", `Upload failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function onScore() {
    if (!inputKey) return;

    setBusy(true);
    setExplainText("");
    clearConversation();
    setActiveTab("chat");

    try {
      addLog(`Running scoring through backend API: inputKey=${inputKey}`);

      const res: any = await scoreFile(inputKey, 92.5, 99.9);
      const meta = res?.meta ?? {};
      const outKey = meta.outputKey as string;

      if (!outKey) {
        throw new Error("Backend did not return outputKey");
      }

      setOutputKey(outKey);
      addLog(`Score OK: wrote ${outKey}`);

      showToast("success", "Scoring completed successfully.");
    } catch (e: any) {
      addLog(`Score ERROR: ${e.message}`);
      showToast("error", `Scoring failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function onExplain() {
    if (!outputKey) return;

    setBusy(true);

    try {
      addLog(`Fetching top anomalies through backend API: scoredKey=${outputKey}, k=${kTop}`);

      const res: any = await explainTop(outputKey, kTop);
      const text = res?.content?.[0]?.text ?? "(no text)";

      setExplainText(text);
      setActiveTab("explain");
      addLog("Explain OK");

      showToast("success", "Top anomalies loaded successfully.");
    } catch (e: any) {
      addLog(`Explain ERROR: ${e.message}`);
      showToast("error", `Explain failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function onSummary() {
    if (!outputKey) return;

    setBusy(true);
    setActiveTab("chat");

    const userText = "Generate an executive summary for the latest scored anomaly file.";
    addChatMessage("user", userText);

    try {
      addLog(`Generating summary through backend API: scoredKey=${outputKey}`);

      const res: any = await summarizeScored(outputKey, 10);
      const answer = res?.content?.[0]?.text ?? "(no text)";

      addChatMessage("assistant", answer);
      addLog("LLM Summary OK");

      showToast("success", "Executive summary generated.");
    } catch (e: any) {
      addLog(`LLM Summary ERROR: ${e.message}`);
      addChatMessage("assistant", `Error: ${e.message}`);
      showToast("error", `Summary failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function onQa() {
    if (!outputKey) return;

    setBusy(true);
    setActiveTab("chat");

    const selectedQuestion =
      predefinedQuestions.find((q) => q.id === questionId)?.label ?? questionId;

    addChatMessage("user", selectedQuestion);

    try {
      addLog(`Running Q&A through backend API: questionId=${questionId}`);

      const res: any = await qaLastRun(questionId, 5);
      const answer = res?.content?.[0]?.text ?? "(no text)";

      addChatMessage("assistant", answer);
      addLog("LLM Q&A OK");

      showToast("success", "Answer generated.");
    } catch (e: any) {
      addLog(`LLM Q&A ERROR: ${e.message}`);
      addChatMessage("assistant", `Error: ${e.message}`);
      showToast("error", `Q&A failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function onChecklist() {
    if (!outputKey) return;

    setBusy(true);
    setActiveTab("chat");

    const userText = "Generate a remediation checklist based on the detected anomalies.";
    addChatMessage("user", userText);

    try {
      addLog(`Generating remediation checklist through backend API: scoredKey=${outputKey}`);

      const res: any = await remediationChecklist(outputKey, 10);
      const answer = res?.content?.[0]?.text ?? "(no text)";

      addChatMessage("assistant", answer);
      addLog("LLM Checklist OK");

      showToast("success", "Remediation checklist generated.");
    } catch (e: any) {
      addLog(`LLM Checklist ERROR: ${e.message}`);
      addChatMessage("assistant", `Error: ${e.message}`);
      showToast("error", `Checklist failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function onDownload() {
    if (!outputKey) return;

    setBusy(true);

    try {
      addLog(`Downloading scored CSV through backend API: ${outputKey}`);

      await downloadScoredCsv(outputKey);

      addLog(`Download OK: ${outputKey}`);
      showToast("success", "Scored CSV downloaded.");
    } catch (e: any) {
      addLog(`Download ERROR: ${e.message}`);
      showToast("error", `Download failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function onLoadLastRun() {
    setBusy(true);

    try {
      addLog("Loading last run metadata through backend API");

      const res: any = await getLastRun();

      const text = res?.contents?.[0]?.text;

      if (text) {
        const meta = JSON.parse(text);

        if (meta.inputKey) {
          setInputKey(meta.inputKey);
        }

        if (meta.outputKey) {
          setOutputKey(meta.outputKey);
        }

        setExplainText("");
        clearConversation();
        setActiveTab("chat");

        addLog(`Last run loaded: ${meta.outputKey ?? "no outputKey"}`);
        showToast("success", "Last run metadata loaded.");
      } else {
        addLog("Last run loaded, but no metadata text was returned");
        showToast("info", "Last run loaded, but no metadata was returned.");
      }
    } catch (e: any) {
      addLog(`Load last run ERROR: ${e.message}`);
      showToast("error", `Load last run failed: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell">
      {toast && (
        <div className={`toast toast-${toast.type}`}>
          <span>{toast.message}</span>
          <button
            className="toast-close"
            onClick={() => setToast(null)}
            aria-label="Close notification"
          >
            ×
          </button>
        </div>
      )}

      <div className="app-card">
      <div className="app-header">
        <img src={cloudIcon} alt="Cloud Insights icon" className="app-logo"/>
      </div>
        <h2 className="app-title">AnomalyLens</h2>

        <div className="toolbar">
          <label className="file-upload">
            <span className="file-pill">Choose CSV</span>
            <input
              type="file"
              accept=".csv,text/csv"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <span className="file-name">{file ? file.name : "No file chosen"}</span>
          </label>

          <button className="pink-button" disabled={!canUpload} onClick={onUpload}>
            Upload CSV
          </button>

          <button className="pink-button" disabled={!canScore} onClick={onScore}>
            Run Scoring
          </button>

          <button className="pink-button secondary" disabled={busy} onClick={onLoadLastRun}>
            Load Last Run
          </button>

          <label className="k-control">
            Top K:
            <input
              type="number"
              value={kTop}
              min={1}
              max={50}
              onChange={(e) => setKTop(Number(e.target.value))}
            />
          </label>

          <button className="pink-button secondary" disabled={!canExplain} onClick={onExplain}>
            Explain Top-K
          </button>

          <button className="pink-button secondary" disabled={!outputKey || busy} onClick={onDownload}>
            Download Scored
          </button>
        </div>

        <div className="info-panel">
          <div>
            <b>Input File:</b> {inputKey || "—"}
          </div>
          <div>
            <b>Scored File:</b> {outputKey || "—"}
          </div>
        </div>

        {outputKey && (
          <>
            <hr className="soft-divider" />

            <h3 className="section-title">LLM Insights</h3>

            <div className="toolbar llm-toolbar">
              <button className="pink-button" disabled={!canLlm} onClick={onSummary}>
                Generate Executive Summary
              </button>

              <select
                className="pink-select"
                value={questionId}
                onChange={(e) => setQuestionId(e.target.value)}
                disabled={!canLlm}
              >
                {predefinedQuestions.map((q) => (
                  <option key={q.id} value={q.id}>
                    {q.label}
                  </option>
                ))}
              </select>

              <button className="pink-button secondary" disabled={!canLlm} onClick={onQa}>
                Ask
              </button>

              <button className="pink-button danger" disabled={!canLlm} onClick={onChecklist}>
                Generate Remediation Checklist
              </button>
            </div>
          </>
        )}

        {(explainText || chatMessages.length > 0) && (
          <div className="tabs-panel">
            <div className="tabs-header">
              <button
                className={`tab-button ${activeTab === "explain" ? "tab-button-active" : ""}`}
                onClick={() => setActiveTab("explain")}
              >
                Explain Top-K
              </button>

              <button
                className={`tab-button ${activeTab === "chat" ? "tab-button-active" : ""}`}
                onClick={() => setActiveTab("chat")}
              >
                LLM Conversation
              </button>
            </div>

            <div className="tabs-content">
              {activeTab === "explain" && (
                <div className="tab-section">
                  {parsedExplain.rows.length > 0 ? (
                    <div className="explain-table-wrapper">
                      <div className="explain-table-title">{parsedExplain.title}</div>

                      <table className="explain-table">
                        <thead>
                          <tr>
                            <th>#</th>
                            <th>Date / Time</th>
                            <th>Anomaly Score</th>
                            <th>Warning</th>
                            <th>Critical</th>
                          </tr>
                        </thead>

                        <tbody>
                          {parsedExplain.rows.map((row) => (
                            <tr key={`${row.rank}-${row.intervalStartRaw}`}>
                              <td>{row.rank}</td>
                              <td>{row.intervalStartFormatted}</td>
                              <td>{row.anomalyScore}</td>
                              <td>
                                <span className={row.warning ? "status-yes" : "status-no"}>
                                  {row.warning ? "Yes" : "No"}
                                </span>
                              </td>
                              <td>
                                <span className={row.critical ? "status-critical" : "status-no"}>
                                  {row.critical ? "Yes" : "No"}
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>

                      {parsedExplain.summary && (
                        <div className="explain-summary">{parsedExplain.summary}</div>
                      )}
                    </div>
                  ) : explainText ? (
                    <pre className="tab-pre">{humanizeUnixTimestamps(explainText)}</pre>
                  ) : (
                    <div className="tab-empty">No Explain Top-K output yet.</div>
                  )}
                </div>
              )}

              {activeTab === "chat" && (
                <div className="tab-section">
                  <div className="chat-box">
                    {chatMessages.length === 0 ? (
                      <div className="chat-empty">No LLM conversation yet.</div>
                    ) : (
                      chatMessages.map((msg) => (
                        <div
                          key={msg.id}
                          className={`chat-message chat-message-${msg.role}`}
                        >
                          <div className="chat-role">
                            {msg.role === "user" ? "You" : "LLM"}
                          </div>

                          <div className="chat-bubble">{msg.text}</div>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/*
        */}
      </div>
    </div>
  );
}