/**
 * Tipos para el caso de uso CU24: Resumen automático de conversación.
 */

export interface ChatSummaryResponse {
  id: string;
  projectId: string;
  seq: number;
  fromSeq: number;
  toSeq: number;
  totalMessages: number;
  decisions: string[];
  openQuestions: string[];
  plainText: string;
  createdAt: string;
}

export interface ChatSummaryCardMeta {
  type?: string;
  fromSeq?: number;
  toSeq?: number;
  totalMessages?: number;
  decisions?: string[];
  openQuestions?: string[];
}

