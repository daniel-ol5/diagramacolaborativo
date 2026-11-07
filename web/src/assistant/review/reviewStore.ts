import { create } from "zustand";
import type { DiagramReviewResponse, ReviewIssueSeverity } from "./types";

export interface ClassIssueSummary {
  severity: ReviewIssueSeverity;
  count: number;
  messages: string[];
}

interface ReviewState {
  activeReview: DiagramReviewResponse | null;
  issuesByClassId: Record<string, ClassIssueSummary>;
  issuesByClassName: Record<string, ClassIssueSummary>;
  setActiveReview: (review: DiagramReviewResponse | null) => void;
  clearReview: () => void;
}

function computeIssueMaps(review: DiagramReviewResponse | null) {
  const byId: Record<string, ClassIssueSummary> = {};
  const byName: Record<string, ClassIssueSummary> = {};

  if (!review || !review.issues) {
    return { byId, byName };
  }

  const sevOrder: Record<ReviewIssueSeverity, number> = {
    ERROR: 3,
    WARNING: 2,
    SUGGESTION: 1,
  };

  for (const issue of review.issues) {
    const updateEntry = (map: Record<string, ClassIssueSummary>, key?: string) => {
      if (!key) return;
      const current = map[key];
      if (!current) {
        map[key] = {
          severity: issue.severity,
          count: 1,
          messages: [issue.message],
        };
      } else {
        current.count += 1;
        current.messages.push(issue.message);
        if (sevOrder[issue.severity] > sevOrder[current.severity]) {
          current.severity = issue.severity;
        }
      }
    };

    updateEntry(byId, issue.elementId);
    updateEntry(byName, issue.elementName);
  }

  return { byId, byName };
}

export const useReviewStore = create<ReviewState>((set) => ({
  activeReview: null,
  issuesByClassId: {},
  issuesByClassName: {},
  setActiveReview: (review) => {
    const { byId, byName } = computeIssueMaps(review);
    set({
      activeReview: review,
      issuesByClassId: byId,
      issuesByClassName: byName,
    });
  },
  clearReview: () => {
    set({
      activeReview: null,
      issuesByClassId: {},
      issuesByClassName: {},
    });
  },
}));

