// Cloud fallback rewrite: calls a real model, and only when one is configured. It used to run the
// same 16-word find/replace list as the on-device fallback and call that a "cloud rewrite" — that
// was never a real rewrite, so a misconfigured deployment now fails loudly instead of returning
// fake text.
const INSTRUCTIONS = {
  GRAMMAR_ONLY: 'Correct the grammar, spelling, punctuation and capitalization of the text. ' +
    'Keep the writer\'s own words and meaning, and change as little as possible.',
  NATURAL_CASUAL: 'Rewrite the text so it sounds natural and friendly, like a real person wrote it. ' +
    'Fix all grammar and spelling. Keep the meaning and about the same length.',
  PROFESSIONAL: 'Rewrite the text in a clear, polite, professional tone for work messages. ' +
    'Fix all grammar and spelling. Keep the meaning. Do not add greetings or sign-offs.'
};

export function isCloudRewriteConfigured() {
  return Boolean(process.env.ANTHROPIC_API_KEY);
}

export async function rewriteText(text, mode = 'GRAMMAR_ONLY') {
  const clean = String(text || '').trim();
  if (!clean) return '';

  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    throw new Error('cloud_rewrite_not_configured');
  }

  const system = `You are a writing assistant inside a keyboard. ${INSTRUCTIONS[mode] || INSTRUCTIONS.GRAMMAR_ONLY} ` +
    'Never answer, reply to, or comment on the text. Never add new information. Output only the rewritten text.';

  const response = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01'
    },
    body: JSON.stringify({
      model: 'claude-haiku-4-5-20251001',
      max_tokens: Math.min(1024, Math.ceil(clean.length / 2) + 64),
      system,
      messages: [{ role: 'user', content: clean }]
    })
  });

  if (!response.ok) {
    throw new Error(`cloud_rewrite_upstream_error_${response.status}`);
  }

  const body = await response.json();
  const rewritten = body.content?.[0]?.text?.trim();
  if (!rewritten) throw new Error('cloud_rewrite_empty_response');
  return rewritten;
}
