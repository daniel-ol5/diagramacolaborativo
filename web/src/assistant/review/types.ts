export type ReviewIssueSeverity = "ERROR" | "WARNING" | "SUGGESTION";

export interface ReviewIssue {
  severity: ReviewIssueSeverity;
  code: string;
  elementId?: string;
  elementName?: string;
  message: string;
}

export interface DiagramReviewResponse {
  score: number;
  totalErrors: number;
  totalWarnings: number;
  totalSuggestions: number;
  diagramVersion: number;
  mentorComment: string;
  issues: ReviewIssue[];
}

export interface ReviewCardMeta {
  type?: string;
  score: number;
  totalErrors: number;
  totalWarnings: number;
  totalSuggestions: number;
  diagramVersion: number;
  comment: string;
  issues?: ReviewIssue[];
}

