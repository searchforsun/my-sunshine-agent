import { ref, readonly } from 'vue'

export type SpeechErrorKind = 'not-supported' | 'no-speech' | 'aborted' | 'audio-capture' | 'network' | 'not-allowed' | 'service-not-allowed' | 'bad-grammar' | 'language-not-supported' | 'timeout' | 'unknown'

export function voiceInputSupported(): boolean {
  try {
    if (typeof window === 'undefined') return false
    const w = window as any
    return typeof w.SpeechRecognition === 'function' || typeof w.webkitSpeechRecognition === 'function'
  } catch {
    return false
  }
}

const sharedSupported = ref(false)
const sharedListening = ref(false)
/** 录音期间实时显示文本（final 片段 + 当前 interim） */
const sharedDisplay = ref('')
const sharedError = ref<SpeechErrorKind | null>(null)

let recognition: any = null
let finalTranscript = ''
let inited = false
let silenceTimer: ReturnType<typeof setTimeout> | null = null

function createRecognition(): any {
  if (typeof window === 'undefined') return null
  const w = window as any
  const Ctor = w.SpeechRecognition ?? w.webkitSpeechRecognition
  if (typeof Ctor !== 'function') return null
  const r = new Ctor()
  r.continuous = false
  r.interimResults = true
  r.lang = 'zh-CN'
  return r
}

function mapError(e: any): SpeechErrorKind {
  const code: string = String(e.error ?? '')
  if (code === 'not-allowed' || code === 'service-not-allowed') return 'not-allowed'
  if (code === 'no-speech') return 'no-speech'
  if (code === 'aborted') return 'aborted'
  if (code === 'audio-capture') return 'audio-capture'
  if (code === 'network') return 'network'
  if (code === 'bad-grammar') return 'bad-grammar'
  if (code === 'language-not-supported') return 'language-not-supported'
  return 'unknown'
}

function updateDisplay(interim: string) {
  sharedDisplay.value = finalTranscript + interim
}

function resetSilenceTimer() {
  if (silenceTimer) clearTimeout(silenceTimer)
  silenceTimer = setTimeout(() => {
    if (!sharedListening.value) return
    if (!sharedDisplay.value) {
      sharedError.value = 'timeout'
      sharedListening.value = false
      try { recognition?.abort() } catch { /* ignore */ }
      console.debug('[Voice] 超时：8 秒未检测到语音')
    }
  }, 8000)
}

export function useSpeechRecognition() {
  if (!inited) {
    inited = true
    sharedSupported.value = voiceInputSupported()
  }

  function start(): void {
    if (!sharedSupported.value) {
      sharedError.value = 'not-supported'
      return
    }
    if (recognition) {
      try { recognition.abort() } catch { /* ignore */ }
      recognition = null
    }
    recognition = createRecognition()
    if (!recognition) {
      sharedSupported.value = false
      sharedError.value = 'not-supported'
      return
    }

    sharedError.value = null
    finalTranscript = ''
    sharedDisplay.value = ''

    recognition.onstart = () => {
      sharedListening.value = true
      resetSilenceTimer()
      console.debug('[Voice] 语音识别已启动')
    }

    recognition.onresult = (event: any) => {
      resetSilenceTimer()
      let interim = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i]
        if (result.isFinal) {
          finalTranscript += result[0].transcript
        } else {
          interim += result[0].transcript
        }
      }
      updateDisplay(interim)
      console.debug('[Voice] 识别中 — display:', sharedDisplay.value)
    }

    recognition.onerror = (event: any) => {
      const kind = mapError(event)
      console.debug('[Voice] 错误:', kind, event.error, event.message)
      sharedError.value = kind
      if (kind === 'no-speech') return
      sharedListening.value = false
      if (silenceTimer) { clearTimeout(silenceTimer); silenceTimer = null }
    }

    recognition.onend = () => {
      console.debug('[Voice] 识别结束 — display:', sharedDisplay.value)
      if (silenceTimer) { clearTimeout(silenceTimer); silenceTimer = null }
      sharedListening.value = false
    }

    try {
      recognition.start()
    } catch (e) {
      console.debug('[Voice] start() 异常:', e)
      sharedListening.value = false
      sharedError.value = 'unknown'
    }
  }

  function stop(): string {
    if (silenceTimer) { clearTimeout(silenceTimer); silenceTimer = null }
    sharedListening.value = false
    if (recognition) {
      try { recognition.abort() } catch { /* ignore */ }
    }
    const result = sharedDisplay.value.trim()
    console.debug('[Voice] 停止 — 最终文本:', result)
    finalTranscript = ''
    sharedDisplay.value = ''
    return result
  }

  return {
    isSupported: readonly(sharedSupported),
    isListening: readonly(sharedListening),
    displayText: readonly(sharedDisplay),
    error: readonly(sharedError),
    start,
    stop,
  }
}
