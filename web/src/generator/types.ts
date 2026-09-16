/** Espejo de backend/.../diagram/dto/ValidationReportDTO.java (CU13/CU14, §11.2.6). */

export interface ValidationIssue {
  code: string;
  elementId: string;
  elementName: string;
  message: string;
}

export interface ValidationReport {
  valid: boolean;
  errors: ValidationIssue[];
  warnings: ValidationIssue[];
}
