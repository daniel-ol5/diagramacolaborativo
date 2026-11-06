export type AssistantMode = "OFF" | "ON_MENTION" | "ALWAYS" | "MENTOR_PROACTIVE";
export type AssistantLevel = "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
export type AssistantProvider = "CLOUD" | "LOCAL";

export interface AssistantSettingsResponse {
  mode: AssistantMode;
  level: AssistantLevel;
  provider: AssistantProvider;
  localEndpoint?: string | null;
  maxTokens?: number | null;
  summaryFrequency?: number | null;
  canEdit: boolean;
}

export interface AssistantSettingsUpdateRequest {
  mode: AssistantMode;
  level: AssistantLevel;
  provider: AssistantProvider;
  localEndpoint?: string | null;
  maxTokens?: number | null;
  summaryFrequency?: number | null;
}

