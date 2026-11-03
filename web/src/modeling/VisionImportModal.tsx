import { useState } from "react";
import { Modal } from "../components/Modal";
import {
  analyzeImage,
  confirmImport,
  type VisionAttribute,
  type VisionMethod,
  type VisionClass,
  type VisionProposal,
} from "./visionApi";
import { fetchDiagramSnapshot } from "../services/diagramApi";
import { useCanonicalStore } from "../store/canonicalStore";
import "./VisionImportModal.css";

interface Props {
  diagramId: string;
  token: string;
  onClose: () => void;
}

interface EditableAttribute extends VisionAttribute {
  included: boolean;
}

interface EditableMethod extends VisionMethod {
  included: boolean;
}

interface EditableClass extends Omit<VisionClass, "attributes" | "methods"> {
  included: boolean;
  attributes: EditableAttribute[];
  methods: EditableMethod[];
}

/**
 * CU17: importar un diagrama desde una foto, en dos pasos separados por una
 * persona en el medio (RF-03.3) -- "analizar" no sabe escribir, así que no
 * existe ningún camino por el que una foto se aplique sin que alguien la
 * revise y confirme en este mismo modal.
 */
export function VisionImportModal({ diagramId, token, onClose }: Props) {
  const [step, setStep] = useState<"subir" | "revisar">("subir");
  const [file, setFile] = useState<File | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [proposal, setProposal] = useState<VisionProposal | null>(null);
  const [classes, setClasses] = useState<EditableClass[]>([]);
  const [relationships, setRelationships] = useState<VisionProposal["draft"]["relationships"]>([]);
  const [confirmedResult, setConfirmedResult] = useState<string | null>(null);

  async function handleAnalyze() {
    if (!file) return;
    setAnalyzing(true);
    setError(null);
    try {
      const p = await analyzeImage(diagramId, token, file);
      setProposal(p);
      setClasses(
        p.draft.classes.map((c) => ({
          ...c,
          included: true,
          attributes: (c.attributes ?? []).map((a) => ({ ...a, included: true })),
          methods: (c.methods ?? []).map((m) => ({ ...m, included: true })),
        })),
      );
      setRelationships(p.draft.relationships ?? []);
      setStep("revisar");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setAnalyzing(false);
    }
  }

  async function handleConfirm() {
    setConfirming(true);
    setError(null);
    try {
      const nombresIncluidos = new Set(classes.filter((c) => c.included).map((c) => c.name));
      const draftFinal = {
        classes: classes
          .filter((c) => c.included)
          .map((c) => ({
            name: c.name,
            attributes: c.attributes
              .filter((a) => a.included)
              .map((a) => ({ name: a.name, type: a.type })),
            methods: c.methods
              .filter((m) => m.included)
              .map((m) => ({ name: m.name, returnType: m.returnType })),
          })),
        relationships: relationships.filter((r) => {
          const perteneceAlBorrador = (nombre: string) => classes.some((c) => c.name === nombre);
          const excluida = (nombre: string) => perteneceAlBorrador(nombre) && !nombresIncluidos.has(nombre);
          return !excluida(r.from) && !excluida(r.to);
        }),
      };
      const resultado = await confirmImport(diagramId, token, draftFinal, crypto.randomUUID());

      if (resultado.applied) {
        try {
          const snapshot = await fetchDiagramSnapshot(diagramId, token);
          useCanonicalStore.getState().setModel(snapshot);
        } catch (err) {
          console.warn("[vision] no se pudo recargar snapshot directamente:", err);
        }
      }

      setConfirmedResult(
        resultado.applied
          ? `¡Listo! Se agregaron +${resultado.addedClasses} clase(s) y +${resultado.addedRelationships} relación(es).`
          : "No se agregaron elementos nuevos.",
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setConfirming(false);
    }
  }

  function toggleClase(i: number) {
    setClasses((prev) => prev.map((c, idx) => (idx === i ? { ...c, included: !c.included } : c)));
  }
  function renombrarClase(i: number, name: string) {
    setClasses((prev) => prev.map((c, idx) => (idx === i ? { ...c, name } : c)));
  }
  function toggleAtributo(i: number, j: number) {
    setClasses((prev) =>
      prev.map((c, idx) =>
        idx === i
          ? { ...c, attributes: c.attributes.map((a, k) => (k === j ? { ...a, included: !a.included } : a)) }
          : c,
      ),
    );
  }
  function editarAtributo(i: number, j: number, campo: "name" | "type", valor: string) {
    setClasses((prev) =>
      prev.map((c, idx) =>
        idx === i
          ? { ...c, attributes: c.attributes.map((a, k) => (k === j ? { ...a, [campo]: valor } : a)) }
          : c,
      ),
    );
  }

  function toggleMetodo(i: number, k: number) {
    setClasses((prev) =>
      prev.map((c, idx) =>
        idx === i
          ? { ...c, methods: c.methods.map((m, mIdx) => (mIdx === k ? { ...m, included: !m.included } : m)) }
          : c,
      ),
    );
  }
  function editarMetodo(i: number, k: number, campo: "name" | "returnType", valor: string) {
    setClasses((prev) =>
      prev.map((c, idx) =>
        idx === i
          ? { ...c, methods: c.methods.map((m, mIdx) => (mIdx === k ? { ...m, [campo]: valor } : m)) }
          : c,
      ),
    );
  }

  function invertirRelacion(i: number) {
    setRelationships((prev) =>
      prev.map((r, idx) =>
        idx === i
          ? {
              ...r,
              from: r.to,
              to: r.from,
              sourceMultiplicity: r.targetMultiplicity,
              targetMultiplicity: r.sourceMultiplicity,
            }
          : r,
      ),
    );
  }

  function conectarClaseAsociacion(className: string, srcName: string, tgtName: string) {
    setRelationships((prev) => {
      const filtradas = prev.filter(
        (r) => !((r.from === srcName && r.to === tgtName) || (r.from === tgtName && r.to === srcName)),
      );
      return [
        ...filtradas,
        {
          from: srcName,
          to: className,
          type: "ASSOCIATION",
          sourceMultiplicity: "1..1",
          targetMultiplicity: "0..*",
        },
        {
          from: tgtName,
          to: className,
          type: "ASSOCIATION",
          sourceMultiplicity: "1..1",
          targetMultiplicity: "0..*",
        },
      ];
    });
  }

  return (
    <Modal title="Importar desde una foto" onClose={onClose}>
      <div className="vision-modal">
        {error && <p className="modal__error">⚠ {error}</p>}

        {step === "subir" && (
          <>
            <p className="vision-modal__message">
              Subí una foto de un diagrama dibujado a mano o una captura de pantalla. Vas a poder revisar y
              corregir lo que se detectó antes de que se agregue algo.
            </p>
            <input
              type="file"
              accept="image/*"
              className="vision-modal__file-input"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <div className="modal__actions">
              <button type="button" onClick={onClose}>
                Cancelar
              </button>
              <button type="button" className="modal__primary" onClick={handleAnalyze} disabled={!file || analyzing}>
                {analyzing ? "Analizando…" : "Analizar"}
              </button>
            </div>
          </>
        )}

        {step === "revisar" && proposal && !confirmedResult && (
          <>
            {proposal.simulated && (
              <div className="vision-modal__simulated">
                ⚠ Simulado: sin clave del servicio de visión, esta propuesta no viene de una
                lectura real de la foto.
              </div>
            )}
            <p className="vision-modal__message">{proposal.message}</p>

            {proposal.notes.length > 0 && (
              <ul className="vision-modal__notes">
                {proposal.notes.map((nota, i) => (
                  <li key={i}>{nota}</li>
                ))}
              </ul>
            )}

            <div className="vision-modal__classes">
              {classes.map((c, i) => {
                const nombresOtrasClases = classes
                  .filter((_, idx) => idx !== i)
                  .map((other) => other.name);

                return (
                  <div key={i} className={`vision-modal__class${c.included ? "" : " vision-modal__class--excluded"}`}>
                    <div className="vision-modal__class-header">
                      <label className="vision-modal__include-toggle">
                        <input type="checkbox" checked={c.included} onChange={() => toggleClase(i)} />
                        <span>{c.included ? "Incluir" : "Excluir"}</span>
                      </label>
                      <input
                        type="text"
                        className="vision-modal__class-name"
                        value={c.name}
                        onChange={(e) => renombrarClase(i, e.target.value)}
                        disabled={!c.included}
                      />
                    </div>

                    {c.included && nombresOtrasClases.length >= 2 && (
                      <div className="vision-modal__assoc-picker" style={{ margin: "6px 0", fontSize: "0.82rem", background: "rgba(0,0,0,0.03)", padding: "6px", borderRadius: "4px" }}>
                        <span style={{ display: "block", marginBottom: "4px", color: "var(--color-text-secondary, #666)" }}>
                          ¿Es clase asociación intermedia? Conectar entre:
                        </span>
                        <div style={{ display: "flex", gap: "6px", alignItems: "center" }}>
                          <select id={`src-${i}`} defaultValue={nombresOtrasClases[0]}>
                            {nombresOtrasClases.map((n) => (
                              <option key={n} value={n}>{n}</option>
                            ))}
                          </select>
                          <span>y</span>
                          <select id={`tgt-${i}`} defaultValue={nombresOtrasClases[1] ?? nombresOtrasClases[0]}>
                            {nombresOtrasClases.map((n) => (
                              <option key={n} value={n}>{n}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            style={{ padding: "2px 8px", cursor: "pointer" }}
                            onClick={() => {
                              const s = (document.getElementById(`src-${i}`) as HTMLSelectElement)?.value;
                              const t = (document.getElementById(`tgt-${i}`) as HTMLSelectElement)?.value;
                              if (s && t && s !== t) {
                                conectarClaseAsociacion(c.name, s, t);
                              }
                            }}
                          >
                            Conectar
                          </button>
                        </div>
                      </div>
                    )}

                    {c.attributes.length > 0 && (
                      <div className="vision-modal__attrs-list">
                        <small style={{ fontWeight: 600, color: "var(--color-text-secondary, #666)" }}>Atributos:</small>
                        {c.attributes.map((a, j) => (
                          <div key={j} className="vision-modal__attr">
                            <input type="checkbox" checked={a.included} onChange={() => toggleAtributo(i, j)} disabled={!c.included} />
                            <input
                              type="text"
                              value={a.name}
                              onChange={(e) => editarAtributo(i, j, "name", e.target.value)}
                              disabled={!c.included || !a.included}
                              placeholder="Nombre atributo"
                            />
                            <input
                              type="text"
                              value={a.type}
                              onChange={(e) => editarAtributo(i, j, "type", e.target.value)}
                              disabled={!c.included || !a.included}
                              placeholder="Tipo (VARCHAR, INTEGER, etc.)"
                            />
                          </div>
                        ))}
                      </div>
                    )}

                    {c.methods.length > 0 && (
                      <div className="vision-modal__methods-list" style={{ marginTop: 6 }}>
                        <small style={{ fontWeight: 600, color: "var(--color-text-secondary, #666)" }}>Métodos:</small>
                        {c.methods.map((m, k) => (
                          <div key={k} className="vision-modal__attr">
                            <input type="checkbox" checked={m.included} onChange={() => toggleMetodo(i, k)} disabled={!c.included} />
                            <input
                              type="text"
                              value={m.name}
                              onChange={(e) => editarMetodo(i, k, "name", e.target.value)}
                              disabled={!c.included || !m.included}
                              placeholder="Nombre método"
                            />
                            <input
                              type="text"
                              value={m.returnType}
                              onChange={(e) => editarMetodo(i, k, "returnType", e.target.value)}
                              disabled={!c.included || !m.included}
                              placeholder="Retorno (ej. void)"
                            />
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>

            {relationships.length > 0 && (
              <>
                <p className="vision-modal__section-title">Y estas relaciones</p>
                <div className="vision-modal__relationships">
                  {relationships.map((r, i) => (
                    <div
                      key={i}
                      className="vision-modal__relationship"
                      style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}
                    >
                      <span>
                        {r.from} —[{r.type}]→ {r.to} ({r.sourceMultiplicity ?? "1..1"} / {r.targetMultiplicity ?? "0..*"})
                      </span>
                      <div style={{ display: "flex", gap: "6px", alignItems: "center" }}>
                        <button
                          type="button"
                          onClick={() => invertirRelacion(i)}
                          style={{
                            background: "none",
                            border: "1px solid var(--color-border, #ccc)",
                            borderRadius: "4px",
                            fontSize: "0.8rem",
                            padding: "2px 6px",
                            cursor: "pointer",
                            color: "var(--brand-primary, #4b97c5)",
                          }}
                          title="Invertir origen y destino de la relación"
                        >
                          ⇄ Invertir
                        </button>
                        <button
                          type="button"
                          onClick={() => setRelationships((prev) => prev.filter((_, idx) => idx !== i))}
                          style={{
                            background: "none",
                            border: "none",
                            color: "var(--color-text-secondary, #888)",
                            cursor: "pointer",
                            fontSize: "1.1rem",
                            padding: "0 4px",
                          }}
                          title="Quitar relación"
                        >
                          ×
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}

            <div className="modal__actions">
              <button type="button" onClick={onClose}>
                Cancelar
              </button>
              <button type="button" className="modal__primary" onClick={handleConfirm} disabled={confirming}>
                {confirming ? "Confirmando…" : "Confirmar"}
              </button>
            </div>
          </>
        )}

        {confirmedResult && (
          <>
            <p className="vision-modal__message">{confirmedResult}</p>
            <div className="modal__actions">
              <button type="button" className="modal__primary" onClick={onClose}>
                Ver en el lienzo
              </button>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}
