import { useState, useRef, useEffect, useCallback } from "react";

// Declaración de tipos para Web Speech API
interface SpeechRecognitionResultItem {
  transcript: string;
  confidence: number;
}

interface SpeechRecognitionResultList {
  readonly length: number;
  [index: number]: {
    readonly length: number;
    isFinal: boolean;
    [index: number]: SpeechRecognitionResultItem;
  };
}

interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: SpeechRecognitionResultList;
}

interface SpeechRecognitionErrorEventLike {
  error: string;
  message?: string;
}

interface SpeechRecognitionInstance {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onstart: (() => void) | null;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  onend: (() => void) | null;
}

interface WindowWithSpeechRecognition {
  SpeechRecognition?: new () => SpeechRecognitionInstance;
  webkitSpeechRecognition?: new () => SpeechRecognitionInstance;
}

function getSpeechRecognitionConstructor(): (new () => SpeechRecognitionInstance) | null {
  if (typeof window === "undefined") return null;
  const win = window as unknown as WindowWithSpeechRecognition;
  return win.SpeechRecognition || win.webkitSpeechRecognition || null;
}

export function useSpeechRecognition() {
  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [error, setError] = useState<string | null>(null);

  const isSupported = typeof window !== "undefined" && Boolean(getSpeechRecognitionConstructor());

  const recognitionRef = useRef<SpeechRecognitionInstance | null>(null);
  const onFinalCallbackRef = useRef<((finalText: string) => void) | null>(null);

  const stopListening = useCallback(() => {
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch {
        // Ignorar si ya estaba detenido
      }
    }
    setIsListening(false);
  }, []);

  const startListening = useCallback(
    (onFinal?: (finalText: string) => void) => {
      setError(null);
      setTranscript("");
      onFinalCallbackRef.current = onFinal ?? null;

      const SpeechRecognitionConstructor = getSpeechRecognitionConstructor();

      if (!SpeechRecognitionConstructor) {
        setError("El reconocimiento de voz no está soportado en este navegador. Probá con Chrome o Edge.");
        return;
      }

      try {
        // Si había una instancia anterior corriendo, detenerla
        if (recognitionRef.current) {
          try {
            recognitionRef.current.abort();
          } catch {
            // Ignorar
          }
        }

        const recognition = new SpeechRecognitionConstructor();
        recognitionRef.current = recognition;

        recognition.continuous = false; // Detener automáticamente al terminar la frase
        recognition.interimResults = true; // Mostrar transcripción en tiempo real

        // Usar español como idioma primario
        const navLang = typeof navigator !== "undefined" ? navigator.language : "";
        recognition.lang = navLang.startsWith("es") ? navLang : "es-ES";

        recognition.onstart = () => {
          setIsListening(true);
          setError(null);
        };

        recognition.onresult = (event: SpeechRecognitionEventLike) => {
          let currentText = "";
          let isFinal = false;

          for (let i = 0; i < event.results.length; i++) {
            const res = event.results[i];
            currentText += res[0].transcript;
            if (res.isFinal) {
              isFinal = true;
            }
          }

          setTranscript(currentText);

          if (isFinal && currentText.trim()) {
            if (onFinalCallbackRef.current) {
              onFinalCallbackRef.current(currentText.trim());
            }
          }
        };

        recognition.onerror = (event: SpeechRecognitionErrorEventLike) => {
          if (event.error === "no-speech") {
            // Simplemente no se detectó voz en el lapso
            setIsListening(false);
            return;
          }
          if (event.error === "not-allowed" || event.error === "permission-denied") {
            setError("Permiso de micrófono denegado. Habilitalo en la barra de direcciones de tu navegador.");
          } else {
            setError(`Error de micrófono: ${event.error}`);
          }
          setIsListening(false);
        };

        recognition.onend = () => {
          setIsListening(false);
        };

        recognition.start();
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
        setIsListening(false);
      }
    },
    []
  );

  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
        try {
          recognitionRef.current.abort();
        } catch {
          // Ignorar al desmontar
        }
      }
    };
  }, []);

  return {
    isSupported,
    isListening,
    transcript,
    error,
    startListening,
    stopListening,
  };
}
