export interface CanonicalToolCall {
  id?: string;
  name: string;
  arguments: Record<string, unknown>;
}

function parseArguments(raw: unknown): Record<string, unknown> {
  if (!raw) {
    return {};
  }

  if (typeof raw === 'string') {
    try {
      const parsed: unknown = JSON.parse(raw);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : {};
    } catch {
      return {};
    }
  }

  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as Record<string, unknown>;
  }

  return {};
}

/**
 * Normalize OpenAI/Mistral-style native tool_calls into a canonical shape.
 * Accepts either an array or a single object.
 */
export function normalizeNativeToolCalls(raw: unknown): CanonicalToolCall[] {
  const items = Array.isArray(raw) ? raw : raw ? [raw] : [];
  const normalized: CanonicalToolCall[] = [];

  for (const item of items) {
    if (!item || typeof item !== 'object') {
      continue;
    }

    const record = item as Record<string, unknown>;
    const fn = (record.function ?? {}) as {
      name?: unknown;
      arguments?: unknown;
    };

    const rawName =
      typeof fn.name === 'string' && fn.name.trim()
        ? fn.name.trim()
        : typeof record.name === 'string' && record.name.trim()
          ? record.name.trim()
          : '';

    if (!rawName) {
      continue;
    }

    const args = parseArguments(
      fn.arguments !== undefined ? fn.arguments : record.arguments
    );

    normalized.push({
      id: typeof record.id === 'string' ? record.id : undefined,
      name: rawName,
      arguments: args,
    });
  }

  return normalized;
}

function parseCanonicalObject(obj: unknown): CanonicalToolCall[] {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    return [];
  }

  const record = obj as Record<string, unknown>;
  const name =
    typeof record.name === 'string' && record.name.trim()
      ? record.name.trim()
      : '';

  if (!name) {
    return [];
  }

  return [
    {
      id: typeof record.id === 'string' ? record.id : undefined,
      name,
      arguments: parseArguments(record.arguments),
    },
  ];
}

function tryParseJson(text: string): unknown | undefined {
  const trimmed = text.trim();
  if (!trimmed) {
    return undefined;
  }

  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return undefined;
  }
}

/**
 * Extract canonical tool calls from text responses using a small set of
 * provider-agnostic strategies:
 *   A. Whole-content JSON object.
 *   B. Markdown-fenced JSON block(s).
 *   C. "Calling: <name>" followed by a JSON arguments object.
 */
export function extractToolCallFromText(
  content: string
): CanonicalToolCall[] {
  if (!content || !content.trim()) {
    return [];
  }

  const results: CanonicalToolCall[] = [];

  // A. Whole-content JSON object.
  const whole = tryParseJson(content);
  if (whole) {
    results.push(...parseCanonicalObject(whole));
  }

  // B. Fenced JSON block(s).
  const fenceRegex = /```(?:json)?\s*([\s\S]*?)```/g;
  let fenceMatch: RegExpExecArray | null;
  while ((fenceMatch = fenceRegex.exec(content)) !== null) {
    const parsed = tryParseJson(fenceMatch[1]);
    if (parsed) {
      results.push(...parseCanonicalObject(parsed));
    }
  }

  // C. Calling: <name> followed by a JSON object.
  const callingRegex =
    /Calling:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\n?\s*(\{[\s\S]*?\})/gi;
  let callingMatch: RegExpExecArray | null;
  while ((callingMatch = callingRegex.exec(content)) !== null) {
    const name = callingMatch[1]?.trim();
    if (!name) {
      continue;
    }

    const parsedArgs = tryParseJson(callingMatch[2]);
    results.push({
      name,
      arguments:
        parsedArgs && typeof parsedArgs === 'object' && !Array.isArray(parsedArgs)
          ? (parsedArgs as Record<string, unknown>)
          : {},
    });
  }

  // Deduplicate exact matches while preserving order.
  const seen = new Set<string>();
  return results.filter((call) => {
    const key = JSON.stringify(call);
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

/**
 * Truncate a large tool result while preserving its beginning and end.
 */
export function compactToolResult(
  content: string,
  options?: { maxChars?: number; headChars?: number; tailChars?: number }
): string {
  const maxChars = options?.maxChars ?? 4000;
  const headChars = options?.headChars ?? 2000;
  const tailChars = options?.tailChars ?? 1000;

  if (content.length <= maxChars) {
    return content;
  }

  const truncatedChars = content.length - headChars - tailChars;
  if (truncatedChars <= 0) {
    return content;
  }

  return (
    content.slice(0, headChars) +
    `\n...[truncated ${truncatedChars} characters]...\n` +
    content.slice(-tailChars)
  );
}
