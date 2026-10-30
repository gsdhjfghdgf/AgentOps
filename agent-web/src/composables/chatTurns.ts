import type { MessageItem } from '../api'

export function groupTaskMessages(messages: MessageItem[]) {
  const turns: Array<{ key: string; runId?: string | null; request?: MessageItem; steps: MessageItem[] }> = []
  for (const message of messages) {
    let turn = turns.at(-1)
    // History pages may start midway through a task. Keep that partial task together, too.
    if (!turn || !message.runId || turn.runId !== message.runId) {
      turn = { key: message.runId || `message-${message.seq}`, runId: message.runId, steps: [] }
      if (message.role === 'user') turn.request = message
      else turn.steps.push(message)
      turns.push(turn)
    } else turn.steps.push(message)
  }
  return turns
}
