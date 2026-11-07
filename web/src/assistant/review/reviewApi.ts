import { HTTP_BASE } from "../../services/config";
import type { DiagramReviewResponse } from "./types";

export class ReviewApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = "ReviewApiError";
  }
}

export async function requestDiagramReview(
  diagramId: string,
  token: string,
): Promise<DiagramReviewResponse> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${encodeURIComponent(diagramId)}/review`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
    },
  });

  if (!res.ok) {
    let msg = `Error al solicitar la revisión (${res.status})`;
    try {
      const body = (await res.json()) as { message?: string };
      if (body.message) msg = body.message;
    } catch {
      // Ignora cuerpo no-JSON
    }
    throw new ReviewApiError(res.status, msg);
  }

  return (await res.json()) as DiagramReviewResponse;
}

