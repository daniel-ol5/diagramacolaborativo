import { useState } from "react";
import { Modal } from "../components/Modal";
import { analyzeImage, confirmImport, type VisionAttribute, type VisionClass, type VisionProposal } from "./visionApi";
import "./VisionImportModal.css";

interface Props {
  diagramId: string;
  token: string;
  onClose: () => void;
}

interface EditableAttribute extends VisionAttribute {
  included: boolean;
}

interface EditableClass extends Omit<VisionClass, "attributes"> {
  included: boolean;
  attributes: EditableAttribute[];
}

/**
 * CU17: importar un diagrama desde una foto, en dos pasos separados por una
 * persona en el medio (RF-03.3) -- "analizar" no sabe escribir, así que no
 * existe ningún camino por el que una foto se aplique sin que alguien la
 * revise y confirme en este mismo modal.
 *
 * El paso que de verdad importa es el segundo: mostrar QUÉ se va a crear,
 * no un "¿aceptar?" a ciegas. Por eso cada clase y cada atributo se puede
 * excluir o corregir antes de confirmar -- la persona no aprueba una foto,
 * aprueba lo que va a terminar en el diagrama.
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
          attributes: c.attributes.map((a) => ({ ...a, included: true })),
        })),
      );
      setRelationships(p.draft.relationships);
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
          .map((c) => ({ name: c.name, attributes: c.attributes.filter((a) => a.included).map((a) => ({ name: a.name, type: a.type })) })),
        // Una relacion que menciona una clase excluida de ESTE borrador se cae con ella --
        // si en cambio apunta a una clase que ya existe en el diagrama, se deja pasar
        // (VisionDraft.java: "from"/"to" pueden apuntar a una clase que ya esta en el diagrama).
        relationships: relationships.filter((r) => {
          const perteneceAlBorrador = (nombre: string) => classes.some((c) => c.name === nombre);
          const excluida = (nombre: string) => perteneceAlBorrador(nombre) && !nombresIncluidos.has(nombre);
          return !excluida(r.from) && !excluida(r.to);
        }),
      };
      const resultado = await confirmImport(diagramId, token, draftFinal, crypto.randomUUID());
      setConfirmedResult(
        resultado.applied
          ? `Listo: +${resultado.addedClasses} clase(s), +${resultado.addedRelationships} relación(es).`
          : "No se agregó nada.",
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
        idx === i ? { ...c, attributes: c.attributes.map((a, k) => (k === j ? { ...a, included: !a.included } : a)) } : c,
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

  return (
    <Modal title="Importar desde una foto" onClose={onClose}>
      <div className="vision-modal">
        {error && <p className="modal__error">⚠ {error}</p>}

        {step === "subir" && (
          <>
            <p className="vision-modal__message">
              Subí una foto de un diagrama dibujado a mano o en una pizarra. Vas a poder revisar y
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
                {proposal.notes.map((n, i) => (
                  <li key={i}>{n}</li>
                ))}
              </ul>
            )}

            <p className="vision-modal__section-title">Se van a crear estas clases</p>
            {classes.length === 0 && <p className="vision-modal__empty">No se detectó ninguna clase.</p>}
            <div className="vision-modal__classes">
              {classes.map((c, i) => (
                <div key={i} className={`vision-modal__class${c.included ? "" : " vision-modal__class--excluded"}`}>
                  <div className="vision-modal__class-header">
                    <input type="checkbox" checked={c.included} onChange={() => toggleClase(i)} title="Incluir esta clase" />
                    <input type="text" value={c.name} onChange={(e) => renombrarClase(i, e.target.value)} disabled={!c.included} />
                  </div>
                  {c.attributes.map((a, j) => (
                    <div key={j} className="vision-modal__attr">
                      <input type="checkbox" checked={a.included} onChange={() => toggleAtributo(i, j)} disabled={!c.included} />
                      <input
                        type="text"
                        value={a.name}
                        onChange={(e) => editarAtributo(i, j, "name", e.target.value)}
                        disabled={!c.included || !a.included}
                      />
                      <input
                        type="text"
                        value={a.type}
                        onChange={(e) => editarAtributo(i, j, "type", e.target.value)}
                        disabled={!c.included || !a.included}
                      />
                    </div>
                  ))}
                </div>
              ))}
            </div>

            {relationships.length > 0 && (
              <>
                <p className="vision-modal__section-title">Y estas relaciones</p>
                <div className="vision-modal__relationships">
                  {relationships.map((r, i) => (
                    <div key={i} className="vision-modal__relationship">
                      {r.from} —[{r.type}]→ {r.to} ({r.sourceMultiplicity} / {r.targetMultiplicity})
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
                Cerrar
              </button>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}
