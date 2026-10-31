import { useState } from "react";
import { Modal } from "../components/Modal";
import type { DiagramSummary } from "../access/types";
import type { ValidationReport } from "../generator/types";
import { importXmi } from "./xmiApi";
import "./ImportXmiModal.css";

interface Props {
  projectId: string;
  token: string;
  onClose: () => void;
  /** El diagrama NUEVO que quedó creado -- el llamador navega hasta él. */
  onImported: (diagram: DiagramSummary) => void;
}

/**
 * CU18: traer un modelo de otra herramienta (Enterprise Architect) como un
 * diagrama nuevo del proyecto.
 *
 * El modal existe por dos cosas que un `<input type="file">` suelto en la
 * barra no puede decir:
 *
 * 1. **Que se crea un diagrama aparte.** Importar desde el lienzo abierto
 *    parece que fuera a mezclar el archivo con lo que hay en pantalla, y no
 *    es eso: el trabajo de la sala no se toca.
 * 2. **Por qué se rechazó**, cuando el XMI trae algo que nuestro metamodelo
 *    no acepta. El servidor valida ANTES de crear nada y contesta 422 con el
 *    mismo informe de CU13; sin un lugar donde mostrarlo, la persona ve
 *    "falló" y no tiene forma de saber qué línea del archivo lo causó.
 */
export function ImportXmiModal({ projectId, token, onClose, onImported }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [report, setReport] = useState<ValidationReport | null>(null);

  async function handleImport() {
    if (!file) return;
    setImporting(true);
    setError(null);
    setReport(null);
    try {
      const resultado = await importXmi(projectId, token, file);
      if ("diagram" in resultado) {
        onImported(resultado.diagram);
      } else {
        setReport(resultado.report);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setImporting(false);
    }
  }

  return (
    <Modal title="Importar XMI" onClose={onClose}>
      <div className="import-xmi">
        {error && <p className="modal__error">⚠ {error}</p>}

        <p className="import-xmi__message">
          Subí un archivo XMI 2.1 exportado de otra herramienta. Entra como un{" "}
          <strong>diagrama nuevo</strong> del proyecto: el que tenés abierto no se toca.
        </p>

        <input
          type="file"
          accept=".xmi,.xml,text/xml,application/xml"
          className="import-xmi__file-input"
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null);
            setReport(null);
            setError(null);
          }}
        />

        {report && (
          <div className="import-xmi__report">
            {/* "No se creó nada" va primero y en el banner, no al pie: es lo
                que la persona necesita saber antes de ir a buscar un diagrama
                a medio importar que no existe. */}
            <div className="import-xmi__banner">
              ✗ El archivo tiene {report.errors.length} error
              {report.errors.length === 1 ? "" : "es"} que nuestro modelo no acepta — no se creó
              ningún diagrama.
            </div>
            {report.errors.length > 0 && (
              <>
                <h3 className="import-xmi__section-title import-xmi__section-title--errors">
                  Errores
                </h3>
                <ul className="import-xmi__issues">
                  {report.errors.map((issue, i) => (
                    <li key={`e-${issue.code}-${i}`}>
                      <span className="import-xmi__issue-element">
                        {issue.elementName} · {issue.code}
                      </span>
                      <span className="import-xmi__issue-message">{issue.message}</span>
                    </li>
                  ))}
                </ul>
              </>
            )}
            {report.warnings.length > 0 && (
              <>
                <h3 className="import-xmi__section-title import-xmi__section-title--warnings">
                  Advertencias
                </h3>
                <ul className="import-xmi__issues">
                  {report.warnings.map((issue, i) => (
                    <li key={`w-${issue.code}-${i}`}>
                      <span className="import-xmi__issue-element">
                        {issue.elementName} · {issue.code}
                      </span>
                      <span className="import-xmi__issue-message">{issue.message}</span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        )}

        <div className="modal__actions">
          <button type="button" onClick={onClose}>
            Cancelar
          </button>
          <button
            type="button"
            className="modal__primary"
            onClick={handleImport}
            disabled={!file || importing}
          >
            {importing ? "Importando…" : "Importar"}
          </button>
        </div>
      </div>
    </Modal>
  );
}
